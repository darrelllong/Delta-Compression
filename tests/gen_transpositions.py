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

import array
import mmap
import os
import random
import sys

# Block counts above this threshold use the memory-efficient path that
# avoids holding all block data in memory at once.
_LARGE_N = 5_000_000


def _gen_sizes(rng, n, lo, hi):
    """Return an array.array('I') of n random block sizes in [lo, hi]."""
    return array.array('I', (rng.randint(lo, hi) for _ in range(n)))


def _gen_perm(rng, n, k):
    """
    Return a permutation of range(n) with approximately k elements displaced.

    For small n: uses random.sample + shuffle (exact, original algorithm).
    For large n: uses k random transpositions scattered throughout the array.
    """
    if n <= _LARGE_N:
        perm = list(range(n))
        if k >= 2:
            chosen = rng.sample(range(n), k)
            values = [perm[i] for i in chosen]
            rng.shuffle(values)
            for i, v in zip(chosen, values):
                perm[i] = v
    else:
        perm = array.array('I', range(n))
        if k >= 2:
            # k random transpositions distributed throughout the array;
            # yields approximately k displaced elements for k << n.
            for _ in range(k):
                i = rng.randrange(n)
                j = rng.randrange(n)
                if i != j:
                    perm[i], perm[j] = perm[j], perm[i]
    return perm


def _write_small(sizes, perm, ref_path, ver_path, rng):
    """Write ref and ver files for small n (all block data fits in memory)."""
    blocks = []
    for sz in sizes:
        blocks.append(bytes(rng.getrandbits(8) for _ in range(sz)))
    n = len(perm)
    ref_data = b"".join(blocks)
    ver_data = b"".join(blocks[perm[i]] for i in range(n))
    with open(ref_path, "wb") as f:
        f.write(ref_data)
    with open(ver_path, "wb") as f:
        f.write(ver_data)


def _write_large(sizes, perm, ref_path, ver_path):
    """
    Write ref and ver files for large n using chunked I/O and mmap.

    Reference file content is generated with os.urandom (fast).
    Version file is written by reading reference blocks in permuted order
    via mmap, which lets the OS page cache absorb random-access patterns.
    """
    n = len(perm)

    # Cumulative byte offsets for each block in the reference file.
    # Stored as uint64 in a bytearray to avoid per-element Python object overhead.
    off_buf = bytearray(8 * (n + 1))
    offsets = memoryview(off_buf).cast('Q')
    for i in range(n):
        offsets[i + 1] = offsets[i] + sizes[i]
    total = int(offsets[n])

    # Write reference file in ~8 MB chunks of os.urandom output.
    CHUNK_BLOCKS = 50_000
    with open(ref_path, 'wb') as f:
        for start in range(0, n, CHUNK_BLOCKS):
            end = min(start + CHUNK_BLOCKS, n)
            chunk_bytes = int(offsets[end]) - int(offsets[start])
            f.write(os.urandom(chunk_bytes))

    # Write version file by reading reference in permuted block order.
    FLUSH_BYTES = 64 * 1024 * 1024  # flush every 64 MB
    with open(ref_path, 'rb') as ref_f, open(ver_path, 'wb') as ver_f:
        mm = mmap.mmap(ref_f.fileno(), 0, access=mmap.ACCESS_READ)
        buf = []
        buf_sz = 0
        for i in range(n):
            j   = int(perm[i])
            off = int(offsets[j])
            sz  = int(sizes[j])
            buf.append(mm[off: off + sz])
            buf_sz += sz
            if buf_sz >= FLUSH_BYTES:
                ver_f.write(b''.join(buf))
                buf = []
                buf_sz = 0
        if buf:
            ver_f.write(b''.join(buf))
        mm.close()

    return total


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

    os.makedirs(os.path.dirname(os.path.abspath(ref_path)), exist_ok=True)

    rng  = random.Random(42)
    lo   = max(1, mean_size // 2)
    hi   = mean_size * 3 // 2
    k    = round(n * perm_pct / 100)

    sizes = _gen_sizes(rng, n, lo, hi)
    perm  = _gen_perm(rng, n, k)

    if n <= _LARGE_N:
        _write_small(sizes, perm, ref_path, ver_path, rng)
        total = sum(sizes)
        displaced = sum(1 for i in range(n) if perm[i] != i)
    else:
        total = _write_large(sizes, perm, ref_path, ver_path)
        displaced = k  # approximate for large n

    print(f"blocks:     {n}")
    print(f"mean size:  {mean_size} bytes")
    print(f"perm:       {perm_pct:.0f}%  ({displaced}/{n} blocks displaced)")
    print(f"ref:        {ref_path}  ({total:,} bytes)")
    print(f"ver:        {ver_path}  ({total:,} bytes)")


if __name__ == "__main__":
    main()
