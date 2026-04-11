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


@dataclass
class PlacedMove:
    """Copy from already-written output buffer at src to dst (self-referential).

    Used by DLT\\x04 MOVE/BIGMOVE commands.  Constraint: src + length <= dst,
    so only previously written bytes are referenced.  Always safe for in-place
    reconstruction (src < dst, no CRWI cycle possible).
    """
    src: int
    dst: int
    length: int

    def __repr__(self):
        return f"MOVE(src={self.src}, dst={self.dst}, len={self.length})"


PlacedCommand = Union[PlacedCopy, PlacedAdd, PlacedMove]


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
TABLE_SIZE = 1048573        # hash table capacity (largest prime < 2^20)
                            # Section 8: correcting uses checkpointing to fit any |R|
MAX_TABLE_SIZE = 1_073_741_827  # prime near 2^30; default ceiling for auto-sizing
HASH_BASE = 263      # small prime, avoids b=256 which makes low bits depend only on last byte
HASH_MOD = (1 << 61) - 1  # Mersenne prime 2^61-1: ~2.3 * 10^18
DELTA_BUF_CAP = 256       # lookback buffer capacity for correcting algorithm


@dataclass
class DiffOptions:
    """Options for differencing algorithms.

    Attributes:
        p:         Seed length: minimum match length and fingerprint window (Section 2.1.3).
        q:         Hash table capacity floor; algorithms auto-size upward from input length.
        buf_cap:   Lookback buffer depth for the correcting algorithm (Section 5.2).
        verbose:   Print per-run statistics to stderr when True.
        max_table: Auto-sizing ceiling; prevents unbounded memory use on very large inputs.
    """
    p: int = SEED_LEN
    q: int = TABLE_SIZE
    buf_cap: int = DELTA_BUF_CAP
    verbose: bool = False
    max_table: int = MAX_TABLE_SIZE

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


# Fixed witnesses for deterministic Miller-Rabin.
# Sufficient for all n < 3,317,044,064,679,887,385,961,981 (> 2^81).
# Jaeschke, Math. Comp. 61(204), 1993.
_MR_WITNESSES = (2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37)


