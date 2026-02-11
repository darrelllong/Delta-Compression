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
  - VCDIFF-style binary delta encoding (Appendix, Figure 12)
  - Delta reconstruction (apply delta to reference to recover version)

Usage:
  python delta.py encode  <algorithm> <reference> <version> <delta>
  python delta.py decode  <reference> <delta> <output>
  python delta.py info    <delta>
  python delta.py test
"""

import argparse
import struct
import sys
import time
from collections import defaultdict, deque
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


@dataclass
class InPlaceCopyCmd:
    """In-place: copy buf[src:src+length] to buf[dst:dst+length]."""
    src: int
    dst: int
    length: int

    def __repr__(self):
        return f"IPCOPY(src={self.src}, dst={self.dst}, len={self.length})"


@dataclass
class InPlaceAddCmd:
    """In-place: write literal bytes to buf[dst:dst+len(data)]."""
    dst: int
    data: bytes

    def __repr__(self):
        if len(self.data) <= 20:
            return f"IPADD(dst={self.dst}, {self.data!r})"
        return f"IPADD(dst={self.dst}, len={len(self.data)})"


InPlaceCommand = Union[InPlaceCopyCmd, InPlaceAddCmd]


# ============================================================================
# Karp-Rabin Rolling Hash (Section 2.1.3)
#
# We use a polynomial hash with a large Mersenne prime (2^61 - 1) to make
# collisions astronomically unlikely (~1 in 2^61 per comparison).  The full
# 61-bit fingerprint is used for collision-free seed comparison; a separate
# modular reduction maps fingerprints into the fixed-size hash table.
#
#   F(X_r) = (x_r * b^{p-1} + x_{r+1} * b^{p-2} + ... + x_{r+p-1}) mod Q
#   F(X_{r+1}) = ((F(X_r) - x_r * b^{p-1}) * b + x_{r+p}) mod Q
#
# Parameters:
#   p  = seed length (default 16 bytes, per Section 2.1.3)
#   Q  = 2^61 - 1 (Mersenne prime, for fingerprint computation)
#   TABLE_SIZE = hash table capacity (separate from Q)
#   b  = 263 (small prime base, better mixing than 256)
# ============================================================================

SEED_LEN = 16
TABLE_SIZE = 65521   # hash table capacity (largest prime < 2^16)
HASH_BASE = 263      # small prime, avoids b=256 which makes low bits depend only on last byte
HASH_MOD = (1 << 61) - 1  # Mersenne prime 2^61-1: ~2.3 * 10^18

# Precompute b^{p-1} mod Q for each seed length on first use
_bp_cache: dict = {}


def _get_bp(p: int) -> int:
    """Return HASH_BASE^{p-1} mod HASH_MOD, cached."""
    if p not in _bp_cache:
        _bp_cache[p] = pow(HASH_BASE, p - 1, HASH_MOD)
    return _bp_cache[p]


def _fingerprint(data: bytes, offset: int, p: int, _q_unused: int = 0) -> int:
    """Compute 61-bit Karp-Rabin fingerprint of data[offset:offset+p].

    The _q_unused parameter is accepted for API compatibility but ignored;
    the fingerprint is always computed mod 2^61-1.
    """
    h = 0
    for i in range(p):
        h = (h * HASH_BASE + data[offset + i]) % HASH_MOD
    return h


def _fp_to_index(fp: int, table_size: int) -> int:
    """Map a full fingerprint to a hash table index."""
    return fp % table_size


# ============================================================================
# Greedy Algorithm (Section 3.1, Figure 2)
#
# Finds an optimal delta encoding under the simple cost measure.
# Uses a chained hash table storing ALL offsets in R per footprint.
# Time: O(|V| * |R|) worst case.  Space: O(|R|).
# ============================================================================

def diff_greedy(R: bytes, V: bytes,
                p: int = SEED_LEN, q: int = TABLE_SIZE) -> List[Command]:
    commands: List[Command] = []
    if not V:
        return commands

    # Step (1): Build chained hash table for R
    H_R: dict = defaultdict(list)
    for a in range(max(0, len(R) - p + 1)):
        fp = _fingerprint(R, a, p, q)
        H_R[fp].append(a)

    # Step (2)
    v_c = 0
    v_s = 0

    while True:
        # Step (3)
        if v_c + p > len(V):
            break

        fp_v = _fingerprint(V, v_c, p, q)

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

        if best_len == 0:
            v_c += 1
            continue

        # Step (6): encode
        if v_s < v_c:
            commands.append(AddCmd(data=V[v_s:v_c]))
        commands.append(CopyCmd(offset=best_rm, length=best_len))
        v_s = v_c + best_len

        # Step (7)
        v_c = v_c + best_len

    # Step (8)
    if v_s < len(V):
        commands.append(AddCmd(data=V[v_s:]))

    return commands


# ============================================================================
# One-Pass Algorithm (Section 4.1, Figure 3)
#
# Scans R and V concurrently with two hash tables (one per string).
# Each table stores at most one offset per footprint (retain-existing).
# Hash tables are logically flushed after each match (next-match policy).
# Time: O(n) where n = |R|+|V|.  Space: O(q) (constant).
# ============================================================================

def diff_onepass(R: bytes, V: bytes,
                 p: int = SEED_LEN, q: int = TABLE_SIZE) -> List[Command]:
    commands: List[Command] = []
    if not V:
        return commands

    # Step (1): hash tables with version-based logical flushing.
    # Each slot stores (full_fingerprint, offset, version).
    # We index by fp % q, but compare full 61-bit fingerprints to avoid
    # collisions from the table-size reduction.
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

    # Step (2)
    r_c = 0
    v_c = 0
    v_s = 0

    while True:
        # Step (3)
        can_v = v_c + p <= len(V)
        can_r = r_c + p <= len(R)

        if not can_v and not can_r:
            break

        fp_v = _fingerprint(V, v_c, p) if can_v else None
        fp_r = _fingerprint(R, r_c, p) if can_r else None

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
            if v_cand is not None and R[r_c:r_c + p] == V[v_cand:v_cand + p]:
                r_m, v_m = r_c, v_cand
                match_found = True

        if not match_found and fp_v is not None:
            r_cand = hr_get(fp_v)
            if r_cand is not None and V[v_c:v_c + p] == R[r_cand:r_cand + p]:
                v_m, r_m = v_c, r_cand
                match_found = True

        if not match_found:
            v_c += 1
            r_c += 1
            continue

        # Step (5): extend match forward
        ml = 0
        while (v_m + ml < len(V) and r_m + ml < len(R)
               and V[v_m + ml] == R[r_m + ml]):
            ml += 1

        # Step (6): encode
        if v_s < v_m:
            commands.append(AddCmd(data=V[v_s:v_m]))
        commands.append(CopyCmd(offset=r_m, length=ml))
        v_s = v_m + ml

        # Step (7): advance pointers and flush tables
        v_c = v_m + ml
        r_c = r_m + ml
        ver += 1

    # Step (8)
    if v_s < len(V):
        commands.append(AddCmd(data=V[v_s:]))

    return commands


# ============================================================================
# Correcting 1.5-Pass Algorithm (Section 7, Figure 8)
#
# Pass 1: index the reference string (first-found policy, one entry per fp).
# Pass 2: scan V, extend matches both forwards AND backwards from the seed,
#          and use tail correction (Section 5.1) to fix suboptimal earlier
#          encodings via an encoding lookback buffer.
# Time: linear in practice.  Space: O(q + buffer_capacity).
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
                    buf_cap: int = 256) -> List[Command]:
    commands: List[Command] = []
    if not V:
        return commands

    # Step (1): build hash table for R (first-found policy)
    # Slot stores (full_fingerprint, offset) for collision-free lookup.
    H_R: list = [None] * q
    for a in range(max(0, len(R) - p + 1)):
        fp = _fingerprint(R, a, p)
        idx = fp % q
        if H_R[idx] is None:
            H_R[idx] = (fp, a)

    # Encoding lookback buffer (Section 5.2)
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

    # Step (2)
    v_c = 0
    v_s = 0

    while True:
        # Step (3)
        if v_c + p > len(V):
            break

        fp_v = _fingerprint(V, v_c, p)

        # Step (4): look up footprint in R's table
        idx = fp_v % q
        entry = H_R[idx]
        if entry is None or entry[0] != fp_v:
            v_c += 1
            continue
        r_cand = entry[1]
        if R[r_cand:r_cand + p] != V[v_c:v_c + p]:
            v_c += 1
            continue

        # Step (5): extend match forwards and backwards
        fwd = p
        while (v_c + fwd < len(V) and r_cand + fwd < len(R)
               and V[v_c + fwd] == R[r_cand + fwd]):
            fwd += 1

        bwd = 0
        while (v_c - bwd - 1 >= 0 and r_cand - bwd - 1 >= 0
               and V[v_c - bwd - 1] == R[r_cand - bwd - 1]):
            bwd += 1

        v_m = v_c - bwd
        r_m = r_cand - bwd
        ml = bwd + fwd
        match_end = v_m + ml

        # Step (6): encode with correction
        if v_s <= v_m:
            # (6a) match is entirely in unencoded suffix
            if v_s < v_m:
                buf_emit(v_s, v_m, AddCmd(data=V[v_s:v_m]))
            buf_emit(v_m, match_end, CopyCmd(offset=r_m, length=ml))
            v_s = match_end
        else:
            # (6b) match extends backward into encoded prefix — tail correction
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
                    # Partial copy — don't reclaim (Section 5.1)
                    break

                # No overlap with match
                break

            adj = effective_start - v_m
            new_len = match_end - effective_start
            if new_len > 0:
                buf_emit(effective_start, match_end,
                         CopyCmd(offset=r_m + adj, length=new_len))
            v_s = match_end

        # Step (7)
        v_c = match_end

    # Step (8)
    flush_all()
    if v_s < len(V):
        commands.append(AddCmd(data=V[v_s:]))

    return commands


# ============================================================================
# Binary Delta Encoding (Appendix, Figure 12)
#
# Codeword types (first byte k):
#   k = 0             END   — no more input
#   k in [1, 246]     ADD   — next k bytes are literal data
#   k = 247           ADD   — next 2 bytes = uint16 length, then data
#   k = 248           ADD   — next 4 bytes = uint32 length, then data
#   k = 249           COPY  — offset: uint16, length: uint8
#   k = 250           COPY  — offset: uint16, length: uint16
#   k = 251           COPY  — offset: uint16, length: uint32
#   k = 252           COPY  — offset: uint32, length: uint8
#   k = 253           COPY  — offset: uint32, length: uint16
#   k = 254           COPY  — offset: uint32, length: uint32
#   k = 255           COPY  — offset: uint64, length: uint32
#
# All multi-byte integers are big-endian.
# ============================================================================

def _encode_add(data: bytes) -> bytes:
    """Encode an ADD command (may emit multiple codewords for long data)."""
    out = bytearray()
    pos = 0
    while pos < len(data):
        remaining = len(data) - pos
        if remaining <= 246:
            out.append(remaining)
            out.extend(data[pos:pos + remaining])
            pos += remaining
        elif remaining <= 0xFFFF:
            out.append(247)
            out.extend(struct.pack('>H', remaining))
            out.extend(data[pos:pos + remaining])
            pos += remaining
        elif remaining <= 0xFFFFFFFF:
            chunk = min(remaining, 0xFFFFFFFF)
            out.append(248)
            out.extend(struct.pack('>I', chunk))
            out.extend(data[pos:pos + chunk])
            pos += chunk
        else:
            # Split into 4 GB chunks
            chunk = 0xFFFFFFFF
            out.append(248)
            out.extend(struct.pack('>I', chunk))
            out.extend(data[pos:pos + chunk])
            pos += chunk
    return bytes(out)


def _encode_copy(offset: int, length: int) -> bytes:
    """Encode a COPY command, choosing the most compact representation."""
    out = bytearray()

    if offset <= 0xFFFF:
        if length <= 0xFF:
            out.append(249)
            out.extend(struct.pack('>H', offset))
            out.append(length)
        elif length <= 0xFFFF:
            out.append(250)
            out.extend(struct.pack('>HH', offset, length))
        else:
            out.append(251)
            out.extend(struct.pack('>HI', offset, length))
    elif offset <= 0xFFFFFFFF:
        if length <= 0xFF:
            out.append(252)
            out.extend(struct.pack('>I', offset))
            out.append(length)
        elif length <= 0xFFFF:
            out.append(253)
            out.extend(struct.pack('>IH', offset, length))
        else:
            out.append(254)
            out.extend(struct.pack('>II', offset, length))
    else:
        out.append(255)
        out.extend(struct.pack('>QI', offset, length))

    return bytes(out)


def encode_delta(commands: List[Command]) -> bytes:
    """Encode a list of commands into the binary delta format."""
    out = bytearray()
    for cmd in commands:
        if isinstance(cmd, AddCmd):
            out.extend(_encode_add(cmd.data))
        elif isinstance(cmd, CopyCmd):
            out.extend(_encode_copy(cmd.offset, cmd.length))
    out.append(0)  # END codeword
    return bytes(out)


def decode_delta(data: bytes) -> List[Command]:
    """Decode binary delta format into a list of commands."""
    commands: List[Command] = []
    pos = 0

    while pos < len(data):
        k = data[pos]
        pos += 1

        if k == 0:
            break  # END

        elif 1 <= k <= 246:
            commands.append(AddCmd(data=data[pos:pos + k]))
            pos += k

        elif k == 247:
            length = struct.unpack_from('>H', data, pos)[0]
            pos += 2
            commands.append(AddCmd(data=data[pos:pos + length]))
            pos += length

        elif k == 248:
            length = struct.unpack_from('>I', data, pos)[0]
            pos += 4
            commands.append(AddCmd(data=data[pos:pos + length]))
            pos += length

        elif k == 249:
            offset = struct.unpack_from('>H', data, pos)[0]; pos += 2
            length = data[pos]; pos += 1
            commands.append(CopyCmd(offset=offset, length=length))

        elif k == 250:
            offset, length = struct.unpack_from('>HH', data, pos)
            pos += 4
            commands.append(CopyCmd(offset=offset, length=length))

        elif k == 251:
            offset = struct.unpack_from('>H', data, pos)[0]; pos += 2
            length = struct.unpack_from('>I', data, pos)[0]; pos += 4
            commands.append(CopyCmd(offset=offset, length=length))

        elif k == 252:
            offset = struct.unpack_from('>I', data, pos)[0]; pos += 4
            length = data[pos]; pos += 1
            commands.append(CopyCmd(offset=offset, length=length))

        elif k == 253:
            offset = struct.unpack_from('>I', data, pos)[0]; pos += 4
            length = struct.unpack_from('>H', data, pos)[0]; pos += 2
            commands.append(CopyCmd(offset=offset, length=length))

        elif k == 254:
            offset, length = struct.unpack_from('>II', data, pos)
            pos += 8
            commands.append(CopyCmd(offset=offset, length=length))

        elif k == 255:
            offset = struct.unpack_from('>Q', data, pos)[0]; pos += 8
            length = struct.unpack_from('>I', data, pos)[0]; pos += 4
            commands.append(CopyCmd(offset=offset, length=length))

    return commands


# ============================================================================
# Reconstruction — apply delta to reference to recover version
# ============================================================================

def apply_delta(R: bytes, commands: List[Command]) -> bytes:
    """Reconstruct the version string from reference + delta commands."""
    out = bytearray()
    for cmd in commands:
        if isinstance(cmd, AddCmd):
            out.extend(cmd.data)
        elif isinstance(cmd, CopyCmd):
            out.extend(R[cmd.offset:cmd.offset + cmd.length])
    return bytes(out)


def apply_delta_binary(R: bytes, delta: bytes) -> bytes:
    """Reconstruct from reference + binary delta."""
    return apply_delta(R, decode_delta(delta))


# ============================================================================
# In-Place Reconstruction (Burns, Long, Stockmeyer, IEEE TKDE 2003)
#
# Converts a standard delta encoding into one that can be applied in-place:
# the new version is reconstructed in the same buffer that holds the
# reference, without requiring scratch space.
#
# Algorithm (Section 4):
#   1. Annotate each command with its write offset in the output
#   2. Build CRWI digraph on copy commands — edge from i to j when i's
#      read interval overlaps j's write interval (i must execute before j)
#   3. Topological sort; break cycles by converting copies to adds
#   4. Output: copies in topological order, then all adds
#
# Cycle-breaking policies (Section 4.3):
#   - constant: pick any vertex when a cycle is detected (O(1) per break)
#   - localmin: pick the minimum-length vertex in the cycle (less compression loss)
# ============================================================================

INPLACE_MAGIC = b'IPD\x01'


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
                 policy: str = 'localmin') -> List[InPlaceCommand]:
    """Convert standard delta commands to in-place executable commands.

    The returned commands can be applied to a buffer initialized with R
    to reconstruct V in-place, without a separate output buffer.

    Args:
        R: Reference data (needed to materialize literal bytes when
           a copy is converted to an add during cycle breaking).
        commands: Standard delta commands (CopyCmd / AddCmd).
        policy: 'localmin' (default, better compression) or 'constant'.

    Returns:
        List of InPlaceCopyCmd / InPlaceAddCmd in safe execution order.
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
        return [InPlaceAddCmd(dst=d, data=dat) for d, dat in add_info]

    # Step 2: build CRWI digraph
    # Edge i -> j means i's read interval [src_i, src_i+len_i) overlaps
    # j's write interval [dst_j, dst_j+len_j), so i must execute before j.
    adj = [[] for _ in range(n)]
    in_deg = [0] * n

    for i in range(n):
        si, li = copy_info[i][1], copy_info[i][3]
        for j in range(n):
            if i == j:
                continue
            dj, lj = copy_info[j][2], copy_info[j][3]
            if si < dj + lj and dj < si + li:
                adj[i].append(j)
                in_deg[j] += 1

    # Step 3: topological sort with cycle breaking (Kahn's algorithm)
    removed = [False] * n
    topo_order = []
    converted = set()

    queue = deque(i for i in range(n) if in_deg[i] == 0)
    processed = 0

    while processed < n:
        while queue:
            v = queue.popleft()
            if removed[v]:
                continue
            removed[v] = True
            topo_order.append(v)
            processed += 1
            for w in adj[v]:
                if not removed[w]:
                    in_deg[w] -= 1
                    if in_deg[w] == 0:
                        queue.append(w)

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
                    queue.append(w)

    # Step 4: assemble result — copies in topo order, then all adds
    result: List[InPlaceCommand] = []

    for i in topo_order:
        _, src, dst, length = copy_info[i]
        result.append(InPlaceCopyCmd(src=src, dst=dst, length=length))

    for dst, data in add_info:
        result.append(InPlaceAddCmd(dst=dst, data=data))

    return result


