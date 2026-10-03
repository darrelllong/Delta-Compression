#!/usr/bin/env python3
"""Differential compression with in-place reconstruction.

The differencing algorithms are from

  M. Ajtai, R. Burns, R. Fagin, D.D.E. Long, and L. Stockmeyer,
  "Compactly Encoding Unstructured Inputs with Differential Compression,"
  Journal of the ACM 49(3):318-367, May 2002,

and the in-place conversion from

  R.C. Burns, D.D.E. Long, and L. Stockmeyer,
  "In-Place Reconstruction of Version Differences,"
  IEEE Transactions on Knowledge and Data Engineering 15(4):973-984, 2003.

Section, figure and page numbers in this file refer to the first paper
unless they say otherwise.  R is the reference string and V the version.

Usage:
  python3 delta.py encode  <algorithm> <reference> <version> <delta>
  python3 delta.py decode  <reference> <delta> <output>
  python3 delta.py info    <delta>
  python3 delta.py inplace <reference> <delta_in> <delta_out>
"""

from __future__ import annotations

import argparse
import bisect
import heapq
import mmap
import os
import struct
import sys
import time
from collections import defaultdict, deque
from contextlib import contextmanager
from dataclasses import dataclass
from typing import Optional, Union


# Commands as the differencing algorithms produce them (Section 2.1.1):
# the output position of each is implied by the commands before it.

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


# Placed commands carry their destination, so they can be executed in any
# order that is safe.  These are what the delta file stores.

@dataclass
class PlacedCopy:
    """Copy length bytes from offset src of the reference to offset dst."""
    src: int
    dst: int
    length: int

    def __repr__(self):
        return f"COPY(src={self.src}, dst={self.dst}, len={self.length})"


@dataclass
class PlacedAdd:
    """Write literal bytes at offset dst."""
    dst: int
    data: bytes

    def __repr__(self):
        if len(self.data) <= 20:
            return f"ADD(dst={self.dst}, {self.data!r})"
        return f"ADD(dst={self.dst}, len={len(self.data)})"


@dataclass
class PlacedMove:
    """Copy length bytes from offset src of the output to offset dst.

    The decoder requires src + length <= dst, so a move reads only bytes
    already written.  DLT\\x04 only.
    """
    src: int
    dst: int
    length: int

    def __repr__(self):
        return f"MOVE(src={self.src}, dst={self.dst}, len={self.length})"


PlacedCommand = Union[PlacedCopy, PlacedAdd, PlacedMove]


# Karp-Rabin fingerprints (Section 2.1.3).  A seed is a substring of
# SEED_LEN bytes; its fingerprint is a polynomial in HASH_BASE modulo the
# Mersenne prime HASH_MOD:
#
#   F(X_r)     = (x_r b^(p-1) + x_(r+1) b^(p-2) + ... + x_(r+p-1)) mod Q  (Eq. 1)
#   F(X_(r+1)) = ((F(X_r) - x_r b^(p-1)) b + x_(r+p)) mod Q               (Eq. 2)
#
# The full 61-bit fingerprint identifies a seed; a second reduction, modulo
# the table size, chooses its slot.

SEED_LEN = 16
TABLE_SIZE = 1048573            # largest prime below 2^20
MAX_TABLE_SIZE = 1_073_741_827  # smallest prime above 2^30
HASH_BASE = 263                 # prime; with 256 the low bits would depend on the last byte only
HASH_MOD = (1 << 61) - 1
DELTA_BUF_CAP = 256


@dataclass
class DiffOptions:
    """Tuning parameters for the differencing algorithms."""
    p: int = SEED_LEN                # seed length: fingerprint window and shortest match
    q: int = TABLE_SIZE              # hash table size floor; tables grow with the input
    buf_cap: int = DELTA_BUF_CAP     # commands the correcting algorithm can still revise (Section 5.2)
    verbose: bool = False            # print statistics to stderr
    max_table: int = MAX_TABLE_SIZE  # hash table size ceiling (correcting only)


def _get_d_r(n: int) -> tuple:
    """Return (d, r) with n == d * 2**r and d odd."""
    r = 0
    while n % 2 == 0:
        n //= 2
        r += 1
    return (n, r)


def _witness(a: int, n: int) -> bool:
    """Report whether a proves n composite (the Miller-Rabin witness test)."""
    d, r = _get_d_r(n - 1)
    x = pow(a, d, n)
    for _ in range(r):
        y = pow(x, 2, n)
        if y == 1 and x != 1 and x != n - 1:
            return True
        x = y
    return x != 1


# With these witnesses Miller-Rabin is deterministic for all
# n < 318,665,857,834,031,151,167,461 (Sorenson and Webster, Math. Comp.
# 86(304), 2017).
_MR_WITNESSES = (2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37)


def _is_prime(n: int) -> bool:
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
    """Return the smallest prime >= n."""
    if n <= 2:
        return 2
    if n % 2 == 0:
        n += 1
    while not _is_prime(n):
        n += 2
    return n


def _fingerprint(data: bytes, offset: int, p: int) -> int:
    """Return the fingerprint of data[offset : offset+p] (Eq. 1)."""
    h = 0
    for i in range(offset, offset + p):
        h = (h * HASH_BASE + data[i]) % HASH_MOD
    return h


def _fingerprints(data: bytes, p: int):
    """Yield the fingerprint of every p-byte window of data, in offset order."""
    if len(data) < p:
        return
    bp = pow(HASH_BASE, p - 1, HASH_MOD)
    fp = _fingerprint(data, 0, p)
    yield fp
    for a in range(len(data) - p):
        fp = ((fp - data[a] * bp) * HASH_BASE + data[a + p]) % HASH_MOD
        yield fp


class _RollingHash:
    """Fingerprints of the p-byte windows of data, for a scan that mostly
    advances one byte at a time but jumps past each match.

    A step of one byte costs O(1) (Eq. 2); any other move costs O(p).
    """
    __slots__ = ('value', '_data', '_p', '_bp', '_pos')

    def __init__(self, data: bytes, p: int):
        self._data = data
        self._p = p
        self._bp = pow(HASH_BASE, p - 1, HASH_MOD)
        self._pos = -2      # no window yet: neither at nor just before any offset
        self.value = 0

    def at(self, pos: int) -> int:
        """Return the fingerprint of data[pos : pos+p]."""
        last = self._pos
        if pos == last + 1:
            data = self._data
            self.value = ((self.value - data[last] * self._bp) * HASH_BASE
                          + data[last + self._p]) % HASH_MOD
        elif pos != last:
            self.value = _fingerprint(self._data, pos, self._p)
        self._pos = pos
        return self.value


# Match extension compares slices, which run at memcmp speed, rather than
# bytes.  The compared length doubles until a slice differs, then the
# mismatch is found by bisection, so the cost is O(match length) in bytes
# and O(log) in interpreter steps.

