# Benchmarking

Two paths are available: the original shell-script path using wall-clock timing,
and a statistically rigorous path using
[pilot-bench](https://github.com/darrelllong/pilot-bench).

---

## Quick path — shell-script benchmarks

No external tools required beyond the language toolchains.

### Build all implementations

```bash
./tests/per-language-benchmark.sh
```

This builds all compiled implementations, downloads the Linux 5.1.0 and 5.1.1
kernel tarballs (~871 MB each, cached in `/tmp/delta-kernel-test`), and prints
a per-language timing table for onepass and correcting.

### Per-language speed comparison

```bash
./tests/per-language-benchmark.sh
```

Encodes the linux-5.1.0 → 5.1.1 tarball pair with each compiled implementation
(Rust, C++, C, Java, Go) and reports wall-clock seconds per encode.  Requires
~2 GB disk for the tarballs.

### Extended kernel benchmark (Rust, linux-5.1.0–5.1.7)

```bash
./tests/kernel-delta-test.sh
```

Benchmarks Rust across all seven linux-5.1.x versions in three reference
modes: from-base (5.1.0 → 5.1.x), successive (5.1.n → 5.1.n+1), and
from-5.1.1.  Downloads tarballs on first run; subsequent runs use the cache.
Requires ~8 GB disk for tarballs 5.1.0–5.1.7.

### Transposition benchmark (Rust)

```bash
./tests/transposition-benchmark.sh
```

Synthetic test: generates R and V from the same blocks in different orders at
five permutation levels (0 %, 25 %, 50 %, 75 %, 100 %) and two sizes (16 MB
and 1 GB).  Reports delta ratio, copy/add counts, and wall-clock time for all
three algorithms plus in-place conversion.

### Correctness gate

```bash
./tests/correctness.sh
```

Builds all implementations, runs per-language unit tests, and verifies
cross-language delta compatibility (any implementation can decode a delta
produced by any other).

---

## Rigorous path — pilot-bench

[pilot-bench](https://github.com/darrelllong/pilot-bench) drives the
workload repeatedly until a target confidence interval is reached, correcting
for autocorrelation and startup transients.  This is the preferred path for
numbers that go into the documentation.

### Step 1 — build pilot-bench (one-time)

Prerequisites: `cmake` ≥ 3.14, `boost` ≥ 1.74, a C++14-capable compiler.

```bash
git clone https://github.com/darrelllong/pilot-bench.git ~/pilot-bench
cd ~/pilot-bench
mkdir build && cd build
cmake -DCMAKE_BUILD_TYPE=Release -DWITH_TUI=OFF ..
make -j$(nproc) bench
```

The binary lands at `~/pilot-bench/build/cli/bench`.  If you install it
elsewhere, update the `BENCH=` variable at the top of the bench scripts.

### Step 2 — build prerequisites

**Rust micro-benchmark** (no large files required):

```bash
cd src/rust/delta
cargo build --release --bin pilot_delta
```

**Multi-language benchmark** (builds implementations; `bench_all.sh` downloads
Shakespeare automatically on first run):

```bash
./tests/per-language-benchmark.sh   # builds compiled implementations + downloads kernel tarballs
./tests/get_shakespeare.sh          # optional: pre-download Shakespeare data separately
```

### Step 3 — run the suites

```bash
bash tests/bench_rust.sh    # Rust encode/decode/inplace — 1 MiB synthetic data (MiB/s)
bash tests/bench_all.sh     # compiled languages, onepass + correcting — Shakespeare ~5.4 MB (MiB/s)
```

Each script emits a Markdown table ready to paste into the docs. The
`CI width` column is the full width of the 95% confidence interval that
pilot-bench prints; the interval is the mean plus or minus half of it. Tables
before 2026-09-28 printed the same number as `±CI`.

Both scripts declare their MiB/s performance index as Pilot type 1, a rate,
so that Pilot averages it as a rate. Set `PILOT_BENCH_CLI` if pilot-bench is
not at `~/pilot-bench/build/cli/bench`. On Linux, `tests/bench_all.sh` pins
each session when `PIN_SINGLE` (a `taskset` CPU list for Rust, C and C++,
which are single-threaded) and `PIN_MULTI` (for Java and Go, whose runtimes
start threads of their own) are set; the 2026-09-28 runs on `baase` used

```bash
taskset -c 9 bash tests/bench_rust.sh
PIN_SINGLE=9 PIN_MULTI=5-9,15-19 bash tests/bench_all.sh
```

so that every session ran on the Cortex-X925 cores only.

### Running on a remote machine

When the data directory differs from the default `/tmp/delta-kernel-test`,
set `WORKDIR` before running:

```bash
# Use a different data directory (e.g., an HDD mount point):
WORKDIR=/archive/darrell/tmp bash tests/bench_all.sh
```

`tests/bench_all.sh` runs whichever `java` is first on `PATH`.

On Linux, boost may need to be installed before building pilot-bench:

```bash
sudo apt-get install -y libboost-all-dev   # Ubuntu / Debian
# Then re-run cmake with -Wno-error if GCC 14 warns-as-errors:
cmake -DCMAKE_BUILD_TYPE=Release -DWITH_TUI=OFF \
      -DCMAKE_CXX_FLAGS="-Wno-error -Wno-maybe-uninitialized" ..
```

---

## Workload descriptions

### `tests/bench_rust.sh` — Rust micro-benchmarks (`src/rust/delta`)

Driven by `src/rust/delta/src/bin/pilot_delta.rs`.  Each operation uses 1 MiB
of deterministic LCG-generated data with ~5 % single-byte mutations.  Metric:
**MiB/s** (1 MiB ÷ elapsed time per operation).

| Operation | Description | Internal reps |
|-----------|-------------|:---:|
| `encode_greedy_1m` | Greedy diff (O(n²), optimal ratio) | 10 |
| `encode_onepass_1m` | Onepass diff (O(n) time) | 10 |
| `encode_correcting_1m` | Correcting diff (O(n) with checkpointing) | 5 |
| `decode_1m` | Apply a pre-encoded onepass delta | 100 |
| `inplace_1m` | Convert standard delta to in-place format | 10 |

> **Note:** Greedy is O(n²).  At 1 MiB one operation took 81 ms on the
> Apple M4 Pro (12.42 MiB/s) and 319 ms on the Cortex-X925 (3.13 MiB/s),
> both on 2026-10-03.  Do not use it on multi-MB files; use
> `encode_onepass_1m` and `encode_correcting_1m` for large-file comparisons.

### `tests/bench_all.sh` — multi-language file-encode (`tests/pilot_lang.sh`)

Driven by `tests/pilot_lang.sh`.  Each operation encodes Shakespeare's
complete works (~5.4 MB, PG #100) as the reference and a version with ~5%
random byte mutations applied.  Metric: **MiB/s** (reference file size ÷
elapsed encode time).

The Shakespeare workload was chosen because it is text the delta algorithms
can match, where random data produces no matches and reduces all algorithms to
serialization.  With 5% of its bytes replaced at random (one in 20 on
average), the deltas are large: every
implementation produced a 4,016,248-byte onepass delta and a 4,092,873-byte
correcting delta for the 5,638,480-byte version, 71.2% and 72.6% of its
size.  For large-file results,
see `tests/per-language-benchmark.sh` (871 MB kernel tarballs, single run).

| Operation | Description |
|-----------|-------------|
| `<Lang>-op` | onepass encode |
| `<Lang>-co` | correcting encode |

Languages: Rust, C, C++, Java, Go (in that order).  Python is not measured
here.  Java is skipped automatically if no `java` is on `PATH`, and Go if its
binary has not been built.

---

## Running a single operation manually

```bash
# Rust micro-op (MiB/s):
~/pilot-bench/build/cli/bench run_program --preset quick \
    --pi "encode_onepass_1m,MiB/s,0,1,1" \
    -- ./src/rust/delta/target/release/pilot_delta encode_onepass_1m

# Multi-language op (MiB/s); pilot_lang.sh reads the input pair from
# PILOT_REF and PILOT_VER, which tests/bench_all.sh sets:
PILOT_REF=/tmp/delta-kernel-test/shakespeare.txt \
PILOT_VER=/tmp/delta-kernel-test/shakespeare-5pct.txt \
~/pilot-bench/build/cli/bench run_program --preset quick \
    --pi "Rust-op,MiB/s,0,1,1" \
    -- ./tests/pilot_lang.sh Rust onepass
```

`--preset quick` stops when the confidence interval is at most 20% of the
mean wide; `--preset normal` and `--preset strict` at 10%, with stricter
autocorrelation and sample-size requirements.  Pilot `475063f` or later is
needed for a rate (type 1) to be averaged as a rate.
