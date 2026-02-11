pub mod greedy;
pub mod onepass;
pub mod correcting;

use crate::types::{Algorithm, Command, SEED_LEN, TABLE_SIZE};

/// Dispatch to the appropriate differencing algorithm.
pub fn diff(
    algorithm: Algorithm,
    r: &[u8],
    v: &[u8],
    p: usize,
    q: usize,
) -> Vec<Command> {
    match algorithm {
        Algorithm::Greedy => greedy::diff_greedy(r, v, p, q),
        Algorithm::Onepass => onepass::diff_onepass(r, v, p, q),
        Algorithm::Correcting => correcting::diff_correcting(r, v, p, q, 256),
    }
}

/// Dispatch with default parameters.
pub fn diff_default(algorithm: Algorithm, r: &[u8], v: &[u8]) -> Vec<Command> {
    diff(algorithm, r, v, SEED_LEN, TABLE_SIZE)
}
