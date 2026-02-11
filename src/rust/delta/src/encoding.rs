use crate::types::{DeltaError, PlacedCommand, DELTA_FLAG_INPLACE, DELTA_MAGIC};

/// Encode placed commands to the unified binary delta format.
///
/// Format:
///   Header: magic (4 bytes) + flags (1 byte) + version_size (u32 BE)
///   Commands:
///     END:  type=0
///     COPY: type=1, src:u32, dst:u32, len:u32
///     ADD:  type=2, dst:u32, len:u32, data
pub fn encode_delta(
    commands: &[PlacedCommand],
    inplace: bool,
    version_size: usize,
) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(DELTA_MAGIC);
    out.push(if inplace { DELTA_FLAG_INPLACE } else { 0 });
    out.extend_from_slice(&(version_size as u32).to_be_bytes());

    for cmd in commands {
        match cmd {
            PlacedCommand::Copy { src, dst, length } => {
                out.push(1);
                out.extend_from_slice(&(*src as u32).to_be_bytes());
                out.extend_from_slice(&(*dst as u32).to_be_bytes());
                out.extend_from_slice(&(*length as u32).to_be_bytes());
            }
            PlacedCommand::Add { dst, data } => {
                out.push(2);
                out.extend_from_slice(&(*dst as u32).to_be_bytes());
                out.extend_from_slice(&(data.len() as u32).to_be_bytes());
                out.extend_from_slice(data);
            }
        }
    }

    out.push(0); // END
    out
}

/// Decode the unified binary delta format.
///
/// Returns (commands, inplace, version_size).
pub fn decode_delta(
    data: &[u8],
) -> Result<(Vec<PlacedCommand>, bool, usize), DeltaError> {
    if data.len() < 9 || &data[..4] != DELTA_MAGIC {
        return Err(DeltaError::InvalidFormat("not a delta file".into()));
    }

    let inplace = data[4] & DELTA_FLAG_INPLACE != 0;
    let version_size =
        u32::from_be_bytes([data[5], data[6], data[7], data[8]]) as usize;
    let mut pos = 9;
    let mut commands = Vec::new();

    while pos < data.len() {
        let t = data[pos];
        pos += 1;

        match t {
            0 => break, // END

            1 => {
                // COPY: src:u32, dst:u32, len:u32
                if pos + 12 > data.len() {
                    return Err(DeltaError::UnexpectedEof);
                }
                let src = u32::from_be_bytes([
                    data[pos], data[pos + 1], data[pos + 2], data[pos + 3],
                ]) as usize;
                pos += 4;
                let dst = u32::from_be_bytes([
                    data[pos], data[pos + 1], data[pos + 2], data[pos + 3],
                ]) as usize;
                pos += 4;
                let length = u32::from_be_bytes([
                    data[pos], data[pos + 1], data[pos + 2], data[pos + 3],
                ]) as usize;
                pos += 4;
                commands.push(PlacedCommand::Copy { src, dst, length });
            }

            2 => {
                // ADD: dst:u32, len:u32, data
                if pos + 8 > data.len() {
                    return Err(DeltaError::UnexpectedEof);
                }
                let dst = u32::from_be_bytes([
                    data[pos], data[pos + 1], data[pos + 2], data[pos + 3],
                ]) as usize;
                pos += 4;
                let length = u32::from_be_bytes([
                    data[pos], data[pos + 1], data[pos + 2], data[pos + 3],
                ]) as usize;
                pos += 4;
                if pos + length > data.len() {
                    return Err(DeltaError::UnexpectedEof);
                }
                commands.push(PlacedCommand::Add {
                    dst,
                    data: data[pos..pos + length].to_vec(),
                });
                pos += length;
            }

            _ => {
                return Err(DeltaError::InvalidFormat(format!(
                    "unknown command type: {}",
                    t
                )));
            }
        }
    }

    Ok((commands, inplace, version_size))
}

/// Check if binary data is an in-place delta.
pub fn is_inplace_delta(data: &[u8]) -> bool {
    data.len() >= 5 && &data[..4] == DELTA_MAGIC && data[4] & DELTA_FLAG_INPLACE != 0
}
