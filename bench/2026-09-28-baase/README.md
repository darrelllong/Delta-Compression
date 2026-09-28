# Pilot benchmarks on baase — 2026-09-28

Raw output of `tests/bench_rust.sh` (`bench_rust.md`) and `tests/bench_all.sh`
(`bench_all.md`) on `baase`, with Pilot `475063f`. These are the Cortex-X925
columns of the two Pilot tables in [ANALYSIS.md](../../ANALYSIS.md); the other
columns there are from March 2026, produced with Pilot before `f01eec4`, and
could not be taken again.

## Machine and method

- NVIDIA GB10, Arm Cortex-X925 at 3.9 GHz (CPUs 5–9 and 15–19; the other ten
  are Cortex-A725), `performance` governor, Ubuntu 24.04.5, Linux 7.0.
- Code at `1b38241`, with the fixes to `tests/bench_all.sh`,
  `tests/bench_rust.sh` and `tests/pilot_lang.sh` committed with these
  results.
- rustc 1.95.0; gcc and g++ 13.3.0 (C at `-O2`, C++ as a CMake Release
  build); OpenJDK 21.0.12.1, classes compiled with `javac --release 17`;
  go 1.27.1; Python 3.12.3 for the timing wrapper. `host.txt` has the exact
  versions.
- Input: `shakespeare.txt` (Project Gutenberg #100, 5,638,480 bytes, SHA-256
  prefix `3cf4b3d44ee14cff`) and `shakespeare-5pct.txt` from
  `tests/get_shakespeare.sh` (SHA-256 prefix `7164f27da741910f`).
- `taskset -c 9 bash tests/bench_rust.sh`, then
  `PIN_SINGLE=9 PIN_MULTI=5-9,15-19 bash tests/bench_all.sh`: Rust, C and
  C++ on CPU 9, Java and Go, whose runtimes start threads, on the ten
  Cortex-X925 cores. `run.sh` is the script that ran them, after the
  cryptography rate sessions.
- `quick` preset, 95% confidence, MiB/s declared as Pilot type 1. The mean is
  the harmonic mean; the `CI width` column is the full width of the interval,
  which for a rate is not symmetric about the mean.
- The machine was idle apart from a resident vLLM/Ray inference service that
  was serving nothing. The runs took 165 s (Rust micro-benchmarks) and 122 s
  (languages), 14:27–14:32 PDT.

Before the benchmarks, each implementation's onepass and correcting deltas were
decoded with the Rust decoder and compared with the version file: all five
round-tripped and produced the same 4,016,248-byte onepass and 4,092,873-byte
correcting delta.

## Every session converged

Every session met the `quick` preset's requirements and ended with status 0
(no session limit was set); the rounds run were 30 to 204.

## What was corrected in the method

- `tests/bench_all.sh` and `tests/bench_rust.sh` looked for the repository one
  directory too low after they were moved into `tests/`, so they could not run
  as documented; they now find it, take `PILOT_BENCH_CLI`, and stop if a
  Pilot session fails.
- `tests/pilot_lang.sh` did not check the encoder's exit status, so a failed
  encode would have been timed as a fast one; it now does.
- Pilot prints the full width of the confidence interval. The scripts printed
  it as `±CI` and the documentation read it as a half-width; the column is now
  `CI width`.
