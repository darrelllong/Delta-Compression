//! Commands, options, errors, and the constants of the wire format.
//!
//! Section numbers refer to Ajtai, Burns, Fagin, Long and Stockmeyer,
//! JACM 49(3), 2002, unless another paper is named.

use std::fmt;

/// Default seed length p: the fingerprint window and the shortest match
/// (Section 2.1.3).
pub const SEED_LEN: usize = 16;
/// Default hash table capacity floor q: the largest prime below 2^20.
pub const TABLE_SIZE: usize = 1_048_573;
/// Default ceiling for the correcting algorithm's table capacity: the
/// smallest prime above 2^30.
pub const MAX_TABLE_SIZE: usize = 1_073_741_827;
/// Base of the Karp-Rabin polynomial.
pub const HASH_BASE: u64 = 263;
/// Modulus of the Karp-Rabin polynomial: the Mersenne prime 2^61 - 1.
pub const HASH_MOD: u64 = (1 << 61) - 1;
/// Default depth, in commands, of the correcting algorithm's lookback
/// buffer.
pub const DELTA_BUF_CAP: usize = 256;

/// Magic of the 32-bit format.
pub const DELTA_MAGIC: &[u8; 4] = b"DLT\x03";
/// Magic of the format with 64-bit fields and MOVE commands.
pub const DELTA_MAGIC_LARGE: &[u8; 4] = b"DLT\x04";
/// Header flag bit: the commands are ordered for in-place application.
pub const DELTA_FLAG_INPLACE: u8 = 0x01;

/// Tag of END, which follows the last command.
pub const DELTA_CMD_END: u8 = 0;
/// Tag of COPY, which has u32 fields.
pub const DELTA_CMD_COPY: u8 = 1;
/// Tag of ADD, which has u32 fields.
pub const DELTA_CMD_ADD: u8 = 2;
/// Tag of BIGCOPY: COPY with u64 fields.  `DLT\x04` only.
pub const DELTA_CMD_BIGCOPY: u8 = 3;
/// Tag of BIGADD: ADD with u64 fields.  `DLT\x04` only.
pub const DELTA_CMD_BIGADD: u8 = 4;
/// Tag of MOVE, which has u32 fields.  `DLT\x04` only.
pub const DELTA_CMD_MOVE: u8 = 5;
/// Tag of BIGMOVE: MOVE with u64 fields.  `DLT\x04` only.
pub const DELTA_CMD_BIGMOVE: u8 = 6;

/// Bytes in a CRC-64 digest.
pub const DELTA_CRC_SIZE: usize = 8;
/// Bytes in an offset or length of a COPY, ADD or MOVE command.
pub const DELTA_U32_SIZE: usize = 4;
/// Bytes in an offset or length of a BIG command.
pub const DELTA_U64_SIZE: usize = 8;
/// `DLT\x03` header bytes: magic(4) + flags(1) + version_size(4) + two CRCs(16).
pub const DELTA_HEADER_SIZE: usize = 25;
/// `DLT\x04` header bytes: magic(4) + flags(1) + version_size(8) + two CRCs(16).
pub const DELTA_HEADER_SIZE_LARGE: usize = 29;
/// Bytes after a COPY or MOVE tag: src(4) + dst(4) + len(4).
pub const DELTA_COPY_PAYLOAD: usize = 12;
/// Bytes after an ADD tag and before its literal: dst(4) + len(4).
pub const DELTA_ADD_HEADER: usize = 8;
/// Bytes after a BIGCOPY or BIGMOVE tag: src(8) + dst(8) + len(8).
pub const DELTA_BIGCOPY_PAYLOAD: usize = 24;
/// Bytes after a BIGADD tag and before its literal: dst(8) + len(8).
pub const DELTA_BIGADD_HEADER: usize = 16;

/// The output of a differencing algorithm (Section 2.1.1).  Commands are
/// applied in order, each appending to the version.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Command {
    /// Append `length` bytes of the reference starting at `offset`.
    Copy { offset: usize, length: usize },
    /// Append literal bytes.
    Add { data: Vec<u8> },
}

impl fmt::Display for Command {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Command::Copy { offset, length } => write!(f, "COPY(off={}, len={})", offset, length),
            Command::Add { data } if data.len() <= 20 => write!(f, "ADD({:?})", data),
            Command::Add { data } => write!(f, "ADD(len={})", data.len()),
        }
    }
}

/// A command with an explicit destination, as stored in a delta file.
///
/// In a standard delta `Copy::src` is an offset in the reference and `dst`
/// an offset in the output.  In an in-place delta both are offsets in the
/// one buffer that starts out holding the reference.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PlacedCommand {
    Copy {
        src: usize,
        dst: usize,
        length: usize,
    },
    Add {
        dst: usize,
        data: Vec<u8>,
    },
    /// Copy output already written: `src + length <= dst`.  `DLT\x04` only.
    Move {
        src: usize,
        dst: usize,
        length: usize,
    },
}