def apply_delta_inplace(R: bytes, ip_commands: List[InPlaceCommand],
                        version_size: int) -> bytes:
    """Reconstruct version by applying in-place delta commands.

    Initializes a mutable buffer with R, then executes commands in order.
    Python's slice assignment creates a temporary copy of the RHS, so
    overlapping src/dst within a single copy is handled correctly.
    """
    buf = bytearray(max(len(R), version_size))
    buf[:len(R)] = R

    for cmd in ip_commands:
        if isinstance(cmd, InPlaceCopyCmd):
            buf[cmd.dst:cmd.dst + cmd.length] = buf[cmd.src:cmd.src + cmd.length]
        elif isinstance(cmd, InPlaceAddCmd):
            buf[cmd.dst:cmd.dst + len(cmd.data)] = cmd.data

    return bytes(buf[:version_size])


# ---- In-place binary format ----
#
# Header:
#   Magic:        4 bytes  b'IPD\x01'
#   Version size: 4 bytes  uint32 BE
#
# Commands (in execution order):
#   END:  type=0                                     (1 byte)
#   COPY: type=1, src:u32, dst:u32, len:u32         (13 bytes)
#   ADD:  type=2, dst:u32, len:u32, data             (9 + len bytes)

def encode_inplace_delta(ip_commands: List[InPlaceCommand],
                         version_size: int) -> bytes:
    """Encode in-place delta commands to binary format."""
    out = bytearray()
    out.extend(INPLACE_MAGIC)
    out.extend(struct.pack('>I', version_size))

    for cmd in ip_commands:
        if isinstance(cmd, InPlaceCopyCmd):
            out.append(1)
            out.extend(struct.pack('>III', cmd.src, cmd.dst, cmd.length))
        elif isinstance(cmd, InPlaceAddCmd):
            out.append(2)
            out.extend(struct.pack('>II', cmd.dst, len(cmd.data)))
            out.extend(cmd.data)

    out.append(0)  # END
    return bytes(out)


