# HOWTO — Differential Compression

Practical guide to using the `delta` tool for file differencing and
reconstruction.

## Installation

### Python

No installation required — just Python 3.6+.

```bash
cd src/python
python3 delta.py --help
```

### Rust

```bash
cd src/rust/delta
cargo build --release
# Binary at target/release/delta
```

### C++

Requires a C++20 compiler (GCC 11+, Clang 14+, Apple Clang 15+) and CMake 3.20+.
Catch2 v3 and CLI11 are fetched automatically.

```bash
cd src/cpp
cmake -B build
cmake --build build
# Binary at build/delta
# Run tests
ctest --test-dir build
```

### C

Requires a C11 compiler (GCC, Clang, Apple Clang) with `__uint128_t` support
and POSIX `mmap`.  No external dependencies.

```bash
cd src/c
make
# Binary at ./delta
# Run tests
make test
```

### Java

Requires Java 11+.  No external dependencies.

```bash
cd src/java
javac -d out delta/*.java
# Run via java -cp out delta.Delta
```

## Basic usage

Compute a delta between a reference file (old) and a version file (new),
then reconstruct the version from the reference and the delta.

```bash
# Python
python3 delta.py encode onepass old.bin new.bin delta.bin
python3 delta.py decode old.bin delta.bin recovered.bin

# Rust
delta encode onepass old.bin new.bin delta.bin
delta decode old.bin delta.bin recovered.bin

# C++ (src/cpp/build/delta)
delta encode onepass old.bin new.bin delta.bin
delta decode old.bin delta.bin recovered.bin

# C (src/c/delta)
delta encode onepass old.bin new.bin delta.bin
delta decode old.bin delta.bin recovered.bin

# Java
java -cp out delta.Delta encode onepass old.bin new.bin delta.bin
java -cp out delta.Delta decode old.bin delta.bin recovered.bin

# Verify
diff new.bin recovered.bin
```

## Choosing an algorithm

### onepass (recommended default)

Linear time, constant space.  Good compression for files that share
long common substrings in roughly the same order.

```bash
delta encode onepass old.bin new.bin delta.bin
```

### correcting

Near-optimal 1.5-pass algorithm.  Better than onepass when blocks
have been rearranged or moved (e.g., function reordering in a binary).
Uses checkpointing (Section 8) with an auto-sized hash table.
`--table-size` acts as a floor; the table scales up for large inputs.

```bash
delta encode correcting old.bin new.bin delta.bin
```

### greedy

Optimal compression (smallest possible delta) but O(n^2) time.
Use for small files or when compression ratio matters more than speed.

```bash
delta encode greedy old.bin new.bin delta.bin
```

## Relationship to edit distance and common substrings

Differential compression emerged as an application of the
string-to-string correction problem (Wagner and Fischer 1974), which
asks for the minimum-cost sequence of edits transforming one string into
another.  Levenshtein distance (Levenshtein 1966) is the simplest
instance: single-character insertions, deletions, and substitutions,
computed by O(mn) dynamic programming.

Early differencing algorithms solved string-to-string correction by
computing the longest common subsequence (LCS) of strings R and V, then
treating all characters outside the LCS as data to be added explicitly
(Ajtai et al. 2002, Section 1.1).  This formulation assumes a one-to-one
correspondence between matching substrings and requires that they appear
in the same order in both strings.  Tichy (1984) generalized to the
"string-to-string correction problem with block move," which permits
variable-length substrings to be copied multiple times and out of
sequence.  Traditional algorithms for this problem — the greedy
algorithm of Reichenberger (1991) and the dynamic programming approach
of Miller and Myers (1985) — run in O(mn) or O(n^2) time.

The algorithms implemented here (Ajtai et al. 2002) solve the
string-to-string correction problem with block move using Karp-Rabin
fingerprinting (Karp and Rabin 1987) to discover variable-length common
substrings between R and V in linear time.  A single substring in R may
be copied to multiple locations in V, and matches need not preserve
order.  The onepass and correcting algorithms run in O(n) time with O(1)
space — compared to O(mn) for edit-distance dynamic programming.  For
a 1 MB file with a 1 KB change, Levenshtein requires ~10^12 operations;
onepass finds the change in a single linear scan.

