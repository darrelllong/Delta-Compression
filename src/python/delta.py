#!/usr/bin/env python3
"""
Differential Compression

Implementation of the algorithms from:
  "Compactly Encoding Unstructured Inputs with Differential Compression"
  M. Ajtai, R. Burns, R. Fagin, D.D.E. Long, and L. Stockmeyer
  Journal of the ACM, Vol. 49, No. 3, May 2002, pp. 318-367.

Algorithms implemented:
  - Greedy     (Section 3, Figure 2):  Optimal compression, O(n^2) time, O(n) space
  - One-Pass   (Section 4, Figure 3):  O(n) time, O(1) space
  - Correcting 1.5-Pass (Section 7, Figure 8): Near-optimal, linear time in practice

Also implements:
  - Unified binary delta format with explicit command placement
  - In-place reconstruction (Burns, Long, Stockmeyer, IEEE TKDE 2003)
  - Delta reconstruction (apply delta to reference to recover version)

Usage:
  python delta.py encode  <algorithm> <reference> <version> <delta>
  python delta.py decode  <reference> <delta> <output>
  python delta.py info    <delta>
"""

import argparse
import heapq
import mmap
import os
import struct
import sys
import time
from collections import defaultdict, deque
from contextlib import contextmanager
from dataclasses import dataclass
from random import randrange as _randrange
from typing import List, Union


# ============================================================================
# Delta Commands (Section 2.1.1)
# ============================================================================

@dataclass
class CopyCmd:
    """Copy R[offset : offset+length] to the output."""
    offset: int
    length: int

    def __repr__(self):
        return f"COPY(off={self.offset}, len={self.length})"


@dataclass
class AddCmd:
    """Append literal bytes to the output."""
    data: bytes

    def __repr__(self):
        if len(self.data) <= 20:
            return f"ADD({self.data!r})"
        return f"ADD(len={len(self.data)})"


Command = Union[CopyCmd, AddCmd]


# ============================================================================
# Placed Commands — ready for encoding and application
# ============================================================================

@dataclass
class PlacedCopy:
    """Copy with explicit source and destination offsets."""
    src: int
    dst: int
    length: int

    def __repr__(self):
        return f"COPY(src={self.src}, dst={self.dst}, len={self.length})"


@dataclass
class PlacedAdd:
    """Add literal bytes at an explicit destination offset."""
    dst: int
    data: bytes

    def __repr__(self):
        if len(self.data) <= 20:
            return f"ADD(dst={self.dst}, {self.data!r})"
        return f"ADD(dst={self.dst}, len={len(self.data)})"


PlacedCommand = Union[PlacedCopy, PlacedAdd]


# ============================================================================
# Karp-Rabin Rolling Hash (Karp & Rabin 1987; Section 2.1.3)
#
# We use a polynomial hash with a large Mersenne prime (2^61 - 1) to make
# collisions astronomically unlikely (~1 in 2^61 per comparison).  The full
# 61-bit fingerprint is used for collision-free seed comparison; a separate
# modular reduction maps fingerprints into the fixed-size hash table.
#
#   F(X_r) = (x_r * b^{p-1} + x_{r+1} * b^{p-2} + ... + x_{r+p-1}) mod Q  [Eq. 1]
#   F(X_{r+1}) = ((F(X_r) - x_r * b^{p-1}) * b + x_{r+p}) mod Q           [Eq. 2]
#
# Parameters (Section 2.1.3):
#   p  = seed length (default 16 bytes, per Section 2.1.3 recommendation)
#   Q  = 2^61 - 1 (Mersenne prime, for fingerprint computation)
#   q  = TABLE_SIZE = hash table capacity (separate from Q);
#        for correcting, q should be >= 2|R|/p (Section 8.1, p. 347)
#   b  = 263 (small prime base, better mixing than 256)
# ============================================================================

SEED_LEN = 16
TABLE_SIZE = 1048573  # hash table capacity (largest prime < 2^20)
                      # Section 8: correcting uses checkpointing to fit any |R|
HASH_BASE = 263      # small prime, avoids b=256 which makes low bits depend only on last byte
HASH_MOD = (1 << 61) - 1  # Mersenne prime 2^61-1: ~2.3 * 10^18
DELTA_BUF_CAP = 256       # lookback buffer capacity for correcting algorithm


@dataclass
class DiffOptions:
    """Options for differencing algorithms."""
    p: int = SEED_LEN
    q: int = TABLE_SIZE
    buf_cap: int = DELTA_BUF_CAP
    verbose: bool = False
    min_copy: int = 0

# ── Primality testing ─────────────────────────────────────────────────────


def _get_d_r(n: int) -> tuple:
    """Factor n into d * 2^r, returning (d, r)."""
    r = 0
    while n % 2 == 0:
        n //= 2
        r += 1
    return (n, r)


def _witness(a: int, n: int) -> bool:
    """The witness loop of the Miller-Rabin probabilistic primality test.

    Returns True if a is a witness to the compositeness of n (i.e., n is
    definitely composite).  Returns False if a is a "liar" — n may be prime.
    """
    d, r = _get_d_r(n - 1)
    x = pow(a, d, n)
    for _ in range(r):
        y = pow(x, 2, n)
        if y == 1 and x != 1 and x != n - 1:
            return True
        x = y
    return x != 1


def _is_prime(n: int, k: int = 100) -> bool:
    """Miller-Rabin probabilistic primality test with confidence k.

    Pr[false positive] <= 4^{-k}.  With the default k = 100, the
    probability of a composite being reported as prime is < 10^{-60}.
    """
    if n < 2 or (n != 2 and n % 2 == 0):
        return False
    if n == 2 or n == 3:
        return True
    for _ in range(k):
        a = _randrange(2, n - 1)
        if _witness(a, n):
            return False
    return True


def _next_prime(n: int) -> int:
    """Smallest prime >= n.

    Searches odd candidates upward from n.  By the prime number theorem,
    the expected gap is O(log n), so this terminates quickly.
    """
    if n <= 2:
        return 2
    if n % 2 == 0:
        n += 1
    while not _is_prime(n):
        n += 2
    return n


# Precompute b^{p-1} mod Q for each seed length on first use
_bp_cache: dict = {}


def _get_bp(p: int) -> int:
    """Return HASH_BASE^{p-1} mod HASH_MOD, cached."""
    if p not in _bp_cache:
        _bp_cache[p] = pow(HASH_BASE, p - 1, HASH_MOD)
    return _bp_cache[p]


