//! A workload for pilot-bench.
//!
//! ```text
//! pilot_delta <operation>
//! ```
//!
//! Runs the operation several times on 1 MiB of pseudo-random data and a
//! version of it with about 5% of its bytes replaced, and prints the
//! throughput in MiB/s.
//!
//! ```text
//! encode_greedy_1m      diff with the greedy algorithm
//! encode_onepass_1m     diff with the one-pass algorithm
//! encode_correcting_1m  diff with the correcting algorithm
//! decode_1m             decode and apply a one-pass delta
//! inplace_1m            convert the commands of a one-pass delta to in-place form
//! ```

use std::hint::black_box;
use std::time::Instant;

use delta::{
    apply_placed_to, crc64_xz, decode_delta, diff, encode_delta, make_inplace, place_commands,
    Algorithm, CyclePolicy, DiffOptions,
};

const SIZE: usize = 1 << 20;
const EDITS: usize = SIZE / 20;

/// Knuth's MMIX linear congruential generator.
fn lcg(x: u64) -> u64 {
    x.wrapping_mul(6364136223846793005)
        .wrapping_add(1442695040888963407)
}

fn random_bytes(seed: u64, size: usize) -> Vec<u8> {
    let mut x = seed;
    (0..size)
        .map(|_| {
            x = lcg(x);
            (x >> 33) as u8
        })
        .collect()
}

/// Returns `src` with `edits` bytes, at positions chosen with replacement,
/// set to random values.
fn mutate(src: &[u8], edits: usize, seed: u64) -> Vec<u8> {
    let mut dst = src.to_vec();
    let mut x = seed;
    for _ in 0..edits {
        x = lcg(x);
        let pos = (x >> 8) as usize % dst.len();
        x = lcg(x);
        dst[pos] = (x >> 33) as u8;
    }
    dst
}

/// Runs `op` on 1 MiB `reps` times and returns the throughput in MiB/s.
fn mib_per_sec(reps: usize, mut op: impl FnMut()) -> f64 {
    let t0 = Instant::now();
    for _ in 0..reps {
        op();
    }
    reps as f64 / t0.elapsed().as_secs_f64()
}

fn main() {
    let op = std::env::args().nth(1).unwrap_or_else(|| {
        eprintln!("usage: pilot_delta <operation>");
        std::process::exit(1);
    });

    let old = random_bytes(0x1234_5678_dead_beef, SIZE);
    let new = mutate(&old, EDITS, 0xfeed_cafe_babe_0001);
    let opts = DiffOptions::default();
    let encode = |algorithm, reps| {
        mib_per_sec(reps, || {
            black_box(place_commands(diff(algorithm, &old, &new, &opts)));
        })
    };

    let throughput = match op.as_str() {
        "encode_greedy_1m" => encode(Algorithm::Greedy, 10),
        "encode_onepass_1m" => encode(Algorithm::Onepass, 10),
        "encode_correcting_1m" => encode(Algorithm::Correcting, 5),
        "decode_1m" => {
            let placed = place_commands(diff(Algorithm::Onepass, &old, &new, &opts));
            let delta = encode_delta(&placed, false, new.len(), &crc64_xz(&old), &crc64_xz(&new))
                .expect("pilot inputs fit in 32-bit format");
            mib_per_sec(100, || {
                let (placed, _, version_size, _, _) = decode_delta(&delta).expect("valid delta");
                let mut out = vec![0u8; version_size];
                black_box(apply_placed_to(&old, &placed, &mut out));
            })
        }
        "inplace_1m" => {
            let commands = diff(Algorithm::Onepass, &old, &new, &opts);
            mib_per_sec(10, || {
                black_box(make_inplace(&old, &commands, CyclePolicy::Localmin));
            })
        }
        _ => {
            eprintln!("unknown operation: {}", op);
            std::process::exit(1);
        }
    };

    println!("{:.6}", throughput);
}
