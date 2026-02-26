use std::fmt;

// ============================================================================
// Constants (Ajtai, Burns, Fagin, Long — JACM 2002)
//
// Hash parameters (Section 2.1.3):
//   p (SEED_LEN)  = minimum match length / fingerprint window
//   b (HASH_BASE) = polynomial base for Karp-Rabin hash
//   Q (HASH_MOD)  = Mersenne prime 2^61-1 for fingerprint arithmetic
//   q (TABLE_SIZE) = hash table capacity; correcting uses checkpointing
//                    (Section 8) to fit any |R| into fixed-size table
// Delta commands: Section 2.1.1
// ============================================================================

pub const SEED_LEN: usize = 16;
pub const TABLE_SIZE: usize = 1048573;      // largest prime < 2^20
pub const MAX_TABLE_SIZE: usize = 1_073_741_827; // prime near 2^30; default ceiling for auto-sizing
                                                  // Section 8: correcting uses checkpointing to fit any |R|
pub const HASH_BASE: u64 = 263;
pub const HASH_MOD: u64 = (1 << 61) - 1; // Mersenne prime 2^61-1
pub const DELTA_MAGIC: &[u8; 4] = b"DLT\x03";
pub const DELTA_FLAG_INPLACE: u8 = 0x01;
pub const DELTA_CMD_END: u8 = 0;
pub const DELTA_CMD_COPY: u8 = 1;
pub const DELTA_CMD_ADD: u8 = 2;
pub const DELTA_CRC_SIZE: usize = 8;
pub const DELTA_HEADER_SIZE: usize = 25; // magic(4) + flags(1) + version_size(4) + src_crc(8) + dst_crc(8)
pub const DELTA_U32_SIZE: usize = 4;
pub const DELTA_COPY_PAYLOAD: usize = 12; // src(4) + dst(4) + len(4)
pub const DELTA_ADD_HEADER: usize = 8; // dst(4) + len(4)
pub const DELTA_BUF_CAP: usize = 256;

// ============================================================================
// Delta Commands (Section 2.1.1)
// ============================================================================

/// Algorithm output: copy from reference or add literal bytes.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Command {
    Copy { offset: usize, length: usize },
    Add { data: Vec<u8> },
}

impl fmt::Display for Command {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Command::Copy { offset, length } => write!(f, "COPY(off={}, len={})", offset, length),
            Command::Add { data } => {
                if data.len() <= 20 {
                    write!(f, "ADD({:?})", data)
                } else {
                    write!(f, "ADD(len={})", data.len())
                }
            }
        }
    }
}

// ============================================================================
// Placed Commands — ready for encoding and application
// ============================================================================

/// A command with explicit source and destination offsets.
///
/// For standard deltas, `Copy::src` is an offset into the reference and
/// `Copy::dst` is the write position in the output.  For in-place deltas,
/// both refer to positions in the shared working buffer.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PlacedCommand {
    Copy { src: usize, dst: usize, length: usize },
    Add { dst: usize, data: Vec<u8> },
}

impl fmt::Display for PlacedCommand {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            PlacedCommand::Copy { src, dst, length } => {
                write!(f, "COPY(src={}, dst={}, len={})", src, dst, length)
            }
            PlacedCommand::Add { dst, data } => {
                if data.len() <= 20 {
                    write!(f, "ADD(dst={}, {:?})", dst, data)
                } else {
                    write!(f, "ADD(dst={}, len={})", dst, data.len())
                }
            }
        }
    }
}

// ============================================================================
// Algorithm and Policy enums
// ============================================================================

/// Differencing algorithm selection.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Algorithm {
    /// Optimal under simple cost; O(|V|·|R|) time, O(|R|) space (Section 3).
    Greedy,
    /// Linear time and near-constant space; concurrent scan of R and V (Section 4).
    Onepass,
    /// Near-optimal, 1.5-pass; hash table with fingerprint checkpointing (Sections 7–8).
    Correcting,
}

