# Pilot benchmarks on three machines, C's rolling hash inlined — 2026-10-03

Raw output of `tests/bench_rust.sh` (`bench_rust.md`) and `tests/bench_all.sh`
(`bench_all.md`) on dyson, dmz and baase, with Pilot `a6e6e77` and the code
at `d23229e`. These are the three columns of the two Pilot tables in
[ANALYSIS.md](../../ANALYSIS.md), replacing those measured that morning at
`efa83c6` ([2026-10-03-three-machines](../2026-10-03-three-machines/README.md)).
The difference between the two that bears on speed is in the C
implementation: its fingerprint functions, which were in `hash.c` and
called across source files once per byte, are `static inline` in the
header.

## Method

As in the morning's run: `run.sh HOST` from dyson, `quick` preset, 95%
confidence, MiB/s as a rate, the same Shakespeare pair (SHA-256 prefixes
`3cf4b3d44ee14cff` and `7164f27da741910f`), the same pinning (baase CPU 9
and CPUs 5–9, 15–19; dmz CPU 2 and CPUs 0–7; dyson not pinned). Every
implementation on every host produced the same 4,016,248-byte onepass delta
and 4,092,873-byte correcting delta.

- dmz ran 12:24–12:29 PDT, idle.
- baase ran 12:53–12:56, with only its resident vLLM/Ray service. A first
  run at 12:24 was discarded: another job started on the machine during it
  and the load average reached 16.
- dyson ran 13:51–13:54. A first run at 12:47 was discarded for the same
  reason (a load average over 100). For the run kept, the machine was a
  desktop in use with a Time Machine backup running and a load average
  between 4 and 5. Twelve of its fifteen sessions needed more than the
  minimum 30 rounds (up to 103); its intervals are under 3.1% of the mean
  except Java correcting at 4.4%.

Every session converged with status 0.

## What changed for C

MiB/s, this run against the morning's:

| Machine | onepass before | after | correcting before | after |
|---------|---------------:|------:|------------------:|------:|
| M4 Pro (dyson, Apple clang 21) | 50.58 | 67.75 | 39.41 | 61.07 |
| i5-8259U (dmz, gcc) | 17.00 | 17.92 | 14.81 | 16.61 |
| Cortex-X925 (baase, gcc 13.3) | 28.48 | 29.05 | 43.42 | 43.97 |

The other four implementations did not change in anything timed here, and
their rates are within a few percent of the morning's on dmz and baase.
On dyson the two runs were made under different loads, and the unchanged
implementations differ by up to 5% between them.

## Kernel tarballs

`per-language.txt` in the dyson and dmz directories is the output of
`tests/per-language-benchmark.sh` (linux-5.1 → 5.1.1, 871 MB each, one run
per implementation and algorithm), on dmz at 12:29–12:31 PDT and on dyson
at 13:55–13:56, with the tarballs on the internal SSD and in the page
cache. All ten runs of each algorithm produced the same 5,075,535-byte
onepass delta and 6,948,688-byte correcting delta. C correcting went from
12.4 s to 7.0 s on dyson and from 24.4 s to 20.0 s on dmz.

The script runs Rust first. On dyson a run a minute earlier gave Rust
onepass 2.4 s and the run filed here 1.8 s, the other nine times agreeing
within 0.4 s; Rust alone takes 1.5 to 1.6 s (the extended kernel benchmark
in ANALYSIS.md), so some of its onepass time in this table is its place in
the order.
