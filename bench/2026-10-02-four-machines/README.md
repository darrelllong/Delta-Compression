# Pilot benchmarks on four machines — 2026-10-02

Raw output of `tests/bench_rust.sh` (`bench_rust.md`) and `tests/bench_all.sh`
(`bench_all.md`) on dyson, wigner, dmz and baase, with Pilot `a6e6e77` and
the code at `a10ab04`. These were the four columns of the two Pilot tables in
[ANALYSIS.md](../../ANALYSIS.md) until the measurements of
[2026-10-03](../2026-10-03-three-machines/README.md) replaced them; they
replaced the columns of March 2026
(dyson, wigner, dmz; Pilot before `f01eec4`, arithmetic means of rates, the
code of that time) and of 2026-09-28 (baase, Pilot `475063f`, code at
`1b38241`).

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
  Cortex-X925 cores), as on 2026-09-28; dmz, CPU 2 and CPUs 0–7; the Macs are
  not pinned. The governor was left as found: `performance` on baase,
  `powersave` on dmz.
- baase has no javac or Go: its classes were compiled on dyson with
  `javac 19.0.1 --release 17` and its Go binary cross-compiled there with
  Go 1.27.1 for linux/arm64. The other hosts built everything themselves.
- dmz, wigner and baase ran at the same time, 16:20–16:25 PDT; dyson ran
  afterwards, 16:26–16:29.

## Conditions

- dmz was idle.
- baase had its resident vLLM/Ray inference service, serving nothing, using
  about a fifth of a core.
- wigner and dyson were desktops in use. Spotlight was indexing on both and
  Mail was busy on dyson, whose load average was near 7 during the run. Ten
  of dyson's fifteen sessions needed more than the minimum 30 rounds (up to
  270) to reach the interval, and its intervals are the widest: up to 9% of
  the mean on `decode_1m` and `inplace_1m`, against under 4% on wigner and
  under 1% on most sessions elsewhere.

Every session converged with status 0.

## Kernel tarballs

`per-language.txt` in the dyson, wigner and dmz directories is the output of
`tests/per-language-benchmark.sh` (linux-5.1 → 5.1.1, 871 MB each, one run
per implementation and algorithm) on 2026-10-02, 17:01–17:04 PDT, run on the
three at the same time with the tarballs downloaded to each machine's
internal SSD. All fifteen runs produced the same 5,075,535-byte onepass delta
and 6,948,688-byte correcting delta. These were the kernel tarball tables in
ANALYSIS.md until 2026-10-03. baase was not run: it has no javac or Go, and the script builds
with both.
