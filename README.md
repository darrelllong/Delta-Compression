# delta — Differential Compression

Computes a compact delta between two files so the new file can be
reconstructed from the old file plus the (small) delta.  Supports
in-place reconstruction — the new version can be rebuilt directly in
the buffer holding the old version, with no scratch space.

Five implementations — Python, Rust, C++, C, and Java — producing
byte-identical binary deltas.  Encode with any one, decode with any
other.

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

## Algorithms

| Algorithm | Time | Space | Best for |
|---|---|---|---|
| `onepass` | O(n) | O(1) | General use — fast, good compression |
| `correcting` | ~O(n) | O(q) | Data with rearranged/moved blocks |
| `greedy` | O(n^2) | O(n) | Smallest possible delta (optimal) |

All three use Karp-Rabin rolling hashes (Karp & Rabin 1987) with a
Mersenne prime (2^61-1) for fingerprinting and a polynomial base of 263
for good bit mixing.  Hash tables are auto-sized based on input length
(`--table-size` acts as a floor).  An optional `--splay` flag replaces
the hash table with a Sleator-Tarjan splay tree.  Frequently accessed
fingerprints are splayed to the root and stay there, giving O(log(n/f))
access for a fingerprint with frequency f — the same reason LRU caching
works in practice.  For correcting, `--splay` also improves compression
slightly: the hash table discards fingerprints that collide to the same
slot, while the splay tree stores every checkpoint-passing seed.  See
`HOWTO.md` for benchmark data.  Use `--verbose` to see hash table sizing
and match statistics on stderr.

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
copy commands to literal add commands.

Cycle-breaking policies:

- **`localmin`** (default) — converts the smallest copy in each cycle.
  Minimizes compression loss.
- **`constant`** — converts any vertex in the cycle.  Slightly faster,
  marginally worse compression.

## Binary delta format

Unified format used by all five implementations:

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

| Language | Tests | Command |
|----------|------:|---------|
| Python | 168 | `cd src/python && python3 -m unittest test_delta -v` |
| Rust | 52 | `cd src/rust/delta && cargo test` |
| C++ | 46 | `cd src/cpp && cmake -B build && cmake --build build && ctest --test-dir build` |
| C | 32 | `cd src/c && make test` |
| Java | — | `cd src/java && javac -d out delta/*.java` |

Tests cover all three algorithms, binary round-trips, paper examples,
edge cases (empty/identical/completely different files), in-place
reconstruction with both cycle-breaking policies, variable-length block
transpositions (8–64 blocks with controlled transpositions),
checkpointing correctness, and cross-language compatibility.
A kernel tarball benchmark (`tests/kernel-delta-test.sh`) exercises
onepass and correcting on ~871 MB inputs.  On linux-5.1 → 5.1.1, all
five implementations produce identical deltas; Rust is fastest (0.6s
onepass, 5.3s correcting), Python slowest (69s / 354s); see `HOWTO.md`
for the full table.

## Project layout

```
src/
  python/         Single-file library + CLI + 168-test suite
  rust/delta/     Cargo crate — library + clap CLI + 52 tests
  cpp/            CMake project — static library + CLI11 CLI + Catch2 tests
  c/              Makefile project — single-header API + CLI + 32 tests
  java/delta/     Java 11+ sources — library + CLI
tests/
  kernel-delta-test.sh    Kernel tarball benchmark
pubs/                     Ajtai et al. 2002, Burns et al. 2003 (PDFs)
```

Each implementation has the same architecture: rolling hash, three
algorithm modules, binary encoding, command placement, and CRWI-based
in-place conversion.  See [`HOWTO.md`](HOWTO.md) for detailed file
listings and library API examples.

## References

The Karp-Rabin paper introduces the rolling hash / fingerprinting
technique used by all three differencing algorithms.  Wagner and Fischer
formalized string-to-string correction (edit distance).  Tichy extended
it to block moves — the model solved by the algorithms here.
Reichenberger and Miller-Myers are the prior O(n^2) optimal algorithms
that Ajtai et al. improve upon.  Rabin's paper describes the
Miller-Rabin probabilistic primality test used for hash table
auto-sizing.  Kahn's algorithm is used for topological sorting of the
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
