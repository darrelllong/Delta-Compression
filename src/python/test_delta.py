#!/usr/bin/env python3
"""Tests for delta.py.

Run:  python3 test_delta.py [-v]
"""

import random
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from delta import (
    DELTA_CRC_SIZE, DELTA_MAGIC, DELTA_MAGIC_LARGE, TABLE_SIZE,
    CopyCmd, AddCmd,
    PlacedCopy, PlacedAdd, PlacedMove,
    diff_greedy, diff_onepass, diff_correcting,
    place_commands, encode_delta, encode_delta_large, decode_delta,
    apply_delta, apply_placed, apply_placed_to, apply_placed_inplace,
    is_inplace_delta,
    make_inplace,
    _crc64_xz,
    _is_prime, _next_prime, _witness, _get_d_r,
)


def roundtrip(algo_fn, R, V, p=2, q=TABLE_SIZE):
    """Diff, encode as DLT\\x03, decode and apply; return the result."""
    cmds = algo_fn(R, V, p=p, q=q)
    placed = place_commands(cmds)
    delta = encode_delta(placed, inplace=False, version_size=len(V),
                         src_crc=_crc64_xz(R), dst_crc=_crc64_xz(V))
    placed2, is_ip, vs, src_c, dst_c = decode_delta(delta)
    assert not is_ip
    assert vs == len(V)
    assert src_c == _crc64_xz(R)
    assert dst_c == _crc64_xz(V)
    return apply_placed(R, placed2)


def inplace_roundtrip(algo_fn, R, V, policy='localmin', p=4):
    """Diff, convert to in-place and apply in a copy of R; return the result."""
    cmds = algo_fn(R, V, p=p)
    ip = make_inplace(R, cmds, policy=policy)
    return apply_placed_inplace(R, ip, len(V))


def inplace_binary_roundtrip(algo_fn, R, V, policy='localmin', p=4):
    """As inplace_roundtrip, but through an encoded in-place DLT\\x03 delta."""
    cmds = algo_fn(R, V, p=p)
    ip = make_inplace(R, cmds, policy=policy)
    delta = encode_delta(ip, inplace=True, version_size=len(V),
                         src_crc=_crc64_xz(R), dst_crc=_crc64_xz(V))
    ip2, is_ip, vs, src_c, dst_c = decode_delta(delta)
    assert is_ip
    assert vs == len(V)
    assert src_c == _crc64_xz(R)
    assert dst_c == _crc64_xz(V)
    return apply_placed_inplace(R, ip2, vs)


class TestPaperExample(unittest.TestCase):
    """The example strings of Section 2.1.1 of Ajtai et al. 2002."""

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
    """DLT\\x03 encoding and decoding of placed commands."""

    _src = b'\x00' * 8
    _dst = b'\xff' * 8

    def test_placed_roundtrip(self):
        placed = [
            PlacedCopy(src=100, dst=0, length=50),
            PlacedAdd(dst=50, data=b"hello"),
            PlacedCopy(src=200, dst=55, length=30),
        ]
        delta = encode_delta(placed, inplace=False, version_size=85,
                             src_crc=self._src, dst_crc=self._dst)
        placed2, is_ip, vs, sc, dc = decode_delta(delta)
        self.assertFalse(is_ip)
        self.assertEqual(vs, 85)
        self.assertEqual(sc, self._src)
        self.assertEqual(dc, self._dst)
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
                             src_crc=self._src, dst_crc=self._dst)
        _, is_ip, _, _, _ = decode_delta(delta)
        self.assertTrue(is_ip)

    def test_magic_small(self):
        placed = []
        delta = encode_delta(placed, inplace=False, version_size=0,
                             src_crc=self._src, dst_crc=self._dst)
        self.assertEqual(delta[:4], DELTA_MAGIC)

    def test_header_size(self):
        placed = []
        delta = encode_delta(placed, inplace=False, version_size=0,
                             src_crc=self._src, dst_crc=self._dst)
        # header (25) + END byte (1)
        self.assertEqual(len(delta), 26)


