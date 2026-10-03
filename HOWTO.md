# HOWTO — Differential Compression

Practical guide to building and using the `delta` tool.
For algorithmic background and benchmark data, see [ANALYSIS.md](ANALYSIS.md).

## Installation

### Python

No installation required — just Python 3.7+.

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
CLI11 must be installed (`brew install cli11`, or `libcli11-dev` on Debian
and Ubuntu): the command-line tool uses it and CMake will not configure
without it.  The library and the tests do not use it.

```bash
cd src/cpp
cmake -B build
cmake --build build
# Binary at build/delta
# Run tests
ctest --test-dir build
```

### C

Requires a C11 compiler (GCC, Clang, Apple Clang) with `__uint128_t` support,
POSIX `mmap`, and `getopt_long`.  No external dependencies.

```bash
cd src/c
make
# Binary at ./delta
# Run tests
make test
```

### Java

Requires Java 17+.  No external dependencies.

```bash
cd src/java
javac -d out delta/*.java     # or: make
# Run via java -cp out delta.Delta
# Run tests
make test
```

### Go

Requires Go 1.21+.  No external dependencies.

```bash
cd src/go
go build -o delta/delta ./cmd/delta     # or: make
# Binary at delta/delta
# Run tests
go test ./delta/...
```

## Basic usage

Compute a delta between a reference file (old) and a version file (new),
then reconstruct the version from the reference and the delta.
Options follow the positional arguments; the C, Java and Go tools accept
them nowhere else.

```bash
# Python (in src/python)
python3 delta.py encode onepass old.bin new.bin delta.bin
python3 delta.py decode old.bin delta.bin recovered.bin

# Rust (src/rust/delta/target/release/delta)
delta encode onepass old.bin new.bin delta.bin
delta decode old.bin delta.bin recovered.bin

# C++ (src/cpp/build/delta)
delta encode onepass old.bin new.bin delta.bin
delta decode old.bin delta.bin recovered.bin

# C (src/c/delta)
delta encode onepass old.bin new.bin delta.bin
delta decode old.bin delta.bin recovered.bin

# Java (in src/java)
java -cp out delta.Delta encode onepass old.bin new.bin delta.bin
java -cp out delta.Delta decode old.bin delta.bin recovered.bin

# Go (src/go/delta/delta)
delta encode onepass old.bin new.bin delta.bin
delta decode old.bin delta.bin recovered.bin

# Verify
diff new.bin recovered.bin
```

Every tool writes the DLT\x04 format, which has a 64-bit version size
and uses 64-bit command fields (BIGCOPY, BIGADD) only where an offset or
length does not fit in 32 bits.  `--large`, on `encode` and `inplace`,
forces the 64-bit commands throughout.  Decode also reads DLT\x03,
whose fields are all 32-bit.

## Choosing an algorithm

### onepass (recommended default)

Linear time.  Its two hash tables have one slot for every `seed_len`
bytes of the reference, and at least `--table-size` slots.  Good
compression for files that share long common substrings in roughly the
same order.

```bash
delta encode onepass old.bin new.bin delta.bin
```

### correcting

Near-linear 1.5-pass algorithm using checkpointing (Section 8) with
an auto-sized hash table.  `--table-size` acts as a floor; the table
scales up for large inputs, as far as `--max-table`.

**When correcting wins:** files where large blocks (≥ ~512 B) have
been wholesale transposed or moved.  The algorithm finds these
regardless of how far apart the blocks are in the file.
See the transposition benchmark in ANALYSIS.md.

**When onepass wins:** typical incremental changes (source code patches,
kernel version deltas).  On linux-5.1 → linux-5.1.1 (871 MB), the
onepass delta is 0.58% of the version and the correcting delta 0.80%;
the Rust tool on an M4 Pro takes 2.0 s for onepass and 7.1 s for
correcting.  Correcting looks up only checkpoint seeds (about one seed
in m ≈ seed_len = 16), so it misses short matches that contain none.
For these inputs onepass is both faster and produces a smaller delta.

```bash
delta encode correcting old.bin new.bin delta.bin
```

### greedy

