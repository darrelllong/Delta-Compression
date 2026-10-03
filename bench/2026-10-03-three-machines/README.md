# Pilot benchmarks on three machines — 2026-10-03

Raw output of `tests/bench_rust.sh` (`bench_rust.md`) and `tests/bench_all.sh`
(`bench_all.md`) on dyson, dmz and baase, with Pilot `a6e6e77` and the code
at `efa83c6`. These are the three columns of the two Pilot tables in
[ANALYSIS.md](../../ANALYSIS.md), replacing those of
[2026-10-02](../2026-10-02-four-machines/README.md), which were measured at
`a10ab04`, before C, C++, Rust and Java computed CRC-64 by slicing-by-8.
wigner (Apple M1 Max) is no longer measured.

## Method

- `run.sh HOST`, run from dyson, syncs the tree to the host, builds it, checks
  that the five implementations produce identical deltas, runs the two
  suites, and copies back `bench_rust.md`, `bench_all.md`, `host.txt` (exact
  toolchain versions) and `start.txt`/`end.txt` (time and load average).
- `quick` preset, 95% confidence, MiB/s declared as a rate (Pilot type 1),
  so the mean is the harmonic mean and the `CI width` column is the full
  width of an interval that is not symmetric about it.
- Input: `shakespeare.txt` (5,638,480 bytes, SHA-256 prefix
  `3cf4b3d44ee14cff`) and `shakespeare-5pct.txt` (`7164f27da741910f`), the
  same two files copied to every host. Every implementation on every host
  produced the same 4,016,248-byte onepass delta and 4,092,873-byte
  correcting delta.
- Pinning: baase, Rust/C/C++ on CPU 9 and Java/Go on CPUs 5–9 and 15–19 (the
  Cortex-X925 cores); dmz, CPU 2 and CPUs 0–7; dyson is not pinned. The
  governor was left as found: `performance` on baase, `powersave` on dmz.
- baase has no javac or Go: its classes were compiled on dyson with
  `javac 19.0.1 --release 17` and its Go binary cross-compiled there with
  Go 1.27.1 for linux/arm64. The other hosts built everything themselves.
- dmz and baase ran at the same time, 10:23–10:28 PDT; dyson ran
  afterwards, 10:38–10:45.

## Conditions

- dmz was idle.
- baase had its resident vLLM/Ray inference service, serving nothing, using
  about a fifth of a core.
- dyson was a desktop in use and had been rebooted 22 minutes before its
  run; the run waited until the one-minute load average fell below 2.5, and
  it was near 3 during the run. Thirteen of dyson's fifteen sessions needed
  more than the minimum 30 rounds (up to 302) to reach the interval. Its
  intervals are under 3% of the mean except `encode_correcting_1m` at 4.8%.
  The widest interval of the run is dmz's `decode_1m`, 8.3% of the mean.

Every session converged with status 0.

## Kernel tarballs

`per-language.txt` in the dyson and dmz directories is the output of
`tests/per-language-benchmark.sh` (linux-5.1 → 5.1.1, 871 MB each, one run
per implementation and algorithm), on dmz at 10:28–10:30 PDT and on dyson
at 10:45–10:46, each after that machine's Pilot suites, with the tarballs
on the internal SSD and in the page cache. All ten runs of each algorithm
produced the same 5,075,535-byte onepass delta and 6,948,688-byte correcting
delta. These are the kernel tarball tables in ANALYSIS.md. baase was not
run: it has no javac or Go, and the script builds with both.

Against 2026-10-02 the four implementations whose CRC changed are 1.9 to
3.3 s faster on every row; Go, whose CRC did not change, is within 0.2 s of
its earlier times on both machines.
