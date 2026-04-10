//! Delta-compression throughput benchmark for pilot-bench.
//!
//! Usage: pilot_delta <operation>
//!
//! Performs the named operation N times and prints MiB/s to stdout.
//! Pilot-bench calls this repeatedly until statistical confidence is reached.
//!
//! All operations use 1 MiB of deterministic synthetic data with ~5%
//! single-byte mutations as the version (new) file.
//!
//! Operations:
//!   encode_greedy_1m      diff with the greedy algorithm
//!   encode_onepass_1m     diff with the one-pass algorithm
//!   encode_correcting_1m  diff with the correcting algorithm
//!   decode_1m             apply a pre-encoded one-pass delta (algorithm-agnostic)
//!   inplace_1m            convert a standard one-pass delta to in-place form

use std::hint::black_box;
use std::time::Instant;

use delta::{
    Algorithm, CyclePolicy, DiffOptions,
    apply_placed_to, crc64_xz, decode_delta, diff, encode_delta,
    make_inplace, place_commands,
};

/// Throughput in MiB/s for a 1 MiB workload repeated n times.
fn mib_per_sec(elapsed: std::time::Duration, n: usize) -> f64 {
    n as f64 / elapsed.as_secs_f64()
}

/// Deterministic pseudo-random buffer via a 64-bit LCG.
fn gen_buf(seed: u64, size: usize) -> Vec<u8> {
    let mut v = Vec::with_capacity(size);
    let mut x = seed;
    for _ in 0..size {
        x = x.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        v.push((x >> 33) as u8);
    }
    v
}

/// Apply `n_edits` single-byte substitutions at deterministic positions.
fn mutate(src: &[u8], n_edits: usize, seed: u64) -> Vec<u8> {
    let mut dst = src.to_vec();
    let mut x = seed;
    for _ in 0..n_edits {
        x = x.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        let pos = ((x >> 8) as usize) % dst.len();
        x = x.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        dst[pos] = (x >> 33) as u8;
    }
    dst
}

fn main() {
    let op = std::env::args().nth(1).unwrap_or_else(|| {
        eprintln!("usage: pilot_delta <operation>");
        std::process::exit(1);
    });

    const SIZE: usize = 1 << 20; // 1 MiB
    const EDITS: usize = SIZE / 20; // ~5% byte-level mutations

    let opts = DiffOptions::default();

    let throughput: f64 = match op.as_str() {
        // ── Encode: greedy ────────────────────────────────────────────────────
        "encode_greedy_1m" => {
            let old = gen_buf(0x1234_5678_dead_beef, SIZE);
            let new = mutate(&old, EDITS, 0xfeed_cafe_babe_0001);
            let n = 10;
            let t0 = Instant::now();
            for _ in 0..n {
                black_box(place_commands(diff(Algorithm::Greedy, &old, &new, &opts)));
            }
            mib_per_sec(t0.elapsed(), n)
        }

        // ── Encode: one-pass ─────────────────────────────────────────────────
        "encode_onepass_1m" => {
            let old = gen_buf(0x1234_5678_dead_beef, SIZE);
            let new = mutate(&old, EDITS, 0xfeed_cafe_babe_0001);
            let n = 10;
            let t0 = Instant::now();
            for _ in 0..n {
                black_box(place_commands(diff(Algorithm::Onepass, &old, &new, &opts)));
            }
            mib_per_sec(t0.elapsed(), n)
        }

        // ── Encode: correcting ────────────────────────────────────────────────
        "encode_correcting_1m" => {
            let old = gen_buf(0x1234_5678_dead_beef, SIZE);
            let new = mutate(&old, EDITS, 0xfeed_cafe_babe_0001);
            let n = 5;
            let t0 = Instant::now();
            for _ in 0..n {
                black_box(place_commands(diff(Algorithm::Correcting, &old, &new, &opts)));
            }
            mib_per_sec(t0.elapsed(), n)
        }

        // ── Decode: apply a pre-encoded delta ─────────────────────────────────
        "decode_1m" => {
            let old = gen_buf(0x1234_5678_dead_beef, SIZE);
            let new = mutate(&old, EDITS, 0xfeed_cafe_babe_0001);
            let src_crc = crc64_xz(&old);
            let dst_crc = crc64_xz(&new);
            let placed = place_commands(diff(Algorithm::Onepass, &old, &new, &opts));
            let delta = encode_delta(&placed, false, new.len(), &src_crc, &dst_crc)
                .expect("pilot inputs fit in 32-bit format");
            let n = 100;
            let t0 = Instant::now();
            for _ in 0..n {
                let (pd, _, version_size, _, _) =
                    decode_delta(&delta).expect("valid delta");
                let mut out = vec![0u8; version_size];
                black_box(apply_placed_to(&old, &pd, &mut out));
            }
            mib_per_sec(t0.elapsed(), n)
        }

        // ── In-place conversion ───────────────────────────────────────────────
        "inplace_1m" => {
            let old = gen_buf(0x1234_5678_dead_beef, SIZE);
            let new = mutate(&old, EDITS, 0xfeed_cafe_babe_0001);
            let commands = diff(Algorithm::Onepass, &old, &new, &opts);
            let n = 10;
            let t0 = Instant::now();
            for _ in 0..n {
                black_box(make_inplace(&old, &commands, CyclePolicy::Localmin));
            }
            mib_per_sec(t0.elapsed(), n)
        }

        _ => {
            eprintln!("unknown operation: {}", op);
            std::process::exit(1);
        }
    };

    println!("{:.6}", throughput);
}