Takes the longest match at every position of the version, in O(n^2)
time in the worst case.  It finds transposed blocks, as correcting
does, without the checkpoint filter.  Its delta is not always the
smallest of the three.  Use for small files.

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
`next_prime(max(table_size, num_seeds / p))` for onepass, and
`next_prime(min(max_table, max(table_size, 2 * num_seeds / p)))` for
correcting, where `p` is the seed length and `num_seeds = |R| - p + 1`.
For small files the floor is the table size; for large files the table
grows automatically.  Greedy does not use it.

```bash
# Lower the floor — a smaller table for a small reference (saves memory, may miss matches)
delta encode correcting old.bin new.bin delta.bin --table-size 25013

# Raise the floor — useful if auto-sizing picks something too small
delta encode correcting old.bin new.bin delta.bin --table-size 8388593
```

### --max-table (default: 1073741827)

Maximum hash table capacity for the correcting algorithm's auto-sizing
formula.  The actual size is
`next_prime(min(max_table, max(table_size, 2 * num_seeds / p)))`.
Without a cap, a very large reference file would cause the formula to request
a huge allocation; the default ceiling of ~1B entries limits the table
to ~16 GB, at 16 bytes per entry (a fingerprint and an offset).  The
default is 1073741827 (a prime near 2^30).  Onepass and greedy do not
use it.

Accepts `k`, `M`, and `B` decimal suffixes (1k = 1,000; 1M = 1,000,000;
1B = 1,000,000,000).

```bash
# Cap at 128M entries (~2 GB RAM) for a memory-constrained system
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

# Also on the inplace subcommand (shows CRWI edges and cycles broken)
delta inplace old.bin standard.delta inplace.delta --verbose
```

### --splay (Rust, C++, C, Java, and Go)

Replace the hash table with a Tarjan-Sleator splay tree for fingerprint
lookup.  A splay tree is a self-adjusting binary search tree where every
access splays the accessed node to the root via zig/zig-zig/zig-zag
rotations, giving amortized O(log n) per operation (Sleator & Tarjan,
JACM 1985).

```bash
delta encode onepass old.bin new.bin delta.bin --splay
delta encode correcting old.bin new.bin delta.bin --splay
```

For correcting, the delta is the same with and without `--splay`: the
hash table resolves collisions by linear probing on the full
fingerprint, so both keep the first offset in the reference for each
checkpoint fingerprint.  The splay tree is several times slower.  For
onepass the hash table keeps one seed per slot and the splay tree keeps
every seed, so the two deltas can differ by a few bytes, in either
direction.  See [ANALYSIS.md](ANALYSIS.md) for details.

The `--splay` flag does not affect the delta output format — deltas
produced with and without `--splay` are decoded identically.

Not available in Python.

### Checkpointing (correcting algorithm)

The correcting algorithm uses checkpointing (Ajtai et al. 2002, Section 8)
to select which seeds enter the hash table.  The checkpoint stride `m`
controls how finely the reference is sampled: about one seed in `m` is
a checkpoint.  With auto-sizing, `m ≈ p` (the seed length) for a large
reference, and `m = 1` (every seed) for one small enough to fit under
the `--table-size` floor.  A match is found only if it contains a
checkpoint seed, so matches not much longer than `m` bytes may be
missed; backward extension recovers the part of a match that precedes
its first checkpoint.  See [ANALYSIS.md](ANALYSIS.md)
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

### Hash verification

Decode verifies two CRC-64/XZ checksums embedded in every delta file:
the reference file CRC (pre-check, before reconstruction) and the output
CRC (post-check, after reconstruction).  A mismatch aborts with an
error.  When the post-check fails, the C and C++ tools write no output
file and the Python tool removes the one it wrote; Rust, Java and Go
leave the bad output in place.  To attempt recovery
from a corrupted or mismatched delta, pass `--ignore-hash`; both checks
are replaced with warnings and decoding proceeds:

> **Security note:** CRC-64/XZ detects accidental corruption (bit flips,
> truncation, wrong reference file) but is **not** a cryptographic
> integrity check.  An attacker who can modify the delta file can also
> forge a matching CRC.  For firmware OTA or any distribution over an
> untrusted channel, wrap the delta in a signed envelope (GPG, code
> signing, HMAC) before transmitting — do not rely on the embedded CRC
> for authentication.

