# HOWTO — Differential Compression

Practical guide to building and using the `delta` tool.
For algorithmic background and benchmark data, see [ANALYSIS.md](ANALYSIS.md).

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

### --max-table (default: 1073741827)

Maximum hash table capacity for the correcting algorithm's auto-sizing
formula.  The actual size is `next_prime(min(max_table, 2 * num_seeds / p))`.
Without a cap, a very large reference file would cause the formula to request
a huge allocation; the default ceiling of ~1B entries limits consumption
to ~24 GB.  The default is 1073741827 (a prime near 2^30).

Accepts `k`, `M`, and `B` decimal suffixes (1k = 1,000; 1M = 1,000,000;
1B = 1,000,000,000).

```bash
# Cap at 128M entries (~3 GB RAM) for a memory-constrained system
delta encode correcting old.bin new.bin delta.bin --max-table 128M

# Allow a larger table on a high-memory server
delta encode correcting old.bin new.bin delta.bin --max-table 2B
```

A smaller cap increases the checkpoint stride, causing the filter to miss
more matches and raising the compression ratio.  See the `--max-table`
benchmark in [ANALYSIS.md](ANALYSIS.md) for measured ratios across table sizes.

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

For onepass, `--splay` is faster than the hash table (~17% on 871 MB kernel
tarballs) because seeds are inserted and then looked up shortly after
(temporal locality).  For correcting, `--splay` is slower (3.5×) but
achieves slightly better compression by avoiding hash collisions.
See [ANALYSIS.md](ANALYSIS.md) for details.

The `--splay` flag does not affect the delta output format — deltas
produced with and without `--splay` are decoded identically.

Not available in Python: Python's built-in `dict` is a C-optimized hash
table that always outperforms a pure-Python tree structure.

### Checkpointing (correcting algorithm)

The correcting algorithm uses checkpointing (Ajtai et al. 2002, Section 8)
to select which seeds enter the hash table.  The checkpoint stride `m`
controls how finely the reference is sampled; with auto-sizing, `m ≈ p`
(the seed length).  Matches shorter than ~m bytes may be missed; longer
matches are always found via backward extension.  See [ANALYSIS.md](ANALYSIS.md)
for the full parameter description.

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
delta_buffer_t encoded = delta_encode(&placed, false, v_len);

/* Decode and reconstruct */
delta_decode_result_t res = delta_decode(encoded.data, encoded.len);
delta_buffer_t output = delta_apply_placed(r, &res.commands, res.version_size);

/* In-place delta */
delta_placed_commands_t ip = delta_make_inplace(r, r_len, &cmds, POLICY_LOCALMIN);
delta_buffer_t ip_encoded = delta_encode(&ip, true, v_len);

/* Cleanup */
delta_commands_free(&cmds);
delta_placed_commands_free(&placed);
delta_buffer_free(&encoded);
delta_decode_result_free(&res);
delta_buffer_free(&output);
delta_placed_commands_free(&ip);
delta_buffer_free(&ip_encoded);
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
python3 test_delta.py

# Rust — 38 integration tests
cd src/rust/delta
cargo test

# C++ — 46 test cases
cd src/cpp
cmake -B build && cmake --build build
ctest --test-dir build

# C — 39 integration tests
cd src/c
make test

# Java — 39 integration tests
cd src/java
make test
```

A kernel tarball benchmark (`tests/kernel-delta-test.sh`) exercises
onepass and correcting on ~871 MB inputs.  The transposition benchmark
(`tests/transposition-benchmark.sh`) tests in-place conversion under
increasing permutation pressure.
