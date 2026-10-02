//! The binary delta format.
//!
//! A delta is a header, a sequence of commands, and an END byte.  All
//! integers are big-endian.
//!
//! ```text
//! header:  magic(4)  flags(1)  version_size(4 or 8)  src_crc(8)  dst_crc(8)
//! COPY:    1  src(4) dst(4) len(4)      BIGCOPY: 3  src(8) dst(8) len(8)
//! ADD:     2  dst(4) len(4) data(len)   BIGADD:  4  dst(8) len(8) data(len)
//! MOVE:    5  src(4) dst(4) len(4)      BIGMOVE: 6  src(8) dst(8) len(8)
//! END:     0
//! ```
//!
//! `DLT\x03` has a 4-byte version size and only COPY and ADD.  `DLT\x04`
//! has an 8-byte version size and all six commands.

use crate::types::{
    DeltaError, PlacedCommand, DELTA_CMD_ADD, DELTA_CMD_BIGADD, DELTA_CMD_BIGCOPY,
    DELTA_CMD_BIGMOVE, DELTA_CMD_COPY, DELTA_CMD_END, DELTA_CMD_MOVE, DELTA_CRC_SIZE,
    DELTA_FLAG_INPLACE, DELTA_HEADER_SIZE, DELTA_HEADER_SIZE_LARGE, DELTA_MAGIC, DELTA_MAGIC_LARGE,
    DELTA_U32_SIZE, DELTA_U64_SIZE,
};

fn put_header(
    out: &mut Vec<u8>,
    magic: &[u8; 4],
    inplace: bool,
    version_size: &[u8],
    crcs: [&[u8; DELTA_CRC_SIZE]; 2],
) {
    out.extend_from_slice(magic);
    out.push(if inplace { DELTA_FLAG_INPLACE } else { 0 });
    out.extend_from_slice(version_size);
    out.extend_from_slice(crcs[0]);
    out.extend_from_slice(crcs[1]);
}

/// Narrows a field to the 32 bits `DLT\x03` allows it.
fn check_u32(val: usize, field: &str) -> Result<u32, DeltaError> {
    u32::try_from(val).map_err(|_| {
        DeltaError::InvalidFormat(format!("{field} exceeds 4 GiB (32-bit format limit)"))
    })
}

/// Encodes a delta in the `DLT\x03` format.
///
/// Fails if the version size or any offset or length does not fit in 32
/// bits, or if there is a `Move`.
pub fn encode_delta(
    commands: &[PlacedCommand],
    inplace: bool,
    version_size: usize,
    src_crc: &[u8; DELTA_CRC_SIZE],
    dst_crc: &[u8; DELTA_CRC_SIZE],
) -> Result<Vec<u8>, DeltaError> {
    let mut out = Vec::new();
    let version_size = check_u32(version_size, "version_size")?.to_be_bytes();
    put_header(
        &mut out,
        DELTA_MAGIC,
        inplace,
        &version_size,
        [src_crc, dst_crc],
    );

    for cmd in commands {
        match cmd {
            PlacedCommand::Copy { src, dst, length } => {
                out.push(DELTA_CMD_COPY);
                out.extend_from_slice(&check_u32(*src, "copy src offset")?.to_be_bytes());
                out.extend_from_slice(&check_u32(*dst, "copy dst offset")?.to_be_bytes());
                out.extend_from_slice(&check_u32(*length, "copy length")?.to_be_bytes());
            }
            PlacedCommand::Add { dst, data } => {
                out.push(DELTA_CMD_ADD);
                out.extend_from_slice(&check_u32(*dst, "add dst offset")?.to_be_bytes());
                out.extend_from_slice(&check_u32(data.len(), "add length")?.to_be_bytes());
                out.extend_from_slice(data);
            }
            PlacedCommand::Move { .. } => {
                return Err(DeltaError::InvalidFormat(
                    "MOVE commands require DLT\\x04 format; use encode_delta_large".into(),
                ));
            }
        }
    }

    out.push(DELTA_CMD_END);
    Ok(out)
}

