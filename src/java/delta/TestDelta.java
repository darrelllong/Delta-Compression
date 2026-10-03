package delta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static delta.Types.*;

/**
 * Tests for the library, run by {@code make test} or
 * {@code java -cp out delta.TestDelta} from src/java.  The real-data test
 * reads README.md and HOWTO.md from two directories up.
 */
public class TestDelta {

    static int pass, fail, tests;

    static final Algorithm[]   ALL_ALGOS    = Algorithm.values();
    static final CyclePolicy[] ALL_POLICIES = CyclePolicy.values();

    static void assertArrayEquals(byte[] expected, byte[] actual, String msg) {
        if (!Arrays.equals(expected, actual))
            throw new AssertionError(String.format(
                "%s: expected %d bytes, got %d", msg, expected.length, actual.length));
    }

    static void assertArrayEquals(byte[] expected, byte[] actual) {
        assertArrayEquals(expected, actual, "byte arrays differ");
    }

    static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    static void assertFalse(boolean cond, String msg) {
        if (cond) throw new AssertionError(msg);
    }

    /**
     * Asserts that body throws an IllegalArgumentException, with the given
     * message unless message is null.
     */
    static void assertRejects(String what, Runnable body, String message) {
        try {
            body.run();
        } catch (IllegalArgumentException e) {
            if (message != null && !message.equals(e.getMessage()))
                throw new AssertionError(String.format(
                    "%s: expected \"%s\", got \"%s\"", what, message, e.getMessage()));
            return;
        }
        throw new AssertionError(what + " should be rejected");
    }

    static void assertEquals(long expected, long actual, String msg) {
        if (expected != actual)
            throw new AssertionError(String.format(
                "%s: expected %d, got %d", msg, expected, actual));
    }

    static void check(String name, Runnable r) {
        tests++;
        try {
            r.run();
            pass++;
            System.out.printf("  ok  %s%n", name);
        } catch (Throwable t) {
            fail++;
            String msg = t.getMessage();
            System.out.printf("FAIL  %s: %s%n", name, msg != null ? msg : t.toString());
        }
    }

    /** Returns the default options with seed length p. */
    static DiffOptions opts(int p) {
        DiffOptions o = new DiffOptions();
        o.p = p;
        return o;
    }

