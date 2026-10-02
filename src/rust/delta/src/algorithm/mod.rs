//! The differencing algorithms of Ajtai, Burns, Fagin, Long and Stockmeyer,
//! JACM 49(3), 2002.  Section and figure numbers in this module refer to
//! that paper; R is the reference string and V the version.

pub mod correcting;
pub mod greedy;
pub mod onepass;

use crate::types::{Algorithm, Command, DiffOptions};

/// Computes a delta from `r` to `v` with the chosen algorithm.
pub fn diff(algorithm: Algorithm, r: &[u8], v: &[u8], opts: &DiffOptions) -> Vec<Command> {
    match algorithm {
        Algorithm::Greedy => greedy::diff_greedy(r, v, opts),
        Algorithm::Onepass => onepass::diff_onepass(r, v, opts),
        Algorithm::Correcting => correcting::diff_correcting(r, v, opts),
    }
}

/// Computes a delta with the default options.
pub fn diff_default(algorithm: Algorithm, r: &[u8], v: &[u8]) -> Vec<Command> {
    diff(algorithm, r, v, &DiffOptions::default())
}

/// Returns the number of seeds, substrings of length `p`, in a string of
/// length `len`.
fn seed_count(len: usize, p: usize) -> usize {
    (len + 1).saturating_sub(p)
}

/// Returns the length of the longest common prefix of `a` and `b`.
#[inline]
fn common_prefix(a: &[u8], b: &[u8]) -> usize {
    a.iter().zip(b).take_while(|(x, y)| x == y).count()
}

/// Returns the length of the longest common suffix of `a` and `b`.
#[inline]
fn common_suffix(a: &[u8], b: &[u8]) -> usize {
    a.iter()
        .rev()
        .zip(b.iter().rev())
        .take_while(|(x, y)| x == y)
        .count()
}

/// Names the fingerprint index in verbose output.
fn index_name(opts: &DiffOptions) -> &'static str {
    if opts.use_splay {
        "splay tree"
    } else {
        "hash table"
    }
}

/// Returns `part` as a percentage of `whole`, or 0 if `whole` is 0.
fn percent(part: usize, whole: usize) -> f64 {
    if whole == 0 {
        0.0
    } else {
        part as f64 / whole as f64 * 100.0
    }
}

/// Prints to stderr the statistics that every algorithm reports when
/// verbose.
fn print_command_stats(commands: &[Command]) {
    let mut copy_lens = Vec::new();
    let mut total_add = 0;
    let mut num_adds = 0;
    for cmd in commands {
        match cmd {
            Command::Copy { length, .. } => copy_lens.push(*length),
            Command::Add { data } => {
                total_add += data.len();
                num_adds += 1;
            }
        }
    }
    let total_copy: usize = copy_lens.iter().sum();
    let total_out = total_copy + total_add;
    eprintln!(
        "  result: {} copies ({} bytes), {} adds ({} bytes)\n  \
         result: copy coverage {:.1}%, output {} bytes",
        copy_lens.len(),
        total_copy,
        num_adds,
        total_add,
        percent(total_copy, total_out),
        total_out
    );
    if copy_lens.is_empty() {
        return;
    }
    copy_lens.sort_unstable();
    eprintln!(
        "  copies: {} regions, min={} max={} mean={:.1} median={} bytes",
        copy_lens.len(),
        copy_lens[0],
        copy_lens[copy_lens.len() - 1],
        total_copy as f64 / copy_lens.len() as f64,
        copy_lens[copy_lens.len() / 2]
    );
}
