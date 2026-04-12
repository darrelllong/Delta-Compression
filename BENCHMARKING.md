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
bash bench_rust.sh    # Rust encode/decode/inplace — 1 MiB synthetic data (MiB/s)
bash bench_all.sh     # compiled languages, onepass + correcting — Shakespeare ~5.4 MB (MiB/s)
```

Each script emits a Markdown table ready to paste into the docs.

### Running on a remote machine

When the data directory differs from the default `/tmp/delta-kernel-test`,
set `WORKDIR` before running:

```bash
# Use a different data directory (e.g., an HDD mount point):
WORKDIR=/archive/darrell/tmp bash bench_all.sh

# On macOS without openjdk@17 in Homebrew, pass the Java path:
JAVA=/Library/Java/JavaVirtualMachines/jdk-19.jdk/Contents/Home/bin/java \
    bash bench_all.sh
```

On Linux, boost may need to be installed before building pilot-bench:

```bash
sudo apt-get install -y libboost-all-dev   # Ubuntu / Debian
# Then re-run cmake with -Wno-error if GCC 14 warns-as-errors:
cmake -DCMAKE_BUILD_TYPE=Release -DWITH_TUI=OFF \
      -DCMAKE_CXX_FLAGS="-Wno-error -Wno-maybe-uninitialized" ..
```

---

## Workload descriptions

### `bench_rust.sh` — Rust micro-benchmarks (`src/rust/delta`)

Driven by `src/rust/delta/src/bin/pilot_delta.rs`.  Each operation uses 1 MiB
of deterministic LCG-generated data with ~5 % single-byte mutations.  Metric:
**MiB/s** (1 MiB ÷ elapsed time per operation).

| Operation | Description | Internal reps |
|-----------|-------------|:---:|
| `encode_greedy_1m` | Greedy diff (O(n²), optimal ratio) | 10 |
| `encode_onepass_1m` | Onepass diff (O(n) time, O(1) space) | 10 |
| `encode_correcting_1m` | Correcting diff (O(n) with checkpointing) | 5 |
| `decode_1m` | Apply a pre-encoded onepass delta | 100 |
| `inplace_1m` | Convert standard delta to in-place format | 10 |

> **Note:** Greedy is O(n²) — at 1 MiB it runs in ~90 ms.  Do not use it
> on multi-MB files; use `encode_onepass_1m` and `encode_correcting_1m` for
> large-file comparisons.

### `bench_all.sh` — multi-language file-encode (`tests/pilot_lang.sh`)

Driven by `tests/pilot_lang.sh`.  Each operation encodes Shakespeare's
complete works (~5.4 MB, PG #100) as the reference and a version with ~5%
random byte mutations applied.  Metric: **MiB/s** (reference file size ÷
elapsed encode time).

The Shakespeare workload was chosen because it has realistic textual patterns
that the delta algorithms can actually match (typical ratios: onepass 2–4%,
correcting 1–2%).  Random data produces no matches and reduces all algorithms
to trivial serialization benchmarks.  For I/O-dominated large-file results,
see `tests/per-language-benchmark.sh` (871 MB kernel tarballs, single run).

| Operation | Description |
|-----------|-------------|
| `<Lang>-op` | onepass encode |
| `<Lang>-co` | correcting encode |

Languages: Rust, C, C++, Java, Go, Python (in that order).

> **Note:** Python takes ~6 s per round (≈ 62× slower than Rust); it is
> placed last so the fast languages complete first.  Java is skipped
> automatically if no JDK is found.

---

## Running a single operation manually

```bash
# Rust micro-op (MiB/s):
~/pilot-bench/build/cli/bench run_program --preset quick \
    --pi "encode_onepass_1m,MiB/s,0,1,1" \
    -- ./src/rust/delta/target/release/pilot_delta encode_onepass_1m

# Multi-language op (MiB/s):
~/pilot-bench/build/cli/bench run_program --preset quick \
    --pi "Rust-op,MiB/s,0,1,1" \
    -- ./tests/pilot_lang.sh Rust onepass
```

`--preset quick` targets 20 % CI.  Use `--preset normal` for 10 % or
`--preset strict` for tighter bounds.