## Tuning parameters

### --seed-len (default: 16)

Minimum match length in bytes.  Smaller values find more matches but
increase hash table pressure and produce more (smaller) copy commands.

```bash
# Shorter seeds — more matches, more commands
delta encode onepass old.bin new.bin delta.bin --seed-len 8

# Longer seeds — fewer matches, fewer commands
delta encode onepass old.bin new.bin delta.bin --seed-len 32
```

### --table-size (default: 1048573)

Minimum hash table capacity (floor).  The actual table size is
auto-computed as `next_prime(max(table_size, num_seeds / p))` for
onepass, and `next_prime(max(table_size, 2 * num_seeds / p))` for
correcting, where `num_seeds = |R| - p + 1`.  For small files the
default is used as-is; for large files the table grows automatically.

```bash
# Override the floor — force a smaller table (saves memory, may miss matches)
delta encode correcting old.bin new.bin delta.bin --table-size 25013

# Force a larger floor — useful if auto-sizing picks something too small
delta encode correcting old.bin new.bin delta.bin --table-size 8388593
```

### --verbose

Print hash table sizing, match statistics, and copy-length summary
(min/max/mean/median) to stderr.  Useful for understanding compression
behavior and diagnosing performance.

```bash
delta encode onepass old.bin new.bin delta.bin --verbose
delta encode correcting old.bin new.bin delta.bin --verbose

# Also available on the inplace subcommand (shows CRWI edges and cycles broken)
delta inplace old.bin standard.delta inplace.delta --verbose
```

### --splay (Rust, C++, C, and Java)

Replace the hash table with a Tarjan-Sleator splay tree for fingerprint
lookup.  A splay tree is a self-adjusting binary search tree where every
access splays the accessed node to the root via zig/zig-zig/zig-zag
rotations, giving amortized O(log n) per operation (Sleator & Tarjan,
JACM 1985).

```bash
delta encode onepass old.bin new.bin delta.bin --splay
delta encode correcting old.bin new.bin delta.bin --splay
```

The splay tree exploits temporal locality: recently accessed fingerprints
are near the root and found quickly.  For onepass, where the same seeds
are looked up soon after insertion, this gives a significant speedup
(~3.6x on 871 MB kernel tarballs).  For correcting, where the R pass
inserts millions of checkpoint seeds in random order before any lookups,
the splay tree is slower than the hash table due to O(log n) per
insertion with no locality benefit.

The `--splay` flag does not affect the delta output format — deltas
produced with and without `--splay` are decoded identically.  The
greedy algorithm also supports `--splay`, though greedy is already
O(n^2) so the lookup structure is not the bottleneck.

Not available in Python: Python's built-in `dict` is a C-optimized hash
table that always outperforms a pure-Python tree structure.  Available in
C, Java, Rust, and C++ via the same `--splay` flag.

### Checkpointing (correcting algorithm)

The correcting algorithm uses checkpointing (Ajtai et al. 2002,
Section 8) to select which seeds enter the hash table.

Two parameters govern the hash table:

- **|C|** = auto-sized table capacity (`next_prime(max(table_size, 2 *
  num_seeds / p))`).  Each entry is ~24 bytes.  `--table-size` sets
  the floor.
- **|F|** ≈ 2|R| (auto-computed): the footprint modulus.  Set to
  `next_prime(2 * num_seeds)` for good distribution.

The checkpoint stride is `m = ⌈|F|/|C|⌉`.  A seed is a **checkpoint
seed** if its footprint `f = fingerprint mod |F|` satisfies `f ≡ k
(mod m)` (Section 8.1, Eq. 3), where `k` is a biased checkpoint class
chosen from V (p. 348).  Only checkpoint seeds are stored in or
looked up from the hash table; all others are skipped.  This gives
~|C|/2 occupied slots (~50% load factor) regardless of |R| (Section 8.1,
p. 347: L · |C|/|F| ≈ |C|/2, hence |F| ≈ 2L).

