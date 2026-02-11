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
Uses a larger hash table to find matches across the whole reference.

```bash
# For large files, set --table-size to ~2x reference size
delta encode correcting old.bin new.bin delta.bin --table-size 2000003
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

### --table-size (default: 65521)

Hash table size.  Larger tables reduce collisions and find more matches,
especially for the correcting algorithm.  For correcting on large files,
set to approximately 2x the reference file size divided by seed length.

```bash
# 1 MB reference with 16-byte seeds: ~125K entries, use ~250K
delta encode correcting old.bin new.bin delta.bin --table-size 250007
```

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

The Python and Rust implementations produce byte-identical delta files.
You can encode with one and decode with the other.

```bash
# Encode with Rust, decode with Python
delta encode onepass old.bin new.bin delta.bin
python3 delta.py decode old.bin delta.bin recovered.bin

# Encode with Python, decode with Rust
python3 delta.py encode onepass old.bin new.bin delta.bin
delta decode old.bin delta.bin recovered.bin
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

R = open('old.bin', 'rb').read()
V = open('new.bin', 'rb').read()

# Diff
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

// Diff
let commands = diff(Algorithm::Onepass, r, v, 16, 65521);

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

## Running the tests

```bash
# Python — 133 tests
cd src/python
python3 -m unittest test_delta -v

# Rust — 54 tests
cd src/rust/delta
cargo test
```
