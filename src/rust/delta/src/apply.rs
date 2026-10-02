//! Placing commands at explicit offsets and applying them to reconstruct
//! the version.

use crate::types::{Command, DeltaError, PlacedCommand};

/// Returns the size of the version the commands produce.
pub fn output_size(commands: &[Command]) -> usize {
    commands
        .iter()
        .map(|cmd| match cmd {
            Command::Copy { length, .. } => *length,
            Command::Add { data } => data.len(),
        })
        .sum()
}

/// Gives each command the destination it writes when the commands are
/// applied in order.
pub fn place_commands(commands: Vec<Command>) -> Vec<PlacedCommand> {
    let mut dst = 0;
    commands
        .into_iter()
        .map(|cmd| {
            let at = dst;
            match cmd {
                Command::Copy { offset, length } => {
                    dst += length;
                    PlacedCommand::Copy {
                        src: offset,
                        dst: at,
                        length,
                    }
                }
                Command::Add { data } => {
                    dst += data.len();
                    PlacedCommand::Add { dst: at, data }
                }
            }
        })
        .collect()
}

/// Returns the commands in order of destination, without their
/// destinations.  This inverts [`place_commands`] when the placed commands
/// cover the output without gaps or overlap.
///
/// # Panics
///
/// Panics on a `Move`, which has no equivalent among the commands of the
/// differencing algorithms.
pub fn unplace_commands(mut placed: Vec<PlacedCommand>) -> Vec<Command> {
    placed.sort_by_key(|cmd| match cmd {
        PlacedCommand::Copy { dst, .. }
        | PlacedCommand::Add { dst, .. }
        | PlacedCommand::Move { dst, .. } => *dst,
    });
    placed
        .into_iter()
        .map(|cmd| match cmd {
            PlacedCommand::Copy { src, length, .. } => Command::Copy {
                offset: src,
                length,
            },
            PlacedCommand::Add { data, .. } => Command::Add { data },
            PlacedCommand::Move { .. } => panic!(
                "unplace_commands: Move has no algorithm-level equivalent; \
                 Move commands are DLT\\x04-only and cannot be unplaced"
            ),
        })
        .collect()
}

/// Applies a standard delta: copies read `r`, and every command writes
/// `out`.  Returns the end of the highest range written.
///
/// A `Move` reads `out`, so it must come after the commands that write its
/// source.  [`validate_placed_commands`] cannot check that; the encoder
/// must ensure it.
///
/// # Panics
///
/// Panics if a command reaches outside `r` or `out`.  Commands that pass
/// [`validate_placed_commands`] do not.
pub fn apply_placed_to(r: &[u8], commands: &[PlacedCommand], out: &mut [u8]) -> usize {
    let mut written = 0;
    for cmd in commands {
        let end = match cmd {
            PlacedCommand::Copy { src, dst, length } => {
                out[*dst..dst + length].copy_from_slice(&r[*src..src + length]);
                dst + length
            }
            PlacedCommand::Add { dst, data } => {
                out[*dst..dst + data.len()].copy_from_slice(data);
                dst + data.len()
            }
            PlacedCommand::Move { src, dst, length } => {
                out.copy_within(*src..src + length, *dst);
                dst + length
            }
        };
        written = written.max(end);
    }
    written
}

/// Applies an in-place delta to `buf`, which holds the reference on entry
/// and the version on return.  Source and destination ranges may overlap.
///
/// # Panics
///
/// Panics if a command reaches outside `buf`.
pub fn apply_placed_inplace_to(commands: &[PlacedCommand], buf: &mut [u8]) {
    for cmd in commands {
        match cmd {
            PlacedCommand::Copy { src, dst, length } | PlacedCommand::Move { src, dst, length } => {
                buf.copy_within(*src..src + length, *dst);
            }
            PlacedCommand::Add { dst, data } => {
                buf[*dst..dst + data.len()].copy_from_slice(data);
            }
        }
    }
}

/// Checks that every command stays inside its buffers, so that applying
/// the commands cannot panic.
///
/// An in-place delta is applied to a buffer of
/// `max(reference_size, version_size)` bytes, and its copies may read
/// anywhere in it.
pub fn validate_placed_commands(
    commands: &[PlacedCommand],
    reference_size: usize,
    version_size: usize,
    inplace: bool,
) -> Result<(), DeltaError> {
    let source_limit = if inplace {
        reference_size.max(version_size)
    } else {
        reference_size
    };
    for cmd in commands {
        match cmd {
            PlacedCommand::Copy { src, dst, length } => {
                check_range(*dst, *length, version_size, "copy destination")?;
                check_range(*src, *length, source_limit, "copy source")?;
            }
            PlacedCommand::Add { dst, data } => {
                check_range(*dst, data.len(), version_size, "add destination")?;
            }
            PlacedCommand::Move { src, dst, length } => {
                check_range(*dst, *length, version_size, "move destination")?;
                check_range(*src, *length, version_size, "move source")?;
                // Necessary for the source to have been written already,
                // but not sufficient: that depends on the order of the
                // commands.
                if src + length > *dst {
                    return Err(DeltaError::InvalidFormat(format!(
                        "move src+len ({}+{}) > dst ({}): source not yet written",
                        src, length, dst
                    )));
                }
            }
        }
    }
    Ok(())
}

/// Checks that `start..start + length` lies within `0..limit`, without
/// overflowing.
fn check_range(start: usize, length: usize, limit: usize, name: &str) -> Result<(), DeltaError> {
    if start > limit || length > limit - start {
        return Err(DeltaError::InvalidFormat(format!("{} out of range", name)));
    }
    Ok(())
}

/// Applies unplaced commands, writing the version to the front of `out`.
/// Returns the number of bytes written.
pub fn apply_delta_to(r: &[u8], commands: &[Command], out: &mut [u8]) -> usize {
    let mut pos = 0;
    for cmd in commands {
        match cmd {
            Command::Add { data } => {
                out[pos..pos + data.len()].copy_from_slice(data);
                pos += data.len();
            }
            Command::Copy { offset, length } => {
                out[pos..pos + length].copy_from_slice(&r[*offset..offset + length]);
                pos += length;
            }
        }
    }
    pos
}

/// Returns the version that the commands produce from `r`.
pub fn apply_delta(r: &[u8], commands: &[Command]) -> Vec<u8> {
    let mut out = vec![0u8; output_size(commands)];
    apply_delta_to(r, commands, &mut out);
    out
}

/// Returns the version that in-place commands produce from `r`, working in
/// a copy of `r` resized to hold the larger of the two.
pub fn apply_delta_inplace(r: &[u8], commands: &[PlacedCommand], version_size: usize) -> Vec<u8> {
    let mut buf = vec![0u8; r.len().max(version_size)];
    buf[..r.len()].copy_from_slice(r);
    apply_placed_inplace_to(commands, &mut buf);
    buf.truncate(version_size);
    buf
}