The checkpoint stride `m` equals the average spacing between checkpoint
seeds.  Matching substrings shorter than ~m bytes may be missed because
none of their seeds pass the checkpoint test.  Longer matches are found
reliably: backward extension (Section 5.1) discovers the true start of
the match even when it falls between checkpoint positions (Section 8.2,
p. 349).

With auto-sizing, `m ≈ p` (the seed length), so checkpoint granularity
roughly matches seed granularity.  When the reference is small enough
that |F| ≤ |C|, the stride is m=1 and every seed is a checkpoint —
equivalent to direct indexing with no filtering overhead.

The footprint modulus |F| is chosen as a prime using the Miller-Rabin
probabilistic primality test (Rabin 1980) with 100 random witnesses
drawn uniformly from [2, n-2).  Each witness independently has at most
a 1/4 probability of being a "liar" (falsely certifying a composite as
prime), so 100 rounds give Pr[false positive] ≤ 4^{-100} < 10^{-60}.
Using random witnesses avoids the brittleness of fixed witness sets,
which can be fooled by adversarially chosen composites — fixed witnesses
are, to put it mildly, weak sauce.  Carmichael numbers (561, 1105,
1729, ...) pass the Fermat test for all bases coprime to n, but
Miller-Rabin with random witnesses catches them reliably.

## In-place mode

Produces a delta that can reconstruct the version directly in the
buffer holding the reference, without a separate output buffer.
Useful for space-constrained environments (embedded, IoT).

```bash
# Encode with --inplace
delta encode onepass old.bin new.bin patch.delta --inplace

# Decode auto-detects the format
delta decode old.bin patch.delta recovered.bin
```

### Cycle-breaking policies

When the in-place converter finds circular dependencies between copy
commands, it must break the cycle by converting a copy to a literal add.

```bash
# localmin (default) — converts the smallest copy in each cycle
delta encode onepass old.bin new.bin patch.delta --inplace --policy localmin

# constant — converts any copy in the cycle (faster, slightly worse)
delta encode onepass old.bin new.bin patch.delta --inplace --policy constant
```

### Converting a standard delta to in-place

If you already have a standard delta and want to convert it to in-place
format without re-encoding from the original files:

```bash
# Rust, C++, C, Java
delta inplace old.bin standard.delta inplace.delta

# With verbose output (shows CRWI edge count and cycles broken)
delta inplace old.bin standard.delta inplace.delta --verbose

# Python
python3 delta.py inplace old.bin standard.delta inplace.delta
```

If the input delta is already in-place format, it is copied unchanged.

## Inspecting a delta file

```bash
delta info delta.bin
```

Output:

```
Delta file:   delta.bin (1234 bytes)
Format:       standard
Version size: 5678 bytes
Commands:     12
  Copies:     8 (4500 bytes)
  Adds:       4 (1178 bytes)
Output size:  5678 bytes
```

## Cross-language compatibility

All five implementations (Python, Rust, C++, C, Java) produce byte-identical
delta files.  You can encode with any one and decode with any other.

```bash
# Encode with Rust, decode with Python
delta encode onepass old.bin new.bin delta.bin
python3 delta.py decode old.bin delta.bin recovered.bin

# Encode with C, decode with Rust
src/c/delta encode onepass old.bin new.bin delta.bin
delta decode old.bin delta.bin recovered.bin

# Encode with Java, decode with C++
java -cp out delta.Delta encode onepass old.bin new.bin delta.bin
delta decode old.bin delta.bin recovered.bin

# Encode with Python, decode with Java
python3 delta.py encode onepass old.bin new.bin delta.bin
java -cp out delta.Delta decode old.bin delta.bin recovered.bin
```

## Using as a library

