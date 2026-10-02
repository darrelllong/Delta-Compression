//! Differential compression: encode a version of a file as a delta against
//! a reference, and reconstruct it from the reference and the delta.
//!
//! The differencing algorithms are those of Ajtai, Burns, Fagin, Long and
//! Stockmeyer, "Compactly Encoding Unstructured Inputs with Differential
//! Compression", JACM 49(3), 2002.  A delta can be converted so that the
//! version is reconstructed in the buffer that holds the reference, by the
//! method of Burns, Long and Stockmeyer, "In-Place Reconstruction of
//! Version Differences", IEEE TKDE 15(4), 2003.
//!
//! ```
//! use delta::{apply_delta, diff, Algorithm, DiffOptions};
//!
//! let reference = b"the quick brown fox jumps over the lazy dog";
//! let version = b"the quick brown cat jumps over the lazy dog";
//! let opts = DiffOptions { p: 4, ..DiffOptions::default() };
//! let commands = diff(Algorithm::Onepass, reference, version, &opts);
//! assert_eq!(apply_delta(reference, &commands), version);
//! ```

pub mod algorithm;
pub mod apply;
pub mod encoding;
pub mod hash;
pub mod inplace;
pub mod splay;
pub mod types;

pub use algorithm::correcting::{diff_correcting, diff_correcting_default};
pub use algorithm::greedy::{diff_greedy, diff_greedy_default};
pub use algorithm::onepass::{diff_onepass, diff_onepass_default};
pub use algorithm::{diff, diff_default};
pub use apply::{
    apply_delta, apply_delta_inplace, apply_delta_to, apply_placed_inplace_to, apply_placed_to,
    output_size, place_commands, unplace_commands, validate_placed_commands,
};
pub use encoding::{decode_delta, encode_delta, encode_delta_large, is_inplace_delta};
pub use hash::{
    crc64_xz, fingerprint, fp_to_index, is_prime, mod_mersenne, next_prime, precompute_bp,
    RollingHash,
};
pub use inplace::{make_inplace, InplaceStats};
pub use splay::SplayTree;
pub use types::{
    delta_summary, placed_summary, Algorithm, Command, CyclePolicy, DeltaError, DeltaSummary,
    DiffOptions, PlacedCommand, DELTA_ADD_HEADER, DELTA_BIGADD_HEADER, DELTA_BIGCOPY_PAYLOAD,
    DELTA_BUF_CAP, DELTA_CMD_ADD, DELTA_CMD_BIGADD, DELTA_CMD_BIGCOPY, DELTA_CMD_BIGMOVE,
    DELTA_CMD_COPY, DELTA_CMD_END, DELTA_CMD_MOVE, DELTA_COPY_PAYLOAD, DELTA_CRC_SIZE,
    DELTA_FLAG_INPLACE, DELTA_HEADER_SIZE, DELTA_HEADER_SIZE_LARGE, DELTA_MAGIC, DELTA_MAGIC_LARGE,
    DELTA_U32_SIZE, DELTA_U64_SIZE, HASH_BASE, HASH_MOD, MAX_TABLE_SIZE, SEED_LEN, TABLE_SIZE,
};