class TestBinaryEncodingErrors(unittest.TestCase):

    _src = b"\x00" * 8
    _dst = b"\x00" * 8

    def test_missing_end_rejected(self):
        delta = encode_delta([], inplace=False, version_size=0,
                             src_crc=self._src, dst_crc=self._dst)[:-1]
        with self.assertRaisesRegex(ValueError, "Missing END"):
            decode_delta(delta)

    def test_unknown_opcode_rejected(self):
        delta = (encode_delta([], inplace=False, version_size=0,
                              src_crc=self._src, dst_crc=self._dst)[:-1]
                 + b"\x7f")
        with self.assertRaisesRegex(ValueError, "Unknown command type"):
            decode_delta(delta)

    def test_truncated_copy_rejected(self):
        delta = (encode_delta([], inplace=False, version_size=1,
                              src_crc=self._src, dst_crc=self._dst)[:-1]
                 + bytes([1]))
        with self.assertRaisesRegex(ValueError, "Truncated COPY"):
            decode_delta(delta)

    def test_copy_past_version_size_rejected(self):
        delta = encode_delta([PlacedCopy(src=0, dst=1, length=2)],
                             inplace=False, version_size=2,
                             src_crc=self._src, dst_crc=self._dst)
        with self.assertRaisesRegex(ValueError, "COPY extends past version size"):
            decode_delta(delta)


class TestLargeCopy(unittest.TestCase):
    """A copy with large field values in DLT\\x03; not the DLT\\x04 format."""

    _sh = b'\x01' * 8
    _dh = b'\x02' * 8

    def test_roundtrip(self):
        placed = [PlacedCopy(src=100000, dst=0, length=50000)]
        delta = encode_delta(placed, inplace=False, version_size=50000,
                             src_crc=self._sh, dst_crc=self._dh)
        placed2, _, _, _, _ = decode_delta(delta)
        self.assertEqual(len(placed2), 1)
        self.assertEqual(placed2[0].src, 100000)
        self.assertEqual(placed2[0].dst, 0)
        self.assertEqual(placed2[0].length, 50000)


class TestLargeAdd(unittest.TestCase):
    """A 1024-byte add in DLT\\x03; not the DLT\\x04 format."""

    _sh = b'\x03' * 8
    _dh = b'\x04' * 8

    def test_roundtrip(self):
        big_data = bytes(range(256)) * 4
        placed = [PlacedAdd(dst=0, data=big_data)]
        delta = encode_delta(placed, inplace=False, version_size=len(big_data),
                             src_crc=self._sh, dst_crc=self._dh)
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


class TestInPlaceNoCopies(unittest.TestCase):
    """make_inplace honours return_stats when there is nothing to schedule."""

    def test_empty_commands(self):
        self.assertEqual(make_inplace(b"hello", []), [])
        self.assertEqual(make_inplace(b"hello", [], return_stats=True),
                         ([], {'cycles_broken': 0}))

    def test_adds_only(self):
        cmds = [AddCmd(data=b"abc"), AddCmd(data=b"de")]
        want = [PlacedAdd(dst=0, data=b"abc"), PlacedAdd(dst=3, data=b"de")]
        for pol in ['localmin', 'constant']:
            self.assertEqual(make_inplace(b"", cmds, policy=pol), want)
            self.assertEqual(
                make_inplace(b"", cmds, policy=pol, return_stats=True),
                (want, {'cycles_broken': 0}))

    def test_cli_encode_inplace(self):
        """encode --inplace works when the delta has no copies."""
        script = Path(__file__).with_name("delta.py")
        cases = [(b"", b"hello world"),            # empty reference
                 (b"hello world", b""),            # empty version
                 (bytes(range(64)), b"\xff" * 9),  # version shorter than a seed
                 (bytes(64), b"\xff" * 64)]        # nothing in common
        with tempfile.TemporaryDirectory() as tmp:
            ref, ver, dlt, out = (Path(tmp) / n for n in "rvdo")
            for R, V in cases:
                ref.write_bytes(R)
                ver.write_bytes(V)
                for cmd in (["encode", "onepass", ref, ver, dlt, "--inplace"],
                            ["decode", ref, dlt, out]):
                    proc = subprocess.run([sys.executable, script, *cmd],
                                          capture_output=True, text=True)
                    self.assertEqual(proc.returncode, 0, proc.stderr)
                self.assertTrue(is_inplace_delta(dlt.read_bytes()))
                self.assertEqual(out.read_bytes(), V)


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
                             src_crc=_crc64_xz(R), dst_crc=_crc64_xz(V))
        self.assertFalse(is_inplace_delta(delta))

    def test_inplace_detected(self):
        R = b"ABCDEFGH" * 10
        V = b"EFGHABCD" * 10
        cmds = diff_greedy(R, V, p=2)
        ip = make_inplace(R, cmds, policy='localmin')
        delta = encode_delta(ip, inplace=True, version_size=len(V),
                             src_crc=_crc64_xz(R), dst_crc=_crc64_xz(V))
        self.assertTrue(is_inplace_delta(delta))


