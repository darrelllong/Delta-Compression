#!/usr/bin/env python3
"""Tests for delta.py — differential compression with in-place reconstruction.

Run:  python3 test_delta.py [-v]
"""

import random
import unittest

from delta import (
    ALGORITHMS, TABLE_SIZE,
    CopyCmd, AddCmd,
    PlacedCopy, PlacedAdd,
    diff_greedy, diff_onepass, diff_correcting,
    place_commands, encode_delta, decode_delta,
    apply_delta, apply_placed, apply_placed_inplace,
    is_inplace_delta,
    delta_summary, placed_summary,
    make_inplace,
)


# ── helpers ──────────────────────────────────────────────────────────────

def roundtrip(algo_fn, R, V, p=2, q=TABLE_SIZE):
    """Standard encode → binary → decode → apply, return recovered bytes."""
    cmds = algo_fn(R, V, p=p, q=q)
    placed = place_commands(cmds)
    delta = encode_delta(placed, inplace=False, version_size=len(V))
    placed2, is_ip, vs = decode_delta(delta)
    assert not is_ip
    assert vs == len(V)
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
    delta = encode_delta(ip, inplace=True, version_size=len(V))
    ip2, is_ip, vs = decode_delta(delta)
    assert is_ip
    assert vs == len(V)
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

    def test_placed_roundtrip(self):
        placed = [
            PlacedCopy(src=100, dst=0, length=50),
            PlacedAdd(dst=50, data=b"hello"),
            PlacedCopy(src=200, dst=55, length=30),
        ]
        delta = encode_delta(placed, inplace=False, version_size=85)
        placed2, is_ip, vs = decode_delta(delta)
        self.assertFalse(is_ip)
        self.assertEqual(vs, 85)
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
        delta = encode_delta(placed, inplace=True, version_size=15)
        _, is_ip, _ = decode_delta(delta)
        self.assertTrue(is_ip)


class TestLargeCopy(unittest.TestCase):

    def test_roundtrip(self):
        placed = [PlacedCopy(src=100000, dst=0, length=50000)]
        delta = encode_delta(placed, inplace=False, version_size=50000)
        placed2, _, _ = decode_delta(delta)
        self.assertEqual(len(placed2), 1)
        self.assertEqual(placed2[0].src, 100000)
        self.assertEqual(placed2[0].dst, 0)
        self.assertEqual(placed2[0].length, 50000)


class TestLargeAdd(unittest.TestCase):

    def test_roundtrip(self):
        big_data = bytes(range(256)) * 4
        placed = [PlacedAdd(dst=0, data=big_data)]
        delta = encode_delta(placed, inplace=False, version_size=len(big_data))
        placed2, _, _ = decode_delta(delta)
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
        delta = encode_delta(placed, inplace=False, version_size=len(V))
        self.assertFalse(is_inplace_delta(delta))

    def test_inplace_detected(self):
        R = b"ABCDEFGH" * 10
        V = b"EFGHABCD" * 10
        cmds = diff_greedy(R, V, p=2)
        ip = make_inplace(R, cmds, policy='localmin')
        delta = encode_delta(ip, inplace=True, version_size=len(V))
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


if __name__ == '__main__':
    unittest.main()