def _common_prefix(a, i: int, b, j: int, limit: int) -> int:
    """Return the largest n <= limit with a[i : i+n] == b[j : j+n]."""
    lo, step = 0, 64
    while lo < limit:
        hi = min(lo + step, limit)
        if a[i + lo:i + hi] != b[j + lo:j + hi]:
            break
        lo = hi
        step *= 2
    else:
        return limit
    while hi - lo > 1:
        mid = (lo + hi) >> 1
        if a[i + lo:i + mid] == b[j + lo:j + mid]:
            lo = mid
        else:
            hi = mid
    return lo


def _common_suffix(a, i: int, b, j: int, limit: int) -> int:
    """Return the largest n <= limit with a[i-n : i] == b[j-n : j]."""
    lo, step = 0, 64
    while lo < limit:
        hi = min(lo + step, limit)
        if a[i - hi:i - lo] != b[j - hi:j - lo]:
            break
        lo = hi
        step *= 2
    else:
        return limit
    while hi - lo > 1:
        mid = (lo + hi) >> 1
        if a[i - mid:i - lo] == b[j - mid:j - lo]:
            lo = mid
        else:
            hi = mid
    return lo


def _print_command_stats(commands: list[Command]) -> None:
    copy_lens = sorted(c.length for c in commands if isinstance(c, CopyCmd))
    add_lens = [len(c.data) for c in commands if isinstance(c, AddCmd)]
    total_copy = sum(copy_lens)
    total_add = sum(add_lens)
    total_out = total_copy + total_add
    copy_pct = total_copy / total_out * 100 if total_out else 0
    print(f"  result: {len(copy_lens)} copies ({total_copy} bytes), "
          f"{len(add_lens)} adds ({total_add} bytes)\n"
          f"  result: copy coverage {copy_pct:.1f}%, output {total_out} bytes",
          file=sys.stderr)
    if copy_lens:
        mean = total_copy / len(copy_lens)
        median = copy_lens[len(copy_lens) // 2]
        print(f"  copies: {len(copy_lens)} regions, min={copy_lens[0]} "
              f"max={copy_lens[-1]} mean={mean:.1f} median={median} bytes",
              file=sys.stderr)


def diff_greedy(R: bytes, V: bytes,
                p: int = SEED_LEN, q: int = TABLE_SIZE,
                verbose: bool = False,
                opts: Optional[DiffOptions] = None) -> list[Command]:
    """Greedy algorithm (Section 3.1, Figure 2).

    Indexes every seed of R, and at each position of V takes the longest
    match among all offsets of R with the same fingerprint; ties go to the
    lowest offset.  Optimal under the simple cost measure when p <= 2
    (Section 3.3).  O(|V| * |R|) time in the worst case, O(|R|) space.

    q is unused: the index is a dict.  It is accepted so that the three
    algorithms can be called alike.
    """
    if opts is not None:
        p, verbose = opts.p, opts.verbose
    commands: list[Command] = []
    if not V:
        return commands

    offsets_in_r = defaultdict(list)
    for a, fp in enumerate(_fingerprints(R, p)):
        offsets_in_r[fp].append(a)

    if verbose:
        print(f"greedy: |R|={len(R):,}, |V|={len(V):,}, seed_len={p}",
              file=sys.stderr)

    nV, nR = len(V), len(R)
    hash_v = _RollingHash(V, p)
    v_c = 0     # scan position in V
    v_s = 0     # start of the part of V not yet encoded

    while v_c + p <= nV:
        best_len = 0
        best_r = 0
        for r_cand in offsets_in_r.get(hash_v.at(v_c), ()):
            ml = _common_prefix(V, v_c, R, r_cand, min(nV - v_c, nR - r_cand))
            if ml > best_len:
                best_len = ml
                best_r = r_cand

        # A fingerprint collision yields a match shorter than a seed.
        if best_len < p:
            v_c += 1
            continue

        if v_s < v_c:
            commands.append(AddCmd(data=V[v_s:v_c]))
        commands.append(CopyCmd(offset=best_r, length=best_len))
        v_c += best_len
        v_s = v_c

    if v_s < nV:
        commands.append(AddCmd(data=V[v_s:]))

    if verbose:
        _print_command_stats(commands)
    return commands


def diff_onepass(R: bytes, V: bytes,
                 p: int = SEED_LEN, q: int = TABLE_SIZE,
                 verbose: bool = False,
                 opts: Optional[DiffOptions] = None) -> list[Command]:
    """One-pass algorithm (Section 4.1, Figure 3).

    Scans R and V in step, entering the seed at each position in that
    string's hash table and looking it up in the other's.  A slot keeps the
    first seed that reaches it.  After a match both scans resume past it
    and both tables are flushed.  O(np + q) time and O(q) space
    (Section 4.2); blocks that appear in a different order in R and V are
    not matched (Section 4.3).

    The tables have next_prime(max(q, seeds in R // p)) slots.
    """
    if opts is not None:
        p, q, verbose = opts.p, opts.q, opts.verbose
    commands: list[Command] = []
    if not V:
        return commands

    nV, nR = len(V), len(R)
    num_seeds = max(0, nR - p + 1)
    q = _next_prime(max(q, num_seeds // p))

    if verbose:
        print(f"onepass: q={q:,}, |R|={len(R):,}, "
              f"|V|={len(V):,}, seed_len={p}",
              file=sys.stderr)

    # Each table is three parallel lists, which is faster here than a list
    # of records.  A slot is live only if its stamp equals ver, so
    # incrementing ver flushes both tables at once.
    v_fp, v_off, v_ver = [0] * q, [0] * q, [-1] * q
    r_fp, r_off, r_ver = [0] * q, [0] * q, [-1] * q
    ver = 0

    hash_v = _RollingHash(V, p)
    hash_r = _RollingHash(R, p)
    r_c = v_c = 0   # scan positions
    v_s = 0         # start of the part of V not yet encoded
    positions = lookups = matches = 0

    while v_c + p <= nV or r_c + p <= nR:
        positions += 1
        fp_v = hash_v.at(v_c) if v_c + p <= nV else None
        fp_r = hash_r.at(r_c) if r_c + p <= nR else None

        if fp_v is not None:
            iv = fp_v % q
            if v_ver[iv] != ver:
                v_fp[iv] = fp_v
                v_off[iv] = v_c
                v_ver[iv] = ver
        if fp_r is not None:
            ir = fp_r % q
            if r_ver[ir] != ver:
                r_fp[ir] = fp_r
                r_off[ir] = r_c
                r_ver[ir] = ver

        # Look up each seed in the other string's table.  Equal fingerprints
        # are confirmed by comparing the seeds.
        r_m = v_m = -1
        if fp_r is not None and v_ver[ir] == ver and v_fp[ir] == fp_r:
            lookups += 1
            cand = v_off[ir]
            if R[r_c:r_c + p] == V[cand:cand + p]:
                r_m, v_m = r_c, cand
        if r_m < 0 and fp_v is not None and r_ver[iv] == ver and r_fp[iv] == fp_v:
            lookups += 1
            cand = r_off[iv]
            if V[v_c:v_c + p] == R[cand:cand + p]:
                r_m, v_m = cand, v_c

        if r_m < 0:
            v_c += 1
            r_c += 1
            continue
        matches += 1

        ml = _common_prefix(V, v_m, R, r_m, min(nV - v_m, nR - r_m))
        if v_s < v_m:
            commands.append(AddCmd(data=V[v_s:v_m]))
        commands.append(CopyCmd(offset=r_m, length=ml))
        v_c = v_s = v_m + ml
        r_c = r_m + ml
        ver += 1

    if v_s < nV:
        commands.append(AddCmd(data=V[v_s:]))

    if verbose:
        hit_pct = matches / lookups * 100 if lookups else 0
        print(f"  scan: {positions:,} positions, {lookups:,} lookups, "
              f"{matches:,} matches (flushes)\n"
              f"  scan: hit rate {hit_pct:.1f}% (of lookups)",
              file=sys.stderr)
        _print_command_stats(commands)
    return commands


class _Tentative:
    """A command in the correcting algorithm's lookback buffer (Section 5.2),
    with the interval [v_start, v_end) of V that it encodes."""
    __slots__ = ('v_start', 'v_end', 'cmd')

    def __init__(self, v_start: int, v_end: int, cmd: Command):
        self.v_start = v_start
        self.v_end = v_end
        self.cmd = cmd


def diff_correcting(R: bytes, V: bytes,
                    p: int = SEED_LEN, q: int = TABLE_SIZE,
                    buf_cap: int = DELTA_BUF_CAP,
                    verbose: bool = False,
                    opts: Optional[DiffOptions] = None) -> list[Command]:
    """Correcting 1.5-pass algorithm (Section 7, Figure 8) with
    checkpointing (Section 8).

    The first pass indexes the seeds of R, keeping the first offset found
    for each fingerprint.  The second scans V, extends each match both
    forwards and backwards, and when a match reaches back into V already
    encoded, replaces the commands it covers (tail correction, Section 5.1).
    The last buf_cap commands are held back so that they can be replaced.

    Checkpointing (Section 8.1, pp. 347-348) bounds the table for any |R|.
    With |C| table slots and footprints f = fp mod |F|, where |F| is about
    twice the number of seeds in R, only seeds with f mod m == k are stored
    or looked up, m = ceil(|F| / |C|); such a seed belongs in slot f // m.
    Backward extension recovers the part of a match that precedes its first
    checkpoint seed (Section 8.2, p. 349).

    |C| = next_prime(min(max_table, max(q, 2 * seeds in R // p))), which
    makes m about p when neither q nor max_table sets the size.
    """
    max_table = MAX_TABLE_SIZE
    if opts is not None:
        p, q, buf_cap, verbose = opts.p, opts.q, opts.buf_cap, opts.verbose
        max_table = opts.max_table
    commands: list[Command] = []
    if not V:
        return commands

    nV, nR = len(V), len(R)
    num_seeds = max(0, nR - p + 1)
    C = _next_prime(min(max_table, max(q, 2 * num_seeds // p)))
    F = _next_prime(2 * num_seeds) if num_seeds > 0 else 1
    m = max(1, -(-F // C))
    # k is the class of a seed of V, so that at least that seed is a
    # checkpoint (p. 348).  The paper picks the seed at random; the one in
    # the middle of V keeps the output deterministic.
    k = _fingerprint(V, min(nV // 2, nV - p), p) % F % m if nV >= p else 0

    if verbose:
        expected = num_seeds // m
        print(f"correcting: |C|={C} |F|={F} m={m} k={k}\n"
              f"  checkpoint gap={m} bytes, "
              f"expected fill ~{expected} "
              f"(~{expected * 100 // C}% table occupancy)\n"
              f"  table memory ~{C * 24 // 1048576} MB",
              file=sys.stderr)

    # Build the table: open addressing with linear probing.  A slot holds
    # (fingerprint, offset in R) or None.
    table: list = [None] * C
    passed = stored = probes = 0
    for a, fp in enumerate(_fingerprints(R, p)):
        f = fp % F
        if f % m != k:
            continue
        passed += 1
        i = i0 = f // m
        while table[i] is not None and table[i][0] != fp:
            i = (i + 1) % C
            probes += 1
            if i == i0:     # the table is full
                break
        if table[i] is None:
            table[i] = (fp, a)
            stored += 1

    if verbose:
        passed_pct = passed / num_seeds * 100 if num_seeds else 0
        print(f"  build: {num_seeds} seeds, {passed} passed "
              f"checkpoint ({passed_pct:.2f}%), "
              f"{stored} stored, {probes} extra probes\n"
              f"  build: table occupancy {stored}/{C} ({stored / C * 100:.1f}%)",
              file=sys.stderr)

    buf: deque = deque()

    def emit(v_start, v_end, cmd):
        if buf and len(buf) >= buf_cap:
            commands.append(buf.popleft().cmd)
        buf.append(_Tentative(v_start, v_end, cmd))

    hash_v = _RollingHash(V, p)
    v_c = 0     # scan position in V
    v_s = 0     # start of the part of V not yet encoded
    checkpoints = matches = byte_mismatches = 0

    while v_c + p <= nV:
        fp_v = hash_v.at(v_c)
        f = fp_v % F
        if f % m != k:
            v_c += 1
            continue
        checkpoints += 1

        i = i0 = f // m
        entry = table[i]
        while entry is not None and entry[0] != fp_v:
            i = (i + 1) % C
            entry = table[i] if i != i0 else None
        if entry is None:
            v_c += 1
            continue
        r_offset = entry[1]
        if R[r_offset:r_offset + p] != V[v_c:v_c + p]:
            byte_mismatches += 1
            v_c += 1
            continue
        matches += 1

        fwd = _common_prefix(V, v_c, R, r_offset, min(nV - v_c, nR - r_offset))
        bwd = _common_suffix(V, v_c, R, r_offset, min(v_c, r_offset))
        v_m = v_c - bwd
        r_m = r_offset - bwd
        match_end = v_c + fwd

        if v_s <= v_m:
            # The match lies wholly in the part of V not yet encoded.
            if v_s < v_m:
                emit(v_s, v_m, AddCmd(data=V[v_s:v_m]))
            emit(v_m, match_end, CopyCmd(offset=r_m, length=match_end - v_m))
        else:
            # The match reaches back into encoded V.  Take over what the
            # tail of the buffer encodes there (Section 5.1, p. 339): a
            # command wholly inside the match is dropped and an add that
            # straddles its start is cut short.  A copy that straddles it
            # is left alone, as is everything before it.
            start = v_s
            while buf:
                tail = buf[-1]
                if tail.v_start >= v_m and tail.v_end <= match_end:
                    start = min(start, tail.v_start)
                    buf.pop()
                    continue
                if tail.v_start < v_m < tail.v_end and isinstance(tail.cmd, AddCmd):
                    tail.cmd = AddCmd(data=V[tail.v_start:v_m])
                    tail.v_end = v_m
                    start = min(start, v_m)
                break
            if start < match_end:
                emit(start, match_end,
                     CopyCmd(offset=r_m + (start - v_m), length=match_end - start))
        v_c = v_s = match_end

    commands.extend(e.cmd for e in buf)
    if v_s < nV:
        commands.append(AddCmd(data=V[v_s:]))

    if verbose:
        v_seeds = max(0, nV - p + 1)
        cp_pct = checkpoints / v_seeds * 100 if v_seeds else 0
        hit_pct = matches / checkpoints * 100 if checkpoints else 0
        # The probe compares full fingerprints, so it has no collisions to
        # count; the field is kept for the other implementations' format.
        print(f"  scan: {v_seeds} V positions, {checkpoints} checkpoints "
              f"({cp_pct:.3f}%), {matches} matches\n"
              f"  scan: hit rate {hit_pct:.1f}% (of checkpoints), "
              f"fp collisions 0, "
              f"byte mismatches {byte_mismatches}",
              file=sys.stderr)
        _print_command_stats(commands)
    return commands


ALGORITHMS = {
    'greedy': diff_greedy,
    'onepass': diff_onepass,
    'correcting': diff_correcting,
}


def output_size(commands: list[Command]) -> int:
    """Return the length of the version the commands produce."""
    return sum(cmd.length if isinstance(cmd, CopyCmd) else len(cmd.data)
               for cmd in commands)


def place_commands(commands: list[Command]) -> list[PlacedCommand]:
    """Give each command its destination: the commands write V left to right."""
    placed: list[PlacedCommand] = []
    dst = 0
    for cmd in commands:
        if isinstance(cmd, CopyCmd):
            placed.append(PlacedCopy(src=cmd.offset, dst=dst, length=cmd.length))
            dst += cmd.length
        else:
            placed.append(PlacedAdd(dst=dst, data=cmd.data))
            dst += len(cmd.data)
    return placed


def unplace_commands(placed: list[PlacedCommand]) -> list[Command]:
    """Return the copies and adds in destination order, without destinations.

    Moves are dropped: the algorithm commands have no form for them.
    """
    commands: list[Command] = []
    for cmd in sorted(placed, key=lambda c: c.dst):
        if isinstance(cmd, PlacedCopy):
            commands.append(CopyCmd(offset=cmd.src, length=cmd.length))
        elif isinstance(cmd, PlacedAdd):
            commands.append(AddCmd(data=cmd.data))
    return commands


# Delta file format.  All integers are big-endian.
#
# Header:
#   magic         4 bytes  b'DLT\x03' or b'DLT\x04'
#   flags         1 byte   bit 0: in-place
#   version size  u32 (DLT\x03) or u64 (DLT\x04)
#   src crc       8 bytes  CRC-64/XZ of the reference
#   dst crc       8 bytes  CRC-64/XZ of the version
#
# Commands, in execution order, each a type byte and then its fields:
#   0 END
#   1 COPY     src:u32 dst:u32 len:u32
#   2 ADD      dst:u32 len:u32 data
#   3 BIGCOPY  src:u64 dst:u64 len:u64      DLT\x04 only
#   4 BIGADD   dst:u64 len:u64 data         DLT\x04 only
#   5 MOVE     src:u32 dst:u32 len:u32      DLT\x04 only
#   6 BIGMOVE  src:u64 dst:u64 len:u64      DLT\x04 only
#
# COPY reads the reference; MOVE reads the output written so far.

DELTA_MAGIC = b'DLT\x03'
DELTA_MAGIC_LARGE = b'DLT\x04'
DELTA_FLAG_INPLACE = 0x01
DELTA_CMD_END = 0
DELTA_CMD_COPY = 1
DELTA_CMD_ADD = 2
DELTA_CMD_BIGCOPY = 3
DELTA_CMD_BIGADD = 4
DELTA_CMD_MOVE = 5
DELTA_CMD_BIGMOVE = 6
DELTA_CRC_SIZE = 8
DELTA_U32_SIZE = 4
DELTA_U64_SIZE = 8
DELTA_HEADER_SIZE = 25          # magic(4) + flags(1) + version size(4) + crcs(16)
DELTA_HEADER_SIZE_LARGE = 29    # magic(4) + flags(1) + version size(8) + crcs(16)
DELTA_COPY_PAYLOAD = 12         # src(4) + dst(4) + len(4)
DELTA_ADD_HEADER = 8            # dst(4) + len(4)
DELTA_BIGCOPY_PAYLOAD = 24      # src(8) + dst(8) + len(8)
DELTA_BIGADD_HEADER = 16        # dst(8) + len(8)
_U32_MAX = 0xFFFFFFFF

_COPY_FIELDS = struct.Struct('>III')
_ADD_FIELDS = struct.Struct('>II')
_BIGCOPY_FIELDS = struct.Struct('>QQQ')
_BIGADD_FIELDS = struct.Struct('>QQ')

# type byte -> (name for error messages, field layout, placed class);
# these commands are src, dst, len with no payload.
_THREE_FIELD = {
    DELTA_CMD_COPY:    ('COPY', _COPY_FIELDS, PlacedCopy),
    DELTA_CMD_BIGCOPY: ('BIGCOPY', _BIGCOPY_FIELDS, PlacedCopy),
    DELTA_CMD_MOVE:    ('MOVE', _COPY_FIELDS, PlacedMove),
    DELTA_CMD_BIGMOVE: ('BIGMOVE', _BIGCOPY_FIELDS, PlacedMove),
}
_ADDS = {
    DELTA_CMD_ADD:    ('ADD', _ADD_FIELDS),
    DELTA_CMD_BIGADD: ('BIGADD', _BIGADD_FIELDS),
}
_LARGE_ONLY = (DELTA_CMD_BIGCOPY, DELTA_CMD_BIGADD,
               DELTA_CMD_MOVE, DELTA_CMD_BIGMOVE)


def _make_crc64_table():
    poly = 0xC96C5795D7870F42   # ECMA-182, reflected
    table = []
    for crc in range(256):
        for _ in range(8):
            crc = (crc >> 1) ^ poly if crc & 1 else crc >> 1
        table.append(crc)
    return table


_CRC64_TABLE = _make_crc64_table()


def _crc64_xz(data: bytes) -> bytes:
    """Return the CRC-64/XZ of data as 8 big-endian bytes.

    The CRC of b"123456789" is 0x995DC9BBDF1939FA.
    """
    table = _CRC64_TABLE
    crc = 0xFFFFFFFFFFFFFFFF
    for b in data:
        crc = table[(crc ^ b) & 0xFF] ^ (crc >> 8)
    return (crc ^ 0xFFFFFFFFFFFFFFFF).to_bytes(8, 'big')


def encode_delta(commands: list[PlacedCommand], *,
                 inplace: bool = False, version_size: int,
                 src_crc: bytes, dst_crc: bytes) -> bytes:
    """Encode placed commands as DLT\\x03, whose fields are all u32.

    src_crc and dst_crc are DELTA_CRC_SIZE bytes each.  Raises ValueError
    for a PlacedMove, which only DLT\\x04 can express.
    """
    out = bytearray(DELTA_MAGIC)
    out.append(DELTA_FLAG_INPLACE if inplace else 0)
    out += struct.pack('>I', version_size)
    out += src_crc
    out += dst_crc
    for cmd in commands:
        if isinstance(cmd, PlacedCopy):
            out.append(DELTA_CMD_COPY)
            out += _COPY_FIELDS.pack(cmd.src, cmd.dst, cmd.length)
        elif isinstance(cmd, PlacedAdd):
            out.append(DELTA_CMD_ADD)
            out += _ADD_FIELDS.pack(cmd.dst, len(cmd.data))
            out += cmd.data
        elif isinstance(cmd, PlacedMove):
            raise ValueError("PlacedMove requires encode_delta_large")
    out.append(DELTA_CMD_END)
    return bytes(out)


def encode_delta_large(commands: list[PlacedCommand], *,
                       inplace: bool = False, version_size: int,
                       src_crc: bytes, dst_crc: bytes,
                       force_large: bool = False) -> bytes:
    """Encode placed commands as DLT\\x04.

    Each command takes its u32 form if all its fields fit and force_large
    is false, and its u64 form otherwise.
    """
    out = bytearray(DELTA_MAGIC_LARGE)
    out.append(DELTA_FLAG_INPLACE if inplace else 0)
    out += struct.pack('>Q', version_size)
    out += src_crc
    out += dst_crc
    for cmd in commands:
        if isinstance(cmd, PlacedAdd):
            fields = (cmd.dst, len(cmd.data))
            if force_large or max(fields) > _U32_MAX:
                out.append(DELTA_CMD_BIGADD)
                out += _BIGADD_FIELDS.pack(*fields)
            else:
                out.append(DELTA_CMD_ADD)
                out += _ADD_FIELDS.pack(*fields)
            out += cmd.data
        else:
            if isinstance(cmd, PlacedCopy):
                small, big = DELTA_CMD_COPY, DELTA_CMD_BIGCOPY
            else:
                small, big = DELTA_CMD_MOVE, DELTA_CMD_BIGMOVE
            fields = (cmd.src, cmd.dst, cmd.length)
            if force_large or max(fields) > _U32_MAX:
                out.append(big)
                out += _BIGCOPY_FIELDS.pack(*fields)
            else:
                out.append(small)
                out += _COPY_FIELDS.pack(*fields)
    out.append(DELTA_CMD_END)
    return bytes(out)


def decode_delta(data: bytes):
    """Decode a DLT\\x03 or DLT\\x04 delta.

    Returns (commands, inplace, version_size, src_crc, dst_crc).  Raises
    ValueError if the delta is malformed.  The CRCs are returned, not
    checked: the caller has the files.
    """
    magic = bytes(data[:4])
    if magic == DELTA_MAGIC:
        large, header_size, size_format = False, DELTA_HEADER_SIZE, '>I'
    elif magic == DELTA_MAGIC_LARGE:
        large, header_size, size_format = True, DELTA_HEADER_SIZE_LARGE, '>Q'
    else:
        raise ValueError("Not a delta file")
    if len(data) < header_size:
        raise ValueError("Not a delta file")

    inplace = bool(data[4] & DELTA_FLAG_INPLACE)
    version_size = struct.unpack_from(size_format, data, 5)[0]
    crcs = header_size - 2 * DELTA_CRC_SIZE
    src_crc = bytes(data[crcs:crcs + DELTA_CRC_SIZE])
    dst_crc = bytes(data[crcs + DELTA_CRC_SIZE:header_size])
    commands = _decode_commands(data, header_size, version_size, large)
    return commands, inplace, version_size, src_crc, dst_crc


def _decode_commands(data: bytes, pos: int, version_size: int,
                     large: bool) -> list[PlacedCommand]:
    """Parse the command stream that runs from data[pos] to the end of data."""
    commands: list[PlacedCommand] = []
    end = len(data)
    while pos < end:
        t = data[pos]
        pos += 1
        if t == DELTA_CMD_END:
            if pos != end:
                raise ValueError("Trailing data after END")
            return commands
        if not large and t in _LARGE_ONLY:
            raise ValueError(f"Command type {t} requires DLT\\x04 format")

        if t in _THREE_FIELD:
            name, fields, placed = _THREE_FIELD[t]
            if pos + fields.size > end:
                raise ValueError(f"Truncated {name} command")
            src, dst, length = fields.unpack_from(data, pos)
            pos += fields.size
            if placed is PlacedMove and src + length > dst:
                raise ValueError(f"{name} src overlaps dst (not yet written)")
            if dst + length > version_size:
                raise ValueError(f"{name} extends past version size")
            commands.append(placed(src=src, dst=dst, length=length))
        elif t in _ADDS:
            name, fields = _ADDS[t]
            if pos + fields.size > end:
                raise ValueError(f"Truncated {name} command header")
            dst, length = fields.unpack_from(data, pos)
            pos += fields.size
            if pos + length > end:
                raise ValueError(f"Truncated {name} payload")
            if dst + length > version_size:
                raise ValueError(f"{name} extends past version size")
            commands.append(PlacedAdd(dst=dst, data=data[pos:pos + length]))
            pos += length
        else:
            raise ValueError(f"Unknown command type: {t}")
    raise ValueError("Missing END command")


def is_inplace_delta(data: bytes) -> bool:
    """Report whether data begins with the header of an in-place delta."""
    return (len(data) >= 5
            and data[:4] in (DELTA_MAGIC, DELTA_MAGIC_LARGE)
            and bool(data[4] & DELTA_FLAG_INPLACE))


def _placed_length(cmd: PlacedCommand) -> int:
    return len(cmd.data) if isinstance(cmd, PlacedAdd) else cmd.length


def apply_placed_to(R, commands: list[PlacedCommand], buf) -> int:
    """Execute placed commands, reading R and writing buf.

    Returns the end of the highest write.
    """
    written = 0
    for cmd in commands:
        dst = cmd.dst
        if isinstance(cmd, PlacedAdd):
            end = dst + len(cmd.data)
            buf[dst:end] = cmd.data
        else:
            end = dst + cmd.length
            source = R if isinstance(cmd, PlacedCopy) else buf
            buf[dst:end] = source[cmd.src:cmd.src + cmd.length]
        if end > written:
            written = end
    return written


def apply_placed_inplace_to(commands: list[PlacedCommand], buf) -> None:
    """Execute placed commands in buf, which holds the reference.

    A copy whose source and destination overlap is safe: the source slice
    is taken before the assignment.
    """
    for cmd in commands:
        dst = cmd.dst
        if isinstance(cmd, PlacedAdd):
            buf[dst:dst + len(cmd.data)] = cmd.data
        else:
            buf[dst:dst + cmd.length] = buf[cmd.src:cmd.src + cmd.length]


def apply_placed(R, commands: list[PlacedCommand]) -> bytes:
    """Return the version that placed commands build from R."""
    buf = bytearray(sum(_placed_length(cmd) for cmd in commands))
    apply_placed_to(R, commands, buf)
    return bytes(buf)


def apply_placed_inplace(R, commands: list[PlacedCommand],
                         version_size: int) -> bytes:
    """Return the version that in-place commands build in a copy of R."""
    buf = bytearray(max(len(R), version_size))
    buf[:len(R)] = R
    apply_placed_inplace_to(commands, buf)
    return bytes(buf[:version_size])


def apply_delta_to(R, commands: list[Command], buf) -> int:
    """Execute algorithm commands, writing buf from offset 0.

    Returns the number of bytes written.
    """
    pos = 0
    for cmd in commands:
        if isinstance(cmd, AddCmd):
            end = pos + len(cmd.data)
            buf[pos:end] = cmd.data
        else:
            end = pos + cmd.length
            buf[pos:end] = R[cmd.offset:cmd.offset + cmd.length]
        pos = end
    return pos


def apply_delta(R, commands: list[Command]) -> bytes:
    """Return the version that algorithm commands build from R."""
    buf = bytearray(output_size(commands))
    apply_delta_to(R, commands, buf)
    return bytes(buf)


def apply_binary(R: bytes, delta: bytes) -> bytes:
    """Return the version that an encoded delta, of either kind, builds from R."""
    commands, inplace, version_size, _, _ = decode_delta(delta)
    if inplace:
        return apply_placed_inplace(R, commands, version_size)
    return apply_placed(R, commands)


# In-place conversion (Burns, Long and Stockmeyer 2003; section numbers
# below are that paper's).
#
# An in-place delta is applied in the buffer that holds the reference, so a
# copy must run before any other copy overwrites what it reads.  In the CRWI
# digraph (Section 4.2) the vertices are the copies and there is an edge
# i -> j when copy i reads bytes that copy j writes: i must run first.  A
# topological order of the graph is a safe schedule.  Where the graph has a
# cycle there is none, and one copy on the cycle is replaced by an add of
# the bytes it would have copied, which reads nothing.  Adds run last.
#
# The topological sort is Kahn's, taking the ready copy of least
# (length, index) first so that the order is deterministic.  When no copy is
# ready, a cycle remains.  Policy 'constant' removes the lowest-numbered
# remaining copy; 'localmin' finds a cycle and removes its shortest copy,
# which costs the least compression (Section 4.3).

@dataclass(frozen=True)
class _Copy:
    src: int
    dst: int
    length: int


def _build_crwi_digraph(copies: list[_Copy]) -> list[list[int]]:
    """Return adjacency lists: adj[i] holds each j whose write interval
    intersects copy i's read interval.  O(n log n + edges).
    """
    n = len(copies)
    by_dst = sorted(range(n), key=lambda j: copies[j].dst)
    starts = [copies[j].dst for j in by_dst]

    adj: list[list[int]] = [[] for _ in range(n)]
    for i, c in enumerate(copies):
        # Write intervals are disjoint.  Those that start inside the read
        # interval intersect it; of those that start before it, only the
        # last can reach it.
        lo = bisect.bisect_left(starts, c.src)
        hi = bisect.bisect_left(starts, c.src + c.length)
        if lo > 0:
            j = by_dst[lo - 1]
            if j != i and copies[j].dst + copies[j].length > c.src:
                adj[i].append(j)
        adj[i].extend(j for j in by_dst[lo:hi] if j != i)
    return adj


def _tarjan_scc(adj: list[list[int]], n: int) -> list[list[int]]:
    """Return the strongly connected components, sinks first.

    Iterative, so that deep graphs do not exhaust the interpreter's stack.
    R.E. Tarjan, "Depth-first search and linear graph algorithms,"
    SIAM Journal on Computing 1(2):146-160, June 1972.
    """
    index = [-1] * n            # -1: not yet visited
    lowlink = [0] * n
    on_stack = [False] * n
    stack: list[int] = []
    sccs: list[list[int]] = []
    counter = 0

    for start in range(n):
        if index[start] != -1:
            continue
        index[start] = lowlink[start] = counter
        counter += 1
        on_stack[start] = True
        stack.append(start)
        # Each frame is a vertex and an iterator over its unexplored edges.
        frames = [(start, iter(adj[start]))]

        while frames:
            v, edges = frames[-1]
            for w in edges:
                if index[w] == -1:
                    index[w] = lowlink[w] = counter
                    counter += 1
                    on_stack[w] = True
                    stack.append(w)
                    frames.append((w, iter(adj[w])))
                    break
                if on_stack[w] and index[w] < lowlink[v]:
                    lowlink[v] = index[w]
            else:
                frames.pop()
                if frames:
                    parent = frames[-1][0]
                    if lowlink[v] < lowlink[parent]:
                        lowlink[parent] = lowlink[v]
                if lowlink[v] == index[v]:
                    scc = []
                    while True:
                        w = stack.pop()
                        on_stack[w] = False
                        scc.append(w)
                        if w == v:
                            break
                    sccs.append(scc)
    return sccs


# Depth-first search marks.
_UNVISITED, _ON_PATH, _DONE = 0, 1, 2


class _Cycles:
    """The components of the CRWI digraph that contain cycles, and the
    state of the search for cycles in them as copies are removed.

    The search is a depth-first search of one component at a time that
    resumes where it stopped, so that after a cycle is found only the
    path that led to it is searched again:

      - A vertex marked _DONE has no cycle reachable from it.  Removing
        vertices cannot create one, so the mark never has to be undone.
      - Within a component, the search for the next cycle starts from the
        vertex that the search for the last one started from.
    """
    def __init__(self, adj: list[list[int]], n: int):
        self.adj = adj
        self.sccs = [scc for scc in _tarjan_scc(adj, n) if len(scc) > 1]
        self.active = [len(scc) for scc in self.sccs]   # vertices not yet removed
        self.scc_of: list = [None] * n                  # None: in no cycle
        for sid, scc in enumerate(self.sccs):
            for v in scc:
                self.scc_of[v] = sid
        self.removed = [False] * n
        self._color = [_UNVISITED] * n
        self._sid = 0       # component being searched
        self._scan = 0      # next start vertex within it

    def remove(self, v: int) -> None:
        self.removed[v] = True
        sid = self.scc_of[v]
        if sid is not None:
            self.active[sid] -= 1

    def find(self) -> Optional[list[int]]:
        """Return the vertices of a cycle among those not removed, or None."""
        while self._sid < len(self.sccs):
            if self.active[self._sid] > 0:
                cycle = self._find_in_component()
                if cycle is not None:
                    return cycle
            self._sid += 1
            self._scan = 0
        return None

    def _find_in_component(self) -> Optional[list[int]]:
        adj, removed, color = self.adj, self.removed, self._color
        scc_of, sid = self.scc_of, self._sid
        scc = self.sccs[sid]

        while self._scan < len(scc):
            start = scc[self._scan]
            if removed[start] or color[start] != _UNVISITED:
                self._scan += 1
                continue

            color[start] = _ON_PATH
            path = [start]
            frames = [iter(adj[start])]     # frames[d] has the edges of path[d] left to explore
            while frames:
                for w in frames[-1]:
                    if scc_of[w] != sid or removed[w]:
                        continue
                    if color[w] == _ON_PATH:
                        # The vertices on the path are not finished: unmark
                        # them so that the next search can visit them again.
                        for u in path:
                            color[u] = _UNVISITED
                        return path[path.index(w):]
                    if color[w] == _UNVISITED:
                        color[w] = _ON_PATH
                        path.append(w)
                        frames.append(iter(adj[w]))
                        break
                else:
                    frames.pop()
                    color[path.pop()] = _DONE
            self._scan += 1
        return None


def _pick_victim(copies: list[_Copy], cycles: _Cycles, policy: str) -> int:
    """Choose the copy to turn into an add when no copy is ready."""
    if policy != 'constant':
        cycle = cycles.find()
        if cycle is not None:
            return min(cycle, key=lambda v: (copies[v].length, v))
        # Not reached: with no copy ready, the remaining ones contain a
        # cycle.  Removing any copy still gives a correct delta.
    return cycles.removed.index(False)


def _schedule(copies: list[_Copy], adj: list[list[int]], policy: str):
    """Order the copies so that each runs before its reads are overwritten.

    Returns (order, victims): the copies to keep, in execution order, and
    those that must become adds, in the order they were chosen.
    """
    n = len(copies)
    in_degree = [0] * n
    for successors in adj:
        for j in successors:
            in_degree[j] += 1

    cycles = _Cycles(adj, n)
    removed = cycles.removed
    ready = [(copies[i].length, i) for i in range(n) if in_degree[i] == 0]
    heapq.heapify(ready)

    def remove(v):
        cycles.remove(v)
        for w in adj[v]:
            if not removed[w]:
                in_degree[w] -= 1
                if in_degree[w] == 0:
                    heapq.heappush(ready, (copies[w].length, w))

    order: list[int] = []
    victims: list[int] = []
    while True:
        while ready:
            _, v = heapq.heappop(ready)
            order.append(v)
            remove(v)
        if len(order) + len(victims) == n:
            return order, victims
        victim = _pick_victim(copies, cycles, policy)
        victims.append(victim)
        remove(victim)


def make_inplace(R: bytes, commands: list[Command],
                 policy: str = 'localmin',
                 return_stats: bool = False):
    """Convert algorithm commands to placed commands that can run in place.

    Applied in order to a buffer that holds R, the result builds V there.
    policy is 'localmin' or 'constant'.  R supplies the bytes of each copy
    that has to become an add.

    Returns the placed commands, copies first; with return_stats, returns
    (commands, {'cycles_broken': n}).
    """
    copies: list[_Copy] = []
    adds: list[PlacedCommand] = []
    dst = 0
    for cmd in commands:
        if isinstance(cmd, CopyCmd):
            copies.append(_Copy(src=cmd.offset, dst=dst, length=cmd.length))
            dst += cmd.length
        else:
            adds.append(PlacedAdd(dst=dst, data=cmd.data))
            dst += len(cmd.data)

    order, victims = _schedule(copies, _build_crwi_digraph(copies), policy)

    result: list[PlacedCommand] = [
        PlacedCopy(src=copies[i].src, dst=copies[i].dst, length=copies[i].length)
        for i in order]
    result += adds
    for i in victims:
        c = copies[i]
        result.append(PlacedAdd(dst=c.dst, data=bytes(R[c.src:c.src + c.length])))

    if return_stats:
        return result, {'cycles_broken': len(victims)}
    return result


def _summary(copy_lens: list[int], add_lens: list[int], num_commands: int) -> dict:
    copy_bytes = sum(copy_lens)
    add_bytes = sum(add_lens)
    return {
        'num_commands': num_commands,
        'num_copies': len(copy_lens),
        'num_adds': len(add_lens),
        'copy_bytes': copy_bytes,
        'add_bytes': add_bytes,
        'total_output_bytes': copy_bytes + add_bytes,
    }


def delta_summary(commands: list[Command]) -> dict:
    """Return counts and byte totals of the copies and adds."""
    return _summary([c.length for c in commands if isinstance(c, CopyCmd)],
                    [len(c.data) for c in commands if isinstance(c, AddCmd)],
                    len(commands))


def placed_summary(commands: list[PlacedCommand]) -> dict:
    """Return counts and byte totals of the copies and adds.

    Moves count as commands but as neither copies nor adds.
    """
    return _summary([c.length for c in commands if isinstance(c, PlacedCopy)],
                    [len(c.data) for c in commands if isinstance(c, PlacedAdd)],
                    len(commands))


def _read_with_crc(path: str):
    """Return the contents of a file and their CRC-64/XZ."""
    with open(path, 'rb') as f:
        data = f.read()
    return data, _crc64_xz(data)


@contextmanager
def mmap_create(path, size):
    """Create a file of size bytes and yield a writable mapping of it.

    An empty file cannot be mapped; for size 0 an empty bytearray is
    yielded in its place.
    """
    with open(path, 'wb') as f:
        f.truncate(size)
    if size == 0:
        yield bytearray()
        return
    with open(path, 'r+b') as f:
        mm = mmap.mmap(f.fileno(), size)
        try:
            yield mm
        finally:
            mm.flush()
            mm.close()


def _parse_size_suffix(s: str) -> int:
    """Parse an integer with an optional decimal suffix: k, M, or B."""
    s = s.strip()
    if not s:
        raise argparse.ArgumentTypeError("empty size value")
    multipliers = {'k': 1_000, 'm': 1_000_000, 'b': 1_000_000_000}
    if s[-1].lower() in multipliers:
        return int(s[:-1]) * multipliers[s[-1].lower()]
    return int(s)


def cmd_encode(args):
    """Encode args.version against args.reference and write args.delta."""
    if args.seed_len < 1:
        raise SystemExit("error: --seed-len must be >= 1")
    opts = DiffOptions(p=args.seed_len, q=args.table_size,
                       verbose=args.verbose, max_table=args.max_table)

    R, src_crc = _read_with_crc(args.reference)
    V, dst_crc = _read_with_crc(args.version)

    t0 = time.time()
    commands = ALGORITHMS[args.algorithm](R, V, opts=opts)
    if args.inplace:
        placed, stats = make_inplace(R, commands, policy=args.policy,
                                     return_stats=True)
        cycles_broken = stats['cycles_broken']
    else:
        placed = place_commands(commands)
    elapsed = time.time() - t0

    delta = encode_delta_large(placed, inplace=args.inplace, version_size=len(V),
                               src_crc=src_crc, dst_crc=dst_crc,
                               force_large=args.large)
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
    """Rebuild the version from args.reference and args.delta.

    The reference is checked against the source CRC before the commands
    run and the output against the destination CRC after; --ignore-hash
    turns either failure into a warning.
    """
    R, r_crc = _read_with_crc(args.reference)
    with open(args.delta, 'rb') as f:
        delta_bytes = f.read()

    t0 = time.time()
    placed, inplace, version_size, src_crc, dst_crc = decode_delta(delta_bytes)

    if r_crc != src_crc:
        if not args.ignore_hash:
            raise SystemExit(
                f"error: source file does not match delta: "
                f"expected {src_crc.hex()}, got {r_crc.hex()}"
            )
        print("warning: skipping source CRC check (--ignore-hash)", file=sys.stderr)

    if inplace:
        # The buffer must hold all of R while the commands run.
        buf_size = max(len(R), version_size)
        with mmap_create(args.output, buf_size) as buf:
            buf[:len(R)] = R
            apply_placed_inplace_to(placed, buf)
            output_crc = _crc64_xz(buf[:version_size])
        if version_size < buf_size:
            os.truncate(args.output, version_size)
    else:
        with mmap_create(args.output, version_size) as buf:
            apply_placed_to(R, placed, buf)
            output_crc = _crc64_xz(buf[:version_size])
    elapsed = time.time() - t0

    if output_crc != dst_crc:
        if not args.ignore_hash:
            raise SystemExit("error: output integrity check failed")
        print("warning: skipping output CRC check (--ignore-hash)", file=sys.stderr)

    print(f"Format:       {'in-place' if inplace else 'standard'}")
    print(f"Reference:    {args.reference} ({len(R):,} bytes)")
    print(f"Delta:        {args.delta} ({len(delta_bytes):,} bytes)")
    print(f"Output:       {args.output} ({version_size:,} bytes)")
    print(f"Time:         {elapsed:.3f}s")


def cmd_info(args):
    """Print the header and command statistics of args.delta."""
    with open(args.delta, 'rb') as f:
        delta_bytes = f.read()
    placed, inplace, version_size, src_crc, dst_crc = decode_delta(delta_bytes)
    stats = placed_summary(placed)

    print(f"Delta file:   {args.delta} ({len(delta_bytes):,} bytes)")
    print(f"Format:       {'in-place' if inplace else 'standard'}")
    print(f"Version size: {version_size:,} bytes")
    print(f"Src CRC:      {src_crc.hex()}")
    print(f"Dst CRC:      {dst_crc.hex()}")
    print(f"Commands:     {stats['num_commands']}")
    print(f"  Copies:     {stats['num_copies']} ({stats['copy_bytes']:,} bytes)")
    print(f"  Adds:       {stats['num_adds']} ({stats['add_bytes']:,} bytes)")
    print(f"Output size:  {stats['total_output_bytes']:,} bytes")


def cmd_inplace(args):
    """Convert a standard delta to an in-place one.

    A delta that is already in-place is copied unchanged.
    """
    with open(args.reference, 'rb') as f:
        R = f.read()
    with open(args.delta_in, 'rb') as f:
        delta_bytes = f.read()
    placed, inplace, version_size, src_crc, dst_crc = decode_delta(delta_bytes)

    if inplace:
        with open(args.delta_out, 'wb') as f:
            f.write(delta_bytes)
        print("Delta is already in-place format; copied unchanged.")
        return

    t0 = time.time()
    ip_placed = make_inplace(R, unplace_commands(placed), policy=args.policy)
    elapsed = time.time() - t0

    # The CRCs describe the reference and the version, which the
    # conversion does not change.
    ip_delta = encode_delta_large(ip_placed, inplace=True, version_size=version_size,
                                  src_crc=src_crc, dst_crc=dst_crc,
                                  force_large=args.large)
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
    enc.add_argument('--large', action='store_true',
                     help='Force 64-bit (BIGCOPY/BIGADD/BIGMOVE) commands')
    enc.add_argument('--policy', choices=['localmin', 'constant'],
                     default='localmin',
                     help='Cycle-breaking policy for --inplace (default: localmin)')
    enc.add_argument('--verbose', action='store_true',
                     help='Print diagnostic messages to stderr')
    enc.set_defaults(func=cmd_encode)

    dec = sub.add_parser('decode', help='Reconstruct version from delta')
    dec.add_argument('reference', help='Reference file')
    dec.add_argument('delta', help='Delta file')
    dec.add_argument('output', help='Output (reconstructed version) file')
    dec.add_argument('--ignore-hash', action='store_true',
                     help='Skip hash verification (for partial recovery)')
    dec.set_defaults(func=cmd_decode)

    inf = sub.add_parser('info', help='Show delta file statistics')
    inf.add_argument('delta', help='Delta file')
    inf.set_defaults(func=cmd_info)

    inp = sub.add_parser('inplace',
                         help='Convert standard delta to in-place delta')
    inp.add_argument('reference', help='Reference file')
    inp.add_argument('delta_in', help='Input (standard) delta file')
    inp.add_argument('delta_out', help='Output (in-place) delta file')
    inp.add_argument('--policy', choices=['localmin', 'constant'],
                     default='localmin',
                     help='Cycle-breaking policy (default: localmin)')
    inp.add_argument('--large', action='store_true',
                     help='Force 64-bit (BIGCOPY/BIGADD/BIGMOVE) commands')
    inp.set_defaults(func=cmd_inplace)

    args = ap.parse_args()
    if args.command is None:
        ap.print_help()
        sys.exit(1)
    args.func(args)


if __name__ == '__main__':
    main()
