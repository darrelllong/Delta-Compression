pub mod types;
pub mod hash;
pub mod encoding;
pub mod algorithm;
pub mod apply;
pub mod inplace;

// Re-exports for convenience
pub use types::{
    Algorithm, Command, CyclePolicy, DeltaError, DeltaSummary, PlacedCommand,
    DELTA_FLAG_INPLACE, DELTA_MAGIC, HASH_BASE, HASH_MOD, SEED_LEN, TABLE_SIZE,
};
pub use hash::{fingerprint, fp_to_index, is_prime, mod_mersenne, next_prime, precompute_bp, RollingHash};
pub use encoding::{decode_delta, encode_delta, is_inplace_delta};
pub use algorithm::{diff, diff_default};
pub use algorithm::greedy::{diff_greedy, diff_greedy_default};
pub use algorithm::onepass::{diff_onepass, diff_onepass_default};
pub use algorithm::correcting::{diff_correcting, diff_correcting_default};
pub use apply::{
    apply_delta, apply_delta_inplace, apply_delta_to,
    apply_placed_inplace_to, apply_placed_to,
    output_size, place_commands,
};
pub use inplace::make_inplace;
pub use types::{delta_summary, placed_summary};
