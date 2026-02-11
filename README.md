# delta — Differential Compression

Computes a compact delta between two files so the new file can be
reconstructed from the old file plus the (small) delta.  Supports
in-place reconstruction — the new version can be rebuilt directly in
the buffer holding the old version, with no scratch space.

Implements the algorithms from two papers:

1. M. Ajtai, R. Burns, R. Fagin, D.D.E. Long, L. Stockmeyer.
   *Compactly Encoding Unstructured Inputs with Differential Compression.*
   Journal of the ACM, 49(3):318-367, May 2002.

2. R.C. Burns, D.D.E. Long, L. Stockmeyer.
   *In-Place Reconstruction of Version Differences.*
   IEEE Transactions on Knowledge and Data Engineering, 15(4):973-984, Jul/Aug 2003.

Both papers are in [`pubs/`](pubs/).

## Quick start

```bash
cd src/python

# Encode: compute delta between reference and new version
python3 delta.py encode onepass old.bin new.bin delta.bin

# Decode: reconstruct new version from reference + delta
python3 delta.py decode old.bin delta.bin recovered.bin

# Verify
diff new.bin recovered.bin   # should be empty (identical)
```

## Algorithms

| Algorithm | Time | Space | Best for |
|---|---|---|---|
| `onepass` | O(n) | O(1) | General use — fast, good compression |
| `correcting` | ~O(n) | O(q) | Data with rearranged/moved blocks |
| `greedy` | O(n^2) | O(n) | Smallest possible delta (optimal) |

All three use Karp-Rabin rolling hashes with a Mersenne prime (2^61-1)
for fingerprinting and a polynomial base of 263 for good bit mixing.

## Commands

### encode

```
python3 delta.py encode <algorithm> <reference> <version> <delta> [options]
```

Options:

```
--seed-len N      Seed length in bytes (default 16)
--table-size N    Hash table entries (default 65521)
--inplace         Produce in-place reconstructible delta
--policy P        Cycle-breaking policy: localmin (default) or constant
```

For `correcting` on large files, set `--table-size` to roughly 2x
the reference file size:

```bash
python3 delta.py encode correcting old.bin new.bin delta.bin \
    --table-size 2000003
```

### decode

```
python3 delta.py decode <reference> <delta> <output>
```

Auto-detects standard vs in-place delta format.

### info

```
python3 delta.py info <delta>
```

Shows command counts, copy/add byte totals, and format type.

## In-place mode

Standard deltas require a separate output buffer.  In-place mode
produces a delta that reconstructs the new version directly in the
buffer holding the reference, with no scratch space.  This is useful
for space-constrained devices (embedded systems, IoT) where you want
to apply a software update without needing 2x the file size in memory.

```bash
# Produce an in-place delta
python3 delta.py encode onepass old.bin new.bin patch.ipd --inplace

# Decode works the same (auto-detects format)
python3 delta.py decode old.bin patch.ipd recovered.bin
```

### How it works

The in-place converter takes a standard delta and reorders its commands
so they can safely be applied in a shared buffer.  It builds a CRWI
(Conflicting Read-Write Interval) digraph where an edge from command i
to j means "i must execute before j" (because i reads from a region j
will overwrite).  A topological sort gives a safe execution order.
Cycles are broken by converting copy commands to literal add commands.

### Cycle-breaking policies

- **`localmin`** (default) — when a cycle is found, converts the
  smallest copy in the cycle to an add.  Minimizes compression loss.
- **`constant`** — converts any vertex in the cycle.  Slightly faster,
  marginally worse compression.

In practice the overhead is small — the paper reports ~0.5% larger
with `localmin` and ~3.6% larger with `constant` on real-world data.

## Testing

```bash
cd src/python
python3 -m unittest test_delta -v
```

133 tests covering:

- All three differencing algorithms
- Binary delta encoding/decoding round-trips
- Paper examples (Section 2.1.1 and Appendix)
- Edge cases (empty files, identical files, completely different files)
- In-place reconstruction with both cycle-breaking policies
- Variable-length block transpositions (8 blocks, 200-5000 bytes each,
  permuted, reversed, interleaved with junk, duplicated, halved and
  scrambled, plus 20 random subset trials)
- Verification that `localmin` never produces more add bytes than `constant`

## Project layout

```
src/python/
    delta.py          Library + CLI
    test_delta.py     Test suite (unittest)
pubs/
    ajtai-et-al-jacm-2002-differential-compression.pdf
    burns-et-al-tkde-2003-inplace-reconstruction.pdf
README.md
```

## Binary delta format

### Standard format

VCDIFF-style codewords (first byte `k`):

| k | Meaning |
|---|---|
| 0 | END |
| 1-246 | ADD next `k` literal bytes |
| 247 | ADD with uint16 length |
| 248 | ADD with uint32 length |
| 249-255 | COPY with varying offset/length widths |

All multi-byte integers are big-endian.

### In-place format

Starts with magic bytes `IPD\x01`, then uint32 version size, then
commands in safe execution order:

| Type byte | Meaning |
|---|---|
| 0 | END |
| 1 | COPY: src(u32), dst(u32), len(u32) |
| 2 | ADD: dst(u32), len(u32), data |