/// Encodes a delta in the `DLT\x04` format.
///
/// Each command takes its 32-bit form if all its fields fit, and its BIG
/// form otherwise.  With `force_large`, every command takes its BIG form.
pub fn encode_delta_large(
    commands: &[PlacedCommand],
    inplace: bool,
    version_size: usize,
    src_crc: &[u8; DELTA_CRC_SIZE],
    dst_crc: &[u8; DELTA_CRC_SIZE],
    force_large: bool,
) -> Vec<u8> {
    let mut out = Vec::new();
    let version_size = (version_size as u64).to_be_bytes();
    put_header(
        &mut out,
        DELTA_MAGIC_LARGE,
        inplace,
        &version_size,
        [src_crc, dst_crc],
    );

    for cmd in commands {
        match cmd {
            PlacedCommand::Copy { src, dst, length } => {
                let tags = (DELTA_CMD_COPY, DELTA_CMD_BIGCOPY);
                put_copy(&mut out, tags, *src, *dst, *length, force_large);
            }
            PlacedCommand::Add { dst, data } => {
                if !force_large && fits_u32(*dst) && fits_u32(data.len()) {
                    out.push(DELTA_CMD_ADD);
                    out.extend_from_slice(&(*dst as u32).to_be_bytes());
                    out.extend_from_slice(&(data.len() as u32).to_be_bytes());
                } else {
                    out.push(DELTA_CMD_BIGADD);
                    out.extend_from_slice(&(*dst as u64).to_be_bytes());
                    out.extend_from_slice(&(data.len() as u64).to_be_bytes());
                }
                out.extend_from_slice(data);
            }
            PlacedCommand::Move { src, dst, length } => {
                let tags = (DELTA_CMD_MOVE, DELTA_CMD_BIGMOVE);
                put_copy(&mut out, tags, *src, *dst, *length, force_large);
            }
        }
    }

    out.push(DELTA_CMD_END);
    out
}

/// Reports whether a field fits the 32-bit form of a command.
#[inline]
fn fits_u32(field: usize) -> bool {
    u32::try_from(field).is_ok()
}

/// Writes a COPY or MOVE: `small` with u32 fields if they all fit and
/// `force_large` is not set, else `big` with u64 fields.
#[inline]
fn put_copy(
    out: &mut Vec<u8>,
    (small, big): (u8, u8),
    src: usize,
    dst: usize,
    length: usize,
    force_large: bool,
) {
    if !force_large && fits_u32(src) && fits_u32(dst) && fits_u32(length) {
        out.push(small);
        out.extend_from_slice(&(src as u32).to_be_bytes());
        out.extend_from_slice(&(dst as u32).to_be_bytes());
        out.extend_from_slice(&(length as u32).to_be_bytes());
    } else {
        out.push(big);
        out.extend_from_slice(&(src as u64).to_be_bytes());
        out.extend_from_slice(&(dst as u64).to_be_bytes());
        out.extend_from_slice(&(length as u64).to_be_bytes());
    }
}

/// The part of a delta not yet decoded.
///
/// The methods are forced inline because the decoder's loop is measurably
/// slower when they are calls that return a `Result`.
struct Reader<'a>(&'a [u8]);

impl<'a> Reader<'a> {
    /// Removes and returns the next byte, if there is one.
    #[inline(always)]
    fn byte(&mut self) -> Option<u8> {
        let (&first, rest) = self.0.split_first()?;
        self.0 = rest;
        Some(first)
    }

    /// Removes and returns the next `n` bytes.
    #[inline(always)]
    fn take(&mut self, n: usize) -> Result<&'a [u8], DeltaError> {
        if n > self.0.len() {
            return Err(DeltaError::UnexpectedEof);
        }
        let (head, rest) = self.0.split_at(n);
        self.0 = rest;
        Ok(head)
    }

    /// Reads `N` u32 offsets or lengths.  Nothing is consumed unless all
    /// are present.
    #[inline(always)]
    fn u32s<const N: usize>(&mut self) -> Result<[usize; N], DeltaError> {
        let raw = self.take(N * DELTA_U32_SIZE)?;
        Ok(std::array::from_fn(|i| {
            let at = i * DELTA_U32_SIZE;
            u32::from_be_bytes([raw[at], raw[at + 1], raw[at + 2], raw[at + 3]]) as usize
        }))
    }

    /// Reads `N` u64 offsets or lengths.  Nothing is consumed unless all
    /// are present.
    ///
    /// A value too large for usize, which can happen only where usize is
    /// narrower than 64 bits, is read as `usize::MAX`.  No buffer is that
    /// large, so the range checks that follow reject it.
    #[inline(always)]
    fn u64s<const N: usize>(&mut self) -> Result<[usize; N], DeltaError> {
        let raw = self.take(N * DELTA_U64_SIZE)?;
        Ok(std::array::from_fn(|i| {
            let at = i * DELTA_U64_SIZE;
            let value = u64::from_be_bytes([
                raw[at],
                raw[at + 1],
                raw[at + 2],
                raw[at + 3],
                raw[at + 4],
                raw[at + 5],
                raw[at + 6],
                raw[at + 7],
            ]);
            usize::try_from(value).unwrap_or(usize::MAX)
        }))
    }
}

/// Checks that a command writes within the version.
#[inline(always)]
fn check_dst(dst: usize, length: usize, version_size: usize, kind: &str) -> Result<(), DeltaError> {
    if dst > version_size || length > version_size - dst {
        return Err(invalid(format_args!(
            "{} command exceeds version size",
            kind
        )));
    }
    Ok(())
}