def decode_inplace_delta(data: bytes):
    """Decode binary in-place delta format.

    Returns (ip_commands, version_size).
    """
    if len(data) < 8 or data[:4] != INPLACE_MAGIC:
        raise ValueError("Not an in-place delta file")
    version_size = struct.unpack_from('>I', data, 4)[0]
    pos = 8
    commands: List[InPlaceCommand] = []

    while pos < len(data):
        t = data[pos]
        pos += 1
        if t == 0:
            break
        elif t == 1:  # COPY
            src, dst, length = struct.unpack_from('>III', data, pos)
            pos += 12
            commands.append(InPlaceCopyCmd(src=src, dst=dst, length=length))
        elif t == 2:  # ADD
            dst, length = struct.unpack_from('>II', data, pos)
            pos += 8
            commands.append(InPlaceAddCmd(dst=dst, data=data[pos:pos + length]))
            pos += length

    return commands, version_size


def is_inplace_delta(data: bytes) -> bool:
    """Check if binary data is an in-place delta (vs standard)."""
    return len(data) >= 4 and data[:4] == INPLACE_MAGIC


def apply_inplace_binary(R: bytes, delta: bytes) -> bytes:
    """Reconstruct version from reference + binary in-place delta."""
    commands, version_size = decode_inplace_delta(delta)
    return apply_delta_inplace(R, commands, version_size)


