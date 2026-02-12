use crate::types::{Command, PlacedCommand};

/// Compute the total output size of algorithm commands.
pub fn output_size(commands: &[Command]) -> usize {
    commands
        .iter()
        .map(|cmd| match cmd {
            Command::Copy { length, .. } => *length,
            Command::Add { data } => data.len(),
        })
        .sum()
}

/// Convert algorithm output to placed commands with sequential destinations.
pub fn place_commands(commands: &[Command]) -> Vec<PlacedCommand> {
    let mut placed = Vec::with_capacity(commands.len());
    let mut dst = 0;
    for cmd in commands {
        match cmd {
            Command::Copy { offset, length } => {
                placed.push(PlacedCommand::Copy {
                    src: *offset,
                    dst,
                    length: *length,
                });
                dst += length;
            }
            Command::Add { data } => {
                placed.push(PlacedCommand::Add {
                    dst,
                    data: data.clone(),
                });
                dst += data.len();
            }
        }
    }
    placed
}

/// Apply placed commands in standard mode: read from R, write to out.
///
/// Returns the number of bytes written.
pub fn apply_placed_to(r: &[u8], commands: &[PlacedCommand], out: &mut [u8]) -> usize {
    let mut max_written = 0;
    for cmd in commands {
        match cmd {
            PlacedCommand::Copy { src, dst, length } => {
                out[*dst..*dst + *length].copy_from_slice(&r[*src..*src + *length]);
                let end = dst + length;
                if end > max_written {
                    max_written = end;
                }
            }
            PlacedCommand::Add { dst, data } => {
                out[*dst..*dst + data.len()].copy_from_slice(data);
                let end = dst + data.len();
                if end > max_written {
                    max_written = end;
                }
            }
        }
    }
    max_written
}

/// Apply placed commands in-place within a single buffer.
///
/// Uses `copy_within` (maps to libc `memmove`) so overlapping src/dst is safe.
pub fn apply_placed_inplace_to(commands: &[PlacedCommand], buf: &mut [u8]) {
    for cmd in commands {
        match cmd {
            PlacedCommand::Copy { src, dst, length } => {
                buf.copy_within(*src..*src + *length, *dst);
            }
            PlacedCommand::Add { dst, data } => {
                buf[*dst..*dst + data.len()].copy_from_slice(data);
            }
        }
    }
}

// ── convenience wrappers (Command → output) ─────────────────────────────

/// Apply algorithm commands, writing into a pre-allocated buffer.
///
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
                out[pos..pos + *length].copy_from_slice(&r[*offset..*offset + *length]);
                pos += *length;
            }
        }
    }
    pos
}

/// Reconstruct the version from reference + algorithm commands.
pub fn apply_delta(r: &[u8], commands: &[Command]) -> Vec<u8> {
    let mut out = vec![0u8; output_size(commands)];
    apply_delta_to(r, commands, &mut out);
    out
}

/// Apply placed in-place commands to a buffer initialized with R.
pub fn apply_delta_inplace(
    r: &[u8],
    commands: &[PlacedCommand],
    version_size: usize,
) -> Vec<u8> {
    let buf_size = r.len().max(version_size);
    let mut buf = vec![0u8; buf_size];
    buf[..r.len()].copy_from_slice(r);
    apply_placed_inplace_to(commands, &mut buf);
    buf.truncate(version_size);
    buf
}