/// Cycle-breaking policy for in-place reordering (Section 4.3 of Burns et al. 2003).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CyclePolicy {
    /// Break each cycle at the copy with the shortest length, minimising literal bytes added.
    Localmin,
    /// Break each cycle at the first remaining vertex; simpler but ignores copy lengths.
    Constant,
}

/// Tuning parameters for differencing algorithms.
#[derive(Clone, Debug)]
pub struct DiffOptions {
    /// Seed length: minimum match length and fingerprint window (Section 2.1.3).
    pub p: usize,
    /// Hash table capacity floor; algorithms auto-size upward from input length.
    pub q: usize,
    /// Lookback buffer depth for the correcting algorithm (Section 5.2).
    pub buf_cap: usize,
    /// Print per-run statistics to stderr when true.
    pub verbose: bool,
    /// Use a Sleator-Tarjan splay tree instead of a hash table for R lookups.
    pub use_splay: bool,
    /// Auto-sizing ceiling; prevents unbounded memory use on very large inputs.
    pub max_table: usize,
}

impl Default for DiffOptions {
    fn default() -> Self {
        Self {
            p: SEED_LEN,
            q: TABLE_SIZE,
            buf_cap: DELTA_BUF_CAP,
            verbose: false,
            use_splay: false,
            max_table: MAX_TABLE_SIZE,
        }
    }
}

// ============================================================================
// Error type
// ============================================================================

/// Errors produced by delta encoding, decoding, and I/O.
#[derive(Debug)]
pub enum DeltaError {
    /// The binary data does not match the expected delta format.
    InvalidFormat(String),
    /// The delta data ended before all commands were read.
    UnexpectedEof,
    /// An I/O error occurred while reading or writing a file.
    IoError(std::io::Error),
}

impl fmt::Display for DeltaError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            DeltaError::InvalidFormat(msg) => write!(f, "invalid delta format: {}", msg),
            DeltaError::UnexpectedEof => write!(f, "unexpected end of delta data"),
            DeltaError::IoError(e) => write!(f, "I/O error: {}", e),
        }
    }
}

impl std::error::Error for DeltaError {}

impl From<std::io::Error> for DeltaError {
    fn from(e: std::io::Error) -> Self {
        DeltaError::IoError(e)
    }
}

// ============================================================================
// Summary statistics
// ============================================================================

/// Summary statistics for a set of commands.
#[derive(Debug)]
pub struct DeltaSummary {
    pub num_commands: usize,
    pub num_copies: usize,
    pub num_adds: usize,
    pub copy_bytes: usize,
    pub add_bytes: usize,
    pub total_output_bytes: usize,
}

/// Compute summary statistics from algorithm-level commands.
pub fn delta_summary(commands: &[Command]) -> DeltaSummary {
    let mut num_copies = 0;
    let mut num_adds = 0;
    let mut copy_bytes = 0;
    let mut add_bytes = 0;
    for cmd in commands {
        match cmd {
            Command::Copy { length, .. } => {
                num_copies += 1;
                copy_bytes += length;
            }
            Command::Add { data } => {
                num_adds += 1;
                add_bytes += data.len();
            }
        }
    }
    DeltaSummary {
        num_commands: commands.len(),
        num_copies,
        num_adds,
        copy_bytes,
        add_bytes,
        total_output_bytes: copy_bytes + add_bytes,
    }
}

/// Compute summary statistics from placed commands.
pub fn placed_summary(commands: &[PlacedCommand]) -> DeltaSummary {
    let mut num_copies = 0;
    let mut num_adds = 0;
    let mut copy_bytes = 0;
    let mut add_bytes = 0;
    for cmd in commands {
        match cmd {
            PlacedCommand::Copy { length, .. } => {
                num_copies += 1;
                copy_bytes += length;
            }
            PlacedCommand::Add { data, .. } => {
                num_adds += 1;
                add_bytes += data.len();
            }
        }
    }
    DeltaSummary {
        num_commands: commands.len(),
        num_copies,
        num_adds,
        copy_bytes,
        add_bytes,
        total_output_bytes: copy_bytes + add_bytes,
    }
}
