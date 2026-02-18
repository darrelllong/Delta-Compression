#!/usr/bin/env python3
"""
Generate a reference/version file pair with controlled block transpositions.

Arguments:
  num_blocks       Number of blocks
  mean_block_size  Mean block size in bytes
  perm_pct         Degree of permutation, 0–100 (percent of blocks displaced)
  [ref_path]       Reference output file  (default: ref.bin)
  [ver_path]       Version output file    (default: ver.bin)

Usage:
  python gen_transpositions.py 64 4096 50
  python gen_transpositions.py 100 1024 75 old.bin new.bin
"""

import random
import sys


def main():
    if len(sys.argv) < 4:
        print(__doc__.strip(), file=sys.stderr)
        sys.exit(1)

    n         = int(sys.argv[1])
    mean_size = int(sys.argv[2])
    perm_pct  = float(sys.argv[3])
    ref_path  = sys.argv[4] if len(sys.argv) > 4 else "ref.bin"
    ver_path  = sys.argv[5] if len(sys.argv) > 5 else "ver.bin"

    if not (0.0 <= perm_pct <= 100.0):
        sys.exit("perm_pct must be between 0 and 100")

    rng = random.Random(42)

    # Generate n blocks of distinct random bytes.  Block size varies uniformly
    # in [mean/2, 3*mean/2] to avoid alignment artifacts.
    blocks = []
    for _ in range(n):
        size = rng.randint(max(1, mean_size // 2), mean_size * 3 // 2)
        blocks.append(bytes(rng.getrandbits(8) for _ in range(size)))

    # Build a permutation with approximately perm_pct% of blocks displaced.
    # Select k = round(n * perm_pct/100) positions, then shuffle them.
    # A small number of selected positions may land back on their original
    # index (fixed points), so actual displaced count may be slightly < k.
    perm = list(range(n))
    k = round(n * perm_pct / 100)
    if k >= 2:
        chosen = rng.sample(range(n), k)
        values = [perm[i] for i in chosen]
        rng.shuffle(values)
        for i, v in zip(chosen, values):
            perm[i] = v

    ref_data = b"".join(blocks)
    ver_data = b"".join(blocks[perm[i]] for i in range(n))

    with open(ref_path, "wb") as f:
        f.write(ref_data)
    with open(ver_path, "wb") as f:
        f.write(ver_data)

    displaced = sum(1 for i in range(n) if perm[i] != i)
    print(f"blocks:     {n}")
    print(f"mean size:  {mean_size} bytes")
    print(f"perm:       {perm_pct:.0f}%  ({displaced}/{n} blocks displaced)")
    print(f"ref:        {ref_path}  ({len(ref_data):,} bytes)")
    print(f"ver:        {ver_path}  ({len(ver_data):,} bytes)")


if __name__ == "__main__":
    main()