```bash
delta decode wrong-ref.bin delta.bin recovered.bin --ignore-hash
```

### Cycle-breaking policies

When the in-place converter finds circular dependencies between copy
commands, it must break the cycle by converting a copy to a literal add.

```bash
# localmin (default) — converts the smallest copy in each cycle
delta encode onepass old.bin new.bin patch.delta --inplace --policy localmin

# constant — converts the first copy not yet scheduled, without
# searching for the cycle (may convert more bytes than localmin)
delta encode onepass old.bin new.bin patch.delta --inplace --policy constant
```

### Converting a standard delta to in-place

If you already have a standard delta and want to convert it to in-place
format without re-encoding from the original files:

```bash
# Rust, C++, C, Go
delta inplace old.bin standard.delta inplace.delta

# With another cycle-breaking policy
delta inplace old.bin standard.delta inplace.delta --policy constant

# Verbose output (shows CRWI edge count and cycles broken)
delta inplace old.bin standard.delta inplace.delta --verbose

# Python
python3 delta.py inplace old.bin standard.delta inplace.delta

# Java
java -cp out delta.Delta inplace old.bin standard.delta inplace.delta
```

If the input delta is already in-place format, it is copied unchanged.
Otherwise the reference is checked against the CRC in the delta before
anything is written, since a copy that becomes an add takes its bytes
from the reference; the wrong reference is an error.  A delta that
contains a MOVE command cannot be converted and is also an error.

## Inspecting a delta file

```bash
delta info delta.bin
```

Output:

```
Delta file:   delta.bin (4091 bytes)
Format:       standard
Version size: 19017 bytes
Src CRC:      bca14d779e200e05
Dst CRC:      91587b5def57f55f
Commands:     4
  Copies:     2 (15000 bytes)
  Adds:       2 (4017 bytes)
Output size:  19017 bytes
```

`Src CRC` and `Dst CRC` are the CRC-64/XZ of the reference and of the
version.  The Python tool prints byte counts with thousands separators
(`4,091 bytes`).

## Cross-language compatibility

All six implementations (Python, Rust, C++, C, Java, Go)
produce byte-identical delta files from the same inputs and options.
You can encode with any one and decode with any other.

```bash
# From the repository root

# Encode with Rust, decode with Python
src/rust/delta/target/release/delta encode onepass old.bin new.bin delta.bin
python3 src/python/delta.py decode old.bin delta.bin recovered.bin

# Encode with C, decode with Go
src/c/delta encode onepass old.bin new.bin delta.bin
src/go/delta/delta decode old.bin delta.bin recovered.bin

# Encode with Java, decode with C++
java -cp src/java/out delta.Delta encode onepass old.bin new.bin delta.bin
src/cpp/build/delta decode old.bin delta.bin recovered.bin

# Encode with Python, decode with Java
python3 src/python/delta.py encode onepass old.bin new.bin delta.bin
java -cp src/java/out delta.Delta decode old.bin delta.bin recovered.bin
```

## Using as a library

Each example computes a delta, encodes it, decodes it, and reconstructs
the version, first as a standard delta and then in place.  The examples
write DLT\x04, the format the CLI writes: a 64-bit version size, and
64-bit command fields where a 32-bit field would not hold the value.
Each library also has an encoder for DLT\x03, whose fields are all
32-bit; it is named in a comment in each example.  The decoder reads
both.

### Python

Run from `src/python`, or with that directory on `sys.path`.

```python
from delta import (
    diff_onepass, diff_greedy, diff_correcting,
    place_commands, encode_delta_large, decode_delta,
    apply_placed, apply_placed_inplace,
    make_inplace, _crc64_xz,
)

with open('old.bin', 'rb') as f:
    R = f.read()
with open('new.bin', 'rb') as f:
    V = f.read()

# Diff (verbose=True prints hash table stats to stderr)
commands = diff_onepass(R, V, verbose=True)

# Standard binary delta (src_crc/dst_crc required).
# encode_delta_large writes DLT\x04; encode_delta, which takes the same
# arguments, writes DLT\x03.
placed = place_commands(commands)
src_crc = _crc64_xz(R)
dst_crc = _crc64_xz(V)
delta_bytes = encode_delta_large(placed, inplace=False, version_size=len(V),
                                 src_crc=src_crc, dst_crc=dst_crc)

# Decode and reconstruct
placed2, is_inplace, version_size, src_crc2, dst_crc2 = decode_delta(delta_bytes)
recovered = apply_placed(R, placed2)

# In-place delta
ip_commands = make_inplace(R, commands, policy='localmin')
ip_delta = encode_delta_large(ip_commands, inplace=True, version_size=len(V),
                              src_crc=src_crc, dst_crc=dst_crc)
recovered = apply_placed_inplace(R, ip_commands, len(V))
```