def _fingerprint(data: bytes, offset: int, p: int) -> int:
    """Compute 61-bit Karp-Rabin fingerprint of data[offset:offset+p].

    Implements Eq. (1) from Section 2.1.3:
      F(X_r) = (x_r * b^{p-1} + ... + x_{r+p-1}) mod Q
    """
    h = 0
    for i in range(p):
        h = (h * HASH_BASE + data[offset + i]) % HASH_MOD
    return h


class _RollingHash:
    """Rolling Karp-Rabin hash for O(1) incremental fingerprint updates (Eq. 2)."""
    __slots__ = ('value', '_bp', '_p')

    def __init__(self, data: bytes, offset: int, p: int):
        self._bp = _get_bp(p)
        self._p = p
        self.value = _fingerprint(data, offset, p)

    def roll(self, old_byte: int, new_byte: int):
        """Slide window: remove old_byte from left, add new_byte to right."""
        v = (self.value - old_byte * self._bp) % HASH_MOD
        self.value = (v * HASH_BASE + new_byte) % HASH_MOD


def _fp_to_index(fp: int, table_size: int) -> int:
    """Map a full fingerprint to a hash table index (F mod q, Section 2.1.3)."""
    return fp % table_size


def _print_command_stats(commands: List[Command]) -> None:
    """Print shared verbose statistics for diff algorithm output."""
    copy_lens = [c.length for c in commands if isinstance(c, CopyCmd)]
    total_copy = sum(copy_lens)
    total_add = sum(len(c.data) for c in commands if isinstance(c, AddCmd))
    num_copies = len(copy_lens)
    num_adds = sum(1 for c in commands if isinstance(c, AddCmd))
    total_out = total_copy + total_add
    copy_pct = total_copy / total_out * 100 if total_out else 0
    print(f"  result: {num_copies} copies ({total_copy} bytes), "
          f"{num_adds} adds ({total_add} bytes)\n"
          f"  result: copy coverage {copy_pct:.1f}%, output {total_out} bytes",
          file=sys.stderr)
    if copy_lens:
        copy_lens.sort()
        mean = total_copy / len(copy_lens)
        median = copy_lens[len(copy_lens) // 2]
        print(f"  copies: {len(copy_lens)} regions, min={copy_lens[0]} "
              f"max={copy_lens[-1]} mean={mean:.1f} median={median} bytes",
              file=sys.stderr)


# ============================================================================
# Greedy Algorithm (Section 3.1, Figure 2)
#
# Finds an optimal delta encoding under the simple cost measure
# (optimality proof: Section 3.3, Theorem 1).
# Uses a chained hash table storing ALL offsets in R per footprint
# (Section 3.1: hash table stores a chain of all matching offsets).
# Time: O(|V| * |R|) worst case.  Space: O(|R|).
# ============================================================================

def diff_greedy(R: bytes, V: bytes,
                p: int = SEED_LEN, q: int = TABLE_SIZE,
                verbose: bool = False,
                min_copy: int = 0,
                opts: 'DiffOptions' = None) -> List[Command]:
    """Greedy algorithm (Section 3.1, Figure 2).

    Uses a chained hash table (Python dict) that stores ALL offsets
    per footprint (Section 3.1).
    """
    if opts is not None:
        p, q, verbose, min_copy = opts.p, opts.q, opts.verbose, opts.min_copy
    commands: List[Command] = []
    if not V:
        return commands
    # --min-copy raises the seed length so we never fingerprint at a
    # granularity finer than the minimum copy threshold.
    if min_copy > 0 and min_copy > p:
        p = min_copy

    # Step (1): build hash table mapping fingerprints to R offsets
    H_R: dict = defaultdict(list)
    num_seeds = max(0, len(R) - p + 1)
    if num_seeds > 0:
        rh = _RollingHash(R, 0, p)
        H_R[rh.value].append(0)
        for a in range(1, num_seeds):
            rh.roll(R[a - 1], R[a + p - 1])
            H_R[rh.value].append(a)

    if verbose:
        print(f"greedy: |R|={len(R):,}, |V|={len(V):,}, seed_len={p}",
              file=sys.stderr)

    # Step (2): initialize scan pointers
    v_c = 0
    v_s = 0

    # Rolling hash for O(1) per-position V fingerprinting.
    rh_v = _RollingHash(V, 0, p) if len(V) >= p else None
    rh_v_pos = 0

    while True:
        # Step (3): stop when no full seed remains in V
        if v_c + p > len(V):
            break

        if v_c == rh_v_pos:
            fp_v = rh_v.value
        elif v_c == rh_v_pos + 1:
            rh_v.roll(V[v_c - 1], V[v_c + p - 1])
            rh_v_pos = v_c
            fp_v = rh_v.value
        else:
            rh_v = _RollingHash(V, v_c, p)
            rh_v_pos = v_c
            fp_v = rh_v.value

        # Steps (4)+(5): find the longest matching substring among all
        # reference offsets that share this footprint.
        best_rm = -1
        best_len = 0

        for r_cand in H_R.get(fp_v, []):
            # Verify the seed actually matches (footprints can collide)
            if R[r_cand:r_cand + p] != V[v_c:v_c + p]:
                continue
            ml = p
            while (v_c + ml < len(V) and r_cand + ml < len(R)
                   and V[v_c + ml] == R[r_cand + ml]):
                ml += 1
            if ml > best_len:
                best_len = ml
                best_rm = r_cand

        if best_len < p:
            v_c += 1
            continue

        # Step (6): emit ADD for unmatched gap, then COPY for match
        if v_s < v_c:
            commands.append(AddCmd(data=V[v_s:v_c]))
        commands.append(CopyCmd(offset=best_rm, length=best_len))
        v_s = v_c + best_len

        # Step (7): advance past the matched region
        v_c = v_c + best_len

    # Step (8): emit trailing ADD for any unmatched suffix of V
    if v_s < len(V):
        commands.append(AddCmd(data=V[v_s:]))

    if verbose:
        _print_command_stats(commands)

    return commands


# ============================================================================
# One-Pass Algorithm (Section 4.1, Figure 3)
#
# Scans R and V concurrently with two hash tables (one per string).
# Each table stores at most one offset per footprint (retain-existing
# policy: first entry wins, later collisions are discarded).
# Hash tables are logically flushed after each match via version counter
# (next-match policy).
# Time: O(np + q), space: O(q) — both constant for fixed p, q (Section 4.2).
# Suboptimal on transpositions: cannot match blocks that appear in
# different order between R and V (Section 4.3).
# ============================================================================

def diff_onepass(R: bytes, V: bytes,
                 p: int = SEED_LEN, q: int = TABLE_SIZE,
                 verbose: bool = False,
                 min_copy: int = 0,
                 opts: 'DiffOptions' = None) -> List[Command]:
    """One-pass algorithm (Section 4.1, Figure 3).

    The hash table is auto-sized to next_prime(max(q, num_seeds // p)) so
    that large inputs get one slot per seed-length chunk of R.  TABLE_SIZE
    acts as a floor for small files.
    """
    if opts is not None:
        p, q, verbose, min_copy = opts.p, opts.q, opts.verbose, opts.min_copy
    commands: List[Command] = []
    if not V:
        return commands
    # --min-copy raises the seed length so we never fingerprint at a
    # granularity finer than the minimum copy threshold.
    if min_copy > 0 and min_copy > p:
        p = min_copy

    # Auto-size hash table: one slot per p-byte chunk of R (floor = q).
    num_seeds = max(0, len(R) - p + 1)
    q = _next_prime(max(q, num_seeds // p))

    if verbose:
        print(f"onepass: q={q:,}, |R|={len(R):,}, "
              f"|V|={len(V):,}, seed_len={p}",
              file=sys.stderr)

    # Step (1): lookup structures with version-based logical flushing.
    # Each entry stores (fingerprint, offset, version).
    H_V = [None] * q
    H_R = [None] * q
    ver = 0

    def hv_get(fp):
        idx = fp % q
        e = H_V[idx]
        if e is not None and e[2] == ver and e[0] == fp:
            return e[1]
        return None

    def hr_get(fp):
        idx = fp % q
        e = H_R[idx]
        if e is not None and e[2] == ver and e[0] == fp:
            return e[1]
        return None

    def hv_put(fp, off):
        idx = fp % q
        e = H_V[idx]
        if e is None or e[2] != ver:
            H_V[idx] = (fp, off, ver)

    def hr_put(fp, off):
        idx = fp % q
        e = H_R[idx]
        if e is None or e[2] != ver:
            H_R[idx] = (fp, off, ver)

    # Step (2): initialize scan pointers for concurrent R/V traversal
    r_c = 0
    v_c = 0
    v_s = 0
    dbg_positions = 0
    dbg_lookups = 0
    dbg_matches = 0

    # Rolling hashes for O(1) per-position fingerprinting.
    rh_v = _RollingHash(V, 0, p) if len(V) >= p else None
    rh_r = _RollingHash(R, 0, p) if len(R) >= p else None
    rh_v_pos = 0
    rh_r_pos = 0

    while True:
        # Step (3): check if seeds remain in V and/or R
        can_v = v_c + p <= len(V)
        can_r = r_c + p <= len(R)

        if not can_v and not can_r:
            break
        dbg_positions += 1

        fp_v = None
        if can_v and rh_v is not None:
            if v_c == rh_v_pos:
                fp_v = rh_v.value
            elif v_c == rh_v_pos + 1:
                rh_v.roll(V[v_c - 1], V[v_c + p - 1])
                rh_v_pos = v_c
                fp_v = rh_v.value
            else:
                rh_v = _RollingHash(V, v_c, p)
                rh_v_pos = v_c
                fp_v = rh_v.value

        fp_r = None
        if can_r and rh_r is not None:
            if r_c == rh_r_pos:
                fp_r = rh_r.value
            elif r_c == rh_r_pos + 1:
                rh_r.roll(R[r_c - 1], R[r_c + p - 1])
                rh_r_pos = r_c
                fp_r = rh_r.value
            else:
                rh_r = _RollingHash(R, r_c, p)
                rh_r_pos = r_c
                fp_r = rh_r.value

        # Step (4a): store offsets (retain-existing policy)
        if fp_v is not None:
            hv_put(fp_v, v_c)
        if fp_r is not None:
            hr_put(fp_r, r_c)

        # Step (4b): look for a matching seed in the other table
        match_found = False
        r_m = v_m = 0

        if fp_r is not None:
            v_cand = hv_get(fp_r)
            if v_cand is not None:
                dbg_lookups += 1
                if R[r_c:r_c + p] == V[v_cand:v_cand + p]:
                    r_m, v_m = r_c, v_cand
                    match_found = True

        if not match_found and fp_v is not None:
            r_cand = hr_get(fp_v)
            if r_cand is not None:
                dbg_lookups += 1
                if V[v_c:v_c + p] == R[r_cand:r_cand + p]:
                    v_m, r_m = v_c, r_cand
                    match_found = True

        if not match_found:
            v_c += 1
            r_c += 1
            continue
        dbg_matches += 1

        # Step (5): extend match forward past the seed
        ml = 0
        while (v_m + ml < len(V) and r_m + ml < len(R)
               and V[v_m + ml] == R[r_m + ml]):
            ml += 1

        # Filter: skip matches shorter than --min-copy
        if ml < p:
            v_c += 1
            r_c += 1
            continue

        # Step (6): emit ADD for unmatched gap, then COPY for match
        if v_s < v_m:
            commands.append(AddCmd(data=V[v_s:v_m]))
        commands.append(CopyCmd(offset=r_m, length=ml))
        v_s = v_m + ml

        # Step (7): advance past match and flush tables (next-match policy)
        v_c = v_m + ml
        r_c = r_m + ml
        ver += 1

    # Step (8): emit trailing ADD for any unmatched suffix of V
    if v_s < len(V):
        commands.append(AddCmd(data=V[v_s:]))

    if verbose:
        hit_pct = dbg_matches / dbg_lookups * 100 if dbg_lookups else 0
        print(f"  scan: {dbg_positions:,} positions, {dbg_lookups:,} lookups, "
              f"{dbg_matches:,} matches (flushes)\n"
              f"  scan: hit rate {hit_pct:.1f}% (of lookups)",
              file=sys.stderr)
        _print_command_stats(commands)

    return commands


# ============================================================================
# Correcting 1.5-Pass Algorithm (Section 7, Figure 8)
#
# Pass 1: index the reference string using first-found policy (same
#   collision policy as onepass — first entry wins).  The key difference
#   is that tables are never flushed: all of R is indexed before scanning V,
#   whereas onepass flushes both tables after each match.
# Pass 2: scan V, extend matches both forwards AND backwards from the seed,
#   and use tail correction (Section 5.1) to fix suboptimal earlier
#   encodings via an encoding lookback buffer (Section 5.2).
# Time: linear in practice.  Space: O(q + buffer_capacity).
#
# Checkpointing (Section 8, pp. 347-349): the hash table has |C| = q
#   entries (the user's memory budget).  The footprint modulus |F| ~ 2|R|
#   controls which seeds enter the table: only seeds whose footprint
#   satisfies f ≡ k (mod m) where m = ceil(|F|/|C|) are stored or
#   looked up.  This gives ~|C|/2 occupied slots regardless of |R|.
#   Backward extension (Section 5.1) recovers match starts that fall
#   between checkpoint positions (Section 8.2, p. 349).
# ============================================================================

@dataclass
class _BufEntry:
    """Internal buffer entry tracking which region of V a command encodes."""
    v_start: int
    v_end: int
    cmd: Command
    dummy: bool = False


def diff_correcting(R: bytes, V: bytes,
                    p: int = SEED_LEN, q: int = TABLE_SIZE,
                    buf_cap: int = DELTA_BUF_CAP,
                    verbose: bool = False,
                    min_copy: int = 0,
                    opts: 'DiffOptions' = None) -> List[Command]:
    """Correcting 1.5-pass algorithm (Section 7, Figure 8) with
    fingerprint-based checkpointing (Section 8).

    The hash table is auto-sized to max(q, 2 * num_seeds // p) so that
    checkpoint spacing m ≈ p, giving near-seed-granularity sampling.
    TABLE_SIZE acts as a floor for small files.

    |C| = q (hash table capacity, auto-sized from input).
    |F| = next_prime(2 * num_R_seeds) (footprint universe size, Section 8.1,
          p. 347-348: "|F| ≈ 2L" so that ~|C|/2 checkpoint seeds from R
          occupy the table).
    m  = ceil(|F| / |C|) (checkpoint spacing, p. 348).
    k  = checkpoint class (Eq. 3, p. 348).

    A seed with fingerprint fp passes the checkpoint test iff
        (fp % |F|) % m == k.
    Its table index is (fp % |F|) // m  (p. 348: "i = floor(f/m)").

    Step 1 (R pass): compute fingerprint at every R position, apply
    checkpoint filter, store first-found offset per slot.
    Steps 3-4 (V scan): compute fingerprint at every V position, apply
    checkpoint filter, look up matching R offset.
    Step 5: extend match both forwards and backwards (Section 7, p. 345).
    Step 6: encode with tail correction via lookback buffer (Section 5.1).
    Backward extension (Section 8.2, p. 349) recovers true match starts
    that fall between checkpoint positions.
    """
    if opts is not None:
        p, q, buf_cap, verbose, min_copy = opts.p, opts.q, opts.buf_cap, opts.verbose, opts.min_copy
    commands: List[Command] = []
    if not V:
        return commands
    # --min-copy raises the seed length so we never fingerprint at a
    # granularity finer than the minimum copy threshold.
    if min_copy > 0 and min_copy > p:
        p = min_copy

    # ── Checkpointing parameters (Section 8.1, pp. 347-348) ──────────
    num_seeds = max(0, len(R) - p + 1)
    # Auto-size: 2x factor for correcting's |F|=2L convention.
    q = _next_prime(max(q, 2 * num_seeds // p))
    C = q                                                # |C|
    F = _next_prime(2 * num_seeds) if num_seeds > 0 else 1  # |F|
    m = max(1, -(-F // C))                               # ceil(|F| / |C|)
    # Biased k (p. 348): pick a V offset, use its footprint mod m.
    # This biases checkpoints toward footprints common in V.
    if len(V) >= p:
        k = _fingerprint(V, len(V) // 2, p) % F % m
    else:
        k = 0

    if verbose:
        expected = num_seeds // m if m > 0 else 0
        print(f"correcting: |C|={C} |F|={F} m={m} k={k}\n"
              f"  checkpoint gap={m} bytes, "
              f"expected fill ~{expected} "
              f"(~{expected * 100 // C if C else 0}% table occupancy)\n"
              f"  table memory ~{C * 24 // 1048576} MB",
              file=sys.stderr)

    # Debug counters (verbose mode only)
    dbg_build_passed = 0
    dbg_build_stored = 0
    dbg_build_skip_coll = 0
    dbg_scan_checkpoints = 0
    dbg_scan_match = 0
    dbg_scan_fp_miss = 0
    dbg_scan_byte_miss = 0

    # Step (1): Build hash table for R (first-found policy)
    # Scan every R position, apply checkpoint test (Eq. 3), store at
    # index i = floor(f / m) where f = fp % |F|.  (Section 8.2, p. 349)
    H_R: list = [None] * C
    rh_build = _RollingHash(R, 0, p) if num_seeds > 0 else None
    for a in range(num_seeds):
        if a == 0:
            fp = rh_build.value
        else:
            rh_build.roll(R[a - 1], R[a + p - 1])
            fp = rh_build.value
        f = fp % F
        if f % m != k:
            continue                     # not a checkpoint seed
        dbg_build_passed += 1
        i = f // m
        if i >= C:
            continue                     # safety: rounding can overshoot
        if H_R[i] is None:
            H_R[i] = (fp, a)            # first-found (Section 7 Step 1)
            dbg_build_stored += 1
        else:
            dbg_build_skip_coll += 1

    if verbose:
        passed_pct = dbg_build_passed / num_seeds * 100 if num_seeds else 0
        occ_pct = dbg_build_stored / C * 100 if C else 0
        print(f"  build: {num_seeds} seeds, {dbg_build_passed} passed "
              f"checkpoint ({passed_pct:.2f}%), "
              f"{dbg_build_stored} stored, {dbg_build_skip_coll} collisions\n"
              f"  build: table occupancy {dbg_build_stored}/{C} ({occ_pct:.1f}%)",
              file=sys.stderr)

    # ── Encoding lookback buffer (Section 5.2) ────────────────────────
    buf: List[_BufEntry] = []

    def flush_all():
        for e in buf:
            if not e.dummy:
                commands.append(e.cmd)
        buf.clear()

    def buf_emit(v_start, v_end, cmd):
        if len(buf) >= buf_cap:
            oldest = buf.pop(0)
            if not oldest.dummy:
                commands.append(oldest.cmd)
        buf.append(_BufEntry(v_start, v_end, cmd))

    # Step (2): initialize scan pointers
    v_c = 0
    v_s = 0

    # Rolling hash for O(1) per-position V fingerprinting.
    rh_v_scan = _RollingHash(V, 0, p) if len(V) >= p else None
    rh_v_pos = 0

    while True:
        # Step (3): if no more seeds in V, finish up.
        if v_c + p > len(V):
            break

        # Step (4): generate footprint at v_c, apply checkpoint test.
        if v_c == rh_v_pos:
            fp_v = rh_v_scan.value
        elif v_c == rh_v_pos + 1:
            rh_v_scan.roll(V[v_c - 1], V[v_c + p - 1])
            rh_v_pos = v_c
            fp_v = rh_v_scan.value
        else:
            rh_v_scan = _RollingHash(V, v_c, p)
            rh_v_pos = v_c
            fp_v = rh_v_scan.value
        f_v = fp_v % F
        if f_v % m != k:
            v_c += 1
            continue                     # not a checkpoint — skip

        # Checkpoint passed — look up H_R at index i = floor(f/m).
        dbg_scan_checkpoints += 1
        i = f_v // m
        if i >= C:
            v_c += 1
            continue                     # safety: rounding can overshoot
        entry = H_R[i]
        if entry is None:
            v_c += 1
            continue                     # no R entry at this slot

        stored_fp, r_offset = entry
        if stored_fp != fp_v:
            # Different full fingerprint mapped to same slot (fp % |F|
            # differ but floor(f/m) collides).
            dbg_scan_fp_miss += 1
            v_c += 1
            continue

        # Verify seeds are byte-identical (Section 7, Step 4).
        if R[r_offset:r_offset + p] != V[v_c:v_c + p]:
            dbg_scan_byte_miss += 1
            v_c += 1
            continue

        dbg_scan_match += 1

        # Step (5): extend match forwards and backwards
        # (Section 7, Step 5; Section 8.2 backward extension, p. 349)
        fwd = p
        while (v_c + fwd < len(V) and r_offset + fwd < len(R)
               and V[v_c + fwd] == R[r_offset + fwd]):
            fwd += 1

        bwd = 0
        while (v_c - bwd - 1 >= 0 and r_offset - bwd - 1 >= 0
               and V[v_c - bwd - 1] == R[r_offset - bwd - 1]):
            bwd += 1

        v_m = v_c - bwd
        r_m = r_offset - bwd
        ml = bwd + fwd
        match_end = v_m + ml

        # Filter: skip matches shorter than --min-copy
        if ml < p:
            v_c += 1
            continue

        # Step (6): encode with correction
        if v_s <= v_m:
            # (6a) match is entirely in unencoded suffix (Section 7)
            if v_s < v_m:
                buf_emit(v_s, v_m, AddCmd(data=V[v_s:v_m]))
            buf_emit(v_m, match_end, CopyCmd(offset=r_m, length=ml))
            v_s = match_end
        else:
            # (6b) v_m < v_s — match extends backward into the encoded
            # prefix of V.  Perform tail correction (Section 5.1, p. 339):
            # integrate commands from the tail of the buffer into the
            # new copy command.
            effective_start = v_s

            while buf:
                tail = buf[-1]
                if tail.dummy:
                    buf.pop()
                    continue

                if tail.v_start >= v_m and tail.v_end <= match_end:
                    # Wholly within new match — absorb
                    effective_start = min(effective_start, tail.v_start)
                    buf.pop()
                    continue

                if tail.v_end > v_m and tail.v_start < v_m:
                    if isinstance(tail.cmd, AddCmd):
                        # Partial add — trim to [v_start, v_m)
                        keep = v_m - tail.v_start
                        if keep > 0:
                            tail.cmd = AddCmd(data=V[tail.v_start:v_m])
                            tail.v_end = v_m
                        else:
                            buf.pop()
                        effective_start = min(effective_start, v_m)
                    # Partial copy — don't reclaim (Section 5.1, p. 339)
                    break

                # No overlap with match
                break

            adj = effective_start - v_m
            new_len = match_end - effective_start
            if new_len > 0:
                buf_emit(effective_start, match_end,
                         CopyCmd(offset=r_m + adj, length=new_len))
            v_s = match_end

        # Step (7): advance past the matched region
        v_c = match_end

    # Step (8): flush buffer and trailing add
    flush_all()
    if v_s < len(V):
        commands.append(AddCmd(data=V[v_s:]))

    if verbose:
        v_seeds = max(0, len(V) - p + 1)
        cp_pct = dbg_scan_checkpoints / v_seeds * 100 if v_seeds else 0
        hit_pct = (dbg_scan_match / dbg_scan_checkpoints * 100
                   if dbg_scan_checkpoints else 0)
        print(f"  scan: {v_seeds} V positions, {dbg_scan_checkpoints} checkpoints "
              f"({cp_pct:.3f}%), {dbg_scan_match} matches\n"
              f"  scan: hit rate {hit_pct:.1f}% (of checkpoints), "
              f"fp collisions {dbg_scan_fp_miss}, "
              f"byte mismatches {dbg_scan_byte_miss}",
              file=sys.stderr)
        _print_command_stats(commands)

    return commands


# ============================================================================
# Placement — convert algorithm output to placed commands
# ============================================================================

def output_size(commands: List[Command]) -> int:
    """Compute the total output size of a delta encoding."""
    return sum(cmd.length if isinstance(cmd, CopyCmd) else len(cmd.data)
               for cmd in commands)


def place_commands(commands: List[Command]) -> List[PlacedCommand]:
    """Assign sequential destination offsets to algorithm output commands."""
    placed = []
    dst = 0
    for cmd in commands:
        if isinstance(cmd, CopyCmd):
            placed.append(PlacedCopy(src=cmd.offset, dst=dst, length=cmd.length))
            dst += cmd.length
        elif isinstance(cmd, AddCmd):
            placed.append(PlacedAdd(dst=dst, data=cmd.data))
            dst += len(cmd.data)
    return placed


# ============================================================================
# Unified Binary Delta Format
#
# Header:
#   Magic:        4 bytes  b'DLT\x01'
#   Flags:        1 byte   (0x00 = standard, 0x01 = in-place)
#   Version size: 4 bytes  uint32 BE
#
# Commands (in execution order):
#   END:  type=0                                     (1 byte)
#   COPY: type=1, src:u32, dst:u32, len:u32         (13 bytes)
#   ADD:  type=2, dst:u32, len:u32, data             (9 + len bytes)
# ============================================================================

DELTA_MAGIC = b'DLT\x01'
DELTA_FLAG_INPLACE = 0x01
DELTA_CMD_END = 0
DELTA_CMD_COPY = 1
DELTA_CMD_ADD = 2
DELTA_HEADER_SIZE = 9   # magic(4) + flags(1) + version_size(4)
DELTA_U32_SIZE = 4
DELTA_COPY_PAYLOAD = 12 # src(4) + dst(4) + len(4)
DELTA_ADD_HEADER = 8    # dst(4) + len(4)


def encode_delta(commands: List[PlacedCommand], *,
                 inplace: bool = False, version_size: int) -> bytes:
    """Encode placed commands to the unified binary delta format."""
    out = bytearray()
    out.extend(DELTA_MAGIC)
    out.append(DELTA_FLAG_INPLACE if inplace else 0)
    out.extend(struct.pack('>I', version_size))

    for cmd in commands:
        if isinstance(cmd, PlacedCopy):
            out.append(DELTA_CMD_COPY)
            out.extend(struct.pack('>III', cmd.src, cmd.dst, cmd.length))
        elif isinstance(cmd, PlacedAdd):
            out.append(DELTA_CMD_ADD)
            out.extend(struct.pack('>II', cmd.dst, len(cmd.data)))
            out.extend(cmd.data)

    out.append(DELTA_CMD_END)
    return bytes(out)


def decode_delta(data: bytes):
    """Decode the unified binary delta format.

    Returns (commands, inplace, version_size).
    """
    if len(data) < DELTA_HEADER_SIZE or data[:len(DELTA_MAGIC)] != DELTA_MAGIC:
        raise ValueError("Not a delta file")

    inplace = bool(data[len(DELTA_MAGIC)] & DELTA_FLAG_INPLACE)
    version_size = struct.unpack_from('>I', data, len(DELTA_MAGIC) + 1)[0]
    pos = DELTA_HEADER_SIZE
    commands: List[PlacedCommand] = []

    while pos < len(data):
        t = data[pos]
        pos += 1
        if t == DELTA_CMD_END:
            break
        elif t == DELTA_CMD_COPY:
            src, dst, length = struct.unpack_from('>III', data, pos)
            pos += DELTA_COPY_PAYLOAD
            commands.append(PlacedCopy(src=src, dst=dst, length=length))
        elif t == DELTA_CMD_ADD:
            dst, length = struct.unpack_from('>II', data, pos)
            pos += DELTA_ADD_HEADER
            commands.append(PlacedAdd(dst=dst, data=data[pos:pos + length]))
            pos += length

    return commands, inplace, version_size


def is_inplace_delta(data: bytes) -> bool:
    """Check if binary data is an in-place delta."""
    return (len(data) >= len(DELTA_MAGIC) + 1
            and data[:len(DELTA_MAGIC)] == DELTA_MAGIC
            and bool(data[len(DELTA_MAGIC)] & DELTA_FLAG_INPLACE))


# ============================================================================
# Reconstruction — apply delta to reference to recover version
# ============================================================================

def apply_placed_to(R, commands: List[PlacedCommand], buf) -> int:
    """Apply placed commands in standard mode: read from R, write to buf.

    Returns bytes written.
    """
    max_written = 0
    for cmd in commands:
        if isinstance(cmd, PlacedCopy):
            buf[cmd.dst:cmd.dst + cmd.length] = R[cmd.src:cmd.src + cmd.length]
            end = cmd.dst + cmd.length
            if end > max_written:
                max_written = end
        elif isinstance(cmd, PlacedAdd):
            buf[cmd.dst:cmd.dst + len(cmd.data)] = cmd.data
            end = cmd.dst + len(cmd.data)
            if end > max_written:
                max_written = end
    return max_written


def apply_placed_inplace_to(commands: List[PlacedCommand], buf) -> None:
    """Execute placed commands in a buffer that serves as both source and destination.

    Slice assignment creates a temporary copy of the RHS, so overlapping
    src/dst within a single copy is handled correctly.
    """
    for cmd in commands:
        if isinstance(cmd, PlacedCopy):
            buf[cmd.dst:cmd.dst + cmd.length] = buf[cmd.src:cmd.src + cmd.length]
        elif isinstance(cmd, PlacedAdd):
            buf[cmd.dst:cmd.dst + len(cmd.data)] = cmd.data


def apply_placed(R, commands: List[PlacedCommand]) -> bytes:
    """Reconstruct version from reference + placed commands (standard mode)."""
    total = sum(cmd.length if isinstance(cmd, PlacedCopy) else len(cmd.data)
                for cmd in commands)
    buf = bytearray(total)
    apply_placed_to(R, commands, buf)
    return bytes(buf)


def apply_placed_inplace(R, commands: List[PlacedCommand],
                         version_size: int) -> bytes:
    """Reconstruct version by applying placed in-place commands."""
    buf = bytearray(max(len(R), version_size))
    buf[:len(R)] = R
    apply_placed_inplace_to(commands, buf)
    return bytes(buf[:version_size])


# ── convenience wrappers (Command → output) ──────────────────────────────

def apply_delta_to(R, commands: List[Command], buf) -> int:
    """Apply algorithm commands, writing into a pre-allocated buffer."""
    pos = 0
    for cmd in commands:
        if isinstance(cmd, AddCmd):
            n = len(cmd.data)
            buf[pos:pos + n] = cmd.data
            pos += n
        elif isinstance(cmd, CopyCmd):
            buf[pos:pos + cmd.length] = R[cmd.offset:cmd.offset + cmd.length]
            pos += cmd.length
    return pos


def apply_delta(R, commands: List[Command]) -> bytes:
    """Reconstruct the version string from reference + algorithm commands."""
    buf = bytearray(output_size(commands))
    apply_delta_to(R, commands, buf)
    return bytes(buf)


def apply_binary(R: bytes, delta: bytes) -> bytes:
    """Reconstruct version from reference + binary delta (auto-detects format)."""
    commands, inplace, version_size = decode_delta(delta)
    if inplace:
        return apply_placed_inplace(R, commands, version_size)
    else:
        return apply_placed(R, commands)


# ============================================================================
# In-Place Reconstruction (Burns, Long, Stockmeyer, IEEE TKDE 2003)
#
# Converts a standard delta encoding into one that can be applied in-place:
# the new version is reconstructed in the same buffer that holds the
# reference, without requiring scratch space.
#
# Algorithm:
#   1. Annotate each command with its write offset in the output
#   2. Build CRWI (Copy-Read/Write-Intersection) digraph on copy commands
#      (Section 4.2) — edge from i to j when i's read interval overlaps
#      j's write interval (i must execute before j)
#   3. Topological sort; break cycles by converting copies to adds
#   4. Output: copies in topological order, then all adds
#
# Cycle-breaking policies (Section 4.3):
#   - constant: pick any vertex when a cycle is detected (O(1) per break)
#   - localmin: pick the minimum-length vertex in the cycle (less compression loss)
# ============================================================================

def _find_cycle(adj, removed, n):
    """Find a cycle in the subgraph of non-removed vertices.

    Follows forward edges from each non-removed vertex until we revisit
    one (cycle found) or reach a dead end (try next start vertex).
    Returns a list of vertex indices forming the cycle, or None.
    """
    for start in range(n):
        if removed[start]:
            continue
        visited = {}
        path = []
        curr = start
        step = 0
        while curr is not None and curr not in visited:
            visited[curr] = step
            path.append(curr)
            step += 1
            next_v = None
            for w in adj[curr]:
                if not removed[w]:
                    next_v = w
                    break
            curr = next_v
        if curr is not None and curr in visited:
            cycle_idx = visited[curr]
            return path[cycle_idx:]
    return None


def make_inplace(R: bytes, commands: List[Command],
                 policy: str = 'localmin') -> List[PlacedCommand]:
    """Convert standard delta commands to in-place executable commands.

    The returned commands can be applied to a buffer initialized with R
    to reconstruct V in-place, without a separate output buffer.

    Args:
        R: Reference data (needed to materialize literal bytes when
           a copy is converted to an add during cycle breaking).
        commands: Standard delta commands (CopyCmd / AddCmd).
        policy: 'localmin' (default, better compression) or 'constant'.

    Returns:
        List of PlacedCopy / PlacedAdd in safe execution order.
    """
    if not commands:
        return []

    # Step 1: compute write offsets for each command
    copy_info = []   # [(index, src, dst, length)]
    add_info = []    # [(dst, data)]
    write_pos = 0

    for cmd in commands:
        if isinstance(cmd, CopyCmd):
            copy_info.append((len(copy_info), cmd.offset, write_pos, cmd.length))
            write_pos += cmd.length
        elif isinstance(cmd, AddCmd):
            add_info.append((write_pos, cmd.data))
            write_pos += len(cmd.data)

    n = len(copy_info)
    if n == 0:
        return [PlacedAdd(dst=d, data=dat) for d, dat in add_info]

    # Step 2: build CRWI digraph
    # Edge i -> j means i's read interval [src_i, src_i+len_i) overlaps
    # j's write interval [dst_j, dst_j+len_j), so i must execute before j.
    adj = [[] for _ in range(n)]
    in_deg = [0] * n

    # O(n log n + E) sweep-line: sort writes by start, then for each read
    # interval binary-search into the sorted writes to find overlaps.
    import bisect
    write_sorted = sorted(range(n), key=lambda j: copy_info[j][2])
    write_starts = [copy_info[j][2] for j in write_sorted]

    for i in range(n):
        si, li = copy_info[i][1], copy_info[i][3]
        read_end = si + li
        # Find first write whose start < read_end
        hi = bisect.bisect_left(write_starts, read_end)
        for k in range(hi):
            j = write_sorted[k]
            if i == j:
                continue
            dj, lj = copy_info[j][2], copy_info[j][3]
            if dj + lj > si:  # write_end > read_start
                adj[i].append(j)
                in_deg[j] += 1

    # Step 3: topological sort with cycle breaking (Kahn's algorithm)
    # Priority queue keyed on copy length — always process the smallest
    # ready copy first, giving a deterministic topological ordering.
    removed = [False] * n
    topo_order = []
    converted = set()

    heap = []  # min-heap of (copy_length, index)
    for i in range(n):
        if in_deg[i] == 0:
            heapq.heappush(heap, (copy_info[i][3], i))
    processed = 0

    while processed < n:
        while heap:
            _, v = heapq.heappop(heap)
            if removed[v]:
                continue
            removed[v] = True
            topo_order.append(v)
            processed += 1
            for w in adj[v]:
                if not removed[w]:
                    in_deg[w] -= 1
                    if in_deg[w] == 0:
                        heapq.heappush(heap, (copy_info[w][3], w))

        if processed >= n:
            break

        # Cycle detected — choose a victim to convert from copy to add
        if policy == 'constant':
            victim = next(i for i in range(n) if not removed[i])
        else:  # localmin
            cycle = _find_cycle(adj, removed, n)
            if cycle:
                victim = min(cycle, key=lambda i: copy_info[i][3])
            else:
                victim = next(i for i in range(n) if not removed[i])

        # Convert victim: materialize its copy data as a literal add
        _, src, dst, length = copy_info[victim]
        add_info.append((dst, bytes(R[src:src + length])))
        converted.add(victim)
        removed[victim] = True
        processed += 1

        for w in adj[victim]:
            if not removed[w]:
                in_deg[w] -= 1
                if in_deg[w] == 0:
                    heapq.heappush(heap, (copy_info[w][3], w))

    # Step 4: assemble result — copies first (in topo order) because they
    # read from the buffer; adds last because they only write literal data
    # and never read, so they can't conflict with any copy's source region.
    result: List[PlacedCommand] = []

    for i in topo_order:
        _, src, dst, length = copy_info[i]
        result.append(PlacedCopy(src=src, dst=dst, length=length))

    for dst, data in add_info:
        result.append(PlacedAdd(dst=dst, data=data))

    return result


# ============================================================================
# Summaries
# ============================================================================

ALGORITHMS = {
    'greedy': diff_greedy,
    'onepass': diff_onepass,
    'correcting': diff_correcting,
}


def delta_summary(commands: List[Command]) -> dict:
    """Return summary statistics for algorithm output."""
    copies = [c for c in commands if isinstance(c, CopyCmd)]
    adds = [c for c in commands if isinstance(c, AddCmd)]
    copy_bytes = sum(c.length for c in copies)
    add_bytes = sum(len(c.data) for c in adds)
    return {
        'num_commands': len(commands),
        'num_copies': len(copies),
        'num_adds': len(adds),
        'copy_bytes': copy_bytes,
        'add_bytes': add_bytes,
        'total_output_bytes': copy_bytes + add_bytes,
    }


def placed_summary(commands: List[PlacedCommand]) -> dict:
    """Return summary statistics for placed commands."""
    copies = [c for c in commands if isinstance(c, PlacedCopy)]
    adds = [c for c in commands if isinstance(c, PlacedAdd)]
    copy_bytes = sum(c.length for c in copies)
    add_bytes = sum(len(c.data) for c in adds)
    return {
        'num_commands': len(commands),
        'num_copies': len(copies),
        'num_adds': len(adds),
        'copy_bytes': copy_bytes,
        'add_bytes': add_bytes,
        'total_output_bytes': copy_bytes + add_bytes,
    }


# ============================================================================
# Memory-mapped file I/O for large files
# ============================================================================

@contextmanager
def mmap_open(path):
    """Memory-map a file for reading.  Yields b'' for empty files."""
    size = os.path.getsize(path)
    if size == 0:
        yield b""
    else:
        with open(path, 'rb') as f:
            mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
            try:
                yield mm
            finally:
                mm.close()


@contextmanager
def mmap_create(path, size):
    """Create a file of `size` bytes and memory-map it for read-write.

    Yields a writable mmap object (or empty bytearray for size=0).
    """
    if size == 0:
        with open(path, 'wb'):
            pass
        yield bytearray()
    else:
        with open(path, 'wb') as f:
            f.truncate(size)
        with open(path, 'r+b') as f:
            mm = mmap.mmap(f.fileno(), size)
            try:
                yield mm
            finally:
                mm.flush()
                mm.close()


# ============================================================================
# CLI
# ============================================================================

def cmd_encode(args):
    algo = ALGORITHMS[args.algorithm]
    with mmap_open(args.reference) as R, mmap_open(args.version) as V:
        t0 = time.time()
        commands = algo(R, V, p=args.seed_len, q=args.table_size,
                        verbose=args.verbose, min_copy=args.min_copy)

        if args.inplace:
            placed = make_inplace(R, commands, policy=args.policy)
        else:
            placed = place_commands(commands)
        elapsed = time.time() - t0

        delta = encode_delta(placed, inplace=args.inplace,
                             version_size=len(V))
        with open(args.delta, 'wb') as f:
            f.write(delta)

        stats = placed_summary(placed)
        ratio = len(delta) / len(V) if V else 0
        if args.inplace:
            print(f"Algorithm:    {args.algorithm} + in-place ({args.policy})")
        else:
            print(f"Algorithm:    {args.algorithm}")
        print(f"Reference:    {args.reference} ({len(R):,} bytes)")
        print(f"Version:      {args.version} ({len(V):,} bytes)")
        print(f"Delta:        {args.delta} ({len(delta):,} bytes)")
        print(f"Compression:  {ratio:.4f} (delta/version)")
        print(f"Commands:     {stats['num_copies']} copies, {stats['num_adds']} adds")
        print(f"Copy bytes:   {stats['copy_bytes']:,}")
        print(f"Add bytes:    {stats['add_bytes']:,}")
        print(f"Time:         {elapsed:.3f}s")


def cmd_decode(args):
    with mmap_open(args.reference) as R:
        with open(args.delta, 'rb') as f:
            delta_bytes = f.read()

        t0 = time.time()
        placed, is_ip, version_size = decode_delta(delta_bytes)

        if is_ip:
            buf_size = max(len(R), version_size)
            with mmap_create(args.output, buf_size) as buf:
                buf[:len(R)] = R[:len(R)]
                apply_placed_inplace_to(placed, buf)
            if version_size < buf_size:
                os.truncate(args.output, version_size)
        else:
            with mmap_create(args.output, version_size) as buf:
                apply_placed_to(R, placed, buf)
        elapsed = time.time() - t0

        fmt = "in-place" if is_ip else "standard"
        print(f"Format:       {fmt}")
        print(f"Reference:    {args.reference} ({len(R):,} bytes)")
        print(f"Delta:        {args.delta} ({len(delta_bytes):,} bytes)")
        print(f"Output:       {args.output} ({version_size:,} bytes)")
        print(f"Time:         {elapsed:.3f}s")


def cmd_info(args):
    with open(args.delta, 'rb') as f:
        delta_bytes = f.read()

    placed, is_ip, version_size = decode_delta(delta_bytes)
    stats = placed_summary(placed)
    fmt = "in-place" if is_ip else "standard"

    print(f"Delta file:   {args.delta} ({len(delta_bytes):,} bytes)")
    print(f"Format:       {fmt}")
    print(f"Version size: {version_size:,} bytes")
    print(f"Commands:     {stats['num_commands']}")
    print(f"  Copies:     {stats['num_copies']} ({stats['copy_bytes']:,} bytes)")
    print(f"  Adds:       {stats['num_adds']} ({stats['add_bytes']:,} bytes)")
    print(f"Output size:  {stats['total_output_bytes']:,} bytes")


def main():
    ap = argparse.ArgumentParser(
        description='Differential compression (Ajtai et al. 2002)')
    sub = ap.add_subparsers(dest='command')

    # encode
    enc = sub.add_parser('encode', help='Compute delta encoding')
    enc.add_argument('algorithm', choices=list(ALGORITHMS))
    enc.add_argument('reference', help='Reference file')
    enc.add_argument('version', help='Version file')
    enc.add_argument('delta', help='Output delta file')
    enc.add_argument('--seed-len', type=int, default=SEED_LEN)
    enc.add_argument('--table-size', type=int, default=TABLE_SIZE)
    enc.add_argument('--inplace', action='store_true',
                     help='Produce in-place reconstructible delta')
    enc.add_argument('--policy', choices=['localmin', 'constant'],
                     default='localmin',
                     help='Cycle-breaking policy for --inplace (default: localmin)')
    enc.add_argument('--verbose', action='store_true',
                     help='Print diagnostic messages to stderr')
    enc.add_argument('--min-copy', type=int, default=0,
                     help='Minimum copy length (0 = use seed length)')
    enc.set_defaults(func=cmd_encode)

    # decode
    dec = sub.add_parser('decode', help='Reconstruct version from delta')
    dec.add_argument('reference', help='Reference file')
    dec.add_argument('delta', help='Delta file')
    dec.add_argument('output', help='Output (reconstructed version) file')
    dec.set_defaults(func=cmd_decode)

    # info
    inf = sub.add_parser('info', help='Show delta file statistics')
    inf.add_argument('delta', help='Delta file')
    inf.set_defaults(func=cmd_info)

    args = ap.parse_args()
    if args.command is None:
        ap.print_help()
        sys.exit(1)
    args.func(args)


# ============================================================================

if __name__ == '__main__':
    main()
