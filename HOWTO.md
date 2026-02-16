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

# C++
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
```

### --min-copy (default: 0)

Minimum match length for COPY commands.  When `--min-copy` exceeds
`--seed-len`, the effective seed length is raised to `--min-copy` so
that the rolling hash window itself is larger — no computation is
wasted on short matches that would be discarded.  When set to 0 (the
default), the seed length is used as the natural minimum.

```bash
# Only emit copies of 128+ bytes (seed length raised to 128)
delta encode onepass old.bin new.bin delta.bin --min-copy 128

# Only emit copies of 256+ bytes (aggressive filtering)
delta encode correcting old.bin new.bin delta.bin --min-copy 256
```

With a larger effective seed length, fewer seeds exist, the hash table
is smaller (less memory), and fewer checkpoint operations are needed.
On 871 MB kernel tarballs, `--min-copy 128` reduces the correcting
hash table from 2.5 GB to 311 MB and runs in 4.1s versus 5.9s for the
default.  The tradeoff is a larger delta: short matches are absorbed
into ADD commands, increasing delta size from 7 MB to 13 MB.

The flag does not affect the delta format — deltas produced with any
`--min-copy` value are decoded identically by all three implementations.

### --splay (Rust, C++, and Java)

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
Java via the same `--splay` flag.

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

All four implementations (Python, Rust, C++, Java) produce byte-identical
delta files.  You can encode with any one and decode with any other.

```bash
# Encode with Rust, decode with Python
delta encode onepass old.bin new.bin delta.bin
python3 delta.py decode old.bin delta.bin recovered.bin

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
    diff_onepass, diff_greedy, diff_correcting,
    place_commands, encode_delta, decode_delta,
    apply_delta, apply_placed, apply_placed_inplace,
    make_inplace,
)

with open('old.bin', 'rb') as f:
    R = f.read()
with open('new.bin', 'rb') as f:
    V = f.read()

# Diff (verbose=True prints hash table stats to stderr)
commands = diff_onepass(R, V)

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
    diff, Algorithm, CyclePolicy,
    place_commands, encode_delta, decode_delta,
    apply_placed_to, apply_placed_inplace_to,
    make_inplace,
};

let r: &[u8] = &reference_data;
let v: &[u8] = &version_data;

// Diff (verbose=true prints stats, use_splay=true for splay tree, min_copy=0 for default)
let commands = diff(Algorithm::Onepass, r, v, 16, 1048573, false, false, 0);

// Standard binary delta
let placed = place_commands(&commands);
let delta_bytes = encode_delta(&placed, false, v.len());

// Decode and reconstruct
let (placed2, is_ip, version_size) = decode_delta(&delta_bytes)?;
let mut output = vec![0u8; version_size];
apply_placed_to(r, &placed2, &mut output);

// In-place delta
let ip = make_inplace(r, &commands, CyclePolicy::Localmin);
let ip_delta = encode_delta(&ip, true, v.len());
```

### C++

```cpp
#include <delta/delta.h>

using namespace delta;

std::span<const uint8_t> r = reference_data;
std::span<const uint8_t> v = version_data;

// Diff (verbose=true prints stats, use_splay=true for splay tree, min_copy=0 for default)
auto commands = diff(Algorithm::Onepass, r, v, SEED_LEN, TABLE_SIZE, false, false, 0);

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

### Java

```java
import delta.*;
import static delta.Types.*;
import java.util.List;

byte[] r = readReference();
byte[] v = readVersion();

// Diff (verbose=true prints stats, useSplay=true for splay tree, minCopy=0 for default)
List<Command> commands = Diff.diff(Algorithm.ONEPASS, r, v,
    SEED_LEN, TABLE_SIZE, false, false, 0);

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
# Python — 154 tests
cd src/python
python3 -m unittest test_delta -v

# Rust — 52 tests (17 unit + 35 integration)
cd src/rust/delta
cargo test

# C++ — 46 tests (11 hash + 35 integration)
cd src/cpp
cmake -B build && cmake --build build
ctest --test-dir build
```

A kernel tarball benchmark (`tests/kernel-delta-test.sh`) exercises
onepass and correcting on ~871 MB inputs.

## Performance

All three algorithms use Karp-Rabin rolling hash (Eq. 2) for O(1)
per-position fingerprint updates during scanning.  The rolling hash
slides the window one byte at a time; after a match jump, the hash is
reinitialized from scratch (O(p) for one position, amortized across
the scan).

### Kernel tarball benchmark (linux-5.1 → 5.1.1, 871 MB, Rust)

| Algorithm | Flags | Time | Delta | Copies | Median copy |
|-----------|-------|------|-------|--------|-------------|
| onepass | (default) | 1.5s | 5.1 MB | 205,030 | 89 B |
| onepass | `--min-copy 128` | 1.1s | 11.5 MB | 68,478 | 3,950 B |
| onepass | `--splay` | 1.0s | 5.1 MB | 205,030 | 89 B |
| correcting | (default) | 5.9s | 7.0 MB | 243,546 | 91 B |
| correcting | `--min-copy 128` | 4.1s | 13.0 MB | 72,446 | 3,438 B |

The copy-length distribution is heavy-tailed: median is 89–91 bytes
(barely above the 16-byte seed length), but the mean is 3,500–4,200
bytes and the maximum reaches 14 MB.  Most copies are short, but most
*bytes* come from long copies.

With `--min-copy 128`, the ~137,000 short copies are absorbed into ADD
commands.  The delta grows from 5–7 MB to 11–13 MB, but encoding is
faster because the effective seed length is 128 (fewer seeds, smaller
hash table, fewer checkpoint operations).

## References

- M. Ajtai, R. Burns, R. Fagin, D.D.E. Long, and L. Stockmeyer.
  Compactly encoding unstructured inputs with differential compression.
  *Journal of the ACM*, 49(3):318-367, May 2002.

- R.C. Burns, D.D.E. Long, and L. Stockmeyer.
  In-place reconstruction of version differences.
  *IEEE Transactions on Knowledge and Data Engineering*, 15(4):973-984,
  Jul/Aug 2003.

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