def inplace_summary(ip_commands: List[InPlaceCommand]) -> dict:
    """Return summary statistics for an in-place delta encoding."""
    copies = [c for c in ip_commands if isinstance(c, InPlaceCopyCmd)]
    adds = [c for c in ip_commands if isinstance(c, InPlaceAddCmd)]
    copy_bytes = sum(c.length for c in copies)
    add_bytes = sum(len(c.data) for c in adds)
    return {
        'num_commands': len(ip_commands),
        'num_copies': len(copies),
        'num_adds': len(adds),
        'copy_bytes': copy_bytes,
        'add_bytes': add_bytes,
        'total_output_bytes': copy_bytes + add_bytes,
    }


# ============================================================================
# Helpers
# ============================================================================

ALGORITHMS = {
    'greedy': diff_greedy,
    'onepass': diff_onepass,
    'correcting': diff_correcting,
}


def delta_summary(commands: List[Command]) -> dict:
    """Return summary statistics for a delta encoding."""
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


# ============================================================================
# CLI
# ============================================================================

def cmd_encode(args):
    algo = ALGORITHMS[args.algorithm]
    R = open(args.reference, 'rb').read()
    V = open(args.version, 'rb').read()

    t0 = time.time()
    commands = algo(R, V, p=args.seed_len, q=args.table_size)

    if args.inplace:
        ip_commands = make_inplace(R, commands, policy=args.policy)
        elapsed = time.time() - t0
        delta = encode_inplace_delta(ip_commands, len(V))
        open(args.delta, 'wb').write(delta)
        stats = inplace_summary(ip_commands)
        ratio = len(delta) / len(V) if V else 0
        print(f"Algorithm:    {args.algorithm} + in-place ({args.policy})")
        print(f"Reference:    {args.reference} ({len(R):,} bytes)")
        print(f"Version:      {args.version} ({len(V):,} bytes)")
        print(f"Delta:        {args.delta} ({len(delta):,} bytes)")
        print(f"Compression:  {ratio:.4f} (delta/version)")
        print(f"Commands:     {stats['num_copies']} copies, {stats['num_adds']} adds")
        print(f"Copy bytes:   {stats['copy_bytes']:,}")
        print(f"Add bytes:    {stats['add_bytes']:,}")
        print(f"Time:         {elapsed:.3f}s")
    else:
        elapsed = time.time() - t0
        delta = encode_delta(commands)
        open(args.delta, 'wb').write(delta)
        stats = delta_summary(commands)
        ratio = len(delta) / len(V) if V else 0
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
    R = open(args.reference, 'rb').read()
    delta = open(args.delta, 'rb').read()

    t0 = time.time()
    inplace = is_inplace_delta(delta)
    if inplace:
        V = apply_inplace_binary(R, delta)
    else:
        V = apply_delta_binary(R, delta)
    elapsed = time.time() - t0

    open(args.output, 'wb').write(V)
    fmt = "in-place" if inplace else "standard"
    print(f"Format:       {fmt}")
    print(f"Reference:    {args.reference} ({len(R):,} bytes)")
    print(f"Delta:        {args.delta} ({len(delta):,} bytes)")
    print(f"Output:       {args.output} ({len(V):,} bytes)")
    print(f"Time:         {elapsed:.3f}s")


def cmd_info(args):
    delta_bytes = open(args.delta, 'rb').read()
    inplace = is_inplace_delta(delta_bytes)

    if inplace:
        commands, version_size = decode_inplace_delta(delta_bytes)
        stats = inplace_summary(commands)
        print(f"Delta file:   {args.delta} ({len(delta_bytes):,} bytes)")
        print(f"Format:       in-place")
        print(f"Version size: {version_size:,} bytes")
    else:
        commands = decode_delta(delta_bytes)
        stats = delta_summary(commands)
        print(f"Delta file:   {args.delta} ({len(delta_bytes):,} bytes)")
        print(f"Format:       standard")

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
