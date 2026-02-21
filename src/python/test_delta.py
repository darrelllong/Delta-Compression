#!/usr/bin/env python3
"""Tests for delta.py — differential compression with in-place reconstruction.

Run:  python3 test_delta.py [-v]
"""

import random
import unittest

from delta import (
    DELTA_HASH_SIZE, DELTA_MAGIC, TABLE_SIZE,
    CopyCmd, AddCmd,
    PlacedCopy, PlacedAdd,
    diff_greedy, diff_onepass, diff_correcting,
    place_commands, encode_delta, decode_delta,
    apply_delta, apply_placed, apply_placed_inplace,
    is_inplace_delta,
    make_inplace,
    _shake128,
    _is_prime, _next_prime, _witness, _get_d_r,
)


# ── helpers ──────────────────────────────────────────────────────────────

def roundtrip(algo_fn, R, V, p=2, q=TABLE_SIZE):
    """Standard encode → binary → decode → apply, return recovered bytes."""
    cmds = algo_fn(R, V, p=p, q=q)
    placed = place_commands(cmds)
    delta = encode_delta(placed, inplace=False, version_size=len(V),
                         src_hash=_shake128(R), dst_hash=_shake128(V))
    placed2, is_ip, vs, src_h, dst_h = decode_delta(delta)
    assert not is_ip
    assert vs == len(V)
    assert src_h == _shake128(R)
    assert dst_h == _shake128(V)
    return apply_placed(R, placed2)


def inplace_roundtrip(algo_fn, R, V, policy='localmin', p=4):
    """Encode → make_inplace → apply_inplace, return recovered bytes."""
    cmds = algo_fn(R, V, p=p)
    ip = make_inplace(R, cmds, policy=policy)
    return apply_placed_inplace(R, ip, len(V))


def inplace_binary_roundtrip(algo_fn, R, V, policy='localmin', p=4):
    """Encode → make_inplace → binary → decode → apply, return recovered."""
    cmds = algo_fn(R, V, p=p)
    ip = make_inplace(R, cmds, policy=policy)
    delta = encode_delta(ip, inplace=True, version_size=len(V),
                         src_hash=_shake128(R), dst_hash=_shake128(V))
    ip2, is_ip, vs, src_h, dst_h = decode_delta(delta)
    assert is_ip
    assert vs == len(V)
    assert src_h == _shake128(R)
    assert dst_h == _shake128(V)
    return apply_placed_inplace(R, ip2, vs)


# ── standard differencing ────────────────────────────────────────────────

class TestPaperExample(unittest.TestCase):
    """Section 2.1.1 of Ajtai et al. 2002."""

    R = b"ABCDEFGHIJKLMNOP"
    V = b"QWIJKLMNOBCDEFGHZDEFGHIJKL"

    def test_greedy(self):
        self.assertEqual(apply_delta(self.R, diff_greedy(self.R, self.V, p=2)), self.V)

    def test_onepass(self):
        self.assertEqual(apply_delta(self.R, diff_onepass(self.R, self.V, p=2)), self.V)

    def test_correcting(self):
        self.assertEqual(apply_delta(self.R, diff_correcting(self.R, self.V, p=2)), self.V)


class TestIdentical(unittest.TestCase):

    data = b"The quick brown fox jumps over the lazy dog." * 10

    def _run(self, fn):
        cmds = fn(self.data, self.data, p=2)
        self.assertEqual(apply_delta(self.data, cmds), self.data)
        adds = [c for c in cmds if isinstance(c, AddCmd)]
        self.assertEqual(len(adds), 0, "identical strings should produce no adds")

    def test_greedy(self):   self._run(diff_greedy)
    def test_onepass(self):  self._run(diff_onepass)
    def test_correcting(self): self._run(diff_correcting)


class TestCompletelyDifferent(unittest.TestCase):

    R = bytes(range(256)) * 2
    V = bytes(range(255, -1, -1)) * 2

    def _run(self, fn):
        self.assertEqual(apply_delta(self.R, fn(self.R, self.V, p=2)), self.V)

    def test_greedy(self):   self._run(diff_greedy)
    def test_onepass(self):  self._run(diff_onepass)
    def test_correcting(self): self._run(diff_correcting)


class TestEmptyVersion(unittest.TestCase):

    def _run(self, fn):
        cmds = fn(b"hello", b"", p=2)
        self.assertEqual(len(cmds), 0)
        self.assertEqual(apply_delta(b"hello", cmds), b"")

    def test_greedy(self):   self._run(diff_greedy)
    def test_onepass(self):  self._run(diff_onepass)
    def test_correcting(self): self._run(diff_correcting)


class TestEmptyReference(unittest.TestCase):

    V = b"hello world"

    def _run(self, fn):
        self.assertEqual(apply_delta(b"", fn(b"", self.V, p=2)), self.V)

    def test_greedy(self):   self._run(diff_greedy)
    def test_onepass(self):  self._run(diff_onepass)
    def test_correcting(self): self._run(diff_correcting)


class TestBinaryRoundTrip(unittest.TestCase):

    R = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ" * 100
    V = b"0123EFGHIJKLMNOPQRS456ABCDEFGHIJKL789" * 100

    def _run(self, fn):
        self.assertEqual(roundtrip(fn, self.R, self.V, p=4), self.V)

    def test_greedy(self):   self._run(diff_greedy)
    def test_onepass(self):  self._run(diff_onepass)
    def test_correcting(self): self._run(diff_correcting)