### Python

```python
from delta import (
    diff_onepass, diff_greedy, diff_correcting, DiffOptions,
    place_commands, encode_delta, decode_delta,
    apply_delta, apply_placed, apply_placed_inplace,
    make_inplace,
)

with open('old.bin', 'rb') as f:
    R = f.read()
with open('new.bin', 'rb') as f:
    V = f.read()

# Diff (verbose=True prints hash table stats to stderr)
commands = diff_onepass(R, V, verbose=True)

# Standard binary delta
placed = place_commands(commands)
delta_bytes = encode_delta(placed, inplace=False, version_size=len(V))

# Decode and reconstruct
placed2, is_inplace, version_size = decode_delta(delta_bytes)
recovered = apply_placed(R, placed2)

# In-place delta
ip_commands = make_inplace(R, commands, policy='localmin')
ip_delta = encode_delta(ip_commands, inplace=True, version_size=len(V))
recovered = apply_placed_inplace(R, ip_commands, len(V))
```

### Rust

```rust
use delta::{
    diff, Algorithm, CyclePolicy, DiffOptions,
    place_commands, encode_delta, decode_delta,
    apply_placed_to, apply_placed_inplace_to,
    make_inplace,
};

let r: &[u8] = &reference_data;
let v: &[u8] = &version_data;

// Diff with options
let opts = DiffOptions { verbose: true, ..DiffOptions::default() };
let commands = diff(Algorithm::Onepass, r, v, &opts);

// Standard binary delta
let placed = place_commands(&commands);
let delta_bytes = encode_delta(&placed, false, v.len());

// Decode and reconstruct
let (placed2, is_ip, version_size) = decode_delta(&delta_bytes)?;
let mut output = vec![0u8; version_size];
apply_placed_to(r, &placed2, &mut output);

// In-place delta
let (ip, _stats) = make_inplace(r, &commands, CyclePolicy::Localmin);
let ip_delta = encode_delta(&ip, true, v.len());
```

### C++

```cpp
#include <delta/delta.h>

using namespace delta;

std::span<const uint8_t> r = reference_data;
std::span<const uint8_t> v = version_data;

// Diff with options
DiffOptions opts;
opts.verbose = true;
auto commands = diff(Algorithm::Onepass, r, v, opts);

// Standard binary delta
auto placed = place_commands(commands);
auto delta_bytes = encode_delta(placed, false, v.size());

// Decode and reconstruct
auto [placed2, is_ip, version_size] = decode_delta(delta_bytes);
std::vector<uint8_t> output(version_size, 0);
apply_placed_to(r, placed2, output);

// In-place delta
auto ip = make_inplace(r, commands, CyclePolicy::Localmin);
auto ip_delta = encode_delta(ip, true, v.size());
```

### C

```c
#include "delta.h"

uint8_t *r = reference_data;
size_t r_len = reference_len;
uint8_t *v = version_data;
size_t v_len = version_len;

/* Diff with options (flags use delta_flag_set for verbose, splay, etc.) */
delta_diff_options_t opts = DELTA_DIFF_OPTIONS_DEFAULT;
opts.flags = delta_flag_set(opts.flags, DELTA_OPT_VERBOSE);
delta_commands_t cmds = delta_diff(ALGO_ONEPASS, r, r_len, v, v_len, &opts);

/* Standard binary delta */
delta_placed_commands_t placed = delta_place_commands(&cmds);
size_t delta_len;
uint8_t *delta_bytes = delta_encode(&placed, false, v_len, &delta_len);

/* Decode and reconstruct */
delta_decode_result_t res = delta_decode(delta_bytes, delta_len);
uint8_t *output = delta_calloc(res.version_size, 1);
delta_apply_placed(r, r_len, &res.commands, output);

/* In-place delta */
delta_placed_commands_t ip = delta_make_inplace(r, r_len, &cmds, POLICY_LOCALMIN);
size_t ip_len;
uint8_t *ip_delta = delta_encode(&ip, true, v_len, &ip_len);

/* Cleanup */
delta_commands_free(&cmds);
delta_placed_commands_free(&placed);
delta_placed_commands_free(&ip);
free(delta_bytes);
free(ip_delta);
free(output);
```