### Rust

The package is `delta-compression`; its library crate is `delta`.

```rust
use delta::{
    apply_delta_inplace, apply_placed_to, crc64_xz, decode_delta, diff,
    encode_delta_large, make_inplace, place_commands,
    Algorithm, CyclePolicy, DiffOptions,
};

let r: &[u8] = &reference_data;
let v: &[u8] = &version_data;

// Diff with options
let opts = DiffOptions { verbose: true, ..DiffOptions::default() };
let commands = diff(Algorithm::Onepass, r, v, &opts);

// Standard binary delta (src_crc/dst_crc required).
// encode_delta_large writes DLT\x04; encode_delta, without the last
// argument, writes DLT\x03 and returns a Result.
// place_commands takes the commands by value.
let placed = place_commands(commands.clone());
let src_crc = crc64_xz(r);
let dst_crc = crc64_xz(v);
let delta_bytes = encode_delta_large(&placed, false, v.len(), &src_crc, &dst_crc, false);

// Decode and reconstruct
let (placed2, _is_inplace, version_size, _src_crc2, _dst_crc2) = decode_delta(&delta_bytes)?;
let mut output = vec![0u8; version_size];
apply_placed_to(r, &placed2, &mut output);

// In-place delta
let (ip, _stats) = make_inplace(r, &commands, CyclePolicy::Localmin);
let ip_delta = encode_delta_large(&ip, true, v.len(), &src_crc, &dst_crc, false);
let recovered = apply_delta_inplace(r, &ip, v.len());
```

### C++

Compile with `-std=c++20 -Isrc/cpp/include` and link `libdelta_lib.a`
from the build directory.

```cpp
#include <delta/delta.h>

using namespace delta;

std::span<const uint8_t> r = reference_data;
std::span<const uint8_t> v = version_data;

// Diff with options
DiffOptions opts;
opts.verbose = true;
auto commands = diff(Algorithm::Onepass, r, v, opts);

// Standard binary delta (src_crc/dst_crc required).
// encode_delta_large writes DLT\x04; encode_delta, with the same
// arguments, writes DLT\x03.
auto placed = place_commands(commands);
auto src_crc = crc64_xz(r.data(), r.size());
auto dst_crc = crc64_xz(v.data(), v.size());
auto delta_bytes = encode_delta_large(placed, false, v.size(), src_crc, dst_crc);

// Decode and reconstruct
auto [placed2, is_inplace, version_size, src_crc2, dst_crc2] = decode_delta(delta_bytes);
std::vector<uint8_t> output(version_size, 0);
apply_placed_to(r, placed2, output);

// In-place delta
auto ip = make_inplace(r, commands, CyclePolicy::Localmin);
auto ip_delta = encode_delta_large(ip, true, v.size(), src_crc, dst_crc);
auto recovered = apply_delta_inplace(r, ip, v.size());
```

### C

Compile with the library sources in `src/c` (every `.c` file there
except `main.c` and `test_overflow.c`).

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

/* Standard binary delta (src_crc/dst_crc required).
   delta_encode_large writes DLT\x04; delta_encode, without the last
   argument, writes DLT\x03. */
delta_placed_commands_t placed = delta_place_commands(&cmds);
uint8_t src_crc[DELTA_CRC_SIZE], dst_crc[DELTA_CRC_SIZE];
delta_crc64_xz(r, r_len, src_crc);
delta_crc64_xz(v, v_len, dst_crc);
delta_buffer_t encoded = delta_encode_large(&placed, false, v_len, src_crc, dst_crc, false);