class TestBinaryEncoding(unittest.TestCase):
    """Unified binary format encode/decode roundtrip."""

    _src = b'\x00' * 16
    _dst = b'\xff' * 16

    def test_placed_roundtrip(self):
        placed = [
            PlacedCopy(src=100, dst=0, length=50),
            PlacedAdd(dst=50, data=b"hello"),
            PlacedCopy(src=200, dst=55, length=30),
        ]
        delta = encode_delta(placed, inplace=False, version_size=85,
                             src_hash=self._src, dst_hash=self._dst)
        placed2, is_ip, vs, sh, dh = decode_delta(delta)
        self.assertFalse(is_ip)
        self.assertEqual(vs, 85)
        self.assertEqual(sh, self._src)
        self.assertEqual(dh, self._dst)
        self.assertEqual(len(placed2), 3)
        self.assertIsInstance(placed2[0], PlacedCopy)
        self.assertEqual(placed2[0].src, 100)
        self.assertEqual(placed2[0].dst, 0)
        self.assertEqual(placed2[0].length, 50)
        self.assertIsInstance(placed2[1], PlacedAdd)
        self.assertEqual(placed2[1].dst, 50)
        self.assertEqual(placed2[1].data, b"hello")
        self.assertIsInstance(placed2[2], PlacedCopy)

    def test_inplace_flag(self):
        placed = [PlacedCopy(src=0, dst=10, length=5)]
        delta = encode_delta(placed, inplace=True, version_size=15,
                             src_hash=self._src, dst_hash=self._dst)
        _, is_ip, _, _, _ = decode_delta(delta)
        self.assertTrue(is_ip)

    def test_magic_v2(self):
        placed = []
        delta = encode_delta(placed, inplace=False, version_size=0,
                             src_hash=self._src, dst_hash=self._dst)
        self.assertEqual(delta[:4], DELTA_MAGIC)

    def test_header_size(self):
        placed = []
        delta = encode_delta(placed, inplace=False, version_size=0,
                             src_hash=self._src, dst_hash=self._dst)
        # header (41) + END byte (1)
        self.assertEqual(len(delta), 42)


class TestLargeCopy(unittest.TestCase):

    _sh = b'\x01' * 16
    _dh = b'\x02' * 16

    def test_roundtrip(self):
        placed = [PlacedCopy(src=100000, dst=0, length=50000)]
        delta = encode_delta(placed, inplace=False, version_size=50000,
                             src_hash=self._sh, dst_hash=self._dh)
        placed2, _, _, _, _ = decode_delta(delta)
        self.assertEqual(len(placed2), 1)
        self.assertEqual(placed2[0].src, 100000)
        self.assertEqual(placed2[0].dst, 0)
        self.assertEqual(placed2[0].length, 50000)


class TestLargeAdd(unittest.TestCase):

    _sh = b'\x03' * 16
    _dh = b'\x04' * 16

    def test_roundtrip(self):
        big_data = bytes(range(256)) * 4
        placed = [PlacedAdd(dst=0, data=big_data)]
        delta = encode_delta(placed, inplace=False, version_size=len(big_data),
                             src_hash=self._sh, dst_hash=self._dh)
        placed2, _, _, _, _ = decode_delta(delta)
        total = b''.join(c.data for c in placed2 if isinstance(c, PlacedAdd))
        self.assertEqual(total, big_data)


class TestBackwardExtension(unittest.TestCase):

    block = b"ABCDEFGHIJKLMNOP" * 20
    R = b"____" + block + b"____"
    V = b"**" + block + b"**"

    def _run(self, fn):
        self.assertEqual(apply_delta(self.R, fn(self.R, self.V, p=4)), self.V)

    def test_greedy(self):   self._run(diff_greedy)
    def test_onepass(self):  self._run(diff_onepass)
    def test_correcting(self): self._run(diff_correcting)


class TestTransposition(unittest.TestCase):

    X = b"FIRST_BLOCK_DATA_" * 10
    Y = b"SECOND_BLOCK_DATA" * 10
    R = X + Y
    V = Y + X

    def _run(self, fn):
        self.assertEqual(apply_delta(self.R, fn(self.R, self.V, p=4)), self.V)

    def test_greedy(self):   self._run(diff_greedy)
    def test_onepass(self):  self._run(diff_onepass)
    def test_correcting(self): self._run(diff_correcting)