### Java

```java
import delta.*;
import static delta.Types.*;
import java.util.List;

byte[] r = readReference();
byte[] v = readVersion();

// Diff with options
DiffOptions opts = new DiffOptions();
opts.verbose = true;
List<Command> commands = Diff.diff(Algorithm.ONEPASS, r, v, opts);

// Standard binary delta
List<PlacedCommand> placed = Apply.placeCommands(commands);
byte[] deltaBytes = Encoding.encodeDelta(placed, false, v.length);

// Decode and reconstruct
Encoding.DecodeResult result = Encoding.decodeDelta(deltaBytes);
byte[] output = new byte[result.versionSize];
Apply.applyPlacedTo(r, result.commands, output);

// In-place delta
List<PlacedCommand> ip = Apply.makeInplace(r, commands, CyclePolicy.LOCALMIN);
byte[] ipDelta = Encoding.encodeDelta(ip, true, v.length);
byte[] recovered = Apply.applyDeltaInplace(r, ip, v.length);
```

## Running the tests

```bash
# Python — 168 tests
cd src/python
python3 -m unittest test_delta -v

# Rust — 52 tests (17 unit + 35 integration)
cd src/rust/delta
cargo test

# C++ — 46 tests (11 hash + 35 integration)
cd src/cpp
cmake -B build && cmake --build build
ctest --test-dir build

# C — 32 integration tests (includes cross-language verification)
cd src/c
make test

# Java — roundtrip verification via CLI
cd src/java
javac -d out delta/*.java
```

A kernel tarball benchmark (`tests/kernel-delta-test.sh`) exercises
onepass and correcting on ~871 MB inputs.

## Performance

All three algorithms use Karp-Rabin rolling hash (Eq. 2) for O(1)
per-position fingerprint updates during scanning.  The rolling hash
slides the window one byte at a time; after a match jump, the hash is
reinitialized from scratch (O(p) for one position, amortized across
the scan).

### Kernel tarball benchmark (linux-5.1 → linux-5.1.1, 871 MB)

All five implementations, same input pair, default flags.  All produce
byte-identical delta files.

**onepass** (delta: 5.1 MB, ratio: 0.58%)

| Implementation | Time |
|----------------|-----:|
| Rust | 0.6s |
| C | 0.7s |
| C++ | 0.8s |
| Java | 2.2s |
| Python | 69s |

**correcting** (delta: 7.0 MB, ratio: 0.81%)

| Implementation | Time |
|----------------|-----:|
| Rust | 5.3s |
| Java | 7.4s |
| C | 13.6s |
| C++ | 13.6s |
| Python | 354s |

Rust leads on both algorithms.  For correcting, Java outperforms C and
C++ by nearly 2×; for onepass the JVM overhead shows and Java lands
between C and Python.  C and C++ are within measurement noise of each
other on correcting; C edges C++ slightly on onepass.  Python is
60–120× slower than Rust.

**Rust, default vs `--splay` (onepass)**

| Algorithm | Flags | Time | Delta | Copies | Median copy |
|-----------|-------|-----:|------:|-------:|------------:|
| onepass | (default) | 0.6s | 5.1 MB | 205,030 | 89 B |
| onepass | `--splay` | 0.5s | 5.1 MB | 205,030 | 89 B |
| correcting | (default) | 5.3s | 7.0 MB | 243,546 | 91 B |

The copy-length distribution is heavy-tailed: median is 89–91 bytes
(barely above the 16-byte seed length), but the mean is 3,500–4,200
bytes and the maximum reaches 14 MB.  Most copies are short, but most
*bytes* come from long copies.

### Cross-version kernel benchmark (linux-5.1.x, C++)