def _make_blocks():
    """Return eight blocks of 200 to 4999 bytes.

    Each is the same 256-byte ramp at a different phase, so matches are
    not confined to block boundaries.
    """
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
        # Draw what the Permutation test draws, so the permutation below
        # differs from that test's.
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
    """Drop some blocks, duplicate others: |V| != |R|."""

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
    """Version is twice the reference: every block appears twice, shuffled."""

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
    """Version is much smaller: two blocks."""

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

    # The same through the binary format.
    def test_greedy_const_bin(self):    self._run_binary(diff_greedy, 'constant')
    def test_greedy_lmin_bin(self):     self._run_binary(diff_greedy, 'localmin')
    def test_onepass_const_bin(self):   self._run_binary(diff_onepass, 'constant')
    def test_onepass_lmin_bin(self):    self._run_binary(diff_onepass, 'localmin')
    def test_correcting_const_bin(self): self._run_binary(diff_correcting, 'constant')
    def test_correcting_lmin_bin(self):  self._run_binary(diff_correcting, 'localmin')


class TestInPlaceVarlenRandomTrials(unittest.TestCase):
    """20 random trials: random subset of 3 to 8 blocks in random order."""

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


def generate_transposed(num_blocks, block_size, num_transpositions, seed=42):
    """Return (R, V, swaps) where V is R with its blocks permuted.

    R is num_blocks blocks of block_size bytes.  V is R after
    num_transpositions swaps of two blocks chosen at random; swaps counts
    those that chose two different blocks.  Swapping two equal-sized
    blocks makes a CRWI cycle of two copies, each reading what the other
    writes.  Later swaps compose with earlier ones, so the number of
    cycles is not simply the number of swaps.
    """
    rng = random.Random(seed)
    # Each block starts with its index, so no two are equal (num_blocks <= 256).
    blocks = []
    for i in range(num_blocks):
        blk = bytes([i % 256] * 4) + bytes(rng.getrandbits(8) for _ in range(block_size - 4))
        blocks.append(blk)

    R = b''.join(blocks)

    perm = list(range(num_blocks))
    swaps_applied = 0
    for _ in range(num_transpositions):
        a = rng.randint(0, num_blocks - 1)
        b = rng.randint(0, num_blocks - 1)
        if a != b:
            perm[a], perm[b] = perm[b], perm[a]
            swaps_applied += 1

    V = b''.join(blocks[perm[i]] for i in range(num_blocks))
    return R, V, swaps_applied