class TestScatteredModifications(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        rng = random.Random(42)
        cls.R = bytes(rng.getrandbits(8) for _ in range(2000))
        V = bytearray(cls.R)
        for _ in range(100):
            V[rng.randint(0, len(V) - 1)] = rng.getrandbits(8)
        cls.V = bytes(V)

    def _run(self, fn):
        self.assertEqual(roundtrip(fn, self.R, self.V, p=4), self.V)

    def test_greedy(self):   self._run(diff_greedy)
    def test_onepass(self):  self._run(diff_onepass)
    def test_correcting(self): self._run(diff_correcting)


# ── in-place basics ──────────────────────────────────────────────────────

class TestInPlacePaperExample(unittest.TestCase):

    R = b"ABCDEFGHIJKLMNOP"
    V = b"QWIJKLMNOBCDEFGHZDEFGHIJKL"

    def _run(self, fn, pol):
        self.assertEqual(inplace_roundtrip(fn, self.R, self.V, policy=pol, p=2), self.V)

    def test_greedy_const(self):    self._run(diff_greedy, 'constant')
    def test_greedy_lmin(self):     self._run(diff_greedy, 'localmin')
    def test_onepass_const(self):   self._run(diff_onepass, 'constant')
    def test_onepass_lmin(self):    self._run(diff_onepass, 'localmin')
    def test_correcting_const(self): self._run(diff_correcting, 'constant')
    def test_correcting_lmin(self):  self._run(diff_correcting, 'localmin')


class TestInPlaceBinaryRoundTrip(unittest.TestCase):

    R = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ" * 100
    V = b"0123EFGHIJKLMNOPQRS456ABCDEFGHIJKL789" * 100

    def _run(self, fn, pol):
        self.assertEqual(inplace_binary_roundtrip(fn, self.R, self.V, policy=pol), self.V)

    def test_greedy_const(self):    self._run(diff_greedy, 'constant')
    def test_greedy_lmin(self):     self._run(diff_greedy, 'localmin')
    def test_onepass_const(self):   self._run(diff_onepass, 'constant')
    def test_onepass_lmin(self):    self._run(diff_onepass, 'localmin')
    def test_correcting_const(self): self._run(diff_correcting, 'constant')
    def test_correcting_lmin(self):  self._run(diff_correcting, 'localmin')


class TestInPlaceSimpleTransposition(unittest.TestCase):

    X = b"FIRST_BLOCK_DATA_" * 20
    Y = b"SECOND_BLOCK_DATA" * 20
    R = X + Y
    V = Y + X

    def _run(self, fn, pol):
        self.assertEqual(inplace_roundtrip(fn, self.R, self.V, policy=pol), self.V)

    def test_greedy_const(self):    self._run(diff_greedy, 'constant')
    def test_greedy_lmin(self):     self._run(diff_greedy, 'localmin')
    def test_onepass_const(self):   self._run(diff_onepass, 'constant')
    def test_onepass_lmin(self):    self._run(diff_onepass, 'localmin')
    def test_correcting_const(self): self._run(diff_correcting, 'constant')
    def test_correcting_lmin(self):  self._run(diff_correcting, 'localmin')


class TestInPlaceVersionLarger(unittest.TestCase):

    R = b"ABCDEFGH" * 50
    V = b"XXABCDEFGH" * 50 + b"YYABCDEFGH" * 50

    def _run(self, fn, pol):
        self.assertEqual(inplace_roundtrip(fn, self.R, self.V, policy=pol), self.V)

    def test_greedy_const(self):    self._run(diff_greedy, 'constant')
    def test_greedy_lmin(self):     self._run(diff_greedy, 'localmin')
    def test_onepass_const(self):   self._run(diff_onepass, 'constant')
    def test_onepass_lmin(self):    self._run(diff_onepass, 'localmin')
    def test_correcting_const(self): self._run(diff_correcting, 'constant')
    def test_correcting_lmin(self):  self._run(diff_correcting, 'localmin')


class TestInPlaceVersionSmaller(unittest.TestCase):

    R = b"ABCDEFGHIJKLMNOP" * 100
    V = b"EFGHIJKL" * 50

    def _run(self, fn, pol):
        self.assertEqual(inplace_roundtrip(fn, self.R, self.V, policy=pol), self.V)

    def test_greedy_const(self):    self._run(diff_greedy, 'constant')
    def test_greedy_lmin(self):     self._run(diff_greedy, 'localmin')
    def test_onepass_const(self):   self._run(diff_onepass, 'constant')
    def test_onepass_lmin(self):    self._run(diff_onepass, 'localmin')
    def test_correcting_const(self): self._run(diff_correcting, 'constant')
    def test_correcting_lmin(self):  self._run(diff_correcting, 'localmin')


class TestInPlaceIdentical(unittest.TestCase):

    data = b"The quick brown fox jumps over the lazy dog." * 10

    def _run(self, fn, pol):
        self.assertEqual(inplace_roundtrip(fn, self.data, self.data, policy=pol, p=2), self.data)

    def test_greedy_const(self):    self._run(diff_greedy, 'constant')
    def test_greedy_lmin(self):     self._run(diff_greedy, 'localmin')
    def test_onepass_const(self):   self._run(diff_onepass, 'constant')
    def test_onepass_lmin(self):    self._run(diff_onepass, 'localmin')
    def test_correcting_const(self): self._run(diff_correcting, 'constant')
    def test_correcting_lmin(self):  self._run(diff_correcting, 'localmin')


class TestInPlaceEmptyVersion(unittest.TestCase):

    def _run(self, fn):
        cmds = fn(b"hello", b"", p=2)
        ip = make_inplace(b"hello", cmds, policy='localmin')
        self.assertEqual(apply_placed_inplace(b"hello", ip, 0), b"")

    def test_greedy(self):   self._run(diff_greedy)
    def test_onepass(self):  self._run(diff_onepass)
    def test_correcting(self): self._run(diff_correcting)


class TestInPlaceScattered(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        rng = random.Random(99)
        cls.R = bytes(rng.getrandbits(8) for _ in range(2000))
        V = bytearray(cls.R)
        for _ in range(100):
            V[rng.randint(0, len(V) - 1)] = rng.getrandbits(8)
        cls.V = bytes(V)

    def _run(self, fn, pol):
        self.assertEqual(inplace_binary_roundtrip(fn, self.R, self.V, policy=pol), self.V)

    def test_greedy_const(self):    self._run(diff_greedy, 'constant')
    def test_greedy_lmin(self):     self._run(diff_greedy, 'localmin')
    def test_onepass_const(self):   self._run(diff_onepass, 'constant')
    def test_onepass_lmin(self):    self._run(diff_onepass, 'localmin')
    def test_correcting_const(self): self._run(diff_correcting, 'constant')
    def test_correcting_lmin(self):  self._run(diff_correcting, 'localmin')


class TestInPlaceFormatDetection(unittest.TestCase):

    def test_standard_not_detected(self):
        R = b"ABCDEFGH" * 10
        V = b"EFGHABCD" * 10
        placed = place_commands(diff_greedy(R, V, p=2))
        delta = encode_delta(placed, inplace=False, version_size=len(V),
                             src_hash=_shake128(R), dst_hash=_shake128(V))
        self.assertFalse(is_inplace_delta(delta))

    def test_inplace_detected(self):
        R = b"ABCDEFGH" * 10
        V = b"EFGHABCD" * 10
        cmds = diff_greedy(R, V, p=2)
        ip = make_inplace(R, cmds, policy='localmin')
        delta = encode_delta(ip, inplace=True, version_size=len(V),
                             src_hash=_shake128(R), dst_hash=_shake128(V))
        self.assertTrue(is_inplace_delta(delta))


# ── in-place: variable-length transpositions ─────────────────────────────

def _make_blocks():
    """8 blocks with distinct byte patterns and varying sizes (200–5000)."""
    sizes = [200, 500, 1234, 3000, 800, 4999, 1500, 2750]
    return [bytes((i * 37 + j) & 0xFF for j in range(sz))
            for i, sz in enumerate(sizes)]


class TestInPlaceVarlenPermutation(unittest.TestCase):
    """Random permutation of all 8 variable-length blocks."""

    @classmethod
    def setUpClass(cls):
        cls.blocks = _make_blocks()
        cls.R = b''.join(cls.blocks)
        rng = random.Random(2003)
        perm = list(range(8))
        rng.shuffle(perm)
        cls.V = b''.join(cls.blocks[i] for i in perm)

    def _run(self, fn, pol):
        self.assertEqual(inplace_roundtrip(fn, self.R, self.V, policy=pol), self.V)

    def test_greedy_const(self):    self._run(diff_greedy, 'constant')
    def test_greedy_lmin(self):     self._run(diff_greedy, 'localmin')
    def test_onepass_const(self):   self._run(diff_onepass, 'constant')
    def test_onepass_lmin(self):    self._run(diff_onepass, 'localmin')
    def test_correcting_const(self): self._run(diff_correcting, 'constant')
    def test_correcting_lmin(self):  self._run(diff_correcting, 'localmin')


class TestInPlaceVarlenReverse(unittest.TestCase):
    """All 8 blocks in reverse order."""

    @classmethod
    def setUpClass(cls):
        cls.blocks = _make_blocks()
        cls.R = b''.join(cls.blocks)
        cls.V = b''.join(reversed(cls.blocks))

    def _run(self, fn, pol):
        self.assertEqual(inplace_roundtrip(fn, self.R, self.V, policy=pol), self.V)

    def test_greedy_const(self):    self._run(diff_greedy, 'constant')
    def test_greedy_lmin(self):     self._run(diff_greedy, 'localmin')
    def test_onepass_const(self):   self._run(diff_onepass, 'constant')
    def test_onepass_lmin(self):    self._run(diff_onepass, 'localmin')
    def test_correcting_const(self): self._run(diff_correcting, 'constant')
    def test_correcting_lmin(self):  self._run(diff_correcting, 'localmin')


class TestInPlaceVarlenJunk(unittest.TestCase):
    """Permuted blocks interleaved with random junk bytes."""

    @classmethod
    def setUpClass(cls):
        cls.blocks = _make_blocks()
        cls.R = b''.join(cls.blocks)
        rng = random.Random(2003)
        # consume same state as Permutation test so seeds stay independent
        _skip = list(range(8)); rng.shuffle(_skip)
        junk = bytes(rng.getrandbits(8) for _ in range(300))
        perm = list(range(8))
        rng.shuffle(perm)
        pieces = []
        for i in perm:
            pieces.append(cls.blocks[i])
            pieces.append(junk[:rng.randint(50, 300)])
        cls.V = b''.join(pieces)

    def _run(self, fn, pol):
        self.assertEqual(inplace_roundtrip(fn, self.R, self.V, policy=pol), self.V)

    def test_greedy_const(self):    self._run(diff_greedy, 'constant')
    def test_greedy_lmin(self):     self._run(diff_greedy, 'localmin')
    def test_onepass_const(self):   self._run(diff_onepass, 'constant')
    def test_onepass_lmin(self):    self._run(diff_onepass, 'localmin')
    def test_correcting_const(self): self._run(diff_correcting, 'constant')
    def test_correcting_lmin(self):  self._run(diff_correcting, 'localmin')


class TestInPlaceVarlenDropDup(unittest.TestCase):
    """Drop some blocks, duplicate others — |V| != |R|."""

    @classmethod
    def setUpClass(cls):
        cls.blocks = _make_blocks()
        cls.R = b''.join(cls.blocks)
        cls.V = cls.blocks[3] + cls.blocks[0] + cls.blocks[0] + cls.blocks[5] + cls.blocks[3]

    def _run(self, fn, pol):
        self.assertEqual(inplace_roundtrip(fn, self.R, self.V, policy=pol), self.V)

    def test_greedy_const(self):    self._run(diff_greedy, 'constant')
    def test_greedy_lmin(self):     self._run(diff_greedy, 'localmin')
    def test_onepass_const(self):   self._run(diff_onepass, 'constant')
    def test_onepass_lmin(self):    self._run(diff_onepass, 'localmin')
    def test_correcting_const(self): self._run(diff_correcting, 'constant')
    def test_correcting_lmin(self):  self._run(diff_correcting, 'localmin')


class TestInPlaceVarlenDoubleSized(unittest.TestCase):
    """Version is 2x the reference — all blocks appear twice in shuffled order."""

    @classmethod
    def setUpClass(cls):
        cls.blocks = _make_blocks()
        cls.R = b''.join(cls.blocks)
        rng = random.Random(7001)
        p1 = list(range(8)); rng.shuffle(p1)
        p2 = list(range(8)); rng.shuffle(p2)
        cls.V = b''.join(cls.blocks[i] for i in p1) + b''.join(cls.blocks[i] for i in p2)

    def _run(self, fn, pol):
        self.assertEqual(inplace_roundtrip(fn, self.R, self.V, policy=pol), self.V)

    def test_greedy_const(self):    self._run(diff_greedy, 'constant')
    def test_greedy_lmin(self):     self._run(diff_greedy, 'localmin')
    def test_onepass_const(self):   self._run(diff_onepass, 'constant')
    def test_onepass_lmin(self):    self._run(diff_onepass, 'localmin')
    def test_correcting_const(self): self._run(diff_correcting, 'constant')
    def test_correcting_lmin(self):  self._run(diff_correcting, 'localmin')


class TestInPlaceVarlenSubset(unittest.TestCase):
    """Version is much smaller — just two blocks."""

    @classmethod
    def setUpClass(cls):
        cls.blocks = _make_blocks()
        cls.R = b''.join(cls.blocks)
        cls.V = cls.blocks[6] + cls.blocks[2]

    def _run(self, fn, pol):
        self.assertEqual(inplace_roundtrip(fn, self.R, self.V, policy=pol), self.V)

    def test_greedy_const(self):    self._run(diff_greedy, 'constant')
    def test_greedy_lmin(self):     self._run(diff_greedy, 'localmin')
    def test_onepass_const(self):   self._run(diff_onepass, 'constant')
    def test_onepass_lmin(self):    self._run(diff_onepass, 'localmin')
    def test_correcting_const(self): self._run(diff_correcting, 'constant')
    def test_correcting_lmin(self):  self._run(diff_correcting, 'localmin')


class TestInPlaceVarlenHalfBlockScramble(unittest.TestCase):
    """Split each block in half, shuffle all 16 halves."""

    @classmethod
    def setUpClass(cls):
        cls.blocks = _make_blocks()
        cls.R = b''.join(cls.blocks)
        halves = []
        for b in cls.blocks:
            mid = len(b) // 2
            halves.append(b[:mid])
            halves.append(b[mid:])
        rng = random.Random(5555)
        perm = list(range(len(halves)))
        rng.shuffle(perm)
        cls.V = b''.join(halves[i] for i in perm)

    def _run(self, fn, pol):
        self.assertEqual(inplace_roundtrip(fn, self.R, self.V, policy=pol), self.V)

    def _run_binary(self, fn, pol):
        self.assertEqual(inplace_binary_roundtrip(fn, self.R, self.V, policy=pol), self.V)

    def test_greedy_const(self):    self._run(diff_greedy, 'constant')
    def test_greedy_lmin(self):     self._run(diff_greedy, 'localmin')
    def test_onepass_const(self):   self._run(diff_onepass, 'constant')
    def test_onepass_lmin(self):    self._run(diff_onepass, 'localmin')
    def test_correcting_const(self): self._run(diff_correcting, 'constant')
    def test_correcting_lmin(self):  self._run(diff_correcting, 'localmin')

    # binary round-trip too (hardest case)
    def test_greedy_const_bin(self):    self._run_binary(diff_greedy, 'constant')
    def test_greedy_lmin_bin(self):     self._run_binary(diff_greedy, 'localmin')
    def test_onepass_const_bin(self):   self._run_binary(diff_onepass, 'constant')
    def test_onepass_lmin_bin(self):    self._run_binary(diff_onepass, 'localmin')
    def test_correcting_const_bin(self): self._run_binary(diff_correcting, 'constant')
    def test_correcting_lmin_bin(self):  self._run_binary(diff_correcting, 'localmin')


class TestInPlaceVarlenRandomTrials(unittest.TestCase):
    """20 random trials: random subset of 3–8 blocks in random order."""

    @classmethod
    def setUpClass(cls):
        cls.blocks = _make_blocks()
        cls.R = b''.join(cls.blocks)
        rng = random.Random(9999)
        cls.trials = []
        for _ in range(20):
            k = rng.randint(3, 8)
            chosen = rng.sample(range(8), k)
            rng.shuffle(chosen)
            V = b''.join(cls.blocks[i] for i in chosen)
            cls.trials.append((chosen, V))

    def _run_all(self, fn, pol):
        for chosen, V in self.trials:
            got = inplace_roundtrip(fn, self.R, V, policy=pol)
            self.assertEqual(got, V, f"failed on {chosen}")

    def test_greedy_const(self):    self._run_all(diff_greedy, 'constant')
    def test_greedy_lmin(self):     self._run_all(diff_greedy, 'localmin')
    def test_onepass_const(self):   self._run_all(diff_onepass, 'constant')
    def test_onepass_lmin(self):    self._run_all(diff_onepass, 'localmin')
    def test_correcting_const(self): self._run_all(diff_correcting, 'constant')
    def test_correcting_lmin(self):  self._run_all(diff_correcting, 'localmin')


# ── in-place: controlled transpositions (cycle-heavy workloads) ───────────

def generate_transposed(num_blocks, block_size, num_transpositions, seed=42):
    """Generate reference and version data with controlled transpositions.

    Creates num_blocks distinct blocks of block_size bytes each.  The version
    is formed by applying num_transpositions random adjacent-pair swaps to
    the block ordering.  Each swap of adjacent same-sized blocks in place
    creates a CRWI cycle (copy A→B and copy B→A each read what the other
    writes), so this directly controls the number of cycles the in-place
    converter must break.

    Returns (R, V, num_swaps_applied).
    """
    rng = random.Random(seed)
    # Generate distinct blocks (different first bytes guarantee uniqueness)
    blocks = []
    for i in range(num_blocks):
        blk = bytes([i % 256] * 4) + bytes(rng.getrandbits(8) for _ in range(block_size - 4))
        blocks.append(blk)

    R = b''.join(blocks)

    # Build version by applying transpositions to a permutation
    perm = list(range(num_blocks))
    swaps_applied = 0
    for _ in range(num_transpositions):
        # Pick a random pair (not necessarily adjacent — any swap)
        a = rng.randint(0, num_blocks - 1)
        b = rng.randint(0, num_blocks - 1)
        if a != b:
            perm[a], perm[b] = perm[b], perm[a]
            swaps_applied += 1

    V = b''.join(blocks[perm[i]] for i in range(num_blocks))
    return R, V, swaps_applied


class TestInPlaceTranspositions(unittest.TestCase):
    """Test in-place reconstruction with increasing numbers of transpositions.

    Each transposition of equal-sized blocks creates a potential CRWI cycle,
    forcing the in-place converter to break cycles by converting copies to
    adds.  This verifies correctness under cycle-heavy workloads.
    """

    BLOCK_SIZE = 200
    CONFIGS = [
        # (num_blocks, num_transpositions, seed)
        (8,   1,  100),   # 1 swap — 1 cycle
        (8,   4,  101),   # 4 swaps — multiple cycles
        (16,  8,  102),   # larger with many swaps
        (32, 16,  103),   # 32 blocks, 16 swaps
        (32, 31,  104),   # near-total scramble
        (64, 50,  105),   # 64 blocks, heavy scramble
    ]

    @classmethod
    def setUpClass(cls):
        cls.cases = []
        for num_blocks, num_trans, seed in cls.CONFIGS:
            R, V, swaps = generate_transposed(
                num_blocks, cls.BLOCK_SIZE, num_trans, seed)
            cls.cases.append((num_blocks, num_trans, swaps, R, V))

    def _run_all(self, fn, pol):
        for num_blocks, num_trans, swaps, R, V in self.cases:
            got = inplace_roundtrip(fn, R, V, policy=pol)
            self.assertEqual(
                got, V,
                f"failed: {num_blocks} blocks, {num_trans} transpositions "
                f"({swaps} applied), policy={pol}")

    def test_greedy_const(self):    self._run_all(diff_greedy, 'constant')
    def test_greedy_lmin(self):     self._run_all(diff_greedy, 'localmin')
    def test_onepass_const(self):   self._run_all(diff_onepass, 'constant')
    def test_onepass_lmin(self):    self._run_all(diff_onepass, 'localmin')
    def test_correcting_const(self): self._run_all(diff_correcting, 'constant')
    def test_correcting_lmin(self):  self._run_all(diff_correcting, 'localmin')


class TestInPlaceTranspositionsBinary(unittest.TestCase):
    """Same as above but through the full binary encode/decode path."""

    @classmethod
    def setUpClass(cls):
        cls.cases = []
        for num_blocks, num_trans, seed in TestInPlaceTranspositions.CONFIGS:
            R, V, swaps = generate_transposed(
                num_blocks, TestInPlaceTranspositions.BLOCK_SIZE, num_trans, seed)
            cls.cases.append((num_blocks, num_trans, swaps, R, V))

    def _run_all(self, fn, pol):
        for num_blocks, num_trans, swaps, R, V in self.cases:
            got = inplace_binary_roundtrip(fn, R, V, policy=pol)
            self.assertEqual(
                got, V,
                f"binary failed: {num_blocks} blocks, {num_trans} trans, "
                f"policy={pol}")

    def test_greedy_const(self):    self._run_all(diff_greedy, 'constant')
    def test_greedy_lmin(self):     self._run_all(diff_greedy, 'localmin')
    def test_onepass_const(self):   self._run_all(diff_onepass, 'constant')
    def test_onepass_lmin(self):    self._run_all(diff_onepass, 'localmin')
    def test_correcting_const(self): self._run_all(diff_correcting, 'constant')
    def test_correcting_lmin(self):  self._run_all(diff_correcting, 'localmin')


class TestBothPoliciesCorrectOnTranspositions(unittest.TestCase):
    """Both cycle policies produce correct output on cycle-heavy workloads
    with variable-sized blocks."""

    @classmethod
    def setUpClass(cls):
        rng = random.Random(777)
        cls.blocks = []
        for i in range(20):
            size = rng.randint(50, 500)
            blk = bytes([i % 256] * 4) + bytes(rng.getrandbits(8) for _ in range(size - 4))
            cls.blocks.append(blk)
        cls.R = b''.join(cls.blocks)
        perm = list(range(20))
        for _ in range(15):
            a, b = rng.sample(range(20), 2)
            perm[a], perm[b] = perm[b], perm[a]
        cls.V = b''.join(cls.blocks[perm[i]] for i in range(20))

    def test_greedy_constant(self):
        self.assertEqual(
            inplace_roundtrip(diff_greedy, self.R, self.V, policy='constant'),
            self.V)

    def test_greedy_localmin(self):
        self.assertEqual(
            inplace_roundtrip(diff_greedy, self.R, self.V, policy='localmin'),
            self.V)


# ── in-place: localmin actually picks the smaller victim ─────────────────

class TestLocalminPicksSmallest(unittest.TestCase):
    """When blocks have different sizes, localmin should convert fewer bytes
    than constant (or at worst the same)."""

    @classmethod
    def setUpClass(cls):
        cls.blocks = _make_blocks()
        cls.R = b''.join(cls.blocks)
        cls.V = b''.join(reversed(cls.blocks))

    def test_greedy_localmin_leq_constant(self):
        cmds = diff_greedy(self.R, self.V, p=4)
        ip_const = make_inplace(self.R, cmds, policy='constant')
        ip_lmin  = make_inplace(self.R, cmds, policy='localmin')
        add_const = sum(len(c.data) for c in ip_const if isinstance(c, PlacedAdd))
        add_lmin  = sum(len(c.data) for c in ip_lmin  if isinstance(c, PlacedAdd))
        self.assertLessEqual(add_lmin, add_const)


# ── Miller-Rabin primality testing ─────────────────────────────────────────

class TestGetDR(unittest.TestCase):
    """Factor n into d * 2^r."""

    def test_power_of_two(self):
        self.assertEqual(_get_d_r(8), (1, 3))

    def test_odd(self):
        self.assertEqual(_get_d_r(15), (15, 0))

    def test_mixed(self):
        d, r = _get_d_r(12)
        self.assertEqual(d, 3)
        self.assertEqual(r, 2)
        self.assertEqual(d * (2 ** r), 12)

    def test_one(self):
        self.assertEqual(_get_d_r(1), (1, 0))


class TestWitness(unittest.TestCase):
    """The witness loop correctly identifies composites and primes."""

    def test_composite_has_witness(self):
        # 2 is always a witness for even composites and many odd ones
        self.assertTrue(_witness(2, 9))     # 9 = 3^2

    def test_prime_has_no_witness(self):
        # For a true prime, no a in [2, n-1) is a witness
        for a in range(2, 12):
            self.assertFalse(_witness(a, 13), f"a={a} should not be a witness for 13")


class TestIsPrime(unittest.TestCase):
    """Miller-Rabin probabilistic primality with random witnesses."""

    # First 50 primes
    KNOWN_PRIMES = [
        2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47,
        53, 59, 61, 67, 71, 73, 79, 83, 89, 97, 101, 103, 107, 109, 113,
        127, 131, 137, 139, 149, 151, 157, 163, 167, 173, 179, 181, 191,
        193, 197, 199, 211, 223, 227, 229,
    ]

    KNOWN_COMPOSITES = [0, 1, 4, 6, 8, 9, 10, 12, 14, 15, 16, 18, 20,
                        21, 25, 27, 33, 35, 49, 51, 55, 63, 65, 77, 91,
                        100, 121, 143, 169, 221, 1000, 1000000]

    def test_known_primes(self):
        for p in self.KNOWN_PRIMES:
            self.assertTrue(_is_prime(p), f"{p} should be prime")

    def test_known_composites(self):
        for c in self.KNOWN_COMPOSITES:
            self.assertFalse(_is_prime(c), f"{c} should be composite")

    def test_large_primes(self):
        # Large primes used as hash table sizes
        self.assertTrue(_is_prime(1048573))    # largest prime < 2^20
        self.assertTrue(_is_prime(2097143))    # largest prime < 2^21
        self.assertTrue(_is_prime(104729))     # 10000th prime

    def test_carmichael_numbers(self):
        # Carmichael numbers pass the Fermat test for all bases
        # but Miller-Rabin with random witnesses catches them.
        carmichaels = [561, 1105, 1729, 2465, 2821, 6601, 8911]
        for c in carmichaels:
            self.assertFalse(_is_prime(c), f"Carmichael number {c} should be composite")

    def test_mersenne_primes(self):
        # 2^p - 1 for known Mersenne prime exponents
        for p in [2, 3, 5, 7, 13, 17, 19]:
            mp = (1 << p) - 1
            self.assertTrue(_is_prime(mp), f"2^{p}-1 = {mp} should be prime")

    def test_edge_cases(self):
        self.assertFalse(_is_prime(-1))
        self.assertFalse(_is_prime(0))
        self.assertFalse(_is_prime(1))
        self.assertTrue(_is_prime(2))
        self.assertTrue(_is_prime(3))
        self.assertFalse(_is_prime(4))


class TestNextPrime(unittest.TestCase):
    """next_prime(n) returns the smallest prime >= n."""

    def test_exact_prime(self):
        self.assertEqual(_next_prime(7), 7)

    def test_composite(self):
        self.assertEqual(_next_prime(8), 11)
        self.assertEqual(_next_prime(14), 17)

    def test_zero_one_two(self):
        self.assertEqual(_next_prime(0), 2)
        self.assertEqual(_next_prime(1), 2)
        self.assertEqual(_next_prime(2), 2)

    def test_even_input(self):
        self.assertEqual(_next_prime(100), 101)
        self.assertEqual(_next_prime(1000), 1009)

    def test_consecutive(self):
        # Verify next_prime produces a monotonically non-decreasing
        # sequence of primes
        p = 2
        for n in range(2, 500):
            np = _next_prime(n)
            self.assertGreaterEqual(np, n)
            self.assertTrue(_is_prime(np), f"next_prime({n}) = {np} should be prime")
            # No prime was skipped
            if n > p:
                self.assertGreaterEqual(np, p)
            p = np


class TestCheckpointing(unittest.TestCase):
    """Correcting algorithm uses checkpointing (Section 8) for bounded memory."""

    def test_tiny_table_roundtrip(self):
        """With a tiny table (q=7), checkpointing still produces correct output."""
        R = b'ABCDEFGHIJKLMNOP' * 20   # 320 bytes
        V = R[:160] + b'XXXXYYYY' + R[160:]
        cmds = diff_correcting(R, V, p=16, q=7)
        recovered = apply_delta(R, cmds)
        self.assertEqual(recovered, V)

    def test_various_table_sizes(self):
        """Correcting produces correct output across a range of table sizes."""
        rng = random.Random(42)
        R = bytes(rng.getrandbits(8) for _ in range(2000))
        V = R[:500] + bytes(rng.getrandbits(8) for _ in range(50)) + R[500:]
        for q in [7, 31, 101, 1009, TABLE_SIZE]:
            cmds = diff_correcting(R, V, p=16, q=q)
            recovered = apply_delta(R, cmds)
            self.assertEqual(recovered, V, f"failed with q={q}")

    def test_small_file_no_checkpointing(self):
        """When |F| <= |C|, m=1 and all seeds are checkpoints (no filtering)."""
        R = b'hello world, this is a test string!'
        V = b'hello world, this is a new string!'
        cmds = diff_correcting(R, V, p=4, q=TABLE_SIZE)
        recovered = apply_delta(R, cmds)
        self.assertEqual(recovered, V)

    def test_checkpoint_long_matches(self):
        """Checkpointing finds long matches even with tiny tables."""
        # 10 KB of data with a 100-byte insertion in the middle
        R = bytes(range(256)) * 40  # 10240 bytes
        V = R[:5000] + b'X' * 100 + R[5000:]
        cmds = diff_correcting(R, V, p=16, q=31)
        recovered = apply_delta(R, cmds)
        self.assertEqual(recovered, V)


# ── SHAKE128 hash tests ───────────────────────────────────────────────────

class TestShake128(unittest.TestCase):
    """SHAKE128-16 helper correctness and NIST FIPS 202 vectors."""

    def test_output_length(self):
        self.assertEqual(len(_shake128(b'')), DELTA_HASH_SIZE)
        self.assertEqual(len(_shake128(b'hello')), DELTA_HASH_SIZE)

    def test_deterministic(self):
        self.assertEqual(_shake128(b'test'), _shake128(b'test'))

    def test_differs_from_different_input(self):
        self.assertNotEqual(_shake128(b'hello'), _shake128(b'world'))

    def test_nist_empty_input(self):
        # NIST FIPS 202 SHAKE128 test vector: empty message, first 16 bytes.
        # SHA3-128 of empty input is 47bce5c74f589f4867dbe57f31b68e5e —
        # different domain separator (0x06 vs 0x1F); substituting sha3_128
        # here would fail this test.
        expected = bytes.fromhex('7f9c2ba4e88f827d616045507605853e')
        self.assertEqual(_shake128(b''), expected)

    def test_nist_one_byte_bd(self):
        # NIST FIPS 202 SHAKE128 test vector: msg = 0xbd, first 16 bytes
        expected = bytes.fromhex('83388286b2c0065ed237fbe714fc3163')
        self.assertEqual(_shake128(b'\xbd'), expected)

    def test_nist_200_byte_a3(self):
        # NIST FIPS 202 SHAKE128 test vector: msg = 0xa3 * 200, first 16 bytes
        expected = bytes.fromhex('131ab8d2b594946b9c81333f9bb6e0ce')
        self.assertEqual(_shake128(b'\xa3' * 200), expected)

    def test_not_sha3_128(self):
        # SHAKE128 and SHA3-128 share the same permutation and rate but use
        # different domain separators (0x1F vs 0x06) and produce different
        # output.  This test would fail if _shake128() were implemented with
        # hashlib.sha3_128 instead of hashlib.shake_128.
        sha3_128_empty = bytes.fromhex('47bce5c74f589f4867dbe57f31b68e5e')
        self.assertNotEqual(_shake128(b''), sha3_128_empty)


class TestHashEmbeddedInDelta(unittest.TestCase):
    """Hash values round-trip correctly through the binary format."""

    def test_real_hash_roundtrip(self):
        R = b"reference data for testing " * 5
        V = b"version data for testing " * 5
        sh = _shake128(R)
        dh = _shake128(V)
        placed = place_commands(diff_greedy(R, V, p=4))
        delta = encode_delta(placed, inplace=False, version_size=len(V),
                             src_hash=sh, dst_hash=dh)
        _, _, _, sh2, dh2 = decode_delta(delta)
        self.assertEqual(sh2, sh)
        self.assertEqual(dh2, dh)

    def test_hash_mismatch_detection(self):
        """Caller can detect wrong source file by comparing hashes."""
        R = b"correct reference data " * 5
        V = b"version data " * 5
        wrong_R = b"wrong reference data " * 5
        sh = _shake128(R)
        dh = _shake128(V)
        placed = place_commands(diff_greedy(R, V, p=4))
        delta = encode_delta(placed, inplace=False, version_size=len(V),
                             src_hash=sh, dst_hash=dh)
        _, _, _, sh2, _ = decode_delta(delta)
        self.assertNotEqual(_shake128(wrong_R), sh2)

    def test_hash_size_constant(self):
        self.assertEqual(DELTA_HASH_SIZE, 16)


if __name__ == '__main__':
    unittest.main()
