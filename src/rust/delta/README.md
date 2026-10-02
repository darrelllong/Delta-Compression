# delta-compression

Differential compression in Rust. Given a reference and a version of it,
the crate computes a delta: commands that copy ranges of the reference and
add the bytes the reference lacks. The version is reconstructed from the
reference and the delta. A delta can also be converted so that the version
is reconstructed in place, in the buffer that holds the reference.

The algorithms are from two papers:

- M. Ajtai, R. Burns, R. Fagin, D.D.E. Long and L. Stockmeyer, "Compactly
  Encoding Unstructured Inputs with Differential Compression", JACM 49(3),
  2002.
- R.C. Burns, D.D.E. Long and L. Stockmeyer, "In-Place Reconstruction of
  Version Differences", IEEE TKDE 15(4), 2003.

There are three differencing algorithms:

- `diff_onepass` scans both inputs once, in linear time and constant
  space. It is the one to use unless blocks have moved.
- `diff_correcting` indexes the reference and then scans the version, in
  about linear time. It finds blocks that have been rearranged.
- `diff_greedy` finds the smallest delta, in time proportional to the
  product of the input lengths. It is for small inputs.

## Library

The library is named `delta`.

```rust
use delta::{apply_delta, diff, Algorithm, DiffOptions};

let reference = b"the quick brown fox jumps over the lazy dog";
let version = b"the quick brown cat jumps over the lazy dog";
let opts = DiffOptions { p: 4, ..DiffOptions::default() };
let commands = diff(Algorithm::Onepass, reference, version, &opts);
assert_eq!(apply_delta(reference, &commands), version);
```

`place_commands` and `encode_delta_large` turn the commands into a delta
file; `decode_delta` and `apply_placed_to` reverse that. `make_inplace`
produces commands for `apply_placed_inplace_to`.

## Command

```sh
cargo install delta-compression
delta encode onepass old.bin new.bin patch.delta
delta decode old.bin patch.delta recovered.bin
delta info patch.delta
delta encode correcting old.bin new.bin patch.delta --inplace
```

Implementations in C, C++, Go, Java and Python, which read and write the
same delta format, are in the
[repository](https://github.com/darrelllong/delta-compression).

## Licence

BSD 2-Clause. See [`LICENSE`](LICENSE).