class TestInPlaceTranspositions(unittest.TestCase):
    """In-place reconstruction with increasing numbers of transpositions.

    Transposed equal-sized blocks make CRWI cycles, which the converter
    must break by turning copies into adds.
    """

    BLOCK_SIZE = 200
    CONFIGS = [
        # (num_blocks, num_transpositions, seed)
        (8,   1,  100),
        (8,   4,  101),
        (16,  8,  102),
        (32, 16,  103),
        (32, 31,  104),
        (64, 50,  105),
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
    """The cases of TestInPlaceTranspositions through the binary format."""

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
    """Both cycle policies on transposed blocks of unequal sizes."""

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


class TestLocalminPicksSmallest(unittest.TestCase):
    """On the reversed blocks, localmin converts no more bytes to adds than
    constant does."""

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


class TestGetDR(unittest.TestCase):
    """_get_d_r factors n into d * 2^r with d odd."""

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
    """_witness finds a witness for a composite and none for a prime."""

    def test_composite_has_witness(self):
        self.assertTrue(_witness(2, 9))

    def test_prime_has_no_witness(self):
        # A prime has no witness in [2, n-1).
        for a in range(2, 12):
            self.assertFalse(_witness(a, 13), f"a={a} should not be a witness for 13")


class TestIsPrime(unittest.TestCase):
    """_is_prime, the deterministic Miller-Rabin test."""

    # The first 50 primes.
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
        self.assertTrue(_is_prime(1048573))    # TABLE_SIZE, the largest prime < 2^20
        self.assertTrue(_is_prime(2097143))    # largest prime < 2^21
        self.assertTrue(_is_prime(104729))     # 10000th prime

    def test_carmichael_numbers(self):
        # Carmichael numbers pass the Fermat test for every coprime base;
        # Miller-Rabin does not share the weakness.
        carmichaels = [561, 1105, 1729, 2465, 2821, 6601, 8911]
        for c in carmichaels:
            self.assertFalse(_is_prime(c), f"Carmichael number {c} should be composite")

    def test_mersenne_primes(self):
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
    """_next_prime(n) returns the smallest prime >= n."""

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
        # The results are primes, each >= its argument, and never decrease.
        # That no prime is skipped is not checked here.
        p = 2
        for n in range(2, 500):
            np = _next_prime(n)
            self.assertGreaterEqual(np, n)
            self.assertTrue(_is_prime(np), f"next_prime({n}) = {np} should be prime")
            if n > p:
                self.assertGreaterEqual(np, p)
            p = np


class TestCheckpointing(unittest.TestCase):
    """The correcting algorithm with checkpointing (Section 8).

    q is only a floor on the table size |C|, which grows with |R|; the
    comments below give the |C| and m that each case really gets.
    """

    def test_tiny_table_roundtrip(self):
        """A small table (q=7) with checkpointing produces correct output."""
        R = b'ABCDEFGHIJKLMNOP' * 20   # 320 bytes: |C| = 41, m = 15
        V = R[:160] + b'XXXXYYYY' + R[160:]
        cmds = diff_correcting(R, V, p=16, q=7)
        recovered = apply_delta(R, cmds)
        self.assertEqual(recovered, V)

    def test_various_table_sizes(self):
        """Correcting produces correct output across a range of table sizes."""
        rng = random.Random(42)
        R = bytes(rng.getrandbits(8) for _ in range(2000))
        V = R[:500] + bytes(rng.getrandbits(8) for _ in range(50)) + R[500:]
        # |C| = 251 (m = 16) for the first three, then q itself (m = 4, 1).
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
        """Checkpointing on long matches with a small table (q=31)."""
        # A 100-byte insertion in the middle; |C| = 1279, m = 17.
        R = bytes(range(256)) * 40  # 10240 bytes
        V = R[:5000] + b'X' * 100 + R[5000:]
        cmds = diff_correcting(R, V, p=16, q=31)
        recovered = apply_delta(R, cmds)
        self.assertEqual(recovered, V)


class TestCrc64(unittest.TestCase):
    """_crc64_xz against the CRC-64/XZ check values."""

    def test_output_length(self):
        self.assertEqual(len(_crc64_xz(b'')), DELTA_CRC_SIZE)
        self.assertEqual(len(_crc64_xz(b'hello')), DELTA_CRC_SIZE)

    def test_deterministic(self):
        self.assertEqual(_crc64_xz(b'test'), _crc64_xz(b'test'))

    def test_differs_from_different_input(self):
        self.assertNotEqual(_crc64_xz(b'hello'), _crc64_xz(b'world'))

    def test_empty_input(self):
        # The CRC-64/XZ of empty input is 0.
        self.assertEqual(_crc64_xz(b''), bytes(8))

    def test_check_value(self):
        # The standard check value: the CRC of b"123456789" is 0x995DC9BBDF1939FA.
        expected = bytes.fromhex('995dc9bbdf1939fa')
        self.assertEqual(_crc64_xz(b'123456789'), expected)


class TestCrcEmbeddedInDelta(unittest.TestCase):
    """The CRCs in the header survive encoding and decoding."""

    def test_real_crc_roundtrip(self):
        R = b"reference data for testing " * 5
        V = b"version data for testing " * 5
        sc = _crc64_xz(R)
        dc = _crc64_xz(V)
        placed = place_commands(diff_greedy(R, V, p=4))
        delta = encode_delta(placed, inplace=False, version_size=len(V),
                             src_crc=sc, dst_crc=dc)
        _, _, _, sc2, dc2 = decode_delta(delta)
        self.assertEqual(sc2, sc)
        self.assertEqual(dc2, dc)

    def test_crc_mismatch_detection(self):
        """Caller can detect wrong source file by comparing CRCs."""
        R = b"correct reference data " * 5
        V = b"version data " * 5
        wrong_R = b"wrong reference data " * 5
        sc = _crc64_xz(R)
        dc = _crc64_xz(V)
        placed = place_commands(diff_greedy(R, V, p=4))
        delta = encode_delta(placed, inplace=False, version_size=len(V),
                             src_crc=sc, dst_crc=dc)
        _, _, _, sc2, _ = decode_delta(delta)
        self.assertNotEqual(_crc64_xz(wrong_R), sc2)

    def test_crc_size_constant(self):
        self.assertEqual(DELTA_CRC_SIZE, 8)


class TestSingleByte(unittest.TestCase):
    """One-byte inputs with p=1, the shortest seed."""

    def _run(self, fn):
        # Identical bytes: no adds.
        cmds = fn(b'\xAB', b'\xAB', p=1)
        self.assertEqual(apply_delta(b'\xAB', cmds), b'\xAB')
        self.assertFalse(any(isinstance(c, AddCmd) for c in cmds))

        # Different bytes.
        self.assertEqual(apply_delta(b'\xAB', fn(b'\xAB', b'\xCD', p=1)), b'\xCD')

        # Empty V: no commands.
        self.assertEqual(fn(b'\xAB', b'', p=1), [])

        # Empty R.
        self.assertEqual(apply_delta(b'', fn(b'', b'\xAB', p=1)), b'\xAB')

    def test_greedy(self):     self._run(diff_greedy)
    def test_onepass(self):    self._run(diff_onepass)
    def test_correcting(self): self._run(diff_correcting)


class TestBoundaryByteMutations(unittest.TestCase):
    """Changes at the first and last byte of V: flip, append, drop."""

    _base = bytes(range(64))

    def _run(self, fn):
        base = self._base
        for v in [
            bytes([base[0] ^ 0xFF]) + base[1:],   # flip first byte
            base[:-1] + bytes([base[-1] ^ 0xFF]),  # flip last byte
            base + b'\xAB',                         # append one byte
            base[:-1],                              # drop last byte
        ]:
            self.assertEqual(roundtrip(fn, base, v, p=2), v)

    def test_greedy(self):     self._run(diff_greedy)
    def test_onepass(self):    self._run(diff_onepass)
    def test_correcting(self): self._run(diff_correcting)


class TestRefShorterThanSeed(unittest.TestCase):
    """When |R| < p there are no seeds in R and the delta is all adds."""

    def _run(self, fn):
        p = 16
        V = bytes(range(32))
        for r_len in [0, 1, p - 1]:
            R = bytes(range(r_len))
            self.assertEqual(roundtrip(fn, R, V, p=p), V, f"|R|={r_len}")

    def test_greedy(self):     self._run(diff_greedy)
    def test_onepass(self):    self._run(diff_onepass)
    def test_correcting(self): self._run(diff_correcting)


class TestSizeSweep(unittest.TestCase):
    """Round trips at sizes around zero, the seed length and powers of two."""

    def _run(self, fn):
        p = 4
        for n in [0, 1, 2, 3, 4, 5, 7, 8, 9, 63, 64, 65,
                  127, 128, 129, 255, 256, 257, 511, 512, 513]:
            R = bytes(i % 256 for i in range(n))
            V = bytes((i + 1) % 256 for i in range(n))
            self.assertEqual(roundtrip(fn, R, V, p=p), V, f"size={n}")

    def test_greedy(self):     self._run(diff_greedy)
    def test_onepass(self):    self._run(diff_onepass)
    def test_correcting(self): self._run(diff_correcting)


class TestEncodingVersionSizeBoundaries(unittest.TestCase):
    """version_size survives encoding at byte and power-of-two boundaries."""

    _z = b'\x00' * 8

    def test_boundaries(self):
        for size in [127, 128, 255, 256, 32767, 32768,
                     65535, 65536, 8388607, 8388608, 16777215, 16777216]:
            delta = encode_delta([], inplace=False, version_size=size,
                                 src_crc=self._z, dst_crc=self._z)
            _, _, vs, _, _ = decode_delta(delta)
            self.assertEqual(vs, size, f"version_size={size}")


class TestEncodingCommandFieldBoundaries(unittest.TestCase):
    """Copy and add fields survive encoding at byte and power-of-two boundaries."""

    _z = b'\x00' * 8

    def _check_copy(self, src, dst, length):
        placed = [PlacedCopy(src=src, dst=dst, length=length)]
        delta = encode_delta(placed, inplace=False, version_size=dst + length,
                             src_crc=self._z, dst_crc=self._z)
        cmds, _, _, _, _ = decode_delta(delta)
        self.assertEqual(len(cmds), 1)
        c = cmds[0]
        self.assertIsInstance(c, PlacedCopy)
        self.assertEqual((c.src, c.dst, c.length), (src, dst, length))

    def _check_add(self, dst, data):
        placed = [PlacedAdd(dst=dst, data=data)]
        delta = encode_delta(placed, inplace=False, version_size=dst + len(data),
                             src_crc=self._z, dst_crc=self._z)
        cmds, _, _, _, _ = decode_delta(delta)
        self.assertEqual(len(cmds), 1)
        c = cmds[0]
        self.assertIsInstance(c, PlacedAdd)
        self.assertEqual(c.dst, dst)
        self.assertEqual(c.data, data)

    def test_copy_boundaries(self):
        for v in [127, 128, 255, 256, 32767, 32768]:
            self._check_copy(src=v, dst=0, length=1)
            self._check_copy(src=0, dst=v, length=1)
            self._check_copy(src=0, dst=0, length=v)

    def test_add_boundaries(self):
        for dst in [127, 128, 255, 256, 32767, 32768]:
            self._check_add(dst=dst, data=b'\xAB')
        for length in [127, 128, 255, 256]:
            self._check_add(dst=0, data=bytes(range(length)))


class TestInplaceVersionOneLargerTight(unittest.TestCase):
    """In-place reconstruction when |V| = |R| + 1."""

    def _run(self, fn, pol):
        for n in [1, 2, 3, 4, 7, 8, 15, 16, 17, 31, 32, 63, 64]:
            R = bytes(i % 256 for i in range(n))
            V = bytes(i % 256 for i in range(n + 1))
            self.assertEqual(
                inplace_roundtrip(fn, R, V, policy=pol, p=2), V, f"n={n}")

    def _run_all(self, fn):
        for pol in ['localmin', 'constant']:
            self._run(fn, pol)

    def test_greedy(self):     self._run_all(diff_greedy)
    def test_onepass(self):    self._run_all(diff_onepass)
    def test_correcting(self): self._run_all(diff_correcting)


class TestInplaceVersionOneSmallerTight(unittest.TestCase):
    """In-place reconstruction when |V| = |R| - 1."""

    def _run(self, fn, pol):
        for n in [2, 3, 4, 5, 8, 9, 15, 16, 17, 31, 32, 65]:
            R = bytes(i % 256 for i in range(n))
            V = bytes(i % 256 for i in range(n - 1))
            self.assertEqual(
                inplace_roundtrip(fn, R, V, policy=pol, p=2), V, f"n={n}")

    def _run_all(self, fn):
        for pol in ['localmin', 'constant']:
            self._run(fn, pol)

    def test_greedy(self):     self._run_all(diff_greedy)
    def test_onepass(self):    self._run_all(diff_onepass)
    def test_correcting(self): self._run_all(diff_correcting)


class TestInplaceVersionSameSizeTight(unittest.TestCase):
    """In-place reconstruction when |V| = |R|: the halves of R swapped."""

    def _run(self, fn, pol):
        for n in [2, 4, 8, 16, 32, 64, 128, 256]:
            half = n // 2
            R = bytes(range(n))
            V = R[half:] + R[:half]
            self.assertEqual(
                inplace_roundtrip(fn, R, V, policy=pol, p=2), V, f"n={n}")

    def _run_all(self, fn):
        for pol in ['localmin', 'constant']:
            self._run(fn, pol)

    def test_greedy(self):     self._run_all(diff_greedy)
    def test_onepass(self):    self._run_all(diff_onepass)
    def test_correcting(self): self._run_all(diff_correcting)


class TestInplaceVersionOneByteMin(unittest.TestCase):
    """In-place reconstruction of a one-byte version."""

    def _run(self, fn, pol):
        # A copy: R contains the byte.
        self.assertEqual(
            inplace_roundtrip(fn, b'\xAB' * 16, b'\xAB', policy=pol, p=1), b'\xAB')
        # An add: with p=2 a one-byte V has no seed.
        self.assertEqual(
            inplace_roundtrip(fn, b'\x00' * 16, b'\xFF', policy=pol, p=2), b'\xFF')

    def _run_all(self, fn):
        for pol in ['localmin', 'constant']:
            self._run(fn, pol)

    def test_greedy(self):     self._run_all(diff_greedy)
    def test_onepass(self):    self._run_all(diff_onepass)
    def test_correcting(self): self._run_all(diff_correcting)


class TestSeedLengthBoundaries(unittest.TestCase):
    """Seed lengths 1, 2, |R| and |R| + 1."""

    _R = bytes(range(16)) * 4                          # 64 bytes, repeating
    _V = bytes(range(16)) * 3 + bytes(range(15, -1, -1))  # last block reversed

    def _run(self, fn):
        R, V = self._R, self._V
        self.assertEqual(roundtrip(fn, R, V, p=1), V)
        self.assertEqual(roundtrip(fn, R, V, p=2), V)
        self.assertEqual(roundtrip(fn, R, V, p=len(R)), V)      # one seed in R
        self.assertEqual(roundtrip(fn, R, V, p=len(R) + 1), V)  # no seeds in R

    def test_greedy(self):     self._run(diff_greedy)
    def test_onepass(self):    self._run(diff_onepass)
    def test_correcting(self): self._run(diff_correcting)


class TestRealDataRoundTrip(unittest.TestCase):
    """Round trip from README.md to HOWTO.md, the repository's own files."""

    @classmethod
    def setUpClass(cls):
        root = Path(__file__).resolve().parents[2]
        cls.R = (root / "README.md").read_bytes()
        cls.V = (root / "HOWTO.md").read_bytes()

    def _run(self, fn):
        self.assertEqual(roundtrip(fn, self.R, self.V, p=8), self.V)

    def test_greedy(self):     self._run(diff_greedy)
    def test_onepass(self):    self._run(diff_onepass)
    def test_correcting(self): self._run(diff_correcting)


def _zero_crc():
    return b'\x00' * DELTA_CRC_SIZE


def _roundtrip_large(commands, version_size, R=b''):
    """Encode as DLT\\x04, decode and apply to R; return the result."""
    delta = encode_delta_large(commands, version_size=version_size,
                               src_crc=_zero_crc(), dst_crc=_zero_crc())
    cmds2, is_ip, vs, _, _ = decode_delta(delta)
    assert vs == version_size
    assert delta[:4] == DELTA_MAGIC_LARGE
    buf = bytearray(version_size)
    apply_placed_to(R, cmds2, buf)
    return bytes(buf)


class TestDltLargeHeader(unittest.TestCase):
    """The DLT\\x04 header: magic, flags, u64 version size."""

    def test_magic_large(self):
        delta = encode_delta_large([], version_size=0,
                                   src_crc=_zero_crc(), dst_crc=_zero_crc())
        self.assertEqual(delta[:4], DELTA_MAGIC_LARGE)

    def test_small_magic_unchanged(self):
        delta = encode_delta([], version_size=0,
                             src_crc=_zero_crc(), dst_crc=_zero_crc())
        self.assertEqual(delta[:4], DELTA_MAGIC)

    def test_version_size_u64_large(self):
        big = 2**32 + 999
        delta = encode_delta_large([], version_size=big,
                                   src_crc=_zero_crc(), dst_crc=_zero_crc())
        _, _, vs, _, _ = decode_delta(delta)
        self.assertEqual(vs, big)

    def test_header_size_large(self):
        # header (29) + END byte (1)
        delta = encode_delta_large([], version_size=0,
                                   src_crc=_zero_crc(), dst_crc=_zero_crc())
        self.assertEqual(len(delta), 30)

    def test_inplace_flag_large(self):
        delta = encode_delta_large([], inplace=True, version_size=0,
                                   src_crc=_zero_crc(), dst_crc=_zero_crc())
        _, is_ip, _, _, _ = decode_delta(delta)
        self.assertTrue(is_ip)


class TestDltLargeCopy(unittest.TestCase):
    """The choice between COPY and BIGCOPY."""

    def test_copy_small_fields(self):
        R = b'ABCDEFGH'
        cmds = [PlacedCopy(src=0, dst=0, length=8)]
        result = _roundtrip_large(cmds, version_size=8, R=R)
        self.assertEqual(result, R)

    def test_bigcopy_large_src(self):
        # A src above 2^32 - 1 needs BIGCOPY.  No reference that large is
        # allocated; only the command's type byte is checked.
        big_src = 2**32 + 1
        cmds = [PlacedCopy(src=big_src, dst=0, length=1)]
        delta = encode_delta_large(cmds, version_size=1,
                                   src_crc=_zero_crc(), dst_crc=_zero_crc())
        self.assertEqual(delta[29], 3)  # DELTA_CMD_BIGCOPY = 3

    def test_bigcopy_roundtrip_decode(self):
        header = DELTA_MAGIC_LARGE + bytes([0]) + struct.pack('>Q', 100)
        header += _zero_crc() + _zero_crc()
        big_src = 2**32 + 7
        big_dst = 0
        big_len = 5
        body = bytes([3]) + struct.pack('>QQQ', big_src, big_dst, big_len)
        body += bytes([0])  # END
        data = header + body
        cmds, _, vs, _, _ = decode_delta(data)
        self.assertEqual(len(cmds), 1)
        self.assertIsInstance(cmds[0], PlacedCopy)
        self.assertEqual(cmds[0].src, big_src)
        self.assertEqual(cmds[0].length, big_len)


class TestDltLargeAdd(unittest.TestCase):
    """The choice between ADD and BIGADD."""

    def test_add_small(self):
        cmds = [PlacedAdd(dst=0, data=b'hello')]
        result = _roundtrip_large(cmds, version_size=5)
        self.assertEqual(result, b'hello')

    def test_bigadd_large_dst_command_byte(self):
        big_dst = 2**32 + 1
        cmds = [PlacedAdd(dst=big_dst, data=b'x')]
        delta = encode_delta_large(cmds, version_size=big_dst + 1,
                                   src_crc=_zero_crc(), dst_crc=_zero_crc())
        self.assertEqual(delta[29], 4)  # DELTA_CMD_BIGADD = 4


class TestDltLargeMove(unittest.TestCase):
    """MOVE and BIGMOVE, which copy from the output already written."""

    def test_move_basic(self):
        R = b''
        cmds = [
            PlacedAdd(dst=0, data=b'ABC'),
            PlacedMove(src=0, dst=3, length=3),
        ]
        result = _roundtrip_large(cmds, version_size=6, R=R)
        self.assertEqual(result, b'ABCABC')

    def test_move_command_byte(self):
        cmds = [PlacedMove(src=0, dst=3, length=3)]
        delta = encode_delta_large(cmds, version_size=6,
                                   src_crc=_zero_crc(), dst_crc=_zero_crc())
        self.assertEqual(delta[29], 5)  # DELTA_CMD_MOVE = 5

    def test_bigmove_command_byte(self):
        cmds = [PlacedMove(src=0, dst=2**32 + 1, length=1)]
        delta = encode_delta_large(cmds, version_size=2**32 + 2,
                                   src_crc=_zero_crc(), dst_crc=_zero_crc())
        self.assertEqual(delta[29], 6)  # DELTA_CMD_BIGMOVE = 6

    def test_apply_placed_sizes_moves(self):
        cmds = [PlacedAdd(dst=0, data=b'ABC'), PlacedMove(src=0, dst=3, length=3)]
        self.assertEqual(apply_placed(b'', cmds), b'ABCABC')

    def test_move_overlap_rejected(self):
        # A move may read only what is already written: src + length <= dst.
        header = DELTA_MAGIC_LARGE + bytes([0]) + struct.pack('>Q', 10)
        header += _zero_crc() + _zero_crc()
        # MOVE src=5 dst=7 length=4: 5 + 4 > 7.
        body = bytes([5]) + struct.pack('>III', 5, 7, 4) + bytes([0])
        with self.assertRaises(ValueError):
            decode_delta(header + body)

    def test_move_chained(self):
        # Each move doubles the output.
        cmds = [
            PlacedAdd(dst=0, data=b'X'),
            PlacedMove(src=0, dst=1, length=1),
            PlacedMove(src=0, dst=2, length=2),
            PlacedMove(src=0, dst=4, length=4),
        ]
        result = _roundtrip_large(cmds, version_size=8)
        self.assertEqual(result, b'XXXXXXXX')


class TestDltLargeRejected(unittest.TestCase):
    """DLT\\x04 commands are rejected in a DLT\\x03 delta, by encoder and decoder."""

    def test_v3_encoder_rejects_move(self):
        cmds = [PlacedMove(src=0, dst=3, length=3)]
        with self.assertRaises(ValueError):
            encode_delta(cmds, version_size=6,
                         src_crc=_zero_crc(), dst_crc=_zero_crc())

    def test_bigcopy_in_v3_stream_rejected(self):
        header = DELTA_MAGIC + bytes([0]) + struct.pack('>I', 10)
        header += _zero_crc() + _zero_crc()
        body = bytes([3]) + struct.pack('>QQQ', 0, 0, 5) + bytes([0])
        with self.assertRaises(ValueError):
            decode_delta(header + body)

    def test_unknown_magic_rejected(self):
        bad = b'DLT\x99' + bytes([0]) + struct.pack('>I', 0)
        bad += _zero_crc() + _zero_crc() + bytes([0])
        with self.assertRaises(ValueError):
            decode_delta(bad)


class TestDltLargeAlgoRoundtrip(unittest.TestCase):
    """Each algorithm round trips through DLT\\x04."""

    def _roundtrip_algo(self, algo_fn, R, V):
        cmds = algo_fn(R, V, p=4)
        placed = place_commands(cmds)
        delta = encode_delta_large(placed, version_size=len(V),
                                   src_crc=_crc64_xz(R), dst_crc=_crc64_xz(V))
        self.assertEqual(delta[:4], DELTA_MAGIC_LARGE)
        placed2, _, vs, sc, dc = decode_delta(delta)
        self.assertEqual(vs, len(V))
        self.assertEqual(sc, _crc64_xz(R))
        self.assertEqual(dc, _crc64_xz(V))
        return apply_placed(R, placed2)

    def test_greedy_large(self):
        R, V = b'hello world', b'hello earth'
        self.assertEqual(self._roundtrip_algo(diff_greedy, R, V), V)

    def test_onepass_large(self):
        R, V = b'abcdefgh' * 10, b'abcdefgh' * 5 + b'XXXXXXXX' * 5
        self.assertEqual(self._roundtrip_algo(diff_onepass, R, V), V)

    def test_correcting_large(self):
        R, V = b'the quick brown fox', b'the slow brown fox'
        self.assertEqual(self._roundtrip_algo(diff_correcting, R, V), V)


if __name__ == '__main__':
    unittest.main()