All six ordered pairs of linux-5.1.1, 5.1.2, and 5.1.3 (~831 MB each),
encoded with the C++ implementation (default flags):

**onepass**

| Ref → Ver | Ratio | Time |
|-----------|------:|-----:|
| 5.1.1 → 5.1.2 | 0.54% | 3.8s |
| 5.1.1 → 5.1.3 | 0.55% | 2.1s |
| 5.1.2 → 5.1.1 | 0.53% | 0.8s |
| 5.1.2 → 5.1.3 | 0.47% | 0.7s |
| 5.1.3 → 5.1.1 | 0.54% | 0.8s |
| 5.1.3 → 5.1.2 | 0.47% | 0.7s |

**correcting**

| Ref → Ver | Ratio | Time |
|-----------|------:|-----:|
| 5.1.1 → 5.1.2 | 0.86% | 13.8s |
| 5.1.1 → 5.1.3 | 0.81% | 13.9s |
| 5.1.2 → 5.1.1 | 0.81% | 13.9s |
| 5.1.2 → 5.1.3 | 1.02% | 14.0s |
| 5.1.3 → 5.1.1 | 0.78% | 14.0s |
| 5.1.3 → 5.1.2 | 0.85% | 14.0s |

Onepass is 4–20× faster than correcting and achieves better ratios on
every pair.  The 5.1.1 → 5.1.2 onepass time (3.8s) is elevated by a
cold mmap cache on the first run; subsequent pairs drop to 0.7–0.8s.
Correcting times are nearly uniform (~14s) because encoding is dominated
by the build phase over the 831 MB reference.

### Effect of `--splay` on correcting compression ratio

The hash table for correcting uses `f / m` (where `f = fp % |F|`) as the
slot index, so two fingerprints with the same `f / m` collide and only
the first is retained (first-found policy).  The splay tree keys on the
full 64-bit fingerprint, so collisions are negligible: every
checkpoint-passing R seed gets its own node.  The result is that
`--splay` stores more R fingerprints and finds more matches during the V
scan, yielding slightly better compression at the cost of O(log n)
lookups instead of O(1).  On the 22 MB literary corpus benchmark,
correcting+hash gives 22.94% and correcting+splay gives 22.39%.

The correcting+splay cross-version kernel results (same six pairs, ~831 MB):

| Ref → Ver | Ratio (hash) | Ratio (splay) | Time (hash) | Time (splay) |
|-----------|-------------:|--------------:|------------:|-------------:|
| 5.1.1 → 5.1.2 | 0.86% | 0.85% | 13.8s | 48.9s |
| 5.1.1 → 5.1.3 | 0.81% | 0.80% | 13.9s | 49.5s |
| 5.1.2 → 5.1.1 | 0.81% | 0.80% | 13.9s | 48.9s |
| 5.1.2 → 5.1.3 | 1.02% | 1.00% | 14.0s | 48.6s |
| 5.1.3 → 5.1.1 | 0.78% | 0.78% | 14.0s | 49.2s |
| 5.1.3 → 5.1.2 | 0.85% | 0.83% | 14.0s | 48.7s |

Splay wins on ratio by a small but consistent margin (~0.01–0.02 pp) on
every pair, at 3.5× the time.

The O(log n) characterisation of splay lookup is a worst-case amortised
bound.  In practice the cost is closer to O(log(n/f)) for a fingerprint
with frequency f: a fingerprint that appears k times in R is splayed to
the root k times during the build phase, so the most common R fingerprints
are already near the top by the time the V scan begins.  For a Zipfian
frequency distribution — which natural language and source code both
follow closely — the weighted average access cost is O(log H) where H is
the entropy of the distribution, which can be substantially less than
O(log n).  This is the same reason LRU caching works well in practice:
the splay tree automatically performs implicit frequency-based caching,
keeping hot fingerprints near the root.  On kernel tarballs the effect is
visible: common boilerplate (headers, copyright blocks, repeated idioms)
dominates the fingerprint distribution, limiting the practical slowdown
to 3.5× rather than the O(log n) / O(1) asymptotic ratio would suggest.

