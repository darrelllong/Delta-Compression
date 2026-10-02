# Pilot benchmarks on sequoia — 2026-10-02

Before-and-after measurements for the code cleanup that follows `b478106`:
the same algorithms and the same deltas, with the code restructured in all
six implementations. "Before" is `b478106`; "after" is the cleaned tree.

## Machine and method

- Intel Core 5 210H (four hyperthreaded P-cores, CPUs 0–7, to 4.8 GHz; four
  E-cores, CPUs 8–11), 14 GiB, Ubuntu 26.04.1, Linux 7.0. `host.txt` has the
  exact versions.
- gcc and g++ 15.2.0 (C at `-O2`, C++ as a CMake Release build), rustc
  1.95.0, OpenJDK 25.0.4.1 running classes compiled with `javac --release 17`
  (javac 19.0.1), Go 1.27.1 cross-compiled for linux/amd64, Python 3.14.4
  for the timing wrapper.
- Pilot `a6e6e77`, `quick` preset, 95% confidence, MiB/s declared as a rate.
- `performance` governor for the duration of each run. Rust, C and C++ pinned
  to CPU 2; Java and Go to CPUs 0–7. `run.sh` is the script.
- Work directory on `/mnt`, an LVM volume over two 12 TB hard disks: the
  inputs are read from it and every delta is written to it.
- Input: `shakespeare.txt` and `shakespeare-5pct.txt` from
  `tests/get_shakespeare.sh`, the same files as the 2026-09-28 run on baase
  (SHA-256 prefixes `3cf4b3d44ee14cff` and `7164f27da741910f`).
- Order: before-1, then (after the cleanup) before-2, after-1, before-3,
  after-2, back to back, 19:49–19:58 UTC. The machine was otherwise idle.

Before the runs each implementation of each tree encoded the pair, and all
ten onepass deltas were identical, as were all ten correcting deltas.

## Results

Every session converged. Means of the two interleaved runs of each tree
(before-2 and before-3; after-1 and after-2), MiB/s; the two runs of a tree
differ by at most 1.4%. The directories hold the tables as the scripts
printed them, with the confidence intervals.

### `tests/bench_all.sh`: one process per round, Shakespeare 5.4 MB

| Language | Algorithm  | Before | After | Change |
|----------|------------|-------:|------:|-------:|
| Rust     | onepass    |  33.31 | 34.16 |  +2.6% |
| Rust     | correcting |  43.45 | 43.45 |   0.0% |
| C        | onepass    |  28.66 | 32.61 | +13.8% |
| C        | correcting |  39.18 | 39.37 |  +0.5% |
| C++      | onepass    |  28.45 | 33.28 | +17.0% |
| C++      | correcting |  37.91 | 43.31 | +14.2% |
| Java     | onepass    |  19.39 | 19.81 |  +2.2% |
| Java     | correcting |  22.97 | 23.59 |  +2.7% |
| Go       | onepass    |  24.16 | 28.62 | +18.5% |
| Go       | correcting |  29.68 | 38.26 | +28.9% |

### `tests/bench_rust.sh`: in memory, 1 MiB

| Operation            | Before | After | Change |
|----------------------|-------:|------:|-------:|
| encode_greedy_1m     |  5.601 | 5.585 |  -0.3% |
| encode_onepass_1m    |  29.02 | 29.76 |  +2.5% |
| encode_correcting_1m |  26.34 | 26.69 |  +1.3% |
| decode_1m            |   1185 |  1183 |  -0.2% |
| inplace_1m           |  315.7 | 393.7 | +24.7% |

The greedy difference is small but larger than the intervals. The decode
difference is within them.

## Kernel tarballs

`kernel-ab.sh` encodes linux-5.1.tar → linux-5.1.1.tar (871,659,520 and
871,669,760 bytes; the reference is 831.3 MiB), also on `/mnt`, with
`tests/pilot_lang.sh`, three warm-cache rounds per tree, interleaved, then
one round of the cleaned tree after dropping the page cache. These are single
timings, not Pilot sessions; `kernel-ab.txt` has them all. Medians, MiB/s:

| Language | Algorithm  | Before | After | After, cold |
|----------|------------|-------:|------:|------------:|
| Rust     | onepass    |  190.0 | 189.8 |       118.2 |
| Rust     | correcting |   78.6 |  78.1 |        62.6 |
| C        | onepass    |  221.0 | 222.4 |       205.5 |
| C        | correcting |   71.7 |  71.4 |        69.4 |
| C++      | onepass    |  198.7 | 206.9 |       191.7 |
| C++      | correcting |   71.2 |  81.0 |        78.7 |
| Java     | onepass    |      — |     — |           — |
| Java     | correcting |   65.0 |  68.8 |        56.3 |
| Go       | onepass    |  181.1 | 397.4 |       178.6 |
| Go       | correcting |   54.6 |  79.9 |        64.0 |

All implementations that ran produced the same 5,075,535-byte onepass delta
and the same 6,948,688-byte correcting delta, before and after.

- Java onepass runs out of heap on this input with the default maximum heap
  (a quarter of 14 GiB), before and after; it needs `-Xmx`.
- Go doubled on onepass because its CRC-64 is now the standard library's,
  and at this size the checksum of the two inputs is most of the time. The
  C encoder reports 0.93 s for the differencing itself in a command that
  takes 3.7 s; most of the rest is the CRC-64/XZ of 1.7 GB, computed a byte
  at a time.
- The cold column is one round each and should be read as no more than
  that. A cold `cat` of the two tarballs takes 3.0 s, yet a cold C onepass
  encode takes only about 0.3 s longer than a warm one: the disks are read
  while the checksum is computed.