    static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] a : parts) total += a.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] a : parts) {
            System.arraycopy(a, 0, out, pos, a.length);
            pos += a.length;
        }
        return out;
    }

    static byte[] repeat(byte[] data, int n) {
        byte[] out = new byte[data.length * n];
        for (int i = 0; i < n; i++)
            System.arraycopy(data, 0, out, i * data.length, data.length);
        return out;
    }

    /** Returns the bytes of s, one per char (Latin-1). */
    static byte[] b(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Stands in for the CRCs, which the library carries but does not check. */
    static final byte[] ZERO_HASH = new byte[DELTA_CRC_SIZE];

    /** Returns what diff, place, encode, decode and apply make of v. */
    static byte[] roundtrip(Algorithm algo, byte[] r, byte[] v, int p) {
        List<Command> cmds = Diff.diff(algo, r, v, opts(p));
        List<PlacedCommand> placed = Apply.placeCommands(cmds);
        byte[] delta = Encoding.encodeDelta(placed, false, Apply.outputSize(cmds), ZERO_HASH, ZERO_HASH);
        Encoding.DecodeResult res = Encoding.decodeDelta(delta);
        byte[] out = new byte[(int) res.versionSize()];
        Apply.applyPlacedTo(r, res.commands(), out);
        return out;
    }

    /** Returns what diff, makeInplace and applyDeltaInplace make of v, with no encoding. */
    static byte[] inplaceRoundtrip(Algorithm algo, byte[] r, byte[] v,
                                    CyclePolicy pol, int p) {
        List<Command> cmds = Diff.diff(algo, r, v, opts(p));
        List<PlacedCommand> ip = Apply.makeInplace(r, cmds, pol);
        return Apply.applyDeltaInplace(r, ip, v.length);
    }

    /** As inplaceRoundtrip, but through encode and decode. */
    static byte[] inplaceBinaryRoundtrip(Algorithm algo, byte[] r, byte[] v,
                                          CyclePolicy pol, int p) {
        List<Command> cmds = Diff.diff(algo, r, v, opts(p));
        List<PlacedCommand> ip = Apply.makeInplace(r, cmds, pol);
        byte[] delta = Encoding.encodeDelta(ip, true, v.length, ZERO_HASH, ZERO_HASH);
        Encoding.DecodeResult res = Encoding.decodeDelta(delta);
        return Apply.applyDeltaInplace(r, res.commands(), res.versionSize());
    }

    /**
     * Returns the in-place delta that the {@code inplace} subcommand would
     * write: a standard delta, decoded, unplaced, converted and encoded again.
     */
    static byte[] viaInplaceSubcommand(Algorithm algo, byte[] r, byte[] v,
                                        CyclePolicy pol, int p) {
        List<Command> cmds = Diff.diff(algo, r, v, opts(p));
        List<PlacedCommand> placed = Apply.placeCommands(cmds);
        byte[] standard = Encoding.encodeDelta(placed, false, v.length, ZERO_HASH, ZERO_HASH);
        Encoding.DecodeResult res = Encoding.decodeDelta(standard);
        assertFalse(res.inplace(), "standard delta should not be flagged as inplace");
        List<Command> cmds2 = Apply.unplaceCommands(res.commands());
        List<PlacedCommand> ip = Apply.makeInplace(r, cmds2, pol);
        return Encoding.encodeDelta(ip, true, res.versionSize(), ZERO_HASH, ZERO_HASH);
    }

    /** Returns eight blocks of different lengths; byte j of block i is (i * 37 + j) mod 256. */
    static List<byte[]> makeBlocks() {
        int[] sizes = {200, 500, 1234, 3000, 800, 4999, 1500, 2750};
        List<byte[]> blocks = new ArrayList<>();
        for (int i = 0; i < sizes.length; i++) {
            byte[] blk = new byte[sizes[i]];
            for (int j = 0; j < sizes[i]; j++)
                blk[j] = (byte) ((i * 37 + j) & 0xFF);
            blocks.add(blk);
        }
        return blocks;
    }

    /** Returns the reference for the block tests: the blocks in order. */
    static byte[] blocksRef(List<byte[]> blocks) {
        return concat(blocks.toArray(new byte[0][]));
    }

    /** Shuffles arr in place (Fisher-Yates). */
    static void shuffle(int[] arr, Random rng) {
        for (int i = arr.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp;
        }
    }

    /** The example of Section 2.1.1. */
    static void testPaperExample() {
        byte[] r = b("ABCDEFGHIJKLMNOP");
        byte[] v = b("QWIJKLMNOBCDEFGHZDEFGHIJKL");
        for (Algorithm algo : ALL_ALGOS) {
            List<Command> cmds = Diff.diff(algo, r, v, opts(2));
            assertArrayEquals(v, Apply.applyDelta(r, cmds), algo + " paper example");
        }
    }

    static void testIdentical() {
        byte[] data = repeat(b("The quick brown fox jumps over the lazy dog."), 10);
        for (Algorithm algo : ALL_ALGOS) {
            List<Command> cmds = Diff.diff(algo, data, data, opts(2));
            assertArrayEquals(data, Apply.applyDelta(data, cmds), algo + " identical roundtrip");
            for (Command c : cmds)
                assertTrue(c instanceof CopyCmd, algo + ": identical input should produce no adds");
        }
    }

    static void testCompletelyDifferent() {
        // R is 0x00..0xFF twice and V is its reverse, so they share no 2-byte seed.
        byte[] r = new byte[512], v = new byte[512];
        for (int i = 0; i < 512; i++) {
            r[i] = (byte) (i & 0xFF);
            v[i] = (byte) ((255 - (i & 0xFF)) & 0xFF);
        }
        for (Algorithm algo : ALL_ALGOS)
            assertArrayEquals(v, Apply.applyDelta(r, Diff.diff(algo, r, v, opts(2))),
                algo + " completely different");
    }

    static void testEmptyVersion() {
        byte[] r = b("hello");
        for (Algorithm algo : ALL_ALGOS) {
            List<Command> cmds = Diff.diff(algo, r, new byte[0], opts(2));
            assertTrue(cmds.isEmpty(), algo + ": empty version should produce no commands");
            assertArrayEquals(new byte[0], Apply.applyDelta(r, cmds), algo + " empty version");
        }
    }

    static void testEmptyReference() {
        byte[] v = b("hello world");
        for (Algorithm algo : ALL_ALGOS)
            assertArrayEquals(v,
                Apply.applyDelta(new byte[0], Diff.diff(algo, new byte[0], v, opts(2))),
                algo + " empty reference");
    }

    static void testBinaryRoundtrip() {
        byte[] r = repeat(b("ABCDEFGHIJKLMNOPQRSTUVWXYZ"), 100);
        byte[] v = repeat(b("0123EFGHIJKLMNOPQRS456ABCDEFGHIJKL789"), 100);
        for (Algorithm algo : ALL_ALGOS)
            assertArrayEquals(v, roundtrip(algo, r, v, 4), algo + " binary roundtrip");
    }

    /** Encode and decode preserve the fields of hand-built commands. */
    static void testBinaryEncodingRoundtrip() {
        List<PlacedCommand> placed = new ArrayList<>();
        placed.add(new PlacedAdd(0, new byte[]{100, 101, 102}));
        placed.add(new PlacedCopy(888, 3, 488));
        byte[] encoded = Encoding.encodeDelta(placed, false, 491, ZERO_HASH, ZERO_HASH);
        Encoding.DecodeResult res = Encoding.decodeDelta(encoded);
        assertFalse(res.inplace(), "should not be inplace");
        assertEquals(491, res.versionSize(), "version size");
        assertEquals(2, res.commands().size(), "command count");
        PlacedAdd a = (PlacedAdd) res.commands().get(0);
        assertEquals(0, a.dst(), "add dst");
        assertArrayEquals(new byte[]{100, 101, 102}, a.data(), "add data");
        PlacedCopy c = (PlacedCopy) res.commands().get(1);
        assertEquals(888, c.src(), "copy src");
        assertEquals(3, c.dst(), "copy dst");
        assertEquals(488, c.length(), "copy length");
    }

    static void testDecodeRejectsMissingEnd() {
        byte[] encoded = Encoding.encodeDelta(new ArrayList<>(), false, 0, ZERO_HASH, ZERO_HASH);
        byte[] truncated = Arrays.copyOf(encoded, encoded.length - 1);
        assertRejects("missing END", () -> Encoding.decodeDelta(truncated), "missing END command");
    }

    static void testDecodeRejectsTrailingData() {
        byte[] encoded = Encoding.encodeDelta(new ArrayList<>(), false, 0, ZERO_HASH, ZERO_HASH);
        byte[] bad = Arrays.copyOf(encoded, encoded.length + 1);
        bad[bad.length - 1] = 0x7F;
        assertRejects("trailing data", () -> Encoding.decodeDelta(bad), "trailing data after END");
    }

    static void testDecodeRejectsCopyPastVersionSize() {
        List<PlacedCommand> cmds = new ArrayList<>();
        cmds.add(new PlacedCopy(0, 1, 2));
        assertRejects("copy past version size",
            () -> Encoding.decodeDelta(Encoding.encodeDelta(cmds, false, 2, ZERO_HASH, ZERO_HASH)),
            "COPY extends past version size");
    }

    static void testValidatePlacedCommandsRejectsSourceOverflow() {
        List<PlacedCommand> cmds = new ArrayList<>();
        cmds.add(new PlacedCopy(1, 0, 2));
        assertRejects("source overflow",
            () -> Apply.validatePlacedCommands(cmds, 2, 2, false),
            "copy source out of range");
    }

    /** The in-place flag is a header bit that the caller sets, whatever the commands are. */
    static void testBinaryEncodingInplaceFlag() {
        List<PlacedCommand> placed = new ArrayList<>();
        placed.add(new PlacedCopy(0, 10, 5));
        byte[] standard = Encoding.encodeDelta(placed, false, 15, ZERO_HASH, ZERO_HASH);
        byte[] inplace  = Encoding.encodeDelta(placed, true,  15, ZERO_HASH, ZERO_HASH);
        assertFalse(Encoding.isInplaceDelta(standard), "standard should not be inplace");
        assertTrue( Encoding.isInplaceDelta(inplace),  "inplace should be inplace");
        Encoding.DecodeResult r1 = Encoding.decodeDelta(standard);
        Encoding.DecodeResult r2 = Encoding.decodeDelta(inplace);
        assertFalse(r1.inplace(), "standard decoded inplace flag");
        assertTrue( r2.inplace(), "inplace decoded inplace flag");
        assertEquals(r1.versionSize(), r2.versionSize(), "version sizes match");
    }

    static void testLargeCopyRoundtrip() {
        List<PlacedCommand> placed = new ArrayList<>();
        placed.add(new PlacedCopy(100_000, 0, 50_000));
        byte[] encoded = Encoding.encodeDelta(placed, false, 50_000, ZERO_HASH, ZERO_HASH);
        Encoding.DecodeResult res = Encoding.decodeDelta(encoded);
        assertEquals(1, res.commands().size(), "command count");
        PlacedCopy c = (PlacedCopy) res.commands().get(0);
        assertEquals(100_000, c.src(), "copy src");
        assertEquals(0, c.dst(), "copy dst");
        assertEquals(50_000, c.length(), "copy length");
    }

    static void testLargeAddRoundtrip() {
        byte[] bigData = new byte[256 * 4];
        for (int i = 0; i < bigData.length; i++) bigData[i] = (byte) (i & 0xFF);
        List<PlacedCommand> placed = new ArrayList<>();
        placed.add(new PlacedAdd(0, bigData));
        byte[] encoded = Encoding.encodeDelta(placed, false, bigData.length, ZERO_HASH, ZERO_HASH);
        Encoding.DecodeResult res = Encoding.decodeDelta(encoded);
        assertEquals(1, res.commands().size(), "command count");
        PlacedAdd a = (PlacedAdd) res.commands().get(0);
        assertEquals(0, a.dst(), "add dst");
        assertArrayEquals(bigData, a.data(), "add data");
    }

    /** R and V share a block at different offsets, with other bytes before it in each. */
    static void testBackwardExtension() {
        byte[] block = repeat(b("ABCDEFGHIJKLMNOP"), 20);
        byte[] r = concat(b("____"), block, b("____"));
        byte[] v = concat(b("**"), block, b("**"));
        for (Algorithm algo : ALL_ALGOS)
            assertArrayEquals(v, Apply.applyDelta(r, Diff.diff(algo, r, v, opts(4))),
                algo + " backward extension");
    }

    static void testTransposition() {
        byte[] x = repeat(b("FIRST_BLOCK_DATA_"), 10);
        byte[] y = repeat(b("SECOND_BLOCK_DATA"), 10);
        byte[] r = concat(x, y);
        byte[] v = concat(y, x);
        for (Algorithm algo : ALL_ALGOS)
            assertArrayEquals(v, Apply.applyDelta(r, Diff.diff(algo, r, v, opts(4))),
                algo + " transposition");
    }

    /** V is 2000 random bytes of R with 100 of them overwritten at random. */
    static void testScatteredModifications() {
        Random rng = new Random(42);
        byte[] r = new byte[2000];
        rng.nextBytes(r);
        byte[] v = Arrays.copyOf(r, r.length);
        for (int i = 0; i < 100; i++)
            v[rng.nextInt(v.length)] = (byte) rng.nextInt(256);
        for (Algorithm algo : ALL_ALGOS)
            assertArrayEquals(v, roundtrip(algo, r, v, 4), algo + " scattered modifications");
    }

    static void testInplacePaperExample() {
        byte[] r = b("ABCDEFGHIJKLMNOP");
        byte[] v = b("QWIJKLMNOBCDEFGHZDEFGHIJKL");
        for (Algorithm algo : ALL_ALGOS)
            for (CyclePolicy pol : ALL_POLICIES)
                assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 2),
                    algo + "/" + pol + " inplace paper example");
    }

    static void testInplaceBinaryRoundtrip() {
        byte[] r = repeat(b("ABCDEFGHIJKLMNOPQRSTUVWXYZ"), 100);
        byte[] v = repeat(b("0123EFGHIJKLMNOPQRS456ABCDEFGHIJKL789"), 100);
        for (Algorithm algo : ALL_ALGOS)
            for (CyclePolicy pol : ALL_POLICIES)
                assertArrayEquals(v, inplaceBinaryRoundtrip(algo, r, v, pol, 4),
                    algo + "/" + pol + " inplace binary roundtrip");
    }

    static void testInplaceSimpleTransposition() {
        byte[] x = repeat(b("FIRST_BLOCK_DATA_"), 20);
        byte[] y = repeat(b("SECOND_BLOCK_DATA"), 20);
        byte[] r = concat(x, y);
        byte[] v = concat(y, x);
        for (Algorithm algo : ALL_ALGOS)
            for (CyclePolicy pol : ALL_POLICIES)
                assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4),
                    algo + "/" + pol + " inplace simple transposition");
    }

    /** |V| > |R|: the buffer is longer than the reference. */
    static void testInplaceVersionLarger() {
        byte[] r = repeat(b("ABCDEFGH"), 50);
        byte[] v = concat(repeat(b("XXABCDEFGH"), 50), repeat(b("YYABCDEFGH"), 50));
        for (Algorithm algo : ALL_ALGOS)
            for (CyclePolicy pol : ALL_POLICIES)
                assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4),
                    algo + "/" + pol + " inplace version larger");
    }

    /** |V| < |R|: the version is a prefix of the buffer. */
    static void testInplaceVersionSmaller() {
        byte[] r = repeat(b("ABCDEFGHIJKLMNOP"), 100);
        byte[] v = repeat(b("EFGHIJKL"), 50);
        for (Algorithm algo : ALL_ALGOS)
            for (CyclePolicy pol : ALL_POLICIES)
                assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4),
                    algo + "/" + pol + " inplace version smaller");
    }

    static void testInplaceIdentical() {
        byte[] data = repeat(b("The quick brown fox jumps over the lazy dog."), 10);
        for (Algorithm algo : ALL_ALGOS)
            for (CyclePolicy pol : ALL_POLICIES)
                assertArrayEquals(data, inplaceRoundtrip(algo, data, data, pol, 2),
                    algo + "/" + pol + " inplace identical");
    }

    static void testInplaceEmptyVersion() {
        byte[] r = b("hello");
        for (Algorithm algo : ALL_ALGOS) {
            List<Command> cmds = Diff.diff(algo, r, new byte[0], opts(2));
            List<PlacedCommand> ip = Apply.makeInplace(r, cmds, CyclePolicy.LOCALMIN);
            assertArrayEquals(new byte[0], Apply.applyDeltaInplace(r, ip, 0),
                algo + " inplace empty version");
        }
    }

    static void testInplaceScattered() {
        Random rng = new Random(99);
        byte[] r = new byte[2000];
        rng.nextBytes(r);
        byte[] v = Arrays.copyOf(r, r.length);
        for (int i = 0; i < 100; i++)
            v[rng.nextInt(v.length)] = (byte) rng.nextInt(256);
        for (Algorithm algo : ALL_ALGOS)
            for (CyclePolicy pol : ALL_POLICIES)
                assertArrayEquals(v, inplaceBinaryRoundtrip(algo, r, v, pol, 4),
                    algo + "/" + pol + " inplace scattered");
    }

    static void testStandardNotDetectedAsInplace() {
        byte[] r = repeat(b("ABCDEFGH"), 10);
        byte[] v = repeat(b("EFGHABCD"), 10);
        List<Command> cmds = Diff.diff(Algorithm.GREEDY, r, v, opts(2));
        List<PlacedCommand> placed = Apply.placeCommands(cmds);
        byte[] delta = Encoding.encodeDelta(placed, false, v.length, ZERO_HASH, ZERO_HASH);
        assertFalse(Encoding.isInplaceDelta(delta), "standard should not be detected as inplace");
    }

    static void testInplaceDetected() {
        byte[] r = repeat(b("ABCDEFGH"), 10);
        byte[] v = repeat(b("EFGHABCD"), 10);
        List<Command> cmds = Diff.diff(Algorithm.GREEDY, r, v, opts(2));
        List<PlacedCommand> ip = Apply.makeInplace(r, cmds, CyclePolicy.LOCALMIN);
        byte[] delta = Encoding.encodeDelta(ip, true, v.length, ZERO_HASH, ZERO_HASH);
        assertTrue(Encoding.isInplaceDelta(delta), "inplace delta should be detected");
    }

    /** V is a random permutation of the 8 blocks. */
    static void testInplaceVarlenPermutation() {
        List<byte[]> blocks = makeBlocks();
        byte[] r = blocksRef(blocks);
        Random rng = new Random(2003);
        int[] perm = {0, 1, 2, 3, 4, 5, 6, 7};
        shuffle(perm, rng);
        List<byte[]> parts = new ArrayList<>();
        for (int i : perm) parts.add(blocks.get(i));
        byte[] v = concat(parts.toArray(new byte[0][]));
        for (Algorithm algo : ALL_ALGOS)
            for (CyclePolicy pol : ALL_POLICIES)
                assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4),
                    algo + "/" + pol + " varlen permutation");
    }

    /** V is the 8 blocks in reverse order. */
    static void testInplaceVarlenReverse() {
        List<byte[]> blocks = makeBlocks();
        byte[] r = blocksRef(blocks);
        List<byte[]> rev = new ArrayList<>();
        for (int i = blocks.size() - 1; i >= 0; i--) rev.add(blocks.get(i));
        byte[] v = concat(rev.toArray(new byte[0][]));
        for (Algorithm algo : ALL_ALGOS)
            for (CyclePolicy pol : ALL_POLICIES)
                assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4),
                    algo + "/" + pol + " varlen reverse");
    }

    /** V is the 8 blocks permuted, each followed by 50 to 300 bytes that are not in R. */
    static void testInplaceVarlenJunk() {
        List<byte[]> blocks = makeBlocks();
        byte[] r = blocksRef(blocks);
        Random rng = new Random(20030);
        byte[] junk = new byte[300];
        rng.nextBytes(junk);
        int[] perm = {0, 1, 2, 3, 4, 5, 6, 7};
        shuffle(perm, rng);
        List<byte[]> parts = new ArrayList<>();
        for (int i : perm) {
            parts.add(blocks.get(i));
            int junkLen = 50 + rng.nextInt(251);   // 50..300
            parts.add(Arrays.copyOfRange(junk, 0, Math.min(junkLen, junk.length)));
        }
        byte[] v = concat(parts.toArray(new byte[0][]));
        for (Algorithm algo : ALL_ALGOS)
            for (CyclePolicy pol : ALL_POLICIES)
                assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4),
                    algo + "/" + pol + " varlen junk");
    }

    /** V drops some blocks and repeats others: 5 blocks drawn from the 8 of R. */
    static void testInplaceVarlenDropDup() {
        List<byte[]> blocks = makeBlocks();
        byte[] r = blocksRef(blocks);
        byte[] v = concat(blocks.get(3), blocks.get(0), blocks.get(0),
                          blocks.get(5), blocks.get(3));
        for (Algorithm algo : ALL_ALGOS)
            for (CyclePolicy pol : ALL_POLICIES)
                assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4),
                    algo + "/" + pol + " varlen drop+dup");
    }

    /** V is two independent shuffles of the 8 blocks, so |V| = 2 |R|. */
    static void testInplaceVarlenDoubleSized() {
        List<byte[]> blocks = makeBlocks();
        byte[] r = blocksRef(blocks);
        Random rng = new Random(7001);
        int[] p1 = {0, 1, 2, 3, 4, 5, 6, 7}; shuffle(p1, rng);
        int[] p2 = {0, 1, 2, 3, 4, 5, 6, 7}; shuffle(p2, rng);
        List<byte[]> parts = new ArrayList<>();
        for (int i : p1) parts.add(blocks.get(i));
        for (int i : p2) parts.add(blocks.get(i));
        byte[] v = concat(parts.toArray(new byte[0][]));
        for (Algorithm algo : ALL_ALGOS)
            for (CyclePolicy pol : ALL_POLICIES)
                assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4),
                    algo + "/" + pol + " varlen double-sized");
    }

    /** V is two of the 8 blocks, much shorter than R. */
    static void testInplaceVarlenSubset() {
        List<byte[]> blocks = makeBlocks();
        byte[] r = blocksRef(blocks);
        byte[] v = concat(blocks.get(6), blocks.get(2));
        for (Algorithm algo : ALL_ALGOS)
            for (CyclePolicy pol : ALL_POLICIES)
                assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4),
                    algo + "/" + pol + " varlen subset");
    }

    /** V is the 16 halves of the 8 blocks, shuffled. */
    static void testInplaceVarlenHalfBlockScramble() {
        List<byte[]> blocks = makeBlocks();
        byte[] r = blocksRef(blocks);
        List<byte[]> halves = new ArrayList<>();
        for (byte[] blk : blocks) {
            int mid = blk.length / 2;
            halves.add(Arrays.copyOfRange(blk, 0, mid));
            halves.add(Arrays.copyOfRange(blk, mid, blk.length));
        }
        Random rng = new Random(5555);
        int[] perm = new int[halves.size()];
        for (int i = 0; i < perm.length; i++) perm[i] = i;
        shuffle(perm, rng);
        List<byte[]> parts = new ArrayList<>();
        for (int i : perm) parts.add(halves.get(i));
        byte[] v = concat(parts.toArray(new byte[0][]));
        for (Algorithm algo : ALL_ALGOS)
            for (CyclePolicy pol : ALL_POLICIES) {
                assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4),
                    algo + "/" + pol + " half-block scramble (direct)");
                assertArrayEquals(v, inplaceBinaryRoundtrip(algo, r, v, pol, 4),
                    algo + "/" + pol + " half-block scramble (binary)");
            }
    }

    /** Twenty trials; in each, V is 3 to 8 distinct blocks in random order. */
    static void testInplaceVarlenRandomTrials() {
        List<byte[]> blocks = makeBlocks();
        byte[] r = blocksRef(blocks);
        Random rng = new Random(9999);
        // Every algorithm and policy gets the same 20 trials.
        int[][] trials = new int[20][];
        for (int t = 0; t < 20; t++) {
            int k = 3 + rng.nextInt(6);       // 3..8 inclusive
            int[] indices = {0, 1, 2, 3, 4, 5, 6, 7};
            shuffle(indices, rng);
            trials[t] = Arrays.copyOf(indices, k);
            shuffle(trials[t], rng);
        }
        for (Algorithm algo : ALL_ALGOS)
            for (CyclePolicy pol : ALL_POLICIES)
                for (int t = 0; t < 20; t++) {
                    List<byte[]> parts = new ArrayList<>();
                    for (int i : trials[t]) parts.add(blocks.get(i));
                    byte[] v = concat(parts.toArray(new byte[0][]));
                    assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4),
                        algo + "/" + pol + " random trial " + t);
                }
    }

    /** On blocks in reverse order, LOCALMIN adds no more literal bytes than CONSTANT. */
    static void testLocalminPicksSmallest() {
        List<byte[]> blocks = makeBlocks();
        byte[] r = blocksRef(blocks);
        List<byte[]> rev = new ArrayList<>();
        for (int i = blocks.size() - 1; i >= 0; i--) rev.add(blocks.get(i));
        byte[] v = concat(rev.toArray(new byte[0][]));
        List<Command> cmds = Diff.diff(Algorithm.GREEDY, r, v, opts(4));
        List<PlacedCommand> ipConst = Apply.makeInplace(r, cmds, CyclePolicy.CONSTANT);
        List<PlacedCommand> ipLmin  = Apply.makeInplace(r, cmds, CyclePolicy.LOCALMIN);
        long addConst = ipConst.stream()
            .filter(c -> c instanceof PlacedAdd)
            .mapToLong(c -> ((PlacedAdd) c).data().length)
            .sum();
        long addLmin = ipLmin.stream()
            .filter(c -> c instanceof PlacedAdd)
            .mapToLong(c -> ((PlacedAdd) c).data().length)
            .sum();
        assertTrue(addLmin <= addConst,
            "localmin (" + addLmin + ") should produce <= add bytes as constant (" + addConst + ")");
    }

    /**
     * Checkpointing with a table of 7 slots.  q is only a floor, and |R|
     * asks for 38 slots, so maxTable is what holds the table to 7; then
     * |F| = 613 and m = 88.
     */
    static void testCorrectingCheckpointingTinyTable() {
        byte[] r = repeat(b("ABCDEFGHIJKLMNOP"), 20);  // 320 bytes
        // v = r[0..160] + "XXXXYYYY" + r[160..]
        byte[] v = concat(
            Arrays.copyOfRange(r, 0, 160),
            b("XXXXYYYY"),
            Arrays.copyOfRange(r, 160, r.length)
        );
        DiffOptions o = new DiffOptions();
        o.p = 16;
        o.q = 7;
        o.maxTable = 7;
        assertEquals(7, Correcting.tableSize(Diff.seedCount(r, o.p), o), "table size");
        List<Command> cmds = Diff.diff(Algorithm.CORRECTING, r, v, o);
        assertArrayEquals(v, Apply.applyDelta(r, cmds), "correcting 7-slot table");
    }

    /**
     * Tables of several sizes.  q is only a floor, and |R| asks for 248
     * slots, so for the sizes below that maxTable is what sets the size.
     */
    static void testCorrectingCheckpointingVariousSizes() {
        byte[] r = new byte[2000];
        for (int i = 0; i < 2000; i++) r[i] = (byte) (i & 0xFF);
        // v = r[0..500] + fifty 0xFF bytes + r[500..]
        byte[] v = new byte[2050];
        System.arraycopy(r, 0, v, 0, 500);
        Arrays.fill(v, 500, 550, (byte) 0xFF);
        System.arraycopy(r, 500, v, 550, 1500);
        int[] qs = {7, 31, 101, 1009, TABLE_SIZE};
        for (int q : qs) {
            DiffOptions o = new DiffOptions();
            o.p = 16;
            o.q = q;
            o.maxTable = q;
            assertEquals(q, Correcting.tableSize(Diff.seedCount(r, o.p), o), "table size");
            List<Command> cmds = Diff.diff(Algorithm.CORRECTING, r, v, o);
            assertArrayEquals(v, Apply.applyDelta(r, cmds), "correcting, table of " + q);
        }
    }

    static void testNextPrimeIsPrime() {
        assertTrue(Hash.isPrime(TABLE_SIZE), "TABLE_SIZE should be prime");
        assertTrue(Hash.isPrime(Hash.nextPrime(1048574L)), "nextPrime(1048574) should be prime");
        assertEquals(1048573L, Hash.nextPrime(1048573L), "nextPrime of a prime is itself");
    }

    /** A delta converted by the inplace subcommand applies in place to give V. */
    static void testInplaceSubcommandRoundtrip() {
        byte[][] rs = {b("ABCDEF"), b("AAABBBCCC"), b("the quick brown fox"),
                       b("ABCDEF"), b("hello world"), new byte[0]};
        byte[][] vs = {b("FEDCBA"), b("CCCBBBAAA"), b("the quick brown cat"),
                       b("ABCDEF"), new byte[0],     b("hello world")};
        for (int i = 0; i < rs.length; i++) {
            byte[] r = rs[i], v = vs[i];
            for (Algorithm algo : ALL_ALGOS)
                for (CyclePolicy pol : ALL_POLICIES) {
                    byte[] ipDelta = viaInplaceSubcommand(algo, r, v, pol, 2);
                    Encoding.DecodeResult res = Encoding.decodeDelta(ipDelta);
                    byte[] recovered = Apply.applyDeltaInplace(r, res.commands(), v.length);
                    assertArrayEquals(v, recovered,
                        algo + "/" + pol + " subcommand roundtrip case " + i);
                }
        }
    }

    /**
     * An in-place delta decodes with the in-place flag set, which is how the
     * subcommand knows to pass it through.
     */
    static void testInplaceSubcommandIdempotent() {
        byte[] r = b("ABCDEFGHIJ");
        byte[] v = b("JIHGFEDCBA");
        for (Algorithm algo : ALL_ALGOS)
            for (CyclePolicy pol : ALL_POLICIES) {
                List<Command> cmds = Diff.diff(algo, r, v, opts(2));
                List<PlacedCommand> ip = Apply.makeInplace(r, cmds, pol);
                byte[] ipDelta = Encoding.encodeDelta(ip, true, v.length, ZERO_HASH, ZERO_HASH);
                Encoding.DecodeResult res = Encoding.decodeDelta(ipDelta);
                assertTrue(res.inplace(),
                    algo + "/" + pol + ": inplace delta should be detected as inplace");
            }
    }

    /** {@code encode --inplace} and encode followed by {@code inplace} give the same bytes. */
    static void testInplaceSubcommandEquivDirect() {
        byte[][] rs = {b("ABCDEF"), b("AAABBBCCC"),
                       b("the quick brown fox"), b("ABCDEFGHIJKLMNOP")};
        byte[][] vs = {b("FEDCBA"), b("CCCBBBAAA"),
                       b("the quick brown cat"), b("PONMLKJIHGFEDCBA")};
        for (int i = 0; i < rs.length; i++) {
            byte[] r = rs[i], v = vs[i];
            for (Algorithm algo : ALL_ALGOS)
                for (CyclePolicy pol : ALL_POLICIES) {
                    List<Command> cmds = Diff.diff(algo, r, v, opts(2));
                    List<PlacedCommand> ipDirect = Apply.makeInplace(r, cmds, pol);
                    byte[] directBytes = Encoding.encodeDelta(ipDirect, true, v.length, ZERO_HASH, ZERO_HASH);
                    byte[] subBytes = viaInplaceSubcommand(algo, r, v, pol, 2);
                    assertArrayEquals(directBytes, subBytes,
                        algo + "/" + pol + " subcommand vs direct case " + i);
                }
        }
    }

    /** Two blocks exchanged: two copies, each reading what the other writes, so one cycle. */
    static void testInplaceStats() {
        byte[] a = new byte[40], c = new byte[24];
        for (int i = 0; i < a.length; i++) a[i] = (byte) (i * 7 + 1);
        for (int i = 0; i < c.length; i++) c[i] = (byte) (200 - i * 3);
        byte[] r = concat(a, c), v = concat(c, a);
        List<Command> cmds = List.of(new CopyCmd(40, 24), new CopyCmd(0, 40));
        for (CyclePolicy pol : ALL_POLICIES) {
            InplaceResult res = Apply.makeInplaceWithStats(r, cmds, pol);
            InplaceStats s = res.stats();
            // LOCALMIN gives up the shorter copy, CONSTANT the first, which is the shorter here.
            assertEquals(1, s.numCopies(), pol + " copies kept");
            assertEquals(1, s.numAdds(), pol + " adds");
            assertEquals(2, s.edges(), pol + " edges");
            assertEquals(1, s.cyclesBroken(), pol + " cycles broken");
            assertEquals(1, s.copiesConverted(), pol + " copies converted");
            assertEquals(24, s.bytesConverted(), pol + " bytes converted");
            assertArrayEquals(v, Apply.applyDeltaInplace(r, res.commands(), v.length), pol + " stats roundtrip");
            assertArrayEquals(
                Encoding.encodeDelta(Apply.makeInplace(r, cmds, pol), true, v.length, ZERO_HASH, ZERO_HASH),
                Encoding.encodeDelta(res.commands(), true, v.length, ZERO_HASH, ZERO_HASH),
                pol + ": makeInplace and makeInplaceWithStats agree");
        }

        // Copies that do not conflict, and no copies at all.
        InplaceStats none = Apply.makeInplaceWithStats(r,
            List.of(new CopyCmd(0, 64), new AddCmd(b("xyz"))), CyclePolicy.LOCALMIN).stats();
        assertEquals(1, none.numCopies(), "identity copies");
        assertEquals(1, none.numAdds(), "identity adds");
        assertEquals(0, none.edges(), "identity edges");
        assertEquals(0, none.cyclesBroken(), "identity cycles");
        assertEquals(0, none.bytesConverted(), "identity bytes converted");
        InplaceStats adds = Apply.makeInplaceWithStats(r,
            List.of(new AddCmd(b("xyz"))), CyclePolicy.LOCALMIN).stats();
        assertEquals(0, adds.numCopies(), "add-only copies");
        assertEquals(1, adds.numAdds(), "add-only adds");
        assertEquals(0, adds.edges(), "add-only edges");
    }

    /** What a run of the command-line tool left behind. */
    record CliRun(int exit, String out, String err) {}

    /** Runs delta.Delta in a JVM of its own, since it ends with System.exit on failure. */
    static CliRun cli(String... args) throws IOException {
        List<String> argv = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), "delta.Delta"));
        argv.addAll(List.of(args));
        Path dir = Files.createTempDirectory("delta-cli");
        Path out = dir.resolve("out"), err = dir.resolve("err");
        try {
            Process proc = new ProcessBuilder(argv)
                .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
            int exit = proc.waitFor();
            return new CliRun(exit, Files.readString(out), Files.readString(err));
        } catch (InterruptedException e) {
            throw new IOException("interrupted waiting for delta.Delta");
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
            Files.delete(dir);
        }
    }

    /** Runs body with a scratch directory, which is removed afterwards with the files in it. */
    interface InDir { void run(Path dir) throws IOException; }

    static void withTempDir(InDir body) {
        try {
            Path dir = Files.createTempDirectory("delta-test");
            try {
                body.run(dir);
            } finally {
                try (var files = Files.list(dir)) {
                    for (Path f : (Iterable<Path>) files::iterator) Files.delete(f);
                }
                Files.delete(dir);
            }
        } catch (IOException e) {
            throw new AssertionError("I/O: " + e);
        }
    }

    /** Two blocks exchanged, written to dir as ref and ver; returns their paths and that of a delta. */
    static String[] swapFiles(Path dir) throws IOException {
        byte[] a = new byte[400], c = new byte[240];
        for (int i = 0; i < a.length; i++) a[i] = (byte) (i * 7 + 1);
        for (int i = 0; i < c.length; i++) c[i] = (byte) (i * i + 3);
        Files.write(dir.resolve("ref"), concat(a, c));
        Files.write(dir.resolve("ver"), concat(c, a));
        return new String[] {dir.resolve("ref").toString(), dir.resolve("ver").toString(),
                             dir.resolve("std.delta").toString()};
    }

    /** {@code inplace --verbose} reports the digraph and the conversion on stderr. */
    static void testCliInplaceVerbose() {
        withTempDir(dir -> {
            String[] f = swapFiles(dir);
            String ip = dir.resolve("ip.delta").toString(), out = dir.resolve("out").toString();
            assertEquals(0, cli("encode", "greedy", f[0], f[1], f[2]).exit(), "encode exit");

            CliRun quiet = cli("inplace", f[0], f[2], ip);
            assertEquals(0, quiet.exit(), "inplace exit");
            assertTrue(quiet.err().isEmpty(), "inplace without --verbose: stderr " + quiet.err());

            CliRun run = cli("inplace", f[0], f[2], ip, "--verbose");
            assertEquals(0, run.exit(), "inplace --verbose exit");
            String want = String.format("inplace: 2 copies, 2 CRWI edges, 1 cycles broken%n" +
                "  converted 1 copies -> adds (240 bytes materialized)%n");
            assertTrue(want.equals(run.err()), "inplace --verbose stderr: " + run.err());

            assertEquals(0, cli("decode", f[0], ip, out).exit(), "decode exit");
            assertArrayEquals(Files.readAllBytes(Path.of(f[1])), Files.readAllBytes(Path.of(out)),
                "decode of converted delta");
        });
    }

    /** {@code inplace} reads the reference to break cycles, so it refuses one with the wrong CRC. */
    static void testCliInplaceWrongReference() {
        withTempDir(dir -> {
            String[] f = swapFiles(dir);
            Path ip = dir.resolve("ip.delta");
            assertEquals(0, cli("encode", "greedy", f[0], f[1], f[2]).exit(), "encode exit");
            CliRun run = cli("inplace", f[1], f[2], ip.toString());
            assertEquals(1, run.exit(), "inplace with the version as reference: exit");
            assertTrue(run.err().startsWith("source file does not match delta: expected "),
                "stderr: " + run.err());
            assertFalse(Files.exists(ip), "no delta should be written");
        });
    }

    /** A MOVE has no unplaced form: the library throws, and the tool exits 1 with one line. */
    static void testInplaceRejectsMove() {
        byte[] hello = b("hello");
        List<PlacedCommand> cmds = List.of(
            new PlacedAdd(0, hello),
            new PlacedMove(0, 5, hello.length)
        );
        assertRejects("unplace of a MOVE", () -> Apply.unplaceCommands(cmds),
            "PlacedMove has no algorithm-level equivalent");

        withTempDir(dir -> {
            Path ref = dir.resolve("ref"), std = dir.resolve("std.delta"), ip = dir.resolve("ip.delta");
            Files.write(ref, new byte[0]);
            Files.write(std, Encoding.encodeDeltaLarge(cmds, false, 10,
                Hash.Crc64.hash8(new byte[0]), Hash.Crc64.hash8(b("hellohello")), false));
            CliRun run = cli("inplace", ref.toString(), std.toString(), ip.toString());
            assertEquals(1, run.exit(), "inplace of a delta with a MOVE: exit");
            String want = String.format(
                "inplace: %s has MOVE commands, which cannot be converted%n", std);
            assertTrue(want.equals(run.err()), "stderr: " + run.err());
            assertFalse(Files.exists(ip), "no delta should be written");
        });
    }

    /** With --ignore-hash a wrong reference draws warnings only; without it, the complaint and exit 1. */
    static void testCliDecodeIgnoreHash() {
        withTempDir(dir -> {
            String[] f = swapFiles(dir);
            Path out = dir.resolve("out");
            assertEquals(0, cli("encode", "greedy", f[0], f[1], f[2]).exit(), "encode exit");

            CliRun strict = cli("decode", f[1], f[2], out.toString());
            assertEquals(1, strict.exit(), "decode with the wrong reference: exit");
            assertTrue(strict.err().startsWith("source file does not match delta: expected "),
                "stderr: " + strict.err());
            assertFalse(Files.exists(out), "no output should be written");

            CliRun lax = cli("decode", f[1], f[2], out.toString(), "--ignore-hash");
            assertEquals(0, lax.exit(), "decode --ignore-hash exit");
            String want = String.format("warning: skipping source CRC check (--ignore-hash)%n" +
                "warning: skipping output CRC check (--ignore-hash)%n");
            assertTrue(want.equals(lax.err()), "decode --ignore-hash stderr: " + lax.err());
        });
    }

    /** With useSplay set, each algorithm still builds V. */
    static void testSplayRoundtrip() {
        byte[] r = repeat(b("ABCDEFGHIJKLMNOPQRSTUVWXYZ"), 100);
        byte[] v = repeat(b("0123EFGHIJKLMNOPQRS456ABCDEFGHIJKL789"), 100);
        for (Algorithm algo : ALL_ALGOS) {
            DiffOptions splOpts = opts(4);
            splOpts.useSplay = true;
            List<Command> cmds = Diff.diff(algo, r, v, splOpts);
            assertArrayEquals(v, Apply.applyDelta(r, cmds), algo + " splay roundtrip");
        }
    }

    static void testSingleByte() {
        byte[] one    = {0x41};
        byte[] other  = {0x42};
        byte[] empty  = {};
        for (Algorithm algo : ALL_ALGOS) {
            assertArrayEquals(one,   Apply.applyDelta(one,   Diff.diff(algo, one,   one,   opts(1))), algo + " 1b same");
            assertArrayEquals(other, Apply.applyDelta(one,   Diff.diff(algo, one,   other, opts(1))), algo + " 1b differ");
            assertArrayEquals(empty, Apply.applyDelta(one,   Diff.diff(algo, one,   empty, opts(1))), algo + " 1b r=1 v=0");
            assertArrayEquals(one,   Apply.applyDelta(empty, Diff.diff(algo, empty, one,   opts(1))), algo + " 1b r=0 v=1");
        }
    }

    static void testBoundaryByteMutations() {
        int n = 64, p = 4;
        byte[] r = new byte[n];
        for (int i = 0; i < n; i++) r[i] = (byte) i;

        byte[] vFirst  = Arrays.copyOf(r, n); vFirst[0]   ^= (byte) 0xFF;
        byte[] vLast   = Arrays.copyOf(r, n); vLast[n-1]  ^= (byte) 0xFF;
        byte[] vAppend = Arrays.copyOf(r, n + 1); vAppend[n] = (byte) 0x5A;
        byte[] vDrop   = Arrays.copyOf(r, n - 1);

        for (Algorithm algo : ALL_ALGOS) {
            assertArrayEquals(vFirst,  roundtrip(algo, r, vFirst,  p), algo + " byte[0] flipped");
            assertArrayEquals(vLast,   roundtrip(algo, r, vLast,   p), algo + " byte[n-1] flipped");
            assertArrayEquals(vAppend, roundtrip(algo, r, vAppend, p), algo + " one byte appended");
            assertArrayEquals(vDrop,   roundtrip(algo, r, vDrop,   p), algo + " last byte dropped");
        }
    }

    /** A reference shorter than p has no seed, so V is all adds. */
    static void testRefShorterThanSeed() {
        int p = 8;
        byte[] v = {0x10, 0x11, 0x12, 0x13, (byte)0xAA, (byte)0xBB, (byte)0xCC, (byte)0xDD};
        for (int rLen = 0; rLen < p; rLen++) {
            byte[] r = new byte[rLen];
            for (int i = 0; i < rLen; i++) r[i] = (byte) (i + 1);
            for (Algorithm algo : ALL_ALGOS)
                assertArrayEquals(v, Apply.applyDelta(r, Diff.diff(algo, r, v, opts(p))),
                    algo + " ref shorter than seed rLen=" + rLen);
        }
    }

    /** Sizes at 0, 1, p-1, p+1 and powers of two, where loop bounds are off by one if anywhere. */
    static void testSizeSweep() {
        int p = 4;
        int[] sizes = {0, 1, 2, 3, p-1, p, p+1, 2*p-1, 2*p, 2*p+1,
                       63, 64, 65, 127, 128, 129, 255, 256, 257, 511, 512, 513};
        byte[] bigRef = new byte[600];
        for (int i = 0; i < bigRef.length; i++) bigRef[i] = (byte) (i * 3 & 0xFF);

        for (int vLen : sizes) {
            byte[] vPrefix = Arrays.copyOf(bigRef, vLen);
            for (Algorithm algo : ALL_ALGOS)
                assertArrayEquals(vPrefix, roundtrip(algo, bigRef, vPrefix, p),
                    algo + " vLen=" + vLen + " prefix ver");

            byte[] vNew = new byte[vLen];
            for (int i = 0; i < vLen; i++) vNew[i] = (byte) (i * 7 + 1 & 0xFF);
            for (Algorithm algo : ALL_ALGOS)
                assertArrayEquals(vNew, roundtrip(algo, bigRef, vNew, p),
                    algo + " vLen=" + vLen + " all-new ver");
        }

        byte[] fixedVer = Arrays.copyOf(bigRef, 64);
        for (int rLen : sizes) {
            byte[] r = Arrays.copyOf(bigRef, rLen);
            for (Algorithm algo : ALL_ALGOS)
                assertArrayEquals(fixedVer, roundtrip(algo, r, fixedVer, p),
                    algo + " rLen=" + rLen + " fixed ver");
        }
    }

    /** Version sizes on either side of byte boundaries, where a sign-extended byte would corrupt the field. */
    static void testEncodingVersionSizeBoundaries() {
        int[] sizes = {
            0, 1, 127, 128, 255, 256, 257,
            32767, 32768, 32769,
            65535, 65536, 65537,
            8388607, 8388608, 8388609,
            16777215, 16777216, 16777217
        };
        for (int sz : sizes) {
            List<PlacedCommand> cmds = new ArrayList<>();
            byte[] encoded = Encoding.encodeDelta(cmds, false, sz, ZERO_HASH, ZERO_HASH);
            Encoding.DecodeResult res = Encoding.decodeDelta(encoded);
            assertEquals(sz, res.versionSize(), "version_size=" + sz);
            assertEquals(0, res.commands().size(), "no commands at version_size=" + sz);
        }
    }

    /** Copy and add fields on either side of the same boundaries. */
    static void testEncodingCommandFieldBoundaries() {
        int[] offsets = {0, 1, 127, 128, 255, 256, 257, 65535, 65536, 65537};

        for (int src : offsets) {
            List<PlacedCommand> cmds = new ArrayList<>();
            cmds.add(new PlacedCopy(src, 0, 1));
            PlacedCopy c = (PlacedCopy) Encoding.decodeDelta(
                Encoding.encodeDelta(cmds, false, 1, ZERO_HASH, ZERO_HASH)).commands().get(0);
            assertEquals(src, c.src(), "copy src=" + src);
            assertEquals(0,   c.dst(),    "copy dst at src=" + src);
            assertEquals(1,   c.length(), "copy length at src=" + src);
        }

        for (int dst : offsets) {
            List<PlacedCommand> cmds = new ArrayList<>();
            cmds.add(new PlacedCopy(0, dst, 1));
            PlacedCopy c = (PlacedCopy) Encoding.decodeDelta(
                Encoding.encodeDelta(cmds, false, dst + 1, ZERO_HASH, ZERO_HASH)).commands().get(0);
            assertEquals(dst, c.dst(), "copy dst=" + dst);
        }

        for (int len : new int[]{1, 127, 128, 255, 256, 257, 65535, 65536}) {
            List<PlacedCommand> cmds = new ArrayList<>();
            cmds.add(new PlacedCopy(0, 0, len));
            PlacedCopy c = (PlacedCopy) Encoding.decodeDelta(
                Encoding.encodeDelta(cmds, false, len, ZERO_HASH, ZERO_HASH)).commands().get(0);
            assertEquals(len, c.length(), "copy length=" + len);
        }

        for (int dst : offsets) {
            List<PlacedCommand> cmds = new ArrayList<>();
            cmds.add(new PlacedAdd(dst, new byte[]{(byte) 0xFF}));
            PlacedAdd a = (PlacedAdd) Encoding.decodeDelta(
                Encoding.encodeDelta(cmds, false, dst + 1, ZERO_HASH, ZERO_HASH)).commands().get(0);
            assertEquals(dst, a.dst(), "add dst=" + dst);
            assertArrayEquals(new byte[]{(byte) 0xFF}, a.data(), "add data at dst=" + dst);
        }
    }

    /** |V| = |R| + 1: the buffer is one byte longer than the reference. */
    static void testInplaceVersionOneLargerTight() {
        for (int n : new int[]{1, 2, 3, 4, 7, 8, 15, 16, 17, 31, 32, 63, 64}) {
            byte[] r = new byte[n];
            for (int i = 0; i < n; i++) r[i] = (byte) (i & 0xFF);
            byte[] v = Arrays.copyOf(r, n + 1);
            v[n] = (byte) 0x5A;
            for (Algorithm algo : ALL_ALGOS)
                for (CyclePolicy pol : ALL_POLICIES)
                    assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 2),
                        algo + "/" + pol + " inplace |V|=|R|+1 n=" + n);
        }
    }

    /** |V| = |R| - 1: the version stops one byte short of the buffer. */
    static void testInplaceVersionOneSmallerTight() {
        for (int n : new int[]{2, 3, 4, 5, 8, 9, 15, 16, 17, 31, 32, 65}) {
            byte[] r = new byte[n];
            for (int i = 0; i < n; i++) r[i] = (byte) (i & 0xFF);
            byte[] v = Arrays.copyOf(r, n - 1);
            for (Algorithm algo : ALL_ALGOS)
                for (CyclePolicy pol : ALL_POLICIES)
                    assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 2),
                        algo + "/" + pol + " inplace |V|=|R|-1 n=" + n);
        }
    }

    /** |V| = |R| with the halves exchanged; once a half holds a seed, the two copies form a cycle. */
    static void testInplaceVersionSameSizeTight() {
        for (int n : new int[]{2, 4, 8, 16, 32, 64, 128, 256}) {
            byte[] r = new byte[n];
            for (int i = 0; i < n; i++) r[i] = (byte) (i & 0xFF);
            int half = n / 2;
            byte[] v = new byte[n];
            System.arraycopy(r, half, v, 0,    half);
            System.arraycopy(r, 0,    v, half, half);
            for (Algorithm algo : ALL_ALGOS)
                for (CyclePolicy pol : ALL_POLICIES)
                    assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 2),
                        algo + "/" + pol + " inplace same-size swap n=" + n);
        }
    }

    /**
     * A version of one byte.  With p = 1 it is a copy if the byte is in R
     * and an add if not; with p = 2 it is shorter than the seed, so an add.
     */
    static void testInplaceVersionOneByteMin() {
        byte[] r = new byte[64];
        for (int i = 0; i < 64; i++) r[i] = (byte) i;

        byte[] vCopy = {r[32]};           // occurs in R
        byte[] vAdd  = {(byte) 0xAB};     // does not occur in R

        for (Algorithm algo : ALL_ALGOS)
            for (CyclePolicy pol : ALL_POLICIES) {
                String who = algo + "/" + pol + " inplace v=1 byte ";
                oneByteInplace(algo, pol, r, vCopy, 1, true,  who + "(p=1, copy)");
                oneByteInplace(algo, pol, r, vAdd,  1, false, who + "(p=1, add)");
                oneByteInplace(algo, pol, r, vCopy, 2, false, who + "(p=2, add)");
            }
    }

    /** Checks that the in-place delta for the one-byte v is one command of the kind expected, and builds v. */
    static void oneByteInplace(Algorithm algo, CyclePolicy pol, byte[] r, byte[] v,
                               int p, boolean copy, String msg) {
        List<PlacedCommand> ip = Apply.makeInplace(r, Diff.diff(algo, r, v, opts(p)), pol);
        assertEquals(1, ip.size(), msg + ": commands");
        assertTrue(copy ? ip.get(0) instanceof PlacedCopy : ip.get(0) instanceof PlacedAdd,
            msg + ": got " + ip.get(0));
        assertArrayEquals(v, Apply.applyDeltaInplace(r, ip, v.length), msg);
    }

    /** Seed lengths 1, 2, |R|, which leaves R one seed, and |R| + 1, which leaves it none. */
    static void testSeedLengthBoundaries() {
        byte[] r = b("ABCDEFGHIJKLMNOP");
        byte[] v = b("QWIJKLMNOBCDEFGHZDEFGHIJKL");
        for (int p : new int[]{1, 2, r.length, r.length + 1}) {
            for (Algorithm algo : ALL_ALGOS)
                assertArrayEquals(v, Apply.applyDelta(r, Diff.diff(algo, r, v, opts(p))),
                    algo + " p=" + p);
        }
        // p > |R|, with V shorter and longer than p.
        byte[] vShort = b("QW");
        byte[] vLong  = b("QWIJKLMNOBCDEFGHZDEFGHIJKLMNOPQRSTUVWXYZ");
        for (Algorithm algo : ALL_ALGOS) {
            assertArrayEquals(vShort, Apply.applyDelta(r, Diff.diff(algo, r, vShort, opts(r.length + 1))),
                algo + " p>|r| short ver");
            assertArrayEquals(vLong, Apply.applyDelta(r, Diff.diff(algo, r, vLong, opts(r.length + 1))),
                algo + " p>|r| long ver");
        }
    }

    static void testRealDataRoundtrip() {
        try {
            byte[] r = Files.readAllBytes(Path.of("..", "..", "README.md"));
            byte[] v = Files.readAllBytes(Path.of("..", "..", "HOWTO.md"));
            for (Algorithm algo : ALL_ALGOS) {
                assertArrayEquals(v, roundtrip(algo, r, v, 8), algo + " real-data roundtrip");
            }
        } catch (IOException e) {
            throw new AssertionError("failed to read real data: " + e.getMessage());
        }
    }


    static void testCrc64Empty() {
        // CRC-64/XZ of empty input = 0x0000000000000000.
        assertArrayEquals(new byte[8], Hash.Crc64.hash8(new byte[0]), "CRC64 empty");
    }

    static void testCrc64CheckValue() {
        // Standard check value: CRC-64/XZ of b"123456789" = 0x995DC9BBDF1939FA.
        byte[] expected = hexToBytes("995dc9bbdf1939fa");
        assertArrayEquals(expected, Hash.Crc64.hash8(b("123456789")), "CRC64 check value");
    }

    static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    public static void main(String[] args) {

        System.out.println("\n=== Standard differencing ===");
        check("paper example",                  TestDelta::testPaperExample);
        check("identical",                      TestDelta::testIdentical);
        check("completely different",           TestDelta::testCompletelyDifferent);
        check("empty version",                  TestDelta::testEmptyVersion);
        check("empty reference",                TestDelta::testEmptyReference);
        check("binary roundtrip",               TestDelta::testBinaryRoundtrip);
        check("binary encoding roundtrip",      TestDelta::testBinaryEncodingRoundtrip);
        check("decode rejects missing END",     TestDelta::testDecodeRejectsMissingEnd);
        check("decode rejects trailing data",   TestDelta::testDecodeRejectsTrailingData);
        check("decode rejects copy past size",  TestDelta::testDecodeRejectsCopyPastVersionSize);
        check("validate rejects source overflow", TestDelta::testValidatePlacedCommandsRejectsSourceOverflow);
        check("binary encoding inplace flag",   TestDelta::testBinaryEncodingInplaceFlag);
        check("large copy roundtrip",           TestDelta::testLargeCopyRoundtrip);
        check("large add roundtrip",            TestDelta::testLargeAddRoundtrip);
        check("backward extension",             TestDelta::testBackwardExtension);
        check("transposition",                  TestDelta::testTransposition);
        check("scattered modifications",        TestDelta::testScatteredModifications);
        check("real data roundtrip",            TestDelta::testRealDataRoundtrip);

        System.out.println("\n=== In-place basics ===");
        check("inplace paper example",          TestDelta::testInplacePaperExample);
        check("inplace binary roundtrip",       TestDelta::testInplaceBinaryRoundtrip);
        check("inplace simple transposition",   TestDelta::testInplaceSimpleTransposition);
        check("inplace version larger",         TestDelta::testInplaceVersionLarger);
        check("inplace version smaller",        TestDelta::testInplaceVersionSmaller);
        check("inplace identical",              TestDelta::testInplaceIdentical);
        check("inplace empty version",          TestDelta::testInplaceEmptyVersion);
        check("inplace scattered",              TestDelta::testInplaceScattered);
        check("standard not detected as inplace", TestDelta::testStandardNotDetectedAsInplace);
        check("inplace detected",               TestDelta::testInplaceDetected);

        System.out.println("\n=== In-place variable-length blocks ===");
        check("varlen permutation",             TestDelta::testInplaceVarlenPermutation);
        check("varlen reverse",                 TestDelta::testInplaceVarlenReverse);
        check("varlen junk",                    TestDelta::testInplaceVarlenJunk);
        check("varlen drop+dup",                TestDelta::testInplaceVarlenDropDup);
        check("varlen double-sized",            TestDelta::testInplaceVarlenDoubleSized);
        check("varlen subset",                  TestDelta::testInplaceVarlenSubset);
        check("varlen half-block scramble",     TestDelta::testInplaceVarlenHalfBlockScramble);
        check("varlen random trials",           TestDelta::testInplaceVarlenRandomTrials);

        System.out.println("\n=== Cycle policy ===");
        check("localmin picks smallest",        TestDelta::testLocalminPicksSmallest);

        System.out.println("\n=== Checkpointing ===");
        check("correcting tiny table (7 slots)", TestDelta::testCorrectingCheckpointingTinyTable);
        check("correcting various table sizes", TestDelta::testCorrectingCheckpointingVariousSizes);

        System.out.println("\n=== CRC-64/XZ ===");
        check("CRC64 empty input",              TestDelta::testCrc64Empty);
        check("CRC64 check value 123456789",    TestDelta::testCrc64CheckValue);

        System.out.println("\n=== Primality ===");
        check("next prime is prime",            TestDelta::testNextPrimeIsPrime);

        System.out.println("\n=== Inplace subcommand ===");
        check("inplace subcommand roundtrip",   TestDelta::testInplaceSubcommandRoundtrip);
        check("inplace subcommand idempotent",  TestDelta::testInplaceSubcommandIdempotent);
        check("inplace subcommand equiv direct",TestDelta::testInplaceSubcommandEquivDirect);
        check("inplace statistics",             TestDelta::testInplaceStats);
        check("inplace rejects MOVE",           TestDelta::testInplaceRejectsMove);

        System.out.println("\n=== Command line ===");
        check("cli: inplace --verbose",         TestDelta::testCliInplaceVerbose);
        check("cli: inplace, wrong reference",  TestDelta::testCliInplaceWrongReference);
        check("cli: decode --ignore-hash",      TestDelta::testCliDecodeIgnoreHash);

        System.out.println("\n=== Splay tree ===");
        check("splay roundtrip",                TestDelta::testSplayRoundtrip);

        System.out.println("\n=== Edge cases and boundaries ===");
        check("single byte ref/ver",               TestDelta::testSingleByte);
        check("boundary byte mutations",           TestDelta::testBoundaryByteMutations);
        check("ref shorter than seed length",      TestDelta::testRefShorterThanSeed);
        check("size sweep (ver and ref sizes)",    TestDelta::testSizeSweep);
        check("encoding: version-size boundaries", TestDelta::testEncodingVersionSizeBoundaries);
        check("encoding: field boundaries",        TestDelta::testEncodingCommandFieldBoundaries);
        check("inplace: |V|=|R|+1 tight",         TestDelta::testInplaceVersionOneLargerTight);
        check("inplace: |V|=|R|-1 tight",         TestDelta::testInplaceVersionOneSmallerTight);
        check("inplace: |V|=|R| same-size swap",  TestDelta::testInplaceVersionSameSizeTight);
        check("inplace: version is 1 byte",       TestDelta::testInplaceVersionOneByteMin);
        check("seed length at boundaries",         TestDelta::testSeedLengthBoundaries);

        System.out.println("\n=== DLT\\x04 large format ===");
        check("large format: header magic",           TestDelta::testLargeHeaderMagic);
        check("large format: header size",            TestDelta::testLargeHeaderSize);
        check("large format: version_size u64 BE",   TestDelta::testLargeVersionSizeU64);
        check("large format: inplace flag",           TestDelta::testLargeInplaceFlag);
        check("large format: COPY roundtrip",         TestDelta::testLargeFormatCopyRoundtrip);
        check("large format: ADD roundtrip",          TestDelta::testLargeFormatAddRoundtrip);
        check("large format: MOVE roundtrip",         TestDelta::testLargeMoveRoundtrip);
        check("large format: MOVE command byte",      TestDelta::testLargeMoveCommandByte);
        check("large format: MOVE overlap rejected",  TestDelta::testLargeMoveOverlapRejected);
        check("large format: MOVE source overflow rejected", TestDelta::testLargeMoveSourceOverflowRejected);
        check("large format: small rejects large cmds", TestDelta::testSmallRejectsLargeCommandBytes);
        check("large format: unknown magic rejected", TestDelta::testUnknownMagicRejected);
        check("large format: encodeDelta rejects Move", TestDelta::testEncodeDeltaRejectsMove);
        check("large format: u64 overflow rejected",  TestDelta::testLargeU64OverflowRejected);
        check("large format: algo roundtrip greedy",  TestDelta::testLargeAlgoRoundtripGreedy);
        check("large format: algo roundtrip onepass", TestDelta::testLargeAlgoRoundtripOnepass);
        check("large format: algo roundtrip correcting", TestDelta::testLargeAlgoRoundtripCorrecting);

        System.out.println("\n========================================");
        System.out.printf("Results: %d passed, %d failed (of %d)%n", pass, fail, tests);
        System.out.println("========================================");
        if (fail > 0) System.exit(1);
    }

    static void testLargeHeaderMagic() {
        byte[] d = Encoding.encodeDeltaLarge(List.of(), false, 0, ZERO_HASH, ZERO_HASH, false);
        assertTrue(d[0] == 'D' && d[1] == 'L' && d[2] == 'T' && d[3] == 0x04,
            "large format magic must be DLT\\x04");
    }

    static void testLargeHeaderSize() {
        byte[] d = Encoding.encodeDeltaLarge(List.of(), false, 0, ZERO_HASH, ZERO_HASH, false);
        assertEquals(DELTA_HEADER_SIZE_LARGE + 1, d.length, "large header+END size");
    }

    static void testLargeVersionSizeU64() {
        int vsIn = 0x01020304;
        byte[] d = Encoding.encodeDeltaLarge(List.of(), false, vsIn, ZERO_HASH, ZERO_HASH, false);
        // version_size at bytes 5..12 (u64 BE)
        long stored = 0;
        for (int i = 0; i < 8; i++) stored = (stored << 8) | (d[5 + i] & 0xFF);
        assertEquals(Integer.toUnsignedLong(vsIn), stored, "version_size u64 BE encoding");
    }

    static void testLargeInplaceFlag() {
        byte[] di = Encoding.encodeDeltaLarge(List.of(), true,  0, ZERO_HASH, ZERO_HASH, false);
        byte[] dn = Encoding.encodeDeltaLarge(List.of(), false, 0, ZERO_HASH, ZERO_HASH, false);
        assertTrue((di[4] & DELTA_FLAG_INPLACE) != 0, "inplace flag set");
        assertTrue((dn[4] & DELTA_FLAG_INPLACE) == 0, "inplace flag not set");
    }

    static void testLargeFormatCopyRoundtrip() {
        byte[] r = "hello".getBytes();
        List<PlacedCommand> cmds = List.of(new PlacedCopy(0, 0, r.length));
        byte[] d = Encoding.encodeDeltaLarge(cmds, false, r.length, ZERO_HASH, ZERO_HASH, false);
        // COPY command byte (not BIGCOPY) since fields fit in u32
        assertEquals(DELTA_CMD_COPY, d[DELTA_HEADER_SIZE_LARGE] & 0xFF, "COPY command byte");
        Encoding.DecodeResult res = Encoding.decodeDelta(d);
        byte[] out = new byte[(int) res.versionSize()];
        Apply.applyPlacedTo(r, res.commands(), out);
        assertArrayEquals(r, out, "COPY roundtrip via large format");
    }

    static void testLargeFormatAddRoundtrip() {
        byte[] payload = "world".getBytes();
        List<PlacedCommand> cmds = List.of(new PlacedAdd(0, payload));
        byte[] d = Encoding.encodeDeltaLarge(cmds, false, payload.length, ZERO_HASH, ZERO_HASH, false);
        Encoding.DecodeResult res = Encoding.decodeDelta(d);
        byte[] out = new byte[(int) res.versionSize()];
        Apply.applyPlacedTo(new byte[0], res.commands(), out);
        assertArrayEquals(payload, out, "ADD roundtrip via large format");
    }

    static void testLargeMoveRoundtrip() {
        byte[] hello = "hello".getBytes();
        // ADD "hello" at 0, then MOVE it to offset 5
        List<PlacedCommand> cmds = List.of(
            new PlacedAdd(0, hello),
            new PlacedMove(0, 5, hello.length)
        );
        byte[] d = Encoding.encodeDeltaLarge(cmds, false, 10, ZERO_HASH, ZERO_HASH, false);
        Encoding.DecodeResult res = Encoding.decodeDelta(d);
        byte[] out = new byte[(int) res.versionSize()];
        Apply.applyPlacedTo(new byte[0], res.commands(), out);
        assertArrayEquals("hellohello".getBytes(), out, "MOVE roundtrip");
    }

    static void testLargeMoveCommandByte() {
        List<PlacedCommand> cmds = List.of(
            new PlacedAdd(0, new byte[]{'x'}),
            new PlacedMove(0, 1, 1)
        );
        byte[] d = Encoding.encodeDeltaLarge(cmds, false, 2, ZERO_HASH, ZERO_HASH, false);
        // After header(29) + ADD type(1) + ADD header(8) + data(1) = 39
        int moveOff = DELTA_HEADER_SIZE_LARGE + 1 + DELTA_ADD_HEADER + 1;
        assertEquals(DELTA_CMD_MOVE, d[moveOff] & 0xFF, "MOVE command byte");
    }

    static void testLargeMoveOverlapRejected() {
        byte[] buf = new byte[DELTA_HEADER_SIZE_LARGE + DELTA_COPY_PAYLOAD + 2];
        buf[0] = 'D'; buf[1] = 'L'; buf[2] = 'T'; buf[3] = 0x04;
        buf[4] = 0; // flags
        // version_size = 10 as u64 BE at bytes 5..12
        buf[12] = 10;
        // crcs 0 (bytes 13..28)
        buf[DELTA_HEADER_SIZE_LARGE] = (byte) DELTA_CMD_MOVE;
        // MOVE: src=5, dst=8, length=4, so src+length=9 > dst=8
        putU32(buf, DELTA_HEADER_SIZE_LARGE + 1, 5); // src
        putU32(buf, DELTA_HEADER_SIZE_LARGE + 5, 8); // dst
        putU32(buf, DELTA_HEADER_SIZE_LARGE + 9, 4); // length
        buf[buf.length - 1] = (byte) DELTA_CMD_END;
        assertRejects("overlapping MOVE", () -> Encoding.decodeDelta(buf), null);
    }

    /** src + length overflows a long; the decoder must not take the wrapped sum for a small one. */
    static void testLargeMoveSourceOverflowRejected() {
        byte[] buf = new byte[DELTA_HEADER_SIZE_LARGE + DELTA_BIGCOPY_PAYLOAD + 2];
        buf[0] = 'D'; buf[1] = 'L'; buf[2] = 'T'; buf[3] = 0x04;
        buf[12] = 10; // version_size
        int at = DELTA_HEADER_SIZE_LARGE;
        buf[at] = (byte) DELTA_CMD_BIGMOVE;
        // src = Long.MAX_VALUE, dst = 8, length = 2
        buf[at + 1] = 0x7F;
        for (int i = 2; i <= 8; i++) buf[at + i] = (byte) 0xFF;
        buf[at + 16] = 8;
        buf[at + 24] = 2;
        buf[buf.length - 1] = (byte) DELTA_CMD_END;
        assertRejects("BIGMOVE with src+length past Long.MAX_VALUE",
            () -> Encoding.decodeDelta(buf),
            "BIGMOVE src+length > dst: encoder ordering constraint violated");
        assertRejects("PlacedMove with src+length past Long.MAX_VALUE",
            () -> Apply.validatePlacedCommands(
                List.of(new PlacedMove(Long.MAX_VALUE, 8, 2)), 0, 10, false),
            "MOVE src+length > dst: encoder ordering constraint violated");
    }

    static void testSmallRejectsLargeCommandBytes() {
        byte[] buf = new byte[DELTA_HEADER_SIZE + 2];
        buf[0] = 'D'; buf[1] = 'L'; buf[2] = 'T'; buf[3] = 0x03;
        buf[4] = 0; // flags
        // version_size = 1 as u32 BE at bytes 5..8
        buf[8] = 1;
        // crcs 0 (bytes 9..24)
        buf[DELTA_HEADER_SIZE] = (byte) DELTA_CMD_BIGCOPY; // not a DLT\x03 command
        buf[DELTA_HEADER_SIZE + 1] = (byte) DELTA_CMD_END;
        assertRejects("BIGCOPY in small format", () -> Encoding.decodeDelta(buf), null);
    }

    static void testUnknownMagicRejected() {
        byte[] buf = {'X', 'X', 'X', 0x03, 0, 0, 0, 0, 0};
        assertRejects("unknown magic", () -> Encoding.decodeDelta(buf), null);
    }

    static void testEncodeDeltaRejectsMove() {
        List<PlacedCommand> cmds = List.of(new PlacedMove(0, 5, 3));
        assertRejects("PlacedMove in encodeDelta",
            () -> Encoding.encodeDelta(cmds, false, 8, ZERO_HASH, ZERO_HASH),
            null);
    }

    static void testLargeU64OverflowRejected() {
        // version_size = 2^63, which a long holds as a negative number.
        byte[] buf = new byte[DELTA_HEADER_SIZE_LARGE + 1];
        buf[0] = 'D'; buf[1] = 'L'; buf[2] = 'T'; buf[3] = 0x04;
        buf[4] = 0;
        buf[5] = (byte) 0x80;
        buf[DELTA_HEADER_SIZE_LARGE] = (byte) DELTA_CMD_END;
        assertRejects("oversized version_size", () -> Encoding.decodeDelta(buf), null);
    }

    static void testLargeAlgoRoundtripGreedy() {
        largeAlgoRoundtrip(Algorithm.GREEDY, "greedy");
    }

    static void testLargeAlgoRoundtripOnepass() {
        largeAlgoRoundtrip(Algorithm.ONEPASS, "onepass");
    }

    static void testLargeAlgoRoundtripCorrecting() {
        largeAlgoRoundtrip(Algorithm.CORRECTING, "correcting");
    }

    static void largeAlgoRoundtrip(Algorithm algo, String name) {
        byte[] r = "the quick brown fox".getBytes();
        byte[] v = "the slow brown fox".getBytes();
        List<Command> cmds = Diff.diff(algo, r, v, opts(4));
        List<PlacedCommand> placed = Apply.placeCommands(cmds);
        byte[] d = Encoding.encodeDeltaLarge(placed, false, v.length, ZERO_HASH, ZERO_HASH, false);
        Encoding.DecodeResult res = Encoding.decodeDelta(d);
        byte[] out = new byte[(int) res.versionSize()];
        Apply.applyPlacedTo(r, res.commands(), out);
        assertArrayEquals(v, out, name + " large-format roundtrip");
    }

    static void putU32(byte[] buf, int off, int val) {
        buf[off]     = (byte) (val >>> 24);
        buf[off + 1] = (byte) (val >>> 16);
        buf[off + 2] = (byte) (val >>> 8);
        buf[off + 3] = (byte) val;
    }
}
