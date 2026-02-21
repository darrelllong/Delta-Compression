package delta;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static delta.Types.*;

/**
 * Integration tests for the Java delta compression library.
 *
 * Mirrors the Rust and Python test suites: standard differencing, binary
 * encoding, in-place reconstruction, variable-length block transpositions,
 * cycle policy, checkpointing, primality, inplace subcommand, and splay tree.
 *
 * Run:  java -cp out delta.TestDelta
 * Or:   make test  (from src/java/)
 */
public class TestDelta {

    static int pass, fail, tests;

    static final Algorithm[]   ALL_ALGOS    = Algorithm.values();
    static final CyclePolicy[] ALL_POLICIES = CyclePolicy.values();

    // ── assertion helpers ─────────────────────────────────────────────────────

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

    static void assertEquals(long expected, long actual, String msg) {
        if (expected != actual)
            throw new AssertionError(String.format(
                "%s: expected %d, got %d", msg, expected, actual));
    }

    // ── test runner ───────────────────────────────────────────────────────────

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

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Build a DiffOptions with seed length p and all other fields defaulted. */
    static DiffOptions opts(int p) {
        DiffOptions o = new DiffOptions();
        o.p = p;
        return o;
    }

    /** Concatenate byte arrays. */
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

    /** Repeat a byte array n times. */
    static byte[] repeat(byte[] data, int n) {
        byte[] out = new byte[data.length * n];
        for (int i = 0; i < n; i++)
            System.arraycopy(data, 0, out, i * data.length, data.length);
        return out;
    }