### Transposition benchmark

Synthetic test: R and V contain the same blocks in different orders.
R is written in identity order; V is written in a permuted order where
the specified percentage of blocks have been displaced from their
original positions.  Generated by `tests/gen_transpositions.py`;
run with `tests/transposition-benchmark.sh`.

**16 MB — 32,000 blocks × 512 B mean (greedy, onepass, correcting)**

| Algorithm | Perm% | Ratio | Copies | Adds | Time |
|-----------|------:|------:|-------:|-----:|-----:|
| greedy | 0% | 0.0000 | 1 | 0 | 2.403s |
| greedy | 25% | 0.0112 | 14,062 | 0 | 2.377s |
| greedy | 50% | 0.0191 | 24,064 | 0 | 2.478s |
| greedy | 75% | 0.0238 | 30,015 | 0 | 2.509s |
| greedy | 100% | 0.0254 | 31,998 | 0 | 2.736s |
| onepass | 0% | 0.0000 | 1 | 0 | 0.009s |
| onepass | 25% | 0.2580 | 6,064 | 6,063 | 0.206s |
| onepass | 50% | 0.5115 | 8,068 | 8,066 | 0.387s |
| onepass | 75% | 0.7588 | 6,026 | 6,025 | 0.561s |
| onepass | 100% | 0.9921 | 268 | 268 | 0.883s |
| correcting | 0% | 0.0000 | 1 | 0 | 0.133s |
| correcting | 25% | 0.0112 | 14,062 | 0 | 0.137s |
| correcting | 50% | 0.0191 | 24,064 | 0 | 0.139s |
| correcting | 75% | 0.0238 | 30,015 | 0 | 0.144s |
| correcting | 100% | 0.0254 | 31,998 | 0 | 0.147s |

At 16 MB with 512 B blocks (~497 seeds per block), correcting matches
greedy exactly at every permutation level: zero adds, identical copy
counts and ratios.  Each block has enough seeds that at least one
passes the checkpoint filter with overwhelming probability, so no
blocks are missed.  onepass degrades severely — by 100% permutation
its ratio is 0.9921, nearly the full file size as adds.

**1 GB — 8,000,000 blocks × 128 B mean (onepass, correcting)**

| Algorithm | Perm% | Ratio | Copies | Adds | Time |
|-----------|------:|------:|-------:|-----:|-----:|
| onepass | 0% | 0.0000 | 1 | 0 | 0.513s |
| onepass | 25% | 0.4344 | 1,910,225 | 1,910,224 | 21.840s |
| onepass | 50% | 0.6720 | 1,860,789 | 1,860,788 | 33.549s |
| onepass | 75% | 0.8066 | 1,387,053 | 1,387,052 | 39.911s |
| onepass | 100% | 0.8847 | 936,854 | 936,852 | 43.270s |
| correcting | 0% | 0.0000 | 1 | 0 | 9.508s |
| correcting | 25% | 0.0689 | 4,998,726 | 58,453 | 11.494s |
| correcting | 50% | 0.0954 | 6,822,473 | 93,991 | 12.432s |
| correcting | 75% | 0.1052 | 7,491,093 | 108,998 | 12.756s |
| correcting | 100% | 0.1090 | 7,736,399 | 115,630 | 13.066s |

At 1 GB with 128 B blocks (~113 seeds per block), correcting diverges
from greedy.  The checkpoint filter now misses a measurable fraction of
blocks — 58K–116K adds per permutation level — because with only ~7
checkpoint seeds per block (113 seeds / stride m≈16), some blocks have
no seed that passes the checkpoint test for the chosen class k.
correcting is still 6–8× better than onepass at 25–100% permutation and
runs in near-constant time (~10–13 s) regardless of permutation level,
while onepass time grows with permutation as it emits more adds.

**Inplace vs normal — 16 MB, onepass and correcting**