/* Decode and reconstruct */
delta_decode_result_t res = delta_decode(encoded.data, encoded.len);
delta_buffer_t output = delta_apply_placed(r, &res.commands, res.version_size);

/* In-place delta */
delta_placed_commands_t ip = delta_make_inplace(r, r_len, &cmds, POLICY_LOCALMIN);
delta_buffer_t ip_encoded = delta_encode_large(&ip, true, v_len, src_crc, dst_crc, false);
delta_buffer_t recovered = delta_apply_delta_inplace(r, r_len, &ip, v_len);

/* Cleanup */
delta_commands_free(&cmds);
delta_placed_commands_free(&placed);
delta_buffer_free(&encoded);
delta_decode_result_free(&res);
delta_buffer_free(&output);
delta_placed_commands_free(&ip);
delta_buffer_free(&ip_encoded);
delta_buffer_free(&recovered);
```

### Java

Compile and run with `src/java/out` on the class path.

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

// Standard binary delta (srcCrc/dstCrc required).
// encodeDeltaLarge writes DLT\x04; encodeDelta, without the last
// argument, writes DLT\x03.
List<PlacedCommand> placed = Apply.placeCommands(commands);
byte[] srcCrc = Hash.Crc64.hash8(r);
byte[] dstCrc = Hash.Crc64.hash8(v);
byte[] deltaBytes = Encoding.encodeDeltaLarge(placed, false, v.length, srcCrc, dstCrc, false);

// Decode and reconstruct
Encoding.DecodeResult result = Encoding.decodeDelta(deltaBytes);
byte[] output = new byte[(int) result.versionSize()];
Apply.applyPlacedTo(r, result.commands(), output);

// In-place delta
List<PlacedCommand> ip = Apply.makeInplace(r, commands, CyclePolicy.LOCALMIN);
byte[] ipDelta = Encoding.encodeDeltaLarge(ip, true, v.length, srcCrc, dstCrc, false);
byte[] recovered = Apply.applyDeltaInplace(r, ip, v.length);
```

### Go

The module in `src/go` is named `delta` and its package is
`delta/delta`.  A program outside that module needs a `replace`
directive (or a `go.work` file) that points `delta` at `src/go`.

```go
import (
	"delta/delta"
	"log"
)

r := referenceData
v := versionData

// Diff with options
opts := delta.DefaultDiffOptions()
opts.Verbose = true
commands := delta.Diff(delta.AlgorithmOnepass, r, v, opts)

// Standard binary delta (srcCrc/dstCrc required).
// EncodeDeltaLarge writes DLT\x04; EncodeDelta, without the last
// argument, writes DLT\x03 and also returns an error.
placed := delta.PlaceCommands(commands)
srcCrc := delta.Crc64XZ(r)
dstCrc := delta.Crc64XZ(v)
deltaBytes := delta.EncodeDeltaLarge(placed, false, len(v), srcCrc, dstCrc, false)

// Decode and reconstruct
d, err := delta.DecodeDelta(deltaBytes)
if err != nil {
	log.Fatal(err)
}
output := make([]byte, d.VersionSize)
delta.ApplyPlacedTo(r, d.Commands, output)

// In-place delta
ip := delta.MakeInplace(r, commands, delta.CyclePolicyLocalmin)
ipDelta := delta.EncodeDeltaLarge(ip, true, len(v), srcCrc, dstCrc, false)
recovered := delta.ApplyDeltaInplace(r, ip, len(v))
```

## Running the tests

To run all suites at once (builds every implementation, runs unit tests,
then runs cross-language compatibility tests):

```bash
./tests/correctness.sh
```

Individual suites:

```bash
# Python
cd src/python
python3 -m unittest test_delta -v

# Rust
cd src/rust/delta
cargo test

# C++
cd src/cpp
cmake -B build && cmake --build build
ctest --test-dir build

# C, and cross-language compatibility
cd src/c
make test

# Java (requires Java 17+)
cd src/java
make test

# Go
cd src/go
go test ./delta/...
```

A kernel tarball benchmark (`tests/kernel-delta-test.sh`) exercises
onepass and correcting on ~871 MB inputs.  The transposition benchmark
(`tests/transposition-benchmark.sh`) measures the three algorithms and
in-place conversion on files whose blocks are increasingly permuted.