    /** ASCII/Latin-1 string to bytes. */
    static byte[] b(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * Standard encode path: diff → place → encode → decode → applyPlacedTo.
     * Exercises the binary format roundtrip.
     */
    static final byte[] ZERO_HASH = new byte[DELTA_HASH_SIZE];

    static byte[] roundtrip(Algorithm algo, byte[] r, byte[] v, int p) {
        List<Command> cmds = Diff.diff(algo, r, v, opts(p));
        List<PlacedCommand> placed = Apply.placeCommands(cmds);
        byte[] delta = Encoding.encodeDelta(placed, false, Apply.outputSize(cmds), ZERO_HASH, ZERO_HASH);
        Encoding.DecodeResult res = Encoding.decodeDelta(delta);
        byte[] out = new byte[res.versionSize];
        Apply.applyPlacedTo(r, res.commands, out);
        return out;
    }

    /** In-place path: diff → makeInplace → applyDeltaInplace (no binary I/O). */
    static byte[] inplaceRoundtrip(Algorithm algo, byte[] r, byte[] v,
                                    CyclePolicy pol, int p) {
        List<Command> cmds = Diff.diff(algo, r, v, opts(p));
        List<PlacedCommand> ip = Apply.makeInplace(r, cmds, pol);
        return Apply.applyDeltaInplace(r, ip, v.length);
    }

    /** In-place binary path: diff → makeInplace → encode → decode → applyDeltaInplace. */
    static byte[] inplaceBinaryRoundtrip(Algorithm algo, byte[] r, byte[] v,
                                          CyclePolicy pol, int p) {
        List<Command> cmds = Diff.diff(algo, r, v, opts(p));
        List<PlacedCommand> ip = Apply.makeInplace(r, cmds, pol);
        byte[] delta = Encoding.encodeDelta(ip, true, v.length, ZERO_HASH, ZERO_HASH);
        Encoding.DecodeResult res = Encoding.decodeDelta(delta);
        return Apply.applyDeltaInplace(r, res.commands, res.versionSize);
    }

    /**
     * Simulate the {@code delta inplace} subcommand:
     * encode standard → decode → unplaceCommands → makeInplace → encode(inplace).
     */
    static byte[] viaInplaceSubcommand(Algorithm algo, byte[] r, byte[] v,
                                        CyclePolicy pol, int p) {
        List<Command> cmds = Diff.diff(algo, r, v, opts(p));
        List<PlacedCommand> placed = Apply.placeCommands(cmds);
        byte[] standard = Encoding.encodeDelta(placed, false, v.length, ZERO_HASH, ZERO_HASH);
        Encoding.DecodeResult res = Encoding.decodeDelta(standard);
        assertFalse(res.inplace, "standard delta should not be flagged as inplace");
        List<Command> cmds2 = Apply.unplaceCommands(res.commands);
        List<PlacedCommand> ip = Apply.makeInplace(r, cmds2, pol);
        return Encoding.encodeDelta(ip, true, res.versionSize, ZERO_HASH, ZERO_HASH);
    }

    /**
     * Eight variable-length blocks with deterministic byte patterns.
     * Block i has sizes[i] bytes, each byte = (i*37 + j) & 0xFF.
     */
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

    /** Concatenate all blocks into a single byte array. */
    static byte[] blocksRef(List<byte[]> blocks) {
        return concat(blocks.toArray(new byte[0][]));
    }

    /** Fisher-Yates in-place shuffle using the given Random. */
    static void shuffle(int[] arr, Random rng) {
        for (int i = arr.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp;
        }
    }

    // ── standard differencing ─────────────────────────────────────────────────

    /** Section 2.1.1 of Ajtai et al. 2002. */
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
        // R = 0x00..0xFF repeated twice, V = reverse
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

    /** Manually-built placed commands: verify encode → decode preserves fields. */
    static void testBinaryEncodingRoundtrip() {
        List<PlacedCommand> placed = new ArrayList<>();
        placed.add(new PlacedAdd(0, new byte[]{100, 101, 102}));
        placed.add(new PlacedCopy(888, 3, 488));
        byte[] encoded = Encoding.encodeDelta(placed, false, 491, ZERO_HASH, ZERO_HASH);
        Encoding.DecodeResult res = Encoding.decodeDelta(encoded);
        assertFalse(res.inplace, "should not be inplace");
        assertEquals(491, res.versionSize, "version size");
        assertEquals(2, res.commands.size(), "command count");
        PlacedAdd a = (PlacedAdd) res.commands.get(0);
        assertEquals(0, a.dst, "add dst");
        assertArrayEquals(new byte[]{100, 101, 102}, a.data, "add data");
        PlacedCopy c = (PlacedCopy) res.commands.get(1);
        assertEquals(888, c.src, "copy src");
        assertEquals(3, c.dst, "copy dst");
        assertEquals(488, c.length, "copy length");
    }

    /** The inplace flag bit in the header is set/clear independently of commands. */
    static void testBinaryEncodingInplaceFlag() {
        List<PlacedCommand> placed = new ArrayList<>();
        placed.add(new PlacedCopy(0, 10, 5));
        byte[] standard = Encoding.encodeDelta(placed, false, 15, ZERO_HASH, ZERO_HASH);
        byte[] inplace  = Encoding.encodeDelta(placed, true,  15, ZERO_HASH, ZERO_HASH);
        assertFalse(Encoding.isInplaceDelta(standard), "standard should not be inplace");
        assertTrue( Encoding.isInplaceDelta(inplace),  "inplace should be inplace");
        Encoding.DecodeResult r1 = Encoding.decodeDelta(standard);
        Encoding.DecodeResult r2 = Encoding.decodeDelta(inplace);
        assertFalse(r1.inplace, "standard decoded inplace flag");
        assertTrue( r2.inplace, "inplace decoded inplace flag");
        assertEquals(r1.versionSize, r2.versionSize, "version sizes match");
    }

    static void testLargeCopyRoundtrip() {
        List<PlacedCommand> placed = new ArrayList<>();
        placed.add(new PlacedCopy(100_000, 0, 50_000));
        byte[] encoded = Encoding.encodeDelta(placed, false, 50_000, ZERO_HASH, ZERO_HASH);
        Encoding.DecodeResult res = Encoding.decodeDelta(encoded);
        assertEquals(1, res.commands.size(), "command count");
        PlacedCopy c = (PlacedCopy) res.commands.get(0);
        assertEquals(100_000, c.src, "copy src");
        assertEquals(0, c.dst, "copy dst");
        assertEquals(50_000, c.length, "copy length");
    }

    static void testLargeAddRoundtrip() {
        byte[] bigData = new byte[256 * 4];
        for (int i = 0; i < bigData.length; i++) bigData[i] = (byte) (i & 0xFF);
        List<PlacedCommand> placed = new ArrayList<>();
        placed.add(new PlacedAdd(0, bigData));
        byte[] encoded = Encoding.encodeDelta(placed, false, bigData.length, ZERO_HASH, ZERO_HASH);
        Encoding.DecodeResult res = Encoding.decodeDelta(encoded);
        assertEquals(1, res.commands.size(), "command count");
        PlacedAdd a = (PlacedAdd) res.commands.get(0);
        assertEquals(0, a.dst, "add dst");
        assertArrayEquals(bigData, a.data, "add data");
    }

    /** Block shared by R and V is offset by a few bytes; backward extension must find it. */
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

    /** 100 random single-byte mutations in a 2000-byte array. */
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

    // ── in-place basics ───────────────────────────────────────────────────────

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

    /** |V| > |R|: in-place write window extends beyond reference. */
    static void testInplaceVersionLarger() {
        byte[] r = repeat(b("ABCDEFGH"), 50);
        byte[] v = concat(repeat(b("XXABCDEFGH"), 50), repeat(b("YYABCDEFGH"), 50));
        for (Algorithm algo : ALL_ALGOS)
            for (CyclePolicy pol : ALL_POLICIES)
                assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4),
                    algo + "/" + pol + " inplace version larger");
    }

    /** |V| < |R|: in-place write uses only a prefix of the reference buffer. */
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

    // ── in-place variable-length blocks ───────────────────────────────────────

    /** Random permutation of all 8 blocks. */
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

    /** All 8 blocks in reverse order. */
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

    /** Permuted blocks interleaved with random junk bytes. */
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

    /** Drop some blocks, duplicate others: V has 5 blocks drawn from R's 8. */
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

    /** V is two independent shuffles of all 8 blocks concatenated (~2× size of R). */
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

    /** V is just two of the eight blocks — much smaller than R. */
    static void testInplaceVarlenSubset() {
        List<byte[]> blocks = makeBlocks();
        byte[] r = blocksRef(blocks);
        byte[] v = concat(blocks.get(6), blocks.get(2));
        for (Algorithm algo : ALL_ALGOS)
            for (CyclePolicy pol : ALL_POLICIES)
                assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4),
                    algo + "/" + pol + " varlen subset");
    }

    /** Split each block in half to get 16 halves, then shuffle all 16. */
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

    /** 20 random trials: random subset of 3–8 blocks in random order. */
    static void testInplaceVarlenRandomTrials() {
        List<byte[]> blocks = makeBlocks();
        byte[] r = blocksRef(blocks);
        Random rng = new Random(9999);
        // Pre-generate 20 trials so all algo×policy combinations use the same data.
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

    // ── cycle policy ──────────────────────────────────────────────────────────

    /**
     * LOCALMIN must produce ≤ total add bytes vs CONSTANT on a reverse-block workload.
     * Both must produce correct output; localmin optimises which copy is broken per cycle.
     */
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
            .mapToLong(c -> ((PlacedAdd) c).data.length)
            .sum();
        long addLmin = ipLmin.stream()
            .filter(c -> c instanceof PlacedAdd)
            .mapToLong(c -> ((PlacedAdd) c).data.length)
            .sum();
        assertTrue(addLmin <= addConst,
            "localmin (" + addLmin + ") should produce <= add bytes as constant (" + addConst + ")");
    }

    // ── checkpointing ─────────────────────────────────────────────────────────

    /** A table of only 7 entries forces heavy checkpointing; output must still be correct. */
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
        List<Command> cmds = Diff.diff(Algorithm.CORRECTING, r, v, o);
        assertArrayEquals(v, Apply.applyDelta(r, cmds), "correcting q=7 tiny table");
    }

    /** Sweep q across several orders of magnitude; correctness must hold at each size. */
    static void testCorrectingCheckpointingVariousSizes() {
        byte[] r = new byte[2000];
        for (int i = 0; i < 2000; i++) r[i] = (byte) (i & 0xFF);
        // v = r[0..500] + 0xFF×50 + r[500..], length 2050
        byte[] v = new byte[2050];
        System.arraycopy(r, 0, v, 0, 500);
        Arrays.fill(v, 500, 550, (byte) 0xFF);
        System.arraycopy(r, 500, v, 550, 1500);
        int[] qs = {7, 31, 101, 1009, TABLE_SIZE};
        for (int q : qs) {
            DiffOptions o = new DiffOptions();
            o.p = 16;
            o.q = q;
            List<Command> cmds = Diff.diff(Algorithm.CORRECTING, r, v, o);
            assertArrayEquals(v, Apply.applyDelta(r, cmds), "correcting q=" + q);
        }
    }

    // ── primality ─────────────────────────────────────────────────────────────

    static void testNextPrimeIsPrime() {
        assertTrue(Hash.isPrime(TABLE_SIZE), "TABLE_SIZE should be prime");
        assertTrue(Hash.isPrime(Hash.nextPrime(1048574L)), "nextPrime(1048574) should be prime");
        assertEquals(1048573L, Hash.nextPrime(1048573L), "nextPrime of a prime is itself");
    }

    // ── inplace subcommand path ───────────────────────────────────────────────

    /**
     * encode standard → inplace subcommand → decode → applyDeltaInplace produces V.
     */
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
                    byte[] recovered = Apply.applyDeltaInplace(r, res.commands, v.length);
                    assertArrayEquals(v, recovered,
                        algo + "/" + pol + " subcommand roundtrip case " + i);
                }
        }
    }

    /**
     * If the input to the inplace subcommand is already an inplace delta, it is
     * detected (is_ip = true) and passed through unchanged.
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
                assertTrue(res.inplace,
                    algo + "/" + pol + ": inplace delta should be detected as inplace");
            }
    }

    /**
     * Direct encode --inplace and encode-then-subcommand must produce byte-identical
     * output (both call makeInplace on the same reference and commands).
     */
    static void testInplaceSubcommandEquivDirect() {
        byte[][] rs = {b("ABCDEF"), b("AAABBBCCC"),
                       b("the quick brown fox"), b("ABCDEFGHIJKLMNOP")};
        byte[][] vs = {b("FEDCBA"), b("CCCBBBAAA"),
                       b("the quick brown cat"), b("PONMLKJIHGFEDCBA")};
        for (int i = 0; i < rs.length; i++) {
            byte[] r = rs[i], v = vs[i];
            for (Algorithm algo : ALL_ALGOS)
                for (CyclePolicy pol : ALL_POLICIES) {
                    // Direct path: diff → makeInplace → encode(inplace=true)
                    List<Command> cmds = Diff.diff(algo, r, v, opts(2));
                    List<PlacedCommand> ipDirect = Apply.makeInplace(r, cmds, pol);
                    byte[] directBytes = Encoding.encodeDelta(ipDirect, true, v.length, ZERO_HASH, ZERO_HASH);
                    // Subcommand path: diff → place → encode → decode → unplace → makeInplace → encode
                    byte[] subBytes = viaInplaceSubcommand(algo, r, v, pol, 2);
                    assertArrayEquals(directBytes, subBytes,
                        algo + "/" + pol + " subcommand vs direct case " + i);
                }
        }
    }

    // ── splay tree ────────────────────────────────────────────────────────────

    /** --splay must produce correct output for all three algorithms. */
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

    // ── main ──────────────────────────────────────────────────────────────────

    // ── SHAKE128 NIST FIPS 202 test vectors ──────────────────────────────────

    static void testShake128NistEmpty() {
        // NIST FIPS 202 SHAKE128 vector: empty input, first 16 bytes.
        // SHA3-128("") = 47bce5c74f589f4867dbe57f31b68e5e — different domain
        // separator (0x06 vs 0x1F); MessageDigest("SHA3-128") would fail here.
        byte[] expected = hexToBytes("7f9c2ba4e88f827d616045507605853e");
        assertArrayEquals(expected, Hash.Shake128.hash16(new byte[0]), "NIST empty");
    }

    static void testShake128NistOneByteBd() {
        // NIST FIPS 202 SHAKE128 vector: msg = 0xbd, first 16 bytes
        byte[] expected = hexToBytes("83388286b2c0065ed237fbe714fc3163");
        assertArrayEquals(expected, Hash.Shake128.hash16(new byte[]{(byte)0xbd}), "NIST 0xbd");
    }

    static void testShake128Nist200ByteA3() {
        // NIST FIPS 202 SHAKE128 vector: msg = 0xa3 * 200, first 16 bytes
        byte[] expected = hexToBytes("131ab8d2b594946b9c81333f9bb6e0ce");
        byte[] input = new byte[200];
        Arrays.fill(input, (byte)0xa3);
        assertArrayEquals(expected, Hash.Shake128.hash16(input), "NIST 0xa3*200");
    }

    static void testShake128NotSha3_128() {
        // SHAKE128 and SHA3-128 share the same permutation and rate but use
        // different domain separators (0x1F vs 0x06) and produce different
        // output.  This would fail if MessageDigest("SHA3-128") were used.
        byte[] sha3_128_empty = hexToBytes("47bce5c74f589f4867dbe57f31b68e5e");
        byte[] shake128_empty = Hash.Shake128.hash16(new byte[0]);
        if (Arrays.equals(shake128_empty, sha3_128_empty))
            throw new AssertionError("SHAKE128 must differ from SHA3-128");
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
        check("binary encoding inplace flag",   TestDelta::testBinaryEncodingInplaceFlag);
        check("large copy roundtrip",           TestDelta::testLargeCopyRoundtrip);
        check("large add roundtrip",            TestDelta::testLargeAddRoundtrip);
        check("backward extension",             TestDelta::testBackwardExtension);
        check("transposition",                  TestDelta::testTransposition);
        check("scattered modifications",        TestDelta::testScatteredModifications);

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
        check("correcting tiny table (q=7)",    TestDelta::testCorrectingCheckpointingTinyTable);
        check("correcting various table sizes", TestDelta::testCorrectingCheckpointingVariousSizes);

        System.out.println("\n=== SHAKE128 ===");
        check("NIST vector: empty input",       TestDelta::testShake128NistEmpty);
        check("NIST vector: one byte 0xbd",     TestDelta::testShake128NistOneByteBd);
        check("NIST vector: 200 bytes of 0xa3", TestDelta::testShake128Nist200ByteA3);
        check("not SHA3-128",                   TestDelta::testShake128NotSha3_128);

        System.out.println("\n=== Primality ===");
        check("next prime is prime",            TestDelta::testNextPrimeIsPrime);

        System.out.println("\n=== Inplace subcommand ===");
        check("inplace subcommand roundtrip",   TestDelta::testInplaceSubcommandRoundtrip);
        check("inplace subcommand idempotent",  TestDelta::testInplaceSubcommandIdempotent);
        check("inplace subcommand equiv direct",TestDelta::testInplaceSubcommandEquivDirect);

        System.out.println("\n=== Splay tree ===");
        check("splay roundtrip",                TestDelta::testSplayRoundtrip);

        System.out.println("\n========================================");
        System.out.printf("Results: %d passed, %d failed (of %d)%n", pass, fail, tests);
        System.out.println("========================================");
        if (fail > 0) System.exit(1);
    }
}
