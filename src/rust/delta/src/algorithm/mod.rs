pub mod greedy;
pub mod onepass;
pub mod correcting;

use crate::types::{Algorithm, Command, DiffOptions};

/// Print shared verbose statistics for diff algorithm output.
pub(crate) fn print_command_stats(commands: &[Command]) {
    let mut copy_lens: Vec<usize> = Vec::new();
    let mut total_copy: usize = 0;
    let mut total_add: usize = 0;
    let mut num_copies: usize = 0;
    let mut num_adds: usize = 0;
    for cmd in commands {
        match cmd {
            Command::Copy { length, .. } => {
                total_copy += length;
                num_copies += 1;
                copy_lens.push(*length);
            }
            Command::Add { data } => {
                total_add += data.len();
                num_adds += 1;
            }
        }
    }
    let total_out = total_copy + total_add;
    let copy_pct = if total_out > 0 {
        total_copy as f64 / total_out as f64 * 100.0
    } else {
        0.0
    };
    eprintln!(
        "  result: {} copies ({} bytes), {} adds ({} bytes)\n  \
         result: copy coverage {:.1}%, output {} bytes",
        num_copies, total_copy, num_adds, total_add, copy_pct, total_out
    );
    if !copy_lens.is_empty() {
        copy_lens.sort();
        let mean = total_copy as f64 / copy_lens.len() as f64;
        let median = copy_lens[copy_lens.len() / 2];
        eprintln!(
            "  copies: {} regions, min={} max={} mean={:.1} median={} bytes",
            copy_lens.len(),
            copy_lens.first().unwrap(),
            copy_lens.last().unwrap(),
            mean,
            median
        );
    }
}

/// Dispatch to the appropriate differencing algorithm.
pub fn diff(
    algorithm: Algorithm,
    r: &[u8],
    v: &[u8],
    opts: &DiffOptions,
) -> Vec<Command> {
    match algorithm {
        Algorithm::Greedy => greedy::diff_greedy(r, v, opts),
        Algorithm::Onepass => onepass::diff_onepass(r, v, opts),
        Algorithm::Correcting => correcting::diff_correcting(r, v, opts),
    }
}

/// Dispatch with default parameters.
pub fn diff_default(algorithm: Algorithm, r: &[u8], v: &[u8]) -> Vec<Command> {
    diff(algorithm, r, v, &DiffOptions::default())
}
