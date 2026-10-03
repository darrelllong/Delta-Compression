# delta — Differential Compression

Computes a compact delta between two files so the new file can be
reconstructed from the old file plus the (small) delta.  Supports
in-place reconstruction — the new version can be rebuilt directly in
the buffer holding the old version, with no scratch space.

Six implementations — Python, Rust, C++, C, Java, and Go —
producing byte-identical binary deltas.  Encode with any one, decode with
any other.

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
python3 delta.py encode onepass old.bin new.bin delta.bin
python3 delta.py decode old.bin delta.bin recovered.bin
```

### Rust

```bash
cd src/rust/delta
cargo build --release
target/release/delta encode onepass old.bin new.bin delta.bin
target/release/delta decode old.bin delta.bin recovered.bin
```

### C++

```bash
cd src/cpp
cmake -B build && cmake --build build
build/delta encode onepass old.bin new.bin delta.bin
build/delta decode old.bin delta.bin recovered.bin
```

### C

```bash
cd src/c
make
./delta encode onepass old.bin new.bin delta.bin
./delta decode old.bin delta.bin recovered.bin
```

### Java

```bash
cd src/java
javac -d out delta/*.java
java -cp out delta.Delta encode onepass old.bin new.bin delta.bin
java -cp out delta.Delta decode old.bin delta.bin recovered.bin
```

### Go

```bash
cd src/go
go build -o delta/delta ./cmd/delta
delta/delta encode onepass old.bin new.bin delta.bin
delta/delta decode old.bin delta.bin recovered.bin
```

## Algorithms

| Algorithm | Time | Space | Best for |
|---|---|---|---|
| `onepass` | O(n) | O(q) | General use — fast, good compression |
| `correcting` | ~O(n) | O(q) | Data with rearranged/moved blocks |
| `greedy` | O(n^2) | O(n) | Longest match at every position — small inputs |

Here q is the number of hash table slots.  The paper fixes q, which makes
the space constant; these implementations grow the table with the
reference (about one slot for every `--seed-len` bytes for onepass, two
for correcting, up to `--max-table`).  Greedy takes the longest match at
every position.  The paper proves that optimal when matches of every
length are found; with a seed of p bytes, matches shorter than p are
missed.

All three use Karp-Rabin rolling hashes (Karp & Rabin 1987) with a
Mersenne prime (2^61-1) for fingerprinting and a polynomial base of 263
for good bit mixing.  Hash tables are auto-sized based on input length
(`--table-size` acts as a floor).  An optional `--splay` flag replaces
the hash table with a Sleator-Tarjan splay tree.  Frequently accessed
fingerprints are splayed to the root and stay there, giving O(log(n/f))
access for a fingerprint with frequency f — the same reason LRU caching
works in practice.  On the inputs measured the correcting delta is the
same either way, the onepass delta differs by a few bytes at most, and
the hash table is faster (see `ANALYSIS.md`).  Use `--verbose` to
see hash table sizing and match statistics on stderr.

See [`HOWTO.md`](HOWTO.md) for tuning parameters, library API examples,
checkpointing internals, and benchmark results.

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
(because i reads from a region j will overwrite).  Kahn's algorithm
(Kahn 1962) gives a topological sort with deterministic ordering via a
min-heap keyed on (copy length, index).  Cycles are broken by converting
copy commands to literal add commands.  The copies come first in the
delta, in that order, and the adds after them.

Cycle-breaking policies:

- **`localmin`** (default) — converts the smallest copy in each cycle.
  Minimizes compression loss.
- **`constant`** — converts the lowest-numbered copy still waiting,
  without looking for the cycle.  Faster to decide, usually worse
  compression.

## Binary delta format

One format, read and written by all six implementations.  The tools write
`DLT\x04`; they also read the older `DLT\x03`.

```
Header (29 bytes):
  DLT\x04        4-byte magic
  flags           1 byte (bit 0 = in-place)
  version_size    uint64
  src_crc         8 bytes (CRC-64/XZ of reference file)
  dst_crc         8 bytes (CRC-64/XZ of version file)

Commands (repeated):
  type 0          END (1 byte)
  type 1          COPY:    src(u32) dst(u32) len(u32)   — 13 bytes
  type 2          ADD:     dst(u32) len(u32) data       — 9 + len bytes
  type 3          BIGCOPY: src(u64) dst(u64) len(u64)   — 25 bytes
  type 4          BIGADD:  dst(u64) len(u64) data       — 17 + len bytes
  type 5          MOVE:    src(u32) dst(u32) len(u32)   — 13 bytes
  type 6          BIGMOVE: src(u64) dst(u64) len(u64)   — 25 bytes
```

All multi-byte integers are big-endian.  Commands carry explicit
source and destination offsets.  A command takes the 32-bit form when
its fields fit and the BIG form otherwise, so a delta of files under
4 GiB differs from `DLT\x03` only in its header; `--large` forces the
BIG forms.  MOVE copies bytes of the version already written; the
decoders accept it, and the encoders here do not produce it.  The
`flags` byte distinguishes standard deltas (0x00) from in-place deltas
(0x01).  `DLT\x03` has a 25-byte header with a uint32 `version_size`
and only END, COPY and ADD.

The two 8-byte CRC-64/XZ checksums are verified on decode: `src_crc`
is checked against the supplied reference file before reconstruction
begins; `dst_crc` is checked against the reconstructed output
afterwards.  Pass `--ignore-hash` to decode to skip both checks and
attempt recovery from a corrupted or mismatched delta.

## Testing

To run all unit and cross-language compatibility tests in one shot:

```bash
./tests/correctness.sh
```

Individual suites:

| Language | Command |
|----------|---------|
| Python | `cd src/python && python3 -m unittest test_delta -v` |
| Rust | `cd src/rust/delta && cargo test` |
| C++ | `cd src/cpp && cmake -B build && cmake --build build && ctest --test-dir build` |
| C | `cd src/c && make test` |
| Java | `cd src/java && make test` |
| Go | `cd src/go && go test ./delta/...` |

The C suite also checks that the six implementations produce
byte-identical deltas and decode one another's output.

Tests cover all three algorithms, binary round-trips, paper examples,
edge cases (empty/identical/completely different files), in-place
reconstruction with both cycle-breaking policies, variable-length block
transpositions (8–64 blocks with controlled transpositions),
checkpointing correctness, and cross-language compatibility.
A kernel tarball benchmark (`tests/kernel-delta-test.sh`) exercises
onepass and correcting on ~871 MB inputs.  On linux-5.1 → 5.1.1, all
six implementations are byte-compatible.  See `ANALYSIS.md` for
tables and details.

## Project layout

```
src/
  python/         Single-file library + CLI + unit tests
  rust/delta/     Cargo crate — library + clap CLI + tests
  cpp/            CMake project — static library + CLI11 CLI + tests
  c/              Makefile project — single-header API + CLI + cross-language tests
  java/           Makefile project — library + CLI + tests
  go/             Go module — library + CLI + tests
tests/
  correctness.sh          Run all unit + cross-language tests (all 6 implementations)
  kernel-delta-test.sh    Kernel tarball benchmark
  transposition-benchmark.sh  Synthetic permutation benchmark
bench/                    Raw benchmark output behind the tables in ANALYSIS.md
pubs/                     Ajtai et al. 2002, Burns et al. 2003 (PDFs)
```

Each implementation has the same architecture: rolling hash, three
algorithm modules, binary encoding, command placement, and CRWI-based
in-place conversion.  See [`HOWTO.md`](HOWTO.md) for detailed file
listings and library API examples.

## Code architecture

The same pipeline runs in all six implementations:

```
R, V (byte arrays)
    │
    ▼
[Algorithm]  greedy / onepass / correcting
    │         Karp-Rabin rolling hash (Mersenne prime 2^61-1, base 263)
    │         Hash table or splay tree maps fingerprint → source offset(s)
    │         Scan V; on match extend forward (correcting: backward too)
    │
    ▼  Vec<Command>  (Copy {offset, length} | Add {data})
    │
    ▼
[place_commands]  (trivial: assigns sequential dst offsets, no reordering)
    │
    ▼  Vec<PlacedCommand>  (Copy {src, dst, length} | Add {dst, data})
    │
    ├──────────────────────────────────────────────────────────┐
    │  (standard path)                          (--inplace)    │
    │                                     [make_inplace]       │
    │                                  CRWI digraph: edge i→j  │
    │                                  means i reads a region  │
    │                                  j will overwrite.       │
    │                                  Kahn topo-sort; cycles  │
    │                                  broken by converting    │
    │                                  smallest copy to ADD.   │
    │                                           │              │
    ▼                                           ▼              │
[encode_delta]  ←──────────────────────────────┘
    │         Header: DLT\x04 + flags(1) + version_size(u64 BE)
    │                 + src_crc(8) + dst_crc(8)
    │         COPY: 0x01 + src(u32) + dst(u32) + len(u32)
    │         ADD:  0x02 + dst(u32) + len(u32) + payload
    │         BIGCOPY 0x03, BIGADD 0x04: the same with u64 fields
    │         END:  0x00
    ▼
  binary delta file
```

**Key types**

| Type | Description |
|------|-------------|
| `Command` | Algorithm output: offset-relative `Copy` or raw `Add` |
| `PlacedCommand` | Absolute `src`/`dst` offsets — encodable and validatable |
| `DiffOptions` | `p` (seed length, default 16), `q` (table floor, default 1,048,573), `max_table`, `buf_cap`, `verbose`, `use_splay` |
| `CyclePolicy` | `Localmin` (convert smallest copy in cycle) · `Constant` (convert the lowest-numbered copy remaining) |

**Module responsibilities (Rust names; other languages mirror this)**

| Module | Role |
|--------|------|
| `hash` | Karp-Rabin rolling hash; Mersenne modulo; `next_prime` for table sizing; CRC-64/XZ |
| `splay` | Sleator-Tarjan self-adjusting BST — optional alternative to hash table |
| `algorithm/greedy` | $O(n^2)$ optimal: stores every R offset per fingerprint, scans all candidates |
| `algorithm/onepass` | O(n) linear: concurrent R+V scan, a table for each, both flushed after every match by a generation counter (§4.1) |
| `algorithm/correcting` | ~O(n): checkpoint filter (§8) limits table entries; backward extension and a lookback buffer correct what was already encoded (§5) |
| `apply` | `place_commands`, `apply_placed_to`, `apply_delta_inplace`, bounds validation |
| `inplace` | CRWI graph construction, Kahn topological sort, cycle-breaking (Burns et al. 2003) |
| `encoding` | Binary encode/decode of `DLT\x03` and `DLT\x04` |

## References

The Karp-Rabin paper introduces the rolling hash / fingerprinting
technique used by all three differencing algorithms.  Wagner and Fischer
formalized string-to-string correction (edit distance).  Tichy extended
it to block moves — the model solved by the algorithms here.
Reichenberger and Miller-Myers are the prior O(n^2) optimal algorithms
that Ajtai et al. improve upon.  Rabin's paper describes the
deterministic Miller-Rabin primality test (fixed witness set, Sorenson
and Webster 2017) used for hash table auto-sizing.  Kahn's algorithm is used for topological sorting of the
CRWI digraph during in-place conversion.  Sleator and Tarjan's splay
tree provides an alternative to hash tables for fingerprint lookup;
frequent fingerprints self-promote to the root, giving sub-logarithmic
amortised access on Zipfian distributions.

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
  month     = jul # {/} # aug,
  publisher = {IEEE},
  doi       = {10.1109/TKDE.2003.1209013},
}

@article{kahn1962topological,
  author    = {Kahn, Arthur B.},
  title     = {Topological Sorting of Large Networks},
  journal   = {Communications of the ACM},
  volume    = {5},
  number    = {11},
  pages     = {558--562},
  year      = {1962},
  month     = nov,
  publisher = {ACM},
  doi       = {10.1145/368996.369025},
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

@article{rabin1980probabilistic,
  author    = {Rabin, Michael O.},
  title     = {Probabilistic Algorithm for Testing Primality},
  journal   = {Journal of Number Theory},
  volume    = {12},
  number    = {1},
  pages     = {128--138},
  year      = {1980},
  month     = feb,
  publisher = {Elsevier},
  doi       = {10.1016/0022-314X(80)90084-0},
}

@article{levenshtein1966binary,
  author    = {Levenshtein, Vladimir I.},
  title     = {Binary Codes Capable of Correcting Deletions,
               Insertions, and Reversals},
  journal   = {Soviet Physics Doklady},
  volume    = {10},
  number    = {8},
  pages     = {707--710},
  year      = {1966},
}

@article{miller1985file,
  author    = {Miller, Webb and Myers, Eugene W.},
  title     = {A File Comparison Program},
  journal   = {Software---Practice and Experience},
  volume    = {15},
  number    = {11},
  pages     = {1025--1040},
  year      = {1985},
  publisher = {Wiley},
  doi       = {10.1002/spe.4380151102},
}

@article{sleator1985self,
  author    = {Sleator, Daniel D. and Tarjan, Robert E.},
  title     = {Self-Adjusting Binary Search Trees},
  journal   = {Journal of the ACM},
  volume    = {32},
  number    = {3},
  pages     = {652--686},
  year      = {1985},
  month     = jul,
  publisher = {ACM},
  doi       = {10.1145/3828.3835},
}

@inproceedings{reichenberger1991delta,
  author    = {Reichenberger, Christoph},
  title     = {Delta Storage for Arbitrary Non-Text Files},
  booktitle = {Proceedings of the 3rd International Workshop on
               Software Configuration Management},
  pages     = {144--152},
  year      = {1991},
  publisher = {ACM},
  doi       = {10.1145/111062.111080},
}

@article{tichy1984string,
  author    = {Tichy, Walter F.},
  title     = {The String-to-String Correction Problem with Block Moves},
  journal   = {ACM Transactions on Computer Systems},
  volume    = {2},
  number    = {4},
  pages     = {309--321},
  year      = {1984},
  month     = nov,
  publisher = {ACM},
  doi       = {10.1145/357401.357404},
}

@article{wagner1974string,
  author    = {Wagner, Robert A. and Fischer, Michael J.},
  title     = {The String-to-String Correction Problem},
  journal   = {Journal of the ACM},
  volume    = {21},
  number    = {1},
  pages     = {168--173},
  year      = {1974},
  month     = jan,
  publisher = {ACM},
  doi       = {10.1145/321796.321811},
}
```

## License

BSD 2-Clause.  See [`LICENSE`](LICENSE).
