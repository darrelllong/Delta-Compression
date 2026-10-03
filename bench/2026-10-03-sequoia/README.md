# Pilot benchmarks on sequoia — 2026-10-03

What computing CRC-64 by slicing-by-8 (`efa83c6`) changed on sequoia, the
machine of the [2026-10-02 run](../2026-10-02-sequoia/README.md). "After" is
that run's cleaned tree, whose C, C++, Rust and Java take the checksum a
byte at a time; "crc" is `06d5831`, which differs from it in the checksum
and in comments. Go's checksum is the standard library's in both.

## Machine and method

As on 2026-10-02: Intel Core 5 210H, `performance` governor for the
duration of each run, Rust, C and C++ pinned to CPU 2 and Java and Go to
CPUs 0–7, Pilot `a6e6e77`, `quick` preset, work directory on the two-disk
volume at `/mnt`, the same Shakespeare pair. `run.sh` is the script;
[`host.txt`](../2026-10-02-sequoia/host.txt) of that run still describes the
machine. Order: crc-1, after-3, crc-2, back to back, 18:51–18:58 UTC. The
machine was otherwise idle. Each implementation of each tree encoded the
pair beforehand, and all ten onepass deltas were identical, as were all ten
correcting deltas.

## Results

Every session converged. MiB/s; "crc" is the mean of crc-1 and crc-2, which
differ by at most 2.3% (Java correcting) and otherwise by at most 1.5%.

### `tests/bench_all.sh`: one process per round, Shakespeare 5.4 MB

| Language | Algorithm  | After |   Crc | Change |
|----------|------------|------:|------:|-------:|
| Rust     | onepass    | 33.94 | 37.37 | +10.1% |
| Rust     | correcting | 43.64 | 49.50 | +13.4% |
| C        | onepass    | 32.71 | 35.90 |  +9.7% |
| C        | correcting | 39.41 | 44.15 | +12.0% |
| C++      | onepass    | 33.03 | 36.33 | +10.0% |
| C++      | correcting | 43.20 | 48.90 | +13.2% |
| Java     | onepass    | 19.78 | 20.09 |  +1.5% |
| Java     | correcting | 23.60 | 24.78 |  +5.0% |
| Go       | onepass    | 28.50 | 28.67 |  +0.6% |
| Go       | correcting | 38.48 | 38.41 |  -0.2% |

Go, whose code did not change, is the control. Java gains little because
most of its round is the start of the virtual machine.

### `tests/bench_rust.sh`: in memory, 1 MiB

| Operation            | After |   Crc | Change |
|----------------------|------:|------:|-------:|
| encode_greedy_1m     | 5.592 | 5.574 |  -0.3% |
| encode_onepass_1m    | 29.80 | 29.79 |   0.0% |
| encode_correcting_1m | 26.64 | 26.62 |  -0.1% |
| decode_1m            |  1184 |  1169 |  -1.3% |
| inplace_1m           | 393.8 | 393.6 |  -0.1% |

These operations compute no checksum, and they did not change.

## Kernel tarballs

`kernel-crc.sh` encodes linux-5.1.tar → linux-5.1.1.tar (871,659,520 and
871,669,760 bytes) on `/mnt` with `tests/pilot_lang.sh`: three warm-cache
rounds per tree, interleaved, then one round of crc after dropping the page
cache, 18:41–18:51 UTC. Single timings, not Pilot sessions;
`kernel-crc.txt` has them all. Medians, MiB/s:

| Language | Algorithm  | After |   Crc | Crc, cold |
|----------|------------|------:|------:|----------:|
| Rust     | onepass    | 182.4 | 354.5 |     168.6 |
| Rust     | correcting |  77.7 |  99.0 |      75.9 |
| C        | onepass    | 223.5 | 544.2 |     218.2 |
| C        | correcting |  71.4 |  88.4 |      70.9 |
| C++      | onepass    | 207.8 | 461.8 |     203.5 |
| C++      | correcting |  81.3 | 103.4 |      80.5 |
| Java     | onepass    |     — |     — |         — |
| Java     | correcting |  69.0 |  81.2 |      64.7 |
| Go       | onepass    | 404.0 | 394.7 |     177.6 |
| Go       | correcting |  80.0 |  79.9 |      64.0 |

All runs produced the same 5,075,535-byte onepass delta and the same
6,948,688-byte correcting delta.

- The C encoder reports 0.76 s for the onepass differencing in both trees.
  The command took 3.73 s before and takes 1.50 s now; for correcting, 8.66 s
  of differencing in 11.64 s before and 9.41 s now. The checksum of the two
  inputs cost about 2.9 s and now costs about 0.7 s.
- The first warm round of Rust onepass was slow in both trees (115.9 and
  163.9 MiB/s); the medians are of all three rounds.
- With the checksum no longer hiding it, the disks show: a cold `cat` of the
  two tarballs takes 3.0 s, and a cold onepass encode now runs at about the
  rate the disks deliver the data, less than half the warm rate.
- Java onepass still runs out of heap on this input with the default
  maximum heap.
