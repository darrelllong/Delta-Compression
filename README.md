# delta — Differential Compression

Computes a compact delta between two files so the new file can be
reconstructed from the old file plus the (small) delta.  Supports
in-place reconstruction — the new version can be rebuilt directly in
the buffer holding the old version, with no scratch space.

Two implementations — Python and Rust — producing identical binary
deltas.  The Rust version is ~20x faster thanks to O(1) rolling hash
updates and zero-copy mmap I/O.

Implements the algorithms from two papers:

1. M. Ajtai, R. Burns, R. Fagin, D.D.E. Long, L. Stockmeyer.
   *Compactly Encoding Unstructured Inputs with Differential Compression.*
   Journal of the ACM, 49(3):318-367, May 2002.

2. R.C. Burns, D.D.E. Long, L. Stockmeyer.
   *In-Place Reconstruction of Version Differences.*
   IEEE Transactions on Knowledge and Data Engineering, 15(4):973-984, Jul/Aug 2003.

Both papers are in [`pubs/`](pubs/).

## Quick start

### Python

```bash
cd src/python

# Encode
python3 delta.py encode onepass old.bin new.bin delta.bin

# Decode
python3 delta.py decode old.bin delta.bin recovered.bin
```

### Rust

```bash
cd src/rust/delta
cargo build --release

# Encode
cargo run --release -- encode onepass old.bin new.bin delta.bin

# Decode
cargo run --release -- decode old.bin delta.bin recovered.bin
```

The two implementations produce byte-identical delta files.
Encode with one, decode with the other.

## Algorithms

| Algorithm | Time | Space | Best for |
|---|---|---|---|
| `onepass` | O(n) | O(1) | General use — fast, good compression |
| `correcting` | ~O(n) | O(q) | Data with rearranged/moved blocks |
| `greedy` | O(n^2) | O(n) | Smallest possible delta (optimal) |

All three use Karp-Rabin rolling hashes with a Mersenne prime (2^61-1)
for fingerprinting and a polynomial base of 263 for good bit mixing.

## In-place mode

Standard deltas require a separate output buffer.  In-place mode
produces a delta that reconstructs the new version directly in the
buffer holding the reference, with no scratch space.  Useful for
space-constrained devices (embedded systems, IoT) where you want
to apply a software update without needing 2x the file size in memory.

```bash
# Produce an in-place delta
delta encode onepass old.bin new.bin patch.delta --inplace

# Decode works the same (auto-detects format)
delta decode old.bin patch.delta recovered.bin
```

The in-place converter builds a CRWI (Conflicting Read-Write Interval)
digraph where an edge from command i to j means "i must execute before j"
(because i reads from a region j will overwrite).  A topological sort
gives a safe execution order.  Cycles are broken by converting copy
commands to literal add commands.

Cycle-breaking policies:

- **`localmin`** (default) — converts the smallest copy in each cycle.
  Minimizes compression loss.
- **`constant`** — converts any vertex in the cycle.  Slightly faster,
  marginally worse compression.

## Binary delta format

Unified format used by both implementations:

```
Header (9 bytes):
  DLT\x01        4-byte magic
  flags           1 byte (bit 0 = in-place)
  version_size    uint32 big-endian

Commands (repeated):
  type 0          END (1 byte)
  type 1          COPY: src(u32) dst(u32) len(u32)  — 13 bytes
  type 2          ADD:  dst(u32) len(u32) data       — 9 + len bytes
```

All multi-byte integers are big-endian.  Commands carry explicit
source and destination offsets.  The `flags` byte distinguishes
standard deltas (flag 0x00) from in-place deltas (flag 0x01).

## Testing

```bash
# Python — 133 tests
cd src/python
python3 -m unittest test_delta -v

# Rust — 54 tests (4 unit + 50 integration)
cd src/rust/delta
cargo test
```

Tests cover all three algorithms, binary round-trips, paper examples,
edge cases (empty/identical/completely different files), in-place
reconstruction with both cycle-breaking policies, variable-length block
transpositions (8 blocks, 200-5000 bytes each, permuted, reversed,
interleaved with junk, duplicated, halved and scrambled, plus 20
random subset trials), and cross-language compatibility.

## Project layout

```
src/
  python/
    delta.py              Library + CLI
    test_delta.py         Test suite (133 tests)
  rust/delta/
    src/
      lib.rs              Re-exports
      main.rs             CLI (clap)
      types.rs            Command, PlacedCommand, constants
      hash.rs             Karp-Rabin rolling hash
      encoding.rs         Unified binary format
      apply.rs            place_commands, apply_placed_to, apply_placed_inplace_to
      inplace.rs          CRWI digraph, topological sort, cycle breaking
      algorithm/
        mod.rs            Dispatch
        greedy.rs         O(n^2) optimal
        onepass.rs        O(n) linear
        correcting.rs     1.5-pass with tail correction
    tests/
      integration.rs      50 integration tests
    Cargo.toml
pubs/
  ajtai-et-al-jacm-2002-differential-compression.pdf
  burns-et-al-tkde-2003-inplace-reconstruction.pdf
README.md
HOWTO.md
```

## References

### BibTeX

```bibtex
@article{ajtai2002differential,
  author    = {Ajtai, Mikl\'{o}s and Burns, Randal and Fagin, Ronald
               and Long, Darrell D. E. and Stockmeyer, Larry},
  title     = {Compactly Encoding Unstructured Inputs with
               Differential Compression},
  journal   = {Journal of the ACM},
  volume    = {49},
  number    = {3},
  pages     = {318--367},
  year      = {2002},
  month     = may,
  publisher = {ACM},
  doi       = {10.1145/567112.567114},
}

@article{burns2003inplace,
  author    = {Burns, Randal C. and Long, Darrell D. E.
               and Stockmeyer, Larry},
  title     = {In-Place Reconstruction of Version Differences},
  journal   = {IEEE Transactions on Knowledge and Data Engineering},
  volume    = {15},
  number    = {4},
  pages     = {973--984},
  year      = {2003},
  month     = {jul/aug},
  publisher = {IEEE},
  doi       = {10.1109/TKDE.2003.1209013},
}

@article{karp1987efficient,
  author    = {Karp, Richard M. and Rabin, Michael O.},
  title     = {Efficient Randomized Pattern-Matching Algorithms},
  journal   = {IBM Journal of Research and Development},
  volume    = {31},
  number    = {2},
  pages     = {249--260},
  year      = {1987},
  month     = mar,
  publisher = {IBM},
  doi       = {10.1147/rd.312.0249},
}
```

The Karp-Rabin paper describes the rolling hash / fingerprinting
technique used by all three differencing algorithms.