#[cold]
fn invalid(message: std::fmt::Arguments) -> DeltaError {
    DeltaError::InvalidFormat(message.to_string())
}

/// Decodes a delta in either format.
///
/// Returns (commands, inplace, version size, CRC of the reference, CRC of
/// the version).  Every command's destination range is checked against the
/// version size.  Nothing is checked against the reference, which the
/// decoder does not have: see
/// [`validate_placed_commands`](crate::validate_placed_commands) and
/// [`crc64_xz`](crate::crc64_xz).
#[allow(clippy::type_complexity)]
pub fn decode_delta(
    data: &[u8],
) -> Result<
    (
        Vec<PlacedCommand>,
        bool,
        usize,
        [u8; DELTA_CRC_SIZE],
        [u8; DELTA_CRC_SIZE],
    ),
    DeltaError,
> {
    let not_a_delta = || DeltaError::InvalidFormat("not a delta file".into());
    let large = match data.get(..4) {
        Some(magic) if magic == DELTA_MAGIC => false,
        Some(magic) if magic == DELTA_MAGIC_LARGE => true,
        _ => return Err(not_a_delta()),
    };
    let header_size = if large {
        DELTA_HEADER_SIZE_LARGE
    } else {
        DELTA_HEADER_SIZE
    };
    if data.len() < header_size {
        return Err(not_a_delta());
    }

    let inplace = data[4] & DELTA_FLAG_INPLACE != 0;
    let mut rd = Reader(&data[5..]);
    let [version_size] = if large { rd.u64s()? } else { rd.u32s()? };
    let mut src_crc = [0; DELTA_CRC_SIZE];
    let mut dst_crc = [0; DELTA_CRC_SIZE];
    src_crc.copy_from_slice(rd.take(DELTA_CRC_SIZE)?);
    dst_crc.copy_from_slice(rd.take(DELTA_CRC_SIZE)?);

    let mut commands = Vec::new();
    loop {
        let Some(tag) = rd.byte() else {
            return Err(DeltaError::InvalidFormat("missing END command".into()));
        };
        match tag {
            DELTA_CMD_END => break,
            DELTA_CMD_COPY => {
                let [src, dst, length] = rd.u32s()?;
                check_dst(dst, length, version_size, "copy")?;
                commands.push(PlacedCommand::Copy { src, dst, length });
            }
            DELTA_CMD_ADD => {
                let [dst, length] = rd.u32s()?;
                let data = rd.take(length)?;
                check_dst(dst, length, version_size, "add")?;
                commands.push(PlacedCommand::Add {
                    dst,
                    data: data.to_vec(),
                });
            }
            DELTA_CMD_BIGCOPY | DELTA_CMD_BIGADD | DELTA_CMD_MOVE | DELTA_CMD_BIGMOVE if !large => {
                return Err(DeltaError::InvalidFormat(format!(
                    "command type {} requires DLT\\x04 format",
                    tag
                )));
            }
            DELTA_CMD_BIGCOPY => {
                let [src, dst, length] = rd.u64s()?;
                check_dst(dst, length, version_size, "bigcopy")?;
                commands.push(PlacedCommand::Copy { src, dst, length });
            }
            DELTA_CMD_BIGADD => {
                let [dst, length] = rd.u64s()?;
                let data = rd.take(length)?;
                check_dst(dst, length, version_size, "bigadd")?;
                commands.push(PlacedCommand::Add {
                    dst,
                    data: data.to_vec(),
                });
            }
            DELTA_CMD_MOVE => {
                let [src, dst, length] = rd.u32s()?;
                check_dst(dst, length, version_size, "move")?;
                commands.push(PlacedCommand::Move { src, dst, length });
            }
            DELTA_CMD_BIGMOVE => {
                let [src, dst, length] = rd.u64s()?;
                check_dst(dst, length, version_size, "bigmove")?;
                commands.push(PlacedCommand::Move { src, dst, length });
            }
            _ => {
                return Err(DeltaError::InvalidFormat(format!(
                    "unknown command type: {}",
                    tag
                )));
            }
        }
    }

    if !rd.0.is_empty() {
        return Err(DeltaError::InvalidFormat("trailing data after END".into()));
    }
    Ok((commands, inplace, version_size, src_crc, dst_crc))
}

/// Reports whether `data` begins with the header of an in-place delta.
pub fn is_inplace_delta(data: &[u8]) -> bool {
    matches!(data.get(..4), Some(magic) if magic == DELTA_MAGIC || magic == DELTA_MAGIC_LARGE)
        && data
            .get(4)
            .is_some_and(|flags| flags & DELTA_FLAG_INPLACE != 0)
}
