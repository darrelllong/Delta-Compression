pub mod greedy;
pub mod onepass;
pub mod correcting;

use crate::types::{Algorithm, Command, SEED_LEN, TABLE_SIZE};

/// Dispatch to the appropriate differencing algorithm.
///
/// `min_copy`: minimum match length to emit as a COPY command.
/// Matches shorter than this are discarded (absorbed into surrounding ADDs).
/// A value of 0 means use the seed length `p` as the natural floor.
pub fn diff(
    algorithm: Algorithm,
    r: &[u8],
    v: &[u8],
    p: usize,
    q: usize,
    verbose: bool,
    use_splay: bool,
    min_copy: usize,
) -> Vec<Command> {
    match algorithm {
        Algorithm::Greedy => greedy::diff_greedy(r, v, p, q, verbose, use_splay, min_copy),
        Algorithm::Onepass => onepass::diff_onepass(r, v, p, q, verbose, use_splay, min_copy),
        Algorithm::Correcting => correcting::diff_correcting(r, v, p, q, 256, verbose, use_splay, min_copy),
    }
}

/// Dispatch with default parameters.
pub fn diff_default(algorithm: Algorithm, r: &[u8], v: &[u8]) -> Vec<Command> {
    diff(algorithm, r, v, SEED_LEN, TABLE_SIZE, false, false, 0)
}
