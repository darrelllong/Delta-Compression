use crate::types::{
    DeltaError, PlacedCommand,
    DELTA_ADD_HEADER, DELTA_BIGADD_HEADER, DELTA_BIGCOPY_PAYLOAD,
    DELTA_CMD_ADD, DELTA_CMD_BIGADD, DELTA_CMD_BIGCOPY, DELTA_CMD_BIGMOVE,
    DELTA_CMD_COPY, DELTA_CMD_END, DELTA_CMD_MOVE,
    DELTA_COPY_PAYLOAD, DELTA_CRC_SIZE, DELTA_FLAG_INPLACE,
    DELTA_HEADER_SIZE, DELTA_HEADER_SIZE_LARGE,
    DELTA_MAGIC, DELTA_MAGIC_LARGE,
    DELTA_U32_SIZE, DELTA_U64_SIZE,
};

/// Maximum value that fits in a u32, as usize, for per-command size selection.
const U32_MAX: usize = u32::MAX as usize;

fn check_u32(val: usize, field: &str) -> Result<u32, DeltaError> {
    u32::try_from(val)
        .map_err(|_| DeltaError::InvalidFormat(format!("{field} exceeds 4 GiB (32-bit format limit)")))
}

// ── Encoding ─────────────────────────────────────────────────────────────────