| Algorithm | Perm% | Ratio-N | Ratio-IP | Adds-N | Adds-IP | Time-N | Time-IP |
|-----------|------:|--------:|---------:|-------:|--------:|-------:|--------:|
| onepass | 0% | 0.0000 | 0.0000 | 0 | 0 | 0.009s | 0.008s |
| onepass | 25% | 0.2580 | 0.2580 | 6,063 | 6,063 | 0.194s | 0.215s |
| onepass | 50% | 0.5115 | 0.5115 | 8,066 | 8,066 | 0.384s | 0.411s |
| onepass | 75% | 0.7588 | 0.7588 | 6,025 | 6,025 | 0.544s | 0.576s |
| onepass | 100% | 0.9921 | 0.9921 | 268 | 268 | 0.835s | 0.832s |
| correcting | 0% | 0.0000 | 0.0000 | 0 | 0 | 0.131s | 0.127s |
| correcting | 25% | 0.0112 | 0.8164 | 0 | 10,297 | 0.131s | 53.580s |
| correcting | 50% | 0.0191 | 0.6306 | 0 | 13,422 | 0.142s | 111.461s |
| correcting | 75% | 0.0238 | 0.4587 | 0 | 12,791 | 0.138s | 174.946s |
| correcting | 100% | 0.0254 | 0.4028 | 0 | 12,014 | 0.142s | 164.815s |

onepass has zero inplace overhead: it already emits adds for displaced
blocks, so the remaining copies don't create cycles in the CRWI graph.

correcting pays a severe penalty.  Because block sizes vary (uniform in
[256, 768] B), permuting blocks shifts the cumulative byte offsets of
every subsequent block.  Even blocks that stay in the same order land
at different byte positions in R vs V.  correcting emits copy commands
for all matched blocks, but virtually all of them have src ≠ dst in
byte coordinates, producing a dense CRWI dependency graph riddled with
cycles.  At 25% permutation the in-place ratio balloons from 0.0112 to
0.8164 — 73× more data emitted as adds to break the cycles — and the
conversion time grows from 0.131 s to 54 s.

## References

- M. Ajtai, R. Burns, R. Fagin, D.D.E. Long, and L. Stockmeyer.
  Compactly encoding unstructured inputs with differential compression.
  *Journal of the ACM*, 49(3):318-367, May 2002.

- R.C. Burns, D.D.E. Long, and L. Stockmeyer.
  In-place reconstruction of version differences.
  *IEEE Transactions on Knowledge and Data Engineering*, 15(4):973-984,
  Jul/Aug 2003.

- A.B. Kahn.
  Topological sorting of large networks.
  *Communications of the ACM*, 5(11):558-562, November 1962.

- R.M. Karp and M.O. Rabin.
  Efficient randomized pattern-matching algorithms.
  *IBM Journal of Research and Development*, 31(2):249-260, March 1987.

- V.I. Levenshtein.
  Binary codes capable of correcting deletions, insertions, and reversals.
  *Soviet Physics Doklady*, 10(8):707-710, 1966.

- W. Miller and E.W. Myers.
  A file comparison program.
  *Software — Practice and Experience*, 15(11):1025-1040, 1985.

- M.O. Rabin.
  Probabilistic algorithm for testing primality.
  *Journal of Number Theory*, 12(1):128-138, February 1980.

- D.D. Sleator and R.E. Tarjan.
  Self-adjusting binary search trees.
  *Journal of the ACM*, 32(3):652-686, July 1985.

- A. Reichenberger.
  Delta storage for arbitrary non-text files.
  *Proceedings of the 3rd International Workshop on Software Configuration
  Management*, pages 144-152, 1991.

- W.F. Tichy.
  The string-to-string correction problem with block moves.
  *ACM Transactions on Computer Systems*, 2(4):309-321, November 1984.

- R.A. Wagner and M.J. Fischer.
  The string-to-string correction problem.
  *Journal of the ACM*, 21(1):168-173, January 1974.
