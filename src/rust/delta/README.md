# delta-compression

Binary delta compression in pure, safe Rust. Computes a *delta* —
a compact wire-format patch — from a `source` byte sequence to a
`target` byte sequence, and applies the delta to recover `target`
from `source`. Supports in-place patching against a single buffer
when the delta was emitted with the `INPLACE` flag set.

Three diff algorithms are provided:

- `diff_greedy` — greedy maximal-match heuristic; fastest, larger output.
- `diff_onepass` — single-pass scan with rolling-hash matching;
  good time-vs-size balance.
- `diff_correcting` — two-pass with optimisation pass; smallest
  output, slowest.

The library is also reachable as a CLI:

```sh
cargo install delta-compression
delta diff   source.bin target.bin patch.delta
delta apply  source.bin patch.delta  recovered.bin
```

This is the Rust port of the multi-language reference implementation
at <https://github.com/darrelllong/delta-compression>; the C, C++,
Go, Java, and Python ports live in sibling `src/<lang>/` directories
in that repo.

## Licence

BSD 2-Clause. See [`LICENSE`](LICENSE).