def _is_prime(n: int) -> bool:
    """Deterministic Miller-Rabin primality test."""
    if n < 2 or (n != 2 and n % 2 == 0):
        return False
    if n == 2 or n == 3:
        return True
    for a in _MR_WITNESSES:
        if a >= n:
            break
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
                opts: 'DiffOptions' = None) -> List[Command]:
    """Greedy algorithm (Section 3.1, Figure 2).

    Uses a chained hash table (Python dict) that stores ALL offsets
    per footprint (Section 3.1).
    """
    if opts is not None:
        p, q, verbose = opts.p, opts.q, opts.verbose
    commands: List[Command] = []
    if not V:
        return commands

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

    nV = len(V)
    nR = len(R)

    # Rolling hash for O(1) per-position V fingerprinting.
    rh_v = _RollingHash(V, 0, p) if nV >= p else None
    rh_v_pos = 0

    while v_c + p <= nV:
        # Step (3): scan while seeds remain in V
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
            # Binary search for first mismatch beyond the seed.
            _max = min(nV - v_c, nR - r_cand)
            if V[v_c:v_c + _max] == R[r_cand:r_cand + _max]:
                ml = _max
            else:
                _lo, _hi = p, _max
                while _hi - _lo > 1:
                    _mid = (_lo + _hi) >> 1
                    if V[v_c:v_c + _mid] == R[r_cand:r_cand + _mid]:
                        _lo = _mid
                    else:
                        _hi = _mid
                ml = _lo
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
                 opts: 'DiffOptions' = None) -> List[Command]:
    """One-pass algorithm (Section 4.1, Figure 3).

    The hash table is auto-sized to next_prime(max(q, num_seeds // p)) so
    that large inputs get one slot per seed-length chunk of R.  TABLE_SIZE
    acts as a floor for small files.
    """
    if opts is not None:
        p, q, verbose = opts.p, opts.q, opts.verbose
    commands: List[Command] = []
    if not V:
        return commands

    # Auto-size hash table: one slot per p-byte chunk of R (floor = q).
    num_seeds = max(0, len(R) - p + 1)
    q = _next_prime(max(q, num_seeds // p))

    if verbose:
        print(f"onepass: q={q:,}, |R|={len(R):,}, "
              f"|V|={len(V):,}, seed_len={p}",
              file=sys.stderr)

    # Step (1): lookup structures with version-based logical flushing.
    # Three parallel lists per table: fingerprint, offset, version-stamp.
    # H_{V,R}_ver[i] != ver means the slot is stale; sentinel -1 never matches.
    H_V_fp  = [0] * q
    H_V_off = [0] * q
    H_V_ver = [-1] * q
    H_R_fp  = [0] * q
    H_R_off = [0] * q
    H_R_ver = [-1] * q
    ver = 0

    # Step (2): initialize scan pointers for concurrent R/V traversal
    r_c = 0
    v_c = 0
    v_s = 0
    dbg_positions = 0
    dbg_lookups = 0
    dbg_matches = 0

    nV = len(V)
    nR = len(R)

    # Rolling hashes for O(1) per-position fingerprinting.
    rh_v = _RollingHash(V, 0, p) if nV >= p else None
    rh_r = _RollingHash(R, 0, p) if nR >= p else None
    rh_v_pos = 0
    rh_r_pos = 0

    while v_c + p <= nV or r_c + p <= nR:
        # Step (3): which streams still have seeds?
        can_v = v_c + p <= nV
        can_r = r_c + p <= nR
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

        # Step (4a): store offsets (retain-existing policy, first entry wins)
        if fp_v is not None:
            _idx = fp_v % q
            if H_V_ver[_idx] != ver:
                H_V_fp[_idx]  = fp_v
                H_V_off[_idx] = v_c
                H_V_ver[_idx] = ver
        if fp_r is not None:
            _idx = fp_r % q
            if H_R_ver[_idx] != ver:
                H_R_fp[_idx]  = fp_r
                H_R_off[_idx] = r_c
                H_R_ver[_idx] = ver

        # Step (4b): look for a matching seed in the other table
        match_found = False
        r_m = v_m = 0

        if fp_r is not None:
            _idx = fp_r % q
            if H_V_ver[_idx] == ver and H_V_fp[_idx] == fp_r:
                v_cand = H_V_off[_idx]
                dbg_lookups += 1
                if R[r_c:r_c + p] == V[v_cand:v_cand + p]:
                    r_m, v_m = r_c, v_cand
                    match_found = True

        if not match_found and fp_v is not None:
            _idx = fp_v % q
            if H_R_ver[_idx] == ver and H_R_fp[_idx] == fp_v:
                r_cand = H_R_off[_idx]
                dbg_lookups += 1
                if V[v_c:v_c + p] == R[r_cand:r_cand + p]:
                    v_m, r_m = v_c, r_cand
                    match_found = True

        if not match_found:
            v_c += 1
            r_c += 1
            continue
        dbg_matches += 1

        # Step (5): extend match forward; binary search for first mismatch
        # (slice equality is C-speed memcmp, avoids per-byte Python loop).
        _max = min(nV - v_m, nR - r_m)
        if V[v_m:v_m + _max] == R[r_m:r_m + _max]:
            ml = _max
        else:
            _lo, _hi = 0, _max
            while _hi - _lo > 1:
                _mid = (_lo + _hi) >> 1
                if V[v_m:v_m + _mid] == R[r_m:r_m + _mid]:
                    _lo = _mid
                else:
                    _hi = _mid
            ml = _lo

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
    """One entry in the correction lookback buffer (Section 5.2).

    The correcting algorithm may discover that a newly found match overlaps
    commands already emitted. The buffer holds the most recent buf_cap tentative
    commands so they can be trimmed or cancelled (tail correction) when a better
    match is found. Commands are flushed to the output list as they age out.

    Attributes:
        v_start: First V byte covered by this entry.
        v_end:   One past the last V byte covered.
        cmd:     The tentative command (CopyCmd or AddCmd).
        dummy:   Reserved; always False in the current implementation.
    """
    v_start: int
    v_end: int
    cmd: Command
    dummy: bool = False


def diff_correcting(R: bytes, V: bytes,
                    p: int = SEED_LEN, q: int = TABLE_SIZE,
                    buf_cap: int = DELTA_BUF_CAP,
                    verbose: bool = False,
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
    max_table = MAX_TABLE_SIZE
    if opts is not None:
        p, q, buf_cap, verbose = opts.p, opts.q, opts.buf_cap, opts.verbose
        max_table = opts.max_table
    commands: List[Command] = []
    if not V:
        return commands

    # ── Checkpointing parameters (Section 8.1, pp. 347-348) ──────────
    num_seeds = max(0, len(R) - p + 1)
    # Auto-size: 2x factor for correcting's |F|=2L convention.
    # Capped at max_table to prevent runaway allocation on huge inputs.
    q = _next_prime(min(max_table, max(q, 2 * num_seeds // p)))
    C = q                                                # |C|
    F = _next_prime(2 * num_seeds) if num_seeds > 0 else 1  # |F|
    m = max(1, -(-F // C))                               # ceil(|F| / |C|)
    # Biased k (p. 348).
    if len(V) >= p:
        k = _fingerprint(V, min(len(V) // 2, len(V) - p), p) % F % m
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
    dbg_build_probes = 0  # extra slots scanned past the first
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
        i0 = i
        store = True
        while H_R[i] is not None:
            if H_R[i][0] == fp:
                store = False           # same fp already stored — skip
                break
            i = (i + 1) % C
            dbg_build_probes += 1
            if i == i0:
                store = False           # table full (safety)
                break
        if store:
            H_R[i] = (fp, a)           # linear probing (Section 7 Step 1)
            dbg_build_stored += 1

    if verbose:
        passed_pct = dbg_build_passed / num_seeds * 100 if num_seeds else 0
        occ_pct = dbg_build_stored / C * 100 if C else 0
        print(f"  build: {num_seeds} seeds, {dbg_build_passed} passed "
              f"checkpoint ({passed_pct:.2f}%), "
              f"{dbg_build_stored} stored, {dbg_build_probes} extra probes\n"
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

    nV = len(V)
    nR = len(R)

    # Rolling hash for O(1) per-position V fingerprinting.
    rh_v_scan = _RollingHash(V, 0, p) if nV >= p else None
    rh_v_pos = 0

    while v_c + p <= nV:
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

        # Checkpoint passed — look up H_R via linear probing.
        dbg_scan_checkpoints += 1
        i = f_v // m
        i0 = i
        r_offset = -1
        entry = H_R[i]
        while entry is not None and entry[0] != fp_v and (i := (i + 1) % C) != i0:
            entry = H_R[i]
        if entry is not None and entry[0] == fp_v:
            r_offset = entry[1]
        if r_offset < 0:
            v_c += 1
            continue

        # Verify seeds are byte-identical (Section 7, Step 4).
        if R[r_offset:r_offset + p] != V[v_c:v_c + p]:
            dbg_scan_byte_miss += 1
            v_c += 1
            continue

        dbg_scan_match += 1

        # Step (5): extend match forwards and backwards; binary search avoids
        # per-byte Python loop by using C-speed slice equality (memcmp).
        # Forward: first p bytes are the verified seed, search beyond them.
        _fmax = min(nV - v_c, nR - r_offset)
        if V[v_c:v_c + _fmax] == R[r_offset:r_offset + _fmax]:
            fwd = _fmax
        else:
            _lo, _hi = p, _fmax
            while _hi - _lo > 1:
                _mid = (_lo + _hi) >> 1
                if V[v_c:v_c + _mid] == R[r_offset:r_offset + _mid]:
                    _lo = _mid
                else:
                    _hi = _mid
            fwd = _lo

        # Backward: largest bwd s.t. V[v_c-bwd:v_c] == R[r_offset-bwd:r_offset].
        _bmax = min(v_c, r_offset)
        if _bmax == 0:
            bwd = 0
        elif V[v_c - _bmax:v_c] == R[r_offset - _bmax:r_offset]:
            bwd = _bmax
        else:
            _lo, _hi = 0, _bmax
            while _hi - _lo > 1:
                _mid = (_lo + _hi) >> 1
                if V[v_c - _mid:v_c] == R[r_offset - _mid:r_offset]:
                    _lo = _mid
                else:
                    _hi = _mid
            bwd = _lo

        v_m = v_c - bwd
        r_m = r_offset - bwd
        ml = bwd + fwd
        match_end = v_m + ml

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


def unplace_commands(placed: List[PlacedCommand]) -> List[Command]:
    """Convert placed commands back to algorithm commands (strip destinations).

    Commands are sorted by destination offset to recover original sequential
    order, then each PlacedCopy/PlacedAdd is converted to CopyCmd/AddCmd.
    """
    by_dst = sorted(placed, key=lambda c: c.dst)
    commands = []
    for cmd in by_dst:
        if isinstance(cmd, PlacedCopy):
            commands.append(CopyCmd(offset=cmd.src, length=cmd.length))
        elif isinstance(cmd, PlacedAdd):
            commands.append(AddCmd(data=cmd.data))
    return commands


# ============================================================================
# Unified Binary Delta Format
#
# DLT\x03 Header (25 bytes):
#   Magic:        4 bytes  b'DLT\x03'
#   Flags:        1 byte   bit0=INPLACE
#   Version size: 4 bytes  uint32 BE
#   Src CRC:      8 bytes  CRC-64/XZ of reference file (big-endian)
#   Dst CRC:      8 bytes  CRC-64/XZ of version/output file (big-endian)
#
# DLT\x04 Header (29 bytes):
#   Magic:        4 bytes  b'DLT\x04'
#   Flags:        1 byte   bit0=INPLACE
#   Version size: 8 bytes  uint64 BE   ← extended from 4 bytes
#   Src CRC:      8 bytes  CRC-64/XZ of reference file (big-endian)
#   Dst CRC:      8 bytes  CRC-64/XZ of version/output file (big-endian)
#
# Commands (both versions, in execution order):
#   END:     type=0                                       (1 byte)
#   COPY:    type=1, src:u32, dst:u32, len:u32           (13 bytes)
#   ADD:     type=2, dst:u32, len:u32, data               (9 + len bytes)
#   BIGCOPY: type=3, src:u64, dst:u64, len:u64           (25 bytes)  [v4 only]
#   BIGADD:  type=4, dst:u64, len:u64, data              (17 + len bytes)  [v4 only]
#   MOVE:    type=5, src:u32, dst:u32, len:u32           (13 bytes)  [v4 only]
#   BIGMOVE: type=6, src:u64, dst:u64, len:u64           (25 bytes)  [v4 only]
#
# MOVE/BIGMOVE copy from the already-written output buffer (src < dst).
# The encoder selects COPY vs BIGCOPY (and MOVE vs BIGMOVE) per-command
# based on whether all fields fit in u32.
# ============================================================================

DELTA_MAGIC    = b'DLT\x03'
DELTA_MAGIC_LARGE = b'DLT\x04'
DELTA_FLAG_INPLACE  = 0x01
DELTA_CMD_END     = 0
DELTA_CMD_COPY    = 1
DELTA_CMD_ADD     = 2
DELTA_CMD_BIGCOPY = 3
DELTA_CMD_BIGADD  = 4
DELTA_CMD_MOVE    = 5
DELTA_CMD_BIGMOVE = 6
DELTA_CRC_SIZE       = 8
DELTA_U32_SIZE       = 4
DELTA_U64_SIZE       = 8
DELTA_HEADER_SIZE    = 25  # magic(4)+flags(1)+version_size(4)+crcs(16)
DELTA_HEADER_SIZE_LARGE = 29  # magic(4)+flags(1)+version_size(8)+crcs(16)
DELTA_COPY_PAYLOAD    = 12  # src(4)+dst(4)+len(4)
DELTA_ADD_HEADER      = 8   # dst(4)+len(4)
DELTA_BIGCOPY_PAYLOAD = 24  # src(8)+dst(8)+len(8)
DELTA_BIGADD_HEADER   = 16  # dst(8)+len(8)
_U32_MAX = 0xFFFFFFFF

# ── CRC-64/XZ (ECMA-182 reflected) ───────────────────────────────────────────
# Canonical polynomial: 0x42F0E1EBA9EA3693 (normal form)
# Table generated with reflected polynomial: 0xC96C5795D7870F42
# Init = XorOut = 0xFFFFFFFFFFFFFFFF; RefIn = RefOut = True
# Check: crc64_xz(b"123456789") = 0x995DC9BBDF1939FA
# Check: crc64_xz(b"")          = 0x0000000000000000

def _make_crc64_table():
    poly = 0xC96C5795D7870F42
    t = []
    for i in range(256):
        crc = i
        for _ in range(8):
            crc = (crc >> 1) ^ poly if crc & 1 else crc >> 1
        t.append(crc)
    return t

_CRC64_TABLE = _make_crc64_table()


def _crc64_xz(data: bytes) -> bytes:
    """CRC-64/XZ of data; returns 8 bytes big-endian."""
    crc = 0xFFFFFFFFFFFFFFFF
    for b in data:
        crc = _CRC64_TABLE[(crc ^ b) & 0xFF] ^ (crc >> 8)
    return (crc ^ 0xFFFFFFFFFFFFFFFF).to_bytes(8, 'big')


def encode_delta(commands: List[PlacedCommand], *,
                 inplace: bool = False, version_size: int,
                 src_crc: bytes, dst_crc: bytes) -> bytes:
    """Encode placed commands to DLT\\x03 format (u32 fields, max 4 GiB).

    src_crc and dst_crc must each be exactly DELTA_CRC_SIZE (8) bytes.
    Use encode_delta_large for DLT\\x04 (u64 fields, MOVE commands).
    """
    out = bytearray()
    out.extend(DELTA_MAGIC)
    out.append(DELTA_FLAG_INPLACE if inplace else 0)
    out.extend(struct.pack('>I', version_size))
    out.extend(src_crc)
    out.extend(dst_crc)
    for cmd in commands:
        if isinstance(cmd, PlacedCopy):
            out.append(DELTA_CMD_COPY)
            out.extend(struct.pack('>III', cmd.src, cmd.dst, cmd.length))
        elif isinstance(cmd, PlacedAdd):
            out.append(DELTA_CMD_ADD)
            out.extend(struct.pack('>II', cmd.dst, len(cmd.data)))
            out.extend(cmd.data)
        elif isinstance(cmd, PlacedMove):
            raise ValueError("PlacedMove requires encode_delta_large")
    out.append(DELTA_CMD_END)
    return bytes(out)


def encode_delta_large(commands: List[PlacedCommand], *,
                       inplace: bool = False, version_size: int,
                       src_crc: bytes, dst_crc: bytes) -> bytes:
    """Encode placed commands to DLT\\x04 format (u64 fields, MOVE/BIGMOVE support).

    Per-command size selection: COPY/BIGCOPY, ADD/BIGADD, MOVE/BIGMOVE chosen
    based on whether all fields fit in u32.
    """
    out = bytearray()
    out.extend(DELTA_MAGIC_LARGE)
    out.append(DELTA_FLAG_INPLACE if inplace else 0)
    out.extend(struct.pack('>Q', version_size))   # u64
    out.extend(src_crc)
    out.extend(dst_crc)

    for cmd in commands:
        if isinstance(cmd, PlacedCopy):
            if cmd.src <= _U32_MAX and cmd.dst <= _U32_MAX and cmd.length <= _U32_MAX:
                out.append(DELTA_CMD_COPY)
                out.extend(struct.pack('>III', cmd.src, cmd.dst, cmd.length))
            else:
                out.append(DELTA_CMD_BIGCOPY)
                out.extend(struct.pack('>QQQ', cmd.src, cmd.dst, cmd.length))
        elif isinstance(cmd, PlacedAdd):
            if cmd.dst <= _U32_MAX and len(cmd.data) <= _U32_MAX:
                out.append(DELTA_CMD_ADD)
                out.extend(struct.pack('>II', cmd.dst, len(cmd.data)))
                out.extend(cmd.data)
            else:
                out.append(DELTA_CMD_BIGADD)
                out.extend(struct.pack('>QQ', cmd.dst, len(cmd.data)))
                out.extend(cmd.data)
        elif isinstance(cmd, PlacedMove):
            if cmd.src <= _U32_MAX and cmd.dst <= _U32_MAX and cmd.length <= _U32_MAX:
                out.append(DELTA_CMD_MOVE)
                out.extend(struct.pack('>III', cmd.src, cmd.dst, cmd.length))
            else:
                out.append(DELTA_CMD_BIGMOVE)
                out.extend(struct.pack('>QQQ', cmd.src, cmd.dst, cmd.length))

    out.append(DELTA_CMD_END)
    return bytes(out)


def decode_delta(data: bytes):
    """Decode the unified binary delta format (DLT\\x03 or DLT\\x04).

    Returns (commands, inplace, version_size, src_crc, dst_crc).
    CRC validation is the caller's responsibility.
    """
    if len(data) < 5:
        raise ValueError("Not a delta file")
    magic = data[:4]
    if magic == DELTA_MAGIC:
        return _decode_delta_small(data)
    elif magic == DELTA_MAGIC_LARGE:
        return _decode_delta_large(data)
    else:
        raise ValueError("Not a delta file")


def _decode_delta_small(data: bytes):
    """Decode DLT\\x03 format."""
    if len(data) < DELTA_HEADER_SIZE:
        raise ValueError("Not a delta file")
    inplace = bool(data[4] & DELTA_FLAG_INPLACE)
    version_size = struct.unpack_from('>I', data, 5)[0]
    src_crc = bytes(data[9:17])
    dst_crc = bytes(data[17:25])
    return _decode_commands_small(data, pos=DELTA_HEADER_SIZE,
                                 version_size=version_size,
                                 inplace=inplace,
                                 src_crc=src_crc, dst_crc=dst_crc)


def _decode_delta_large(data: bytes):
    """Decode DLT\\x04 format."""
    if len(data) < DELTA_HEADER_SIZE_LARGE:
        raise ValueError("Not a delta file")
    inplace = bool(data[4] & DELTA_FLAG_INPLACE)
    version_size = struct.unpack_from('>Q', data, 5)[0]   # u64
    src_crc = bytes(data[13:21])
    dst_crc = bytes(data[21:29])
    return _decode_commands_large(data, pos=DELTA_HEADER_SIZE_LARGE,
                                  version_size=version_size,
                                  inplace=inplace,
                                  src_crc=src_crc, dst_crc=dst_crc)


def _decode_commands_small(data: bytes, *, pos: int, version_size: int,
                           inplace: bool, src_crc: bytes, dst_crc: bytes):
    """Parse the DLT\\x03 command stream (u32 fields only; no MOVE or big variants)."""
    commands: List[PlacedCommand] = []
    saw_end = False

    while pos < len(data):
        t = data[pos]; pos += 1

        if t == DELTA_CMD_END:
            saw_end = True
            break
        elif t == DELTA_CMD_COPY:
            if pos + DELTA_COPY_PAYLOAD > len(data):
                raise ValueError("Truncated COPY command")
            src, dst, length = struct.unpack_from('>III', data, pos)
            pos += DELTA_COPY_PAYLOAD
            if dst + length > version_size:
                raise ValueError("COPY extends past version size")
            commands.append(PlacedCopy(src=src, dst=dst, length=length))
        elif t == DELTA_CMD_ADD:
            if pos + DELTA_ADD_HEADER > len(data):
                raise ValueError("Truncated ADD command header")
            dst, length = struct.unpack_from('>II', data, pos)
            pos += DELTA_ADD_HEADER
            if pos + length > len(data):
                raise ValueError("Truncated ADD payload")
            if dst + length > version_size:
                raise ValueError("ADD extends past version size")
            commands.append(PlacedAdd(dst=dst, data=data[pos:pos + length]))
            pos += length
        elif t in (DELTA_CMD_BIGCOPY, DELTA_CMD_BIGADD,
                   DELTA_CMD_MOVE, DELTA_CMD_BIGMOVE):
            raise ValueError(f"Command type {t} requires DLT\\x04 format")
        else:
            raise ValueError(f"Unknown command type: {t}")

    if not saw_end:
        raise ValueError("Missing END command")
    if pos != len(data):
        raise ValueError("Trailing data after END")
    return commands, inplace, version_size, src_crc, dst_crc


def _decode_commands_large(data: bytes, *, pos: int, version_size: int,
                           inplace: bool, src_crc: bytes, dst_crc: bytes):
    """Parse the DLT\\x04 command stream (u32 + u64 variants, MOVE/BIGMOVE)."""
    commands: List[PlacedCommand] = []
    saw_end = False

    while pos < len(data):
        t = data[pos]; pos += 1

        if t == DELTA_CMD_END:
            saw_end = True
            break
        elif t == DELTA_CMD_COPY:
            if pos + DELTA_COPY_PAYLOAD > len(data):
                raise ValueError("Truncated COPY command")
            src, dst, length = struct.unpack_from('>III', data, pos)
            pos += DELTA_COPY_PAYLOAD
            if dst + length > version_size:
                raise ValueError("COPY extends past version size")
            commands.append(PlacedCopy(src=src, dst=dst, length=length))
        elif t == DELTA_CMD_ADD:
            if pos + DELTA_ADD_HEADER > len(data):
                raise ValueError("Truncated ADD command header")
            dst, length = struct.unpack_from('>II', data, pos)
            pos += DELTA_ADD_HEADER
            if pos + length > len(data):
                raise ValueError("Truncated ADD payload")
            if dst + length > version_size:
                raise ValueError("ADD extends past version size")
            commands.append(PlacedAdd(dst=dst, data=data[pos:pos + length]))
            pos += length
        elif t == DELTA_CMD_BIGCOPY:
            if pos + DELTA_BIGCOPY_PAYLOAD > len(data):
                raise ValueError("Truncated BIGCOPY command")
            src, dst, length = struct.unpack_from('>QQQ', data, pos)
            pos += DELTA_BIGCOPY_PAYLOAD
            if dst + length > version_size:
                raise ValueError("BIGCOPY extends past version size")
            commands.append(PlacedCopy(src=src, dst=dst, length=length))
        elif t == DELTA_CMD_BIGADD:
            if pos + DELTA_BIGADD_HEADER > len(data):
                raise ValueError("Truncated BIGADD command header")
            dst, length = struct.unpack_from('>QQ', data, pos)
            pos += DELTA_BIGADD_HEADER
            if pos + length > len(data):
                raise ValueError("Truncated BIGADD payload")
            if dst + length > version_size:
                raise ValueError("BIGADD extends past version size")
            commands.append(PlacedAdd(dst=dst, data=data[pos:pos + length]))
            pos += length
        elif t == DELTA_CMD_MOVE:
            if pos + DELTA_COPY_PAYLOAD > len(data):
                raise ValueError("Truncated MOVE command")
            src, dst, length = struct.unpack_from('>III', data, pos)
            pos += DELTA_COPY_PAYLOAD
            if src + length > dst:
                raise ValueError("MOVE src overlaps dst (not yet written)")
            if dst + length > version_size:
                raise ValueError("MOVE extends past version size")
            commands.append(PlacedMove(src=src, dst=dst, length=length))
        elif t == DELTA_CMD_BIGMOVE:
            if pos + DELTA_BIGCOPY_PAYLOAD > len(data):
                raise ValueError("Truncated BIGMOVE command")
            src, dst, length = struct.unpack_from('>QQQ', data, pos)
            pos += DELTA_BIGCOPY_PAYLOAD
            if src + length > dst:
                raise ValueError("BIGMOVE src overlaps dst (not yet written)")
            if dst + length > version_size:
                raise ValueError("BIGMOVE extends past version size")
            commands.append(PlacedMove(src=src, dst=dst, length=length))
        else:
            raise ValueError(f"Unknown command type: {t}")

    if not saw_end:
        raise ValueError("Missing END command")
    if pos != len(data):
        raise ValueError("Trailing data after END")
    return commands, inplace, version_size, src_crc, dst_crc


def is_inplace_delta(data: bytes) -> bool:
    """Check if binary data is an in-place delta (DLT\\x03 or DLT\\x04)."""
    return (len(data) >= 5
            and data[:4] in (DELTA_MAGIC, DELTA_MAGIC_LARGE)
            and bool(data[4] & DELTA_FLAG_INPLACE))


# ============================================================================
# Reconstruction — apply delta to reference to recover version
# ============================================================================

def apply_placed_to(R, commands: List[PlacedCommand], buf) -> int:
    """Apply placed commands in standard mode: read from R, write to buf.

    PlacedMove reads from already-written regions of buf (src < dst guaranteed).
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
        elif isinstance(cmd, PlacedMove):
            # Copy from already-written output (src < dst guaranteed by decoder)
            buf[cmd.dst:cmd.dst + cmd.length] = buf[cmd.src:cmd.src + cmd.length]
            end = cmd.dst + cmd.length
            if end > max_written:
                max_written = end
    return max_written


def apply_placed_inplace_to(commands: List[PlacedCommand], buf) -> None:
    """Execute placed commands in a buffer that serves as both source and destination.

    Slice assignment creates a temporary copy of the RHS, so overlapping
    src/dst within a single copy is handled correctly.
    PlacedMove is always safe (src < dst, reads already-written output).
    """
    for cmd in commands:
        if isinstance(cmd, PlacedCopy):
            buf[cmd.dst:cmd.dst + cmd.length] = buf[cmd.src:cmd.src + cmd.length]
        elif isinstance(cmd, PlacedAdd):
            buf[cmd.dst:cmd.dst + len(cmd.data)] = cmd.data
        elif isinstance(cmd, PlacedMove):
            buf[cmd.dst:cmd.dst + cmd.length] = buf[cmd.src:cmd.src + cmd.length]


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
    commands, inplace, version_size, _src_crc, _dst_crc = decode_delta(delta)
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
# Why overlaps don't always require add conversion:
#   When copy i reads from [src_i, src_i+len_i) and copy j writes to
#   [dst_j, dst_j+len_j), and these intervals overlap, copy i MUST execute
#   before j overwrites its source data.  This creates a directed edge i→j
#   in the CRWI (Copy-Read/Write-Intersection) digraph.  When the graph is
#   acyclic, a topological order exists — every copy can be executed before
#   any copy that would clobber its data.  No conversion is needed.
#
#   A cycle i₁→i₂→...→iₖ→i₁ means each copy needs to run before the next,
#   forming a circular dependency with no valid serial schedule.  Breaking
#   the cycle requires materializing one copy as a literal add (reading its
#   source bytes from R before the buffer is modified), which removes that
#   edge and makes the remaining graph schedulable.
#
# Algorithm:
#   1. Annotate each copy command with its write offset in the output
#   2. Build CRWI digraph: edge i→j iff i's read interval intersects j's
#      write interval (Section 4.2)
#   3. Topological sort (Kahn); when the heap empties with nodes remaining,
#      a cycle exists — find it and convert the minimum copy to an add
#   4. Output: copies in topological order, then all adds
#
# Cycle-breaking policies (Section 4.3):
#   - constant: pick any remaining vertex when a cycle is detected
#   - localmin: pick the minimum-length vertex in the cycle (less compression loss)
# ============================================================================

def _tarjan_scc(adj, n):
    """Compute strongly connected components using iterative Tarjan's algorithm.

    Returns SCCs in reverse topological order (sinks first); caller reverses
    for source-first processing order.

    R.E. Tarjan, "Depth-first search and linear graph algorithms,"
    SIAM Journal on Computing, 1(2):146-160, June 1972.
    """
    index_counter = 0
    index    = [-1] * n          # -1 = unvisited
    lowlink  = [0] * n
    on_stack = [False] * n
    tarjan_stack = []            # Tarjan's SCC stack
    sccs     = []
    call_stack = []              # iterative DFS call stack

    for start in range(n):
        if index[start] != -1:
            continue

        index[start] = lowlink[start] = index_counter
        index_counter += 1
        on_stack[start] = True
        tarjan_stack.append(start)
        call_stack.append(_DfsFrame(start))

        while call_stack:
            frame = call_stack[-1]
            v = frame.v

            if frame.ni < len(adj[v]):
                w = adj[v][frame.ni]
                frame.ni += 1
                if index[w] == -1:
                    # Tree edge: descend into w
                    index[w] = lowlink[w] = index_counter
                    index_counter += 1
                    on_stack[w] = True
                    tarjan_stack.append(w)
                    call_stack.append(_DfsFrame(w))
                elif on_stack[w]:
                    # Back-edge into current SCC
                    if index[w] < lowlink[v]:
                        lowlink[v] = index[w]
            else:
                # Done with v — backtrack
                call_stack.pop()
                if call_stack:
                    parent = call_stack[-1].v
                    if lowlink[v] < lowlink[parent]:
                        lowlink[parent] = lowlink[v]
                # Root of an SCC?
                if lowlink[v] == index[v]:
                    scc = []
                    while True:
                        w = tarjan_stack.pop()
                        on_stack[w] = False
                        scc.append(w)
                        if w == v:
                            break
                    sccs.append(scc)

    return sccs  # sinks first; caller reverses for source-first order


def _find_cycle_in_scc(adj, scc, sid, scc_id, removed, color, scan_start):
    """Find a cycle in the active subgraph of one SCC.

    Three amortizations give O(|SCC| + E_SCC) total work per SCC:
      1. scc_id filter: O(1) per neighbor, no O(|SCC|) set/clear sweep.
      2. color persistence: color=2 (fully explored) persists across calls;
         vertex removal can only reduce edges, so color=2 is monotone-correct.
      3. scan_start: outer loop resumes from last position, O(|SCC|) total.

    Returns (cycle, new_scan) where cycle is the found cycle or None, and
    new_scan is the updated scan position for the next call.
    """
    path = []
    scan = scan_start
    scc_len = len(scc)

    while scan < scc_len:
        start = scc[scan]
        if removed[start] or color[start] != _COLOR_UNVISITED:
            scan += 1
            continue

        color[start] = _COLOR_ON_PATH
        path.append(start)
        stack = [_DfsFrame(start)]

        while stack:
            frame = stack[-1]
            v = frame.v
            advanced = False
            while frame.ni < len(adj[v]):
                w = adj[v][frame.ni]
                frame.ni += 1
                if scc_id[w] != sid or removed[w]:
                    continue
                if color[w] == _COLOR_ON_PATH:
                    # Back-edge: cycle found
                    pos = path.index(w)
                    cycle = path[pos:]
                    for u in path:
                        color[u] = _COLOR_UNVISITED
                    return cycle, scan
                if color[w] == _COLOR_UNVISITED:
                    color[w] = _COLOR_ON_PATH
                    path.append(w)
                    stack.append(_DfsFrame(w))
                    advanced = True
                    break
            if not advanced:
                stack.pop()
                color[v] = _COLOR_DONE  # Fully explored — persists across calls.
                path.pop()

        # start's reachable SCC-subgraph fully explored; no cycle.
        scan += 1

    return None, scan


@dataclass(frozen=True)
class _CopyInfo:
    """Source offset, destination offset, and length of one copy command."""
    src: int
    dst: int
    length: int


@dataclass
class _SccData:
    """Non-trivial SCCs with per-SCC active counts and vertex-to-SCC mapping."""
    sccs:   list   # list[list[int]] — non-trivial SCCs
    active: list   # list[int] — active vertex count per SCC
    scc_id: list   # list[int | None] — vertex → SCC index (None = trivial)


@dataclass
class _ScanCursor:
    """Mutable cursor tracking which SCC and scan position pickVictim is examining."""
    scc_ptr:  int = 0
    scan_pos: int = 0


@dataclass
class _DfsFrame:
    """One frame on the iterative DFS call stack: vertex and next-neighbor index."""
    v:  int
    ni: int = 0


# DFS color states for _find_cycle_in_scc and _tarjan_scc
_COLOR_UNVISITED = 0
_COLOR_ON_PATH   = 1
_COLOR_DONE      = 2


def _build_crwi_digraph(copy_info, n):
    """Build CRWI digraph on copy commands via O(n log n + E) sweep-line.

    Edge i→j means copy i reads from a region that copy j will overwrite,
    so i must execute before j.
    """
    import bisect
    adj = [[] for _ in range(n)]
    write_sorted = sorted(range(n), key=lambda j: copy_info[j].dst)
    write_starts = [copy_info[j].dst for j in write_sorted]

    for i in range(n):
        src, length = copy_info[i].src, copy_info[i].length
        read_end = src + length
        # lo = first write with dst >= src; hi = first write with dst >= read_end.
        # Writes in [lo, hi) start inside [src, read_end) — they always overlap.
        # The write at lo-1 starts before src; overlaps iff its end exceeds src.
        lo = bisect.bisect_left(write_starts, src)
        hi = bisect.bisect_left(write_starts, read_end)
        if lo > 0:
            j = write_sorted[lo - 1]
            if i != j and copy_info[j].dst + copy_info[j].length > src:
                adj[i].append(j)
        for k in range(lo, hi):
            j = write_sorted[k]
            if i != j:
                adj[i].append(j)
    return adj


def _build_scc_list(adj, n):
    """Wrap _tarjan_scc output into an _SccData containing only non-trivial SCCs."""
    all_sccs = _tarjan_scc(adj, n)
    scc_id   = [None] * n
    sccs     = []
    active   = []

    for scc in all_sccs:
        if len(scc) > 1:
            sid = len(sccs)
            for v in scc:
                scc_id[v] = sid
            sccs.append(scc)
            active.append(len(scc))

    return _SccData(sccs=sccs, active=active, scc_id=scc_id)


def _pick_victim(copy_info, adj, scc_data, removed, color, cursor, policy, n):
    """Select a victim copy to break a cycle when Kahn's algorithm stalls.

    Constant: first remaining vertex.  Localmin: minimum-length copy in a cycle.
    cursor.scc_ptr and cursor.scan_pos are advanced in place across repeated calls.
    """
    if policy == 'constant':
        return next(i for i in range(n) if not removed[i])

    victim = None
    while victim is None:
        while cursor.scc_ptr < len(scc_data.sccs) and scc_data.active[cursor.scc_ptr] == 0:
            cursor.scc_ptr += 1
            cursor.scan_pos = 0
        if cursor.scc_ptr >= len(scc_data.sccs):
            victim = next(i for i in range(n) if not removed[i])
        else:
            cycle, new_scan = _find_cycle_in_scc(
                adj, scc_data.sccs[cursor.scc_ptr], cursor.scc_ptr,
                scc_data.scc_id, removed, color, cursor.scan_pos)
            cursor.scan_pos = new_scan
            if cycle is not None:
                victim = min(cycle, key=lambda v: (copy_info[v].length, v))
            else:
                cursor.scc_ptr += 1
                cursor.scan_pos = 0
    return victim


def _run_kahn(copy_info, adj, scc_data, r, add_info, policy, n):
    """Run Kahn topological sort; when the heap stalls, call _pick_victim to
    break the cycle by materialising one copy as a literal add.

    Returns (topo_order, cycles_broken).
    """
    in_deg = [0] * n
    for i in range(n):
        for j in adj[i]:
            in_deg[j] += 1

    removed    = [False] * n
    topo_order = []
    cycles_broken = 0
    color  = [0] * n
    cursor = _ScanCursor()

    heap = []
    for i in range(n):
        if in_deg[i] == 0:
            heapq.heappush(heap, (copy_info[i].length, i))
    processed = 0

    while processed < n:
        while heap:
            _, v = heapq.heappop(heap)
            if removed[v]:
                continue
            removed[v] = True
            topo_order.append(v)
            processed += 1
            sid = scc_data.scc_id[v]
            if sid is not None:
                scc_data.active[sid] -= 1
            for w in adj[v]:
                if not removed[w]:
                    in_deg[w] -= 1
                    if in_deg[w] == 0:
                        heapq.heappush(heap, (copy_info[w].length, w))

        if processed >= n:
            break

        victim = _pick_victim(copy_info, adj, scc_data, removed, color, cursor, policy, n)
        ci = copy_info[victim]
        add_info.append((ci.dst, bytes(r[ci.src:ci.src + ci.length])))
        cycles_broken += 1
        removed[victim] = True
        processed += 1
        sid = scc_data.scc_id[victim]
        if sid is not None:
            scc_data.active[sid] -= 1
        for w in adj[victim]:
            if not removed[w]:
                in_deg[w] -= 1
                if in_deg[w] == 0:
                    heapq.heappush(heap, (copy_info[w].length, w))

    return topo_order, cycles_broken


def make_inplace(R: bytes, commands: List[Command],
                 policy: str = 'localmin',
                 return_stats: bool = False):
    """Convert standard delta commands to in-place executable commands.

    The returned commands can be applied to a buffer initialized with R
    to reconstruct V in-place, without a separate output buffer.

    Args:
        R: Reference data (needed to materialize literal bytes when
           a copy is converted to an add during cycle breaking).
        commands: Standard delta commands (CopyCmd / AddCmd).
        policy: 'localmin' (default, better compression) or 'constant'.
        return_stats: If True, return (commands, {'cycles_broken': N}).

    Returns:
        List of PlacedCopy / PlacedAdd in safe execution order, or a
        (list, stats_dict) tuple if return_stats is True.
    """
    if not commands:
        return []

    # Step 1: compute write offsets
    copy_info = []  # list[_CopyInfo]
    add_info  = []  # list[(dst, data)]
    write_pos = 0
    for cmd in commands:
        if isinstance(cmd, CopyCmd):
            copy_info.append(_CopyInfo(src=cmd.offset, dst=write_pos, length=cmd.length))
            write_pos += cmd.length
        elif isinstance(cmd, AddCmd):
            add_info.append((write_pos, cmd.data))
            write_pos += len(cmd.data)

    n = len(copy_info)
    if n == 0:
        return [PlacedAdd(dst=d, data=dat) for d, dat in add_info]

    # Steps 2-3: build digraph, topological sort, break cycles
    adj              = _build_crwi_digraph(copy_info, n)
    scc_data         = _build_scc_list(adj, n)
    topo_order, ncyc = _run_kahn(copy_info, adj, scc_data, R, add_info, policy, n)

    # Step 4: assemble result — copies in topo order, then all adds
    result: List[PlacedCommand] = []
    for i in topo_order:
        ci = copy_info[i]
        result.append(PlacedCopy(src=ci.src, dst=ci.dst, length=ci.length))
    for dst, data in add_info:
        result.append(PlacedAdd(dst=dst, data=data))

    if return_stats:
        return result, {'cycles_broken': ncyc}
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
# File I/O helpers
# ============================================================================

def _read_with_crc(path: str):
    """Read a file in one sequential pass, returning (data, crc64_xz).

    CRC is computed incrementally while reading so no second pass is needed.
    Data is returned as bytes (may be large for big files).
    """
    crc = 0xFFFFFFFFFFFFFFFF
    size = os.path.getsize(path)
    if size == 0:
        return b'', (crc ^ 0xFFFFFFFFFFFFFFFF).to_bytes(8, 'big')
    parts = []
    with open(path, 'rb') as f:
        while chunk := f.read(1 << 20):  # 1 MB chunks
            for b in chunk:
                crc = _CRC64_TABLE[(crc ^ b) & 0xFF] ^ (crc >> 8)
            parts.append(chunk)
    return b''.join(parts), (crc ^ 0xFFFFFFFFFFFFFFFF).to_bytes(8, 'big')


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
# CLI helpers
# ============================================================================

def _parse_size_suffix(s: str) -> int:
    """Parse a size string with optional k/M/B suffix (decimal multipliers)."""
    s = s.strip()
    if not s:
        raise argparse.ArgumentTypeError("empty size value")
    multipliers = {'k': 1_000, 'K': 1_000, 'm': 1_000_000, 'M': 1_000_000,
                   'b': 1_000_000_000, 'B': 1_000_000_000}
    if s[-1] in multipliers:
        return int(s[:-1]) * multipliers[s[-1]]
    return int(s)


# ============================================================================
# CLI
# ============================================================================

def cmd_encode(args):
    """Encode R→V with the chosen algorithm, write a binary delta file, and print statistics."""
    if args.seed_len < 1:
        raise SystemExit("error: --seed-len must be >= 1")
    algo = ALGORITHMS[args.algorithm]
    opts = DiffOptions(
        p=args.seed_len,
        q=args.table_size,
        verbose=args.verbose,
        max_table=args.max_table,
    )

    # Read files and compute CRC-64/XZ in a single sequential pass each.
    R, src_crc = _read_with_crc(args.reference)
    V, dst_crc = _read_with_crc(args.version)

    t0 = time.time()
    commands = algo(R, V, opts=opts)

    cycles_broken = 0
    if args.inplace:
        placed, ip_stats = make_inplace(R, commands, policy=args.policy,
                                        return_stats=True)
        cycles_broken = ip_stats.get('cycles_broken', 0)
    else:
        placed = place_commands(commands)
    elapsed = time.time() - t0

    delta = encode_delta_large(placed, inplace=args.inplace, version_size=len(V),
                               src_crc=src_crc, dst_crc=dst_crc)
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
    if args.inplace:
        print(f"Cycles broken: {cycles_broken}")
    print(f"Copy bytes:   {stats['copy_bytes']:,}")
    print(f"Add bytes:    {stats['add_bytes']:,}")
    if args.verbose:
        print(f"Src CRC:      {src_crc.hex()}")
        print(f"Dst CRC:      {dst_crc.hex()}")
    print(f"Time:         {elapsed:.3f}s")


def cmd_decode(args):
    """Apply a binary delta file to R and write the reconstructed version."""
    # Read reference and compute its CRC in one sequential pass.
    R, r_crc_actual = _read_with_crc(args.reference)

    with open(args.delta, 'rb') as f:
        delta_bytes = f.read()

    t0 = time.time()
    placed, is_ip, version_size, src_crc, dst_crc = decode_delta(delta_bytes)

    # Pre-check: verify reference matches what was recorded at encode time.
    if r_crc_actual != src_crc:
        if not args.ignore_hash:
            raise SystemExit(
                f"error: source file does not match delta: "
                f"expected {src_crc.hex()}, got {r_crc_actual.hex()}"
            )
        print("warning: skipping source CRC check (--ignore-hash)", file=sys.stderr)

    output_crc = None
    if is_ip:
        buf_size = max(len(R), version_size)
        with mmap_create(args.output, buf_size) as buf:
            buf[:len(R)] = R[:len(R)]
            apply_placed_inplace_to(placed, buf)
            output_crc = _crc64_xz(bytes(buf[:version_size]))
        if version_size < buf_size:
            os.truncate(args.output, version_size)
    else:
        with mmap_create(args.output, version_size) as buf:
            apply_placed_to(R, placed, buf)
            output_crc = _crc64_xz(bytes(buf[:version_size]))
    elapsed = time.time() - t0

    # Post-check: verify reconstructed output matches recorded dest CRC.
    if output_crc != dst_crc:
        if not args.ignore_hash:
            raise SystemExit("error: output integrity check failed")
        print("warning: skipping output CRC check (--ignore-hash)", file=sys.stderr)

    fmt = "in-place" if is_ip else "standard"
    print(f"Format:       {fmt}")
    print(f"Reference:    {args.reference} ({len(R):,} bytes)")
    print(f"Delta:        {args.delta} ({len(delta_bytes):,} bytes)")
    print(f"Output:       {args.output} ({version_size:,} bytes)")
    print(f"Time:         {elapsed:.3f}s")


def cmd_info(args):
    """Print the header fields and command summary of a binary delta file."""
    with open(args.delta, 'rb') as f:
        delta_bytes = f.read()

    placed, is_ip, version_size, src_crc, dst_crc = decode_delta(delta_bytes)
    stats = placed_summary(placed)
    fmt = "in-place" if is_ip else "standard"

    print(f"Delta file:   {args.delta} ({len(delta_bytes):,} bytes)")
    print(f"Format:       {fmt}")
    print(f"Version size: {version_size:,} bytes")
    print(f"Src CRC:      {src_crc.hex()}")
    print(f"Dst CRC:      {dst_crc.hex()}")
    print(f"Commands:     {stats['num_commands']}")
    print(f"  Copies:     {stats['num_copies']} ({stats['copy_bytes']:,} bytes)")
    print(f"  Adds:       {stats['num_adds']} ({stats['add_bytes']:,} bytes)")
    print(f"Output size:  {stats['total_output_bytes']:,} bytes")


def cmd_inplace(args):
    """Convert a standard delta file to in-place format using the CRWI algorithm."""
    # Read reference and compute CRC in one sequential pass.
    R, _r_crc = _read_with_crc(args.reference)

    with open(args.delta_in, 'rb') as f:
        delta_bytes = f.read()

    placed, is_ip, version_size, src_crc, dst_crc = decode_delta(delta_bytes)

    if is_ip:
        # Already in-place — just copy
        with open(args.delta_out, 'wb') as f:
            f.write(delta_bytes)
        print("Delta is already in-place format; copied unchanged.")
        return

    t0 = time.time()
    commands = unplace_commands(placed)
    ip_placed = make_inplace(R, commands, policy=args.policy)
    elapsed = time.time() - t0

    # Preserve the original src_crc and dst_crc from the input delta.
    ip_delta = encode_delta_large(ip_placed, inplace=True, version_size=version_size,
                                  src_crc=src_crc, dst_crc=dst_crc)
    with open(args.delta_out, 'wb') as f:
        f.write(ip_delta)

    stats = placed_summary(ip_placed)
    print(f"Reference:    {args.reference} ({len(R):,} bytes)")
    print(f"Input delta:  {args.delta_in} ({len(delta_bytes):,} bytes)")
    print(f"Output delta: {args.delta_out} ({len(ip_delta):,} bytes)")
    print(f"Format:       in-place ({args.policy})")
    print(f"Commands:     {stats['num_copies']} copies, {stats['num_adds']} adds")
    print(f"Copy bytes:   {stats['copy_bytes']:,}")
    print(f"Add bytes:    {stats['add_bytes']:,}")
    print(f"Time:         {elapsed:.3f}s")


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
    enc.add_argument('--max-table', type=_parse_size_suffix, default=MAX_TABLE_SIZE,
                     metavar='N', help='Max hash table size (k/M/B suffix: 512M, 2B)')
    enc.add_argument('--inplace', action='store_true',
                     help='Produce in-place reconstructible delta')
    enc.add_argument('--policy', choices=['localmin', 'constant'],
                     default='localmin',
                     help='Cycle-breaking policy for --inplace (default: localmin)')
    enc.add_argument('--verbose', action='store_true',
                     help='Print diagnostic messages to stderr')
    enc.set_defaults(func=cmd_encode)

    # decode
    dec = sub.add_parser('decode', help='Reconstruct version from delta')
    dec.add_argument('reference', help='Reference file')
    dec.add_argument('delta', help='Delta file')
    dec.add_argument('output', help='Output (reconstructed version) file')
    dec.add_argument('--ignore-hash', action='store_true',
                     help='Skip hash verification (for partial recovery)')
    dec.set_defaults(func=cmd_decode)

    # info
    inf = sub.add_parser('info', help='Show delta file statistics')
    inf.add_argument('delta', help='Delta file')
    inf.set_defaults(func=cmd_info)

    # inplace
    inp = sub.add_parser('inplace',
                         help='Convert standard delta to in-place delta')
    inp.add_argument('reference', help='Reference file')
    inp.add_argument('delta_in', help='Input (standard) delta file')
    inp.add_argument('delta_out', help='Output (in-place) delta file')
    inp.add_argument('--policy', choices=['localmin', 'constant'],
                     default='localmin',
                     help='Cycle-breaking policy (default: localmin)')
    inp.set_defaults(func=cmd_inplace)

    args = ap.parse_args()
    if args.command is None:
        ap.print_help()
        sys.exit(1)
    args.func(args)


# ============================================================================

if __name__ == '__main__':
    main()