impl fmt::Display for PlacedCommand {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            PlacedCommand::Copy { src, dst, length } => {
                write!(f, "COPY(src={}, dst={}, len={})", src, dst, length)
            }
            PlacedCommand::Add { dst, data } if data.len() <= 20 => {
                write!(f, "ADD(dst={}, {:?})", dst, data)
            }
            PlacedCommand::Add { dst, data } => write!(f, "ADD(dst={}, len={})", dst, data.len()),
            PlacedCommand::Move { src, dst, length } => {
                write!(f, "MOVE(src={}, dst={}, len={})", src, dst, length)
            }
        }
    }
}

/// Differencing algorithm.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Algorithm {
    /// Optimal under the simple cost measure if p <= 2; O(|V| |R|) time,
    /// O(|R|) space (Section 3).
    Greedy,
    /// Linear time, space set by the table size; scans R and V together
    /// (Section 4).
    Onepass,
    /// Indexes R, then scans V and corrects earlier commands when a later
    /// match covers them; checkpointing bounds the index (Sections 7 and 8).
    Correcting,
}

/// How in-place conversion chooses the copy to turn into an add when copies
/// form a cycle (Burns, Long and Stockmeyer, IEEE TKDE 2003, Section 4.3).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CyclePolicy {
    /// The shortest copy on the cycle, which adds the fewest literal bytes.
    Localmin,
    /// The lowest-numbered remaining copy, without looking for the cycle.
    Constant,
}

/// Tuning parameters for the differencing algorithms.
#[derive(Clone, Debug)]
pub struct DiffOptions {
    /// Seed length: fingerprint window and minimum match length.  At least 1.
    pub p: usize,
    /// Floor for the table capacity of the one-pass and correcting
    /// algorithms, which size the table upward from the length of the
    /// reference.  The greedy algorithm ignores it.
    pub q: usize,
    /// Number of commands the correcting algorithm can still revise
    /// (Section 5.2).
    pub buf_cap: usize,
    /// Print statistics to stderr.
    pub verbose: bool,
    /// Index fingerprints in a splay tree instead of a hash table.
    pub use_splay: bool,
    /// Ceiling for the correcting algorithm's table capacity.
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

/// An error in encoding, decoding, or validating a delta.
#[derive(Debug)]
pub enum DeltaError {
    /// The data is not a well-formed delta; the string says why.
    InvalidFormat(String),
    /// The data ends in the middle of a command.
    UnexpectedEof,
    /// A file could not be read or written.
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

/// Counts and byte totals for a list of commands.
#[derive(Debug)]
pub struct DeltaSummary {
    /// `num_copies + num_adds`.
    pub num_commands: usize,
    /// Copies, and moves if the commands are placed.
    pub num_copies: usize,
    /// Adds.
    pub num_adds: usize,
    /// Total length of the copies.
    pub copy_bytes: usize,
    /// Total length of the literals.
    pub add_bytes: usize,
    /// `copy_bytes + add_bytes`: the size of the reconstructed version.
    pub total_output_bytes: usize,
}

impl DeltaSummary {
    /// Summarizes commands given as (is a copy, bytes produced).
    fn of(commands: impl Iterator<Item = (bool, usize)>) -> Self {
        let mut s = DeltaSummary {
            num_commands: 0,
            num_copies: 0,
            num_adds: 0,
            copy_bytes: 0,
            add_bytes: 0,
            total_output_bytes: 0,
        };
        for (is_copy, bytes) in commands {
            s.num_commands += 1;
            if is_copy {
                s.num_copies += 1;
                s.copy_bytes += bytes;
            } else {
                s.num_adds += 1;
                s.add_bytes += bytes;
            }
        }
        s.total_output_bytes = s.copy_bytes + s.add_bytes;
        s
    }
}

/// Summarizes the output of a differencing algorithm.
pub fn delta_summary(commands: &[Command]) -> DeltaSummary {
    DeltaSummary::of(commands.iter().map(|cmd| match cmd {
        Command::Copy { length, .. } => (true, *length),
        Command::Add { data } => (false, data.len()),
    }))
}

/// Summarizes placed commands.  A move counts as a copy.
pub fn placed_summary(commands: &[PlacedCommand]) -> DeltaSummary {
    DeltaSummary::of(commands.iter().map(|cmd| match cmd {
        PlacedCommand::Copy { length, .. } | PlacedCommand::Move { length, .. } => (true, *length),
        PlacedCommand::Add { data, .. } => (false, data.len()),
    }))
}