/// Encode placed commands to the DLT\x03 binary delta format.
///
/// DLT\x03 header: magic(4) + flags(1) + version_size(u32 BE) + crcs(16) = 25 bytes.
/// Commands: END(0), COPY(1, u32×3), ADD(2, u32×2 + data).
/// All offsets must fit in u32; returns Err if any exceed 4 GiB.
pub fn encode_delta(
    commands: &[PlacedCommand],
    inplace: bool,
    version_size: usize,
    src_crc: &[u8; DELTA_CRC_SIZE],
    dst_crc: &[u8; DELTA_CRC_SIZE],
) -> Result<Vec<u8>, DeltaError> {
    let mut out = Vec::new();
    out.extend_from_slice(DELTA_MAGIC);
    out.push(if inplace { DELTA_FLAG_INPLACE } else { 0 });
    out.extend_from_slice(&check_u32(version_size, "version_size")?.to_be_bytes());
    out.extend_from_slice(src_crc);
    out.extend_from_slice(dst_crc);

    for cmd in commands {
        match cmd {
            PlacedCommand::Copy { src, dst, length } => {
                out.push(DELTA_CMD_COPY);
                out.extend_from_slice(&check_u32(*src,    "copy src offset")?.to_be_bytes());
                out.extend_from_slice(&check_u32(*dst,    "copy dst offset")?.to_be_bytes());
                out.extend_from_slice(&check_u32(*length, "copy length")?.to_be_bytes());
            }
            PlacedCommand::Add { dst, data } => {
                out.push(DELTA_CMD_ADD);
                out.extend_from_slice(&check_u32(*dst,       "add dst offset")?.to_be_bytes());
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

/// Encode placed commands to the DLT\x04 binary delta format.
///
/// DLT\x04 header: magic(4) + flags(1) + version_size(u64 BE) + crcs(16) = 29 bytes.
/// Commands: per-command size selection — COPY(1)/BIGCOPY(3), ADD(2)/BIGADD(4),
/// MOVE(5)/BIGMOVE(6).  Small variants use u32 fields; big variants use u64 fields.
/// Fields ≤ U32_MAX get the small variant; larger fields get the big variant.
///
/// This function is **infallible**: `usize → u64` is a widening conversion on all
/// platforms Rust supports (Rust guarantees `usize` ≤ 64 bits), so no field can
/// overflow a u64.  Contrast with `encode_delta` (DLT\x03), which returns `Result`
/// because `usize → u32` truncates on 64-bit platforms.
pub fn encode_delta_large(
    commands: &[PlacedCommand],
    inplace: bool,
    version_size: usize,
    src_crc: &[u8; DELTA_CRC_SIZE],
    dst_crc: &[u8; DELTA_CRC_SIZE],
    force_large: bool,
) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(DELTA_MAGIC_LARGE);
    out.push(if inplace { DELTA_FLAG_INPLACE } else { 0 });
    out.extend_from_slice(&(version_size as u64).to_be_bytes());
    out.extend_from_slice(src_crc);
    out.extend_from_slice(dst_crc);

    for cmd in commands {
        match cmd {
            PlacedCommand::Copy { src, dst, length } => {
                encode_3field(&mut out, DELTA_CMD_COPY, DELTA_CMD_BIGCOPY, *src, *dst, *length, force_large);
            }
            PlacedCommand::Add { dst, data } => {
                if !force_large && *dst <= U32_MAX && data.len() <= U32_MAX {
                    out.push(DELTA_CMD_ADD);
                    out.extend_from_slice(&(*dst       as u32).to_be_bytes());
                    out.extend_from_slice(&(data.len() as u32).to_be_bytes());
                } else {
                    out.push(DELTA_CMD_BIGADD);
                    out.extend_from_slice(&(*dst       as u64).to_be_bytes());
                    out.extend_from_slice(&(data.len() as u64).to_be_bytes());
                }
                out.extend_from_slice(data);
            }
            PlacedCommand::Move { src, dst, length } => {
                encode_3field(&mut out, DELTA_CMD_MOVE, DELTA_CMD_BIGMOVE, *src, *dst, *length, force_large);
            }
        }
    }

    out.push(DELTA_CMD_END);
    out
}

/// Emit a 3-field command (src, dst, length) using small (u32) or big (u64) variant.
///
/// Used for COPY/BIGCOPY and MOVE/BIGMOVE, which share identical wire shapes.
/// When force_large is true the big variant is always emitted.
#[inline]
fn encode_3field(out: &mut Vec<u8>, small: u8, big: u8, a: usize, b: usize, c: usize, force_large: bool) {
    if !force_large && a <= U32_MAX && b <= U32_MAX && c <= U32_MAX {
        out.push(small);
        out.extend_from_slice(&(a as u32).to_be_bytes());
        out.extend_from_slice(&(b as u32).to_be_bytes());
        out.extend_from_slice(&(c as u32).to_be_bytes());
    } else {
        out.push(big);
        out.extend_from_slice(&(a as u64).to_be_bytes());
        out.extend_from_slice(&(b as u64).to_be_bytes());
        out.extend_from_slice(&(c as u64).to_be_bytes());
    }
}

// ── Decoding ─────────────────────────────────────────────────────────────────

/// Decode a binary delta (DLT\x03 or DLT\x04).
///
/// Returns (commands, inplace, version_size, src_crc, dst_crc).
/// CRC validation is the caller's responsibility.
pub fn decode_delta(
    data: &[u8],
) -> Result<(Vec<PlacedCommand>, bool, usize, [u8; DELTA_CRC_SIZE], [u8; DELTA_CRC_SIZE]), DeltaError> {
    if data.len() < 4 {
        return Err(DeltaError::InvalidFormat("not a delta file".into()));
    }
    match &data[..4] {
        m if m == DELTA_MAGIC    => decode_small(data),
        m if m == DELTA_MAGIC_LARGE => decode_large(data),
        _ => Err(DeltaError::InvalidFormat("not a delta file".into())),
    }
}

fn decode_small(
    data: &[u8],
) -> Result<(Vec<PlacedCommand>, bool, usize, [u8; DELTA_CRC_SIZE], [u8; DELTA_CRC_SIZE]), DeltaError> {
    if data.len() < DELTA_HEADER_SIZE {
        return Err(DeltaError::InvalidFormat("not a delta file".into()));
    }
    let inplace = data[4] & DELTA_FLAG_INPLACE != 0;
    let version_size = u32::from_be_bytes([data[5], data[6], data[7], data[8]]) as usize;
    let crc_offset = 9; // 4 + 1 + 4
    let mut src_crc = [0u8; DELTA_CRC_SIZE];
    let mut dst_crc = [0u8; DELTA_CRC_SIZE];
    src_crc.copy_from_slice(&data[crc_offset..crc_offset + DELTA_CRC_SIZE]);
    dst_crc.copy_from_slice(&data[crc_offset + DELTA_CRC_SIZE..crc_offset + 2 * DELTA_CRC_SIZE]);

    let mut pos = DELTA_HEADER_SIZE;
    let mut commands = Vec::new();
    let mut saw_end = false;

    while pos < data.len() {
        let t = data[pos];
        pos += 1;
        match t {
            DELTA_CMD_END => { saw_end = true; break; }

            DELTA_CMD_COPY => {
                if pos + DELTA_COPY_PAYLOAD > data.len() { return Err(DeltaError::UnexpectedEof); }
                let src    = read_u32(data, pos); pos += DELTA_U32_SIZE;
                let dst    = read_u32(data, pos); pos += DELTA_U32_SIZE;
                let length = read_u32(data, pos); pos += DELTA_U32_SIZE;
                validate_placed_range(dst, length, version_size, "copy")?;
                commands.push(PlacedCommand::Copy { src, dst, length });
            }

            DELTA_CMD_ADD => {
                if pos + DELTA_ADD_HEADER > data.len() { return Err(DeltaError::UnexpectedEof); }
                let dst    = read_u32(data, pos); pos += DELTA_U32_SIZE;
                let length = read_u32(data, pos); pos += DELTA_U32_SIZE;
                if pos + length > data.len() { return Err(DeltaError::UnexpectedEof); }
                validate_placed_range(dst, length, version_size, "add")?;
                commands.push(PlacedCommand::Add { dst, data: data[pos..pos + length].to_vec() });
                pos += length;
            }

            // V4-only commands arriving in a V3 stream: specific error.
            DELTA_CMD_BIGCOPY | DELTA_CMD_BIGADD | DELTA_CMD_MOVE | DELTA_CMD_BIGMOVE => {
                return Err(DeltaError::InvalidFormat(format!(
                    "command type {} requires DLT\\x04 format", t
                )));
            }

            _ => {
                return Err(DeltaError::InvalidFormat(format!(
                    "unknown command type: {}", t
                )));
            }
        }
    }

    finish_decode(commands, saw_end, pos, data.len(), inplace, version_size, src_crc, dst_crc)
}

fn decode_large(
    data: &[u8],
) -> Result<(Vec<PlacedCommand>, bool, usize, [u8; DELTA_CRC_SIZE], [u8; DELTA_CRC_SIZE]), DeltaError> {
    if data.len() < DELTA_HEADER_SIZE_LARGE {
        return Err(DeltaError::InvalidFormat("not a delta file".into()));
    }
    let inplace = data[4] & DELTA_FLAG_INPLACE != 0;
    let version_size = u64::from_be_bytes([
        data[5], data[6], data[7],  data[8],
        data[9], data[10], data[11], data[12],
    ]) as usize;
    let crc_offset = 13; // 4 + 1 + 8
    let mut src_crc = [0u8; DELTA_CRC_SIZE];
    let mut dst_crc = [0u8; DELTA_CRC_SIZE];
    src_crc.copy_from_slice(&data[crc_offset..crc_offset + DELTA_CRC_SIZE]);
    dst_crc.copy_from_slice(&data[crc_offset + DELTA_CRC_SIZE..crc_offset + 2 * DELTA_CRC_SIZE]);

    let mut pos = DELTA_HEADER_SIZE_LARGE;
    let mut commands = Vec::new();
    let mut saw_end = false;

    while pos < data.len() {
        let t = data[pos];
        pos += 1;
        match t {
            DELTA_CMD_END => { saw_end = true; break; }

            DELTA_CMD_COPY => {
                if pos + DELTA_COPY_PAYLOAD > data.len() { return Err(DeltaError::UnexpectedEof); }
                let src    = read_u32(data, pos); pos += DELTA_U32_SIZE;
                let dst    = read_u32(data, pos); pos += DELTA_U32_SIZE;
                let length = read_u32(data, pos); pos += DELTA_U32_SIZE;
                validate_placed_range(dst, length, version_size, "copy")?;
                commands.push(PlacedCommand::Copy { src, dst, length });
            }

            DELTA_CMD_ADD => {
                if pos + DELTA_ADD_HEADER > data.len() { return Err(DeltaError::UnexpectedEof); }
                let dst    = read_u32(data, pos); pos += DELTA_U32_SIZE;
                let length = read_u32(data, pos); pos += DELTA_U32_SIZE;
                if pos + length > data.len() { return Err(DeltaError::UnexpectedEof); }
                validate_placed_range(dst, length, version_size, "add")?;
                commands.push(PlacedCommand::Add { dst, data: data[pos..pos + length].to_vec() });
                pos += length;
            }

            DELTA_CMD_BIGCOPY => {
                if pos + DELTA_BIGCOPY_PAYLOAD > data.len() { return Err(DeltaError::UnexpectedEof); }
                let src    = read_u64(data, pos); pos += DELTA_U64_SIZE;
                let dst    = read_u64(data, pos); pos += DELTA_U64_SIZE;
                let length = read_u64(data, pos); pos += DELTA_U64_SIZE;
                validate_placed_range(dst, length, version_size, "bigcopy")?;
                commands.push(PlacedCommand::Copy { src, dst, length });
            }

            DELTA_CMD_BIGADD => {
                if pos + DELTA_BIGADD_HEADER > data.len() { return Err(DeltaError::UnexpectedEof); }
                let dst    = read_u64(data, pos); pos += DELTA_U64_SIZE;
                let length = read_u64(data, pos); pos += DELTA_U64_SIZE;
                if pos + length > data.len() { return Err(DeltaError::UnexpectedEof); }
                validate_placed_range(dst, length, version_size, "bigadd")?;
                commands.push(PlacedCommand::Add { dst, data: data[pos..pos + length].to_vec() });
                pos += length;
            }

            DELTA_CMD_MOVE => {
                if pos + DELTA_COPY_PAYLOAD > data.len() { return Err(DeltaError::UnexpectedEof); }
                let src    = read_u32(data, pos); pos += DELTA_U32_SIZE;
                let dst    = read_u32(data, pos); pos += DELTA_U32_SIZE;
                let length = read_u32(data, pos); pos += DELTA_U32_SIZE;
                validate_placed_range(dst, length, version_size, "move")?;
                commands.push(PlacedCommand::Move { src, dst, length });
            }

            DELTA_CMD_BIGMOVE => {
                if pos + DELTA_BIGCOPY_PAYLOAD > data.len() { return Err(DeltaError::UnexpectedEof); }
                let src    = read_u64(data, pos); pos += DELTA_U64_SIZE;
                let dst    = read_u64(data, pos); pos += DELTA_U64_SIZE;
                let length = read_u64(data, pos); pos += DELTA_U64_SIZE;
                validate_placed_range(dst, length, version_size, "bigmove")?;
                commands.push(PlacedCommand::Move { src, dst, length });
            }

            _ => {
                return Err(DeltaError::InvalidFormat(format!(
                    "unknown command type: {}", t
                )));
            }
        }
    }

    finish_decode(commands, saw_end, pos, data.len(), inplace, version_size, src_crc, dst_crc)
}

#[inline]
fn read_u32(data: &[u8], pos: usize) -> usize {
    u32::from_be_bytes([data[pos], data[pos+1], data[pos+2], data[pos+3]]) as usize
}

#[inline]
fn read_u64(data: &[u8], pos: usize) -> usize {
    u64::from_be_bytes([
        data[pos], data[pos+1], data[pos+2], data[pos+3],
        data[pos+4], data[pos+5], data[pos+6], data[pos+7],
    ]) as usize
}

fn finish_decode(
    commands: Vec<PlacedCommand>,
    saw_end: bool,
    pos: usize,
    data_len: usize,
    inplace: bool,
    version_size: usize,
    src_crc: [u8; DELTA_CRC_SIZE],
    dst_crc: [u8; DELTA_CRC_SIZE],
) -> Result<(Vec<PlacedCommand>, bool, usize, [u8; DELTA_CRC_SIZE], [u8; DELTA_CRC_SIZE]), DeltaError> {
    if !saw_end {
        return Err(DeltaError::InvalidFormat("missing END command".into()));
    }
    if pos != data_len {
        return Err(DeltaError::InvalidFormat("trailing data after END".into()));
    }
    Ok((commands, inplace, version_size, src_crc, dst_crc))
}

// ── Utilities ─────────────────────────────────────────────────────────────────

/// Check if binary data is an in-place delta (DLT\x03 or DLT\x04).
pub fn is_inplace_delta(data: &[u8]) -> bool {
    if data.len() < 5 {
        return false;
    }
    let magic = &data[..4];
    (magic == DELTA_MAGIC || magic == DELTA_MAGIC_LARGE)
        && data[4] & DELTA_FLAG_INPLACE != 0
}

fn validate_placed_range(dst: usize, length: usize, version_size: usize, kind: &str) -> Result<(), DeltaError> {
    if dst > version_size || length > version_size.saturating_sub(dst) {
        return Err(DeltaError::InvalidFormat(format!(
            "{} command exceeds version size",
            kind
        )));
    }
    Ok(())
}
