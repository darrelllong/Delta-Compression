@file:JvmName("TestDelta")

package delta

import java.nio.file.Files
import java.nio.file.Path
import java.util.Arrays
import java.util.Random

/**
 * Integration tests for the Kotlin delta compression library.
 *
 * Mirrors the Java/Rust/Python test suites.
 *
 * Run:  java -cp delta.jar delta.TestDelta
 * Or:   make test  (from src/kotlin/)
 */

// ── counters ──────────────────────────────────────────────────────────────────

var pass  = 0
var fail  = 0
var tests = 0

val ALL_ALGOS    = Algorithm.values()
val ALL_POLICIES = CyclePolicy.values()

// ── assertion helpers ─────────────────────────────────────────────────────────

fun assertArrayEquals(expected: ByteArray, actual: ByteArray, msg: String = "byte arrays differ") {
    if (!expected.contentEquals(actual))
        throw AssertionError("$msg: expected ${expected.size} bytes, got ${actual.size}")
}

fun assertTrue(cond: Boolean, msg: String) {
    if (!cond) throw AssertionError(msg)
}

fun assertFalse(cond: Boolean, msg: String) {
    if (cond) throw AssertionError(msg)
}

fun assertEquals(expected: Long, actual: Long, msg: String) {
    if (expected != actual) throw AssertionError("$msg: expected $expected, got $actual")
}

// ── test runner ───────────────────────────────────────────────────────────────

fun check(name: String, block: () -> Unit) {
    tests++
    try {
        block()
        pass++
        System.out.printf("  ok  %s%n", name)
    } catch (t: Throwable) {
        fail++
        val msg = t.message
        System.out.printf("FAIL  %s: %s%n", name, msg ?: t.toString())
    }
}

// ── helpers ───────────────────────────────────────────────────────────────────

fun opts(p: Int) = DiffOptions(p = p)

val ZERO_HASH = ByteArray(DELTA_CRC_SIZE)

fun b(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)

fun concat(vararg parts: ByteArray): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var pos = 0
    for (a in parts) { a.copyInto(out, pos); pos += a.size }
    return out
}

fun repeatBytes(data: ByteArray, n: Int): ByteArray {
    val out = ByteArray(data.size * n)
    for (i in 0 until n) data.copyInto(out, i * data.size)
    return out
}

fun shuffle(arr: IntArray, rng: Random) {
    for (i in arr.size - 1 downTo 1) {
        val j = rng.nextInt(i + 1)
        val tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp
    }
}

fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

/** Standard encode path: diff → place → encode → decode → applyPlacedTo. */
fun roundtrip(algo: Algorithm, r: ByteArray, v: ByteArray, p: Int): ByteArray {
    val cmds   = diff(algo, r, v, opts(p))
    val placed = placeCommands(cmds)
    val delta  = encodeDelta(placed, false, outputSize(cmds), ZERO_HASH, ZERO_HASH)
    val res    = decodeDelta(delta)
    val out    = ByteArray(res.versionSize)
    applyPlacedTo(r, res.commands, out)
    return out
}

/** In-place path: diff → makeInplace → applyDeltaInplace (no binary I/O). */
fun inplaceRoundtrip(algo: Algorithm, r: ByteArray, v: ByteArray,
                     pol: CyclePolicy, p: Int): ByteArray {
    val cmds = diff(algo, r, v, opts(p))
    val ip   = makeInplace(r, cmds, pol)
    return applyDeltaInplace(r, ip, v.size)
}

/** In-place binary path: diff → makeInplace → encode → decode → applyDeltaInplace. */
fun inplaceBinaryRoundtrip(algo: Algorithm, r: ByteArray, v: ByteArray,
                            pol: CyclePolicy, p: Int): ByteArray {
    val cmds  = diff(algo, r, v, opts(p))
    val ip    = makeInplace(r, cmds, pol)
    val delta = encodeDelta(ip, true, v.size, ZERO_HASH, ZERO_HASH)
    val res   = decodeDelta(delta)
    return applyDeltaInplace(r, res.commands, res.versionSize)
}

/** Simulate the `delta inplace` subcommand. */
fun viaInplaceSubcommand(algo: Algorithm, r: ByteArray, v: ByteArray,
                          pol: CyclePolicy, p: Int): ByteArray {
    val cmds     = diff(algo, r, v, opts(p))
    val placed   = placeCommands(cmds)
    val standard = encodeDelta(placed, false, v.size, ZERO_HASH, ZERO_HASH)
    val res      = decodeDelta(standard)
    assertFalse(res.inplace, "standard delta should not be flagged as inplace")
    val cmds2 = unplaceCommands(res.commands)
    val ip    = makeInplace(r, cmds2, pol)
    return encodeDelta(ip, true, res.versionSize, ZERO_HASH, ZERO_HASH)
}

/** Eight variable-length blocks with deterministic byte patterns. */
fun makeBlocks(): List<ByteArray> {
    val sizes = intArrayOf(200, 500, 1234, 3000, 800, 4999, 1500, 2750)
    return sizes.mapIndexed { i, sz ->
        ByteArray(sz) { j -> ((i * 37 + j) and 0xFF).toByte() }
    }
}

fun blocksRef(blocks: List<ByteArray>): ByteArray = concat(*blocks.toTypedArray())

// ── standard differencing ─────────────────────────────────────────────────────

fun testPaperExample() {
    val r = b("ABCDEFGHIJKLMNOP")
    val v = b("QWIJKLMNOBCDEFGHZDEFGHIJKL")
    for (algo in ALL_ALGOS)
        assertArrayEquals(v, applyDelta(r, diff(algo, r, v, opts(2))), "$algo paper example")
}

fun testIdentical() {
    val data = repeatBytes(b("The quick brown fox jumps over the lazy dog."), 10)
    for (algo in ALL_ALGOS) {
        val cmds = diff(algo, data, data, opts(2))
        assertArrayEquals(data, applyDelta(data, cmds), "$algo identical roundtrip")
        for (c in cmds)
            assertTrue(c is Command.Copy, "$algo: identical input should produce no adds")
    }
}

fun testCompletelyDifferent() {
    val r = ByteArray(512) { i -> (i and 0xFF).toByte() }
    val v = ByteArray(512) { i -> ((255 - (i and 0xFF)) and 0xFF).toByte() }
    for (algo in ALL_ALGOS)
        assertArrayEquals(v, applyDelta(r, diff(algo, r, v, opts(2))), "$algo completely different")
}

fun testEmptyVersion() {
    val r = b("hello")
    for (algo in ALL_ALGOS) {
        val cmds = diff(algo, r, ByteArray(0), opts(2))
        assertTrue(cmds.isEmpty(), "$algo: empty version should produce no commands")
        assertArrayEquals(ByteArray(0), applyDelta(r, cmds), "$algo empty version")
    }
}

fun testEmptyReference() {
    val v = b("hello world")
    for (algo in ALL_ALGOS)
        assertArrayEquals(v, applyDelta(ByteArray(0), diff(algo, ByteArray(0), v, opts(2))),
            "$algo empty reference")
}

fun testBinaryRoundtrip() {
    val r = repeatBytes(b("ABCDEFGHIJKLMNOPQRSTUVWXYZ"), 100)
    val v = repeatBytes(b("0123EFGHIJKLMNOPQRS456ABCDEFGHIJKL789"), 100)
    for (algo in ALL_ALGOS)
        assertArrayEquals(v, roundtrip(algo, r, v, 4), "$algo binary roundtrip")
}

fun testBinaryEncodingRoundtrip() {
    val placed = listOf(
        PlacedCommand.Add(0, byteArrayOf(100, 101, 102)),
        PlacedCommand.Copy(888, 3, 488)
    )
    val encoded = encodeDelta(placed, false, 491, ZERO_HASH, ZERO_HASH)
    val res = decodeDelta(encoded)
    assertFalse(res.inplace, "should not be inplace")
    assertEquals(491L, res.versionSize.toLong(), "version size")
    assertEquals(2L, res.commands.size.toLong(), "command count")
    val a = res.commands[0] as PlacedCommand.Add
    assertEquals(0L, a.dst.toLong(), "add dst")
    assertArrayEquals(byteArrayOf(100, 101, 102), a.data, "add data")
    val c = res.commands[1] as PlacedCommand.Copy
    assertEquals(888L, c.src.toLong(), "copy src")
    assertEquals(3L, c.dst.toLong(), "copy dst")
    assertEquals(488L, c.length.toLong(), "copy length")
}

fun testDecodeRejectsMissingEnd() {
    val encoded = encodeDelta(emptyList(), false, 0, ZERO_HASH, ZERO_HASH)
    val truncated = encoded.copyOf(encoded.size - 1)
    var threw = false
    try {
        decodeDelta(truncated)
    } catch (e: IllegalArgumentException) {
        threw = e.message == "missing END command"
    }
    assertTrue(threw, "missing END should be rejected")
}

fun testDecodeRejectsTrailingData() {
    val encoded = encodeDelta(emptyList(), false, 0, ZERO_HASH, ZERO_HASH)
    val bad = encoded.copyOf(encoded.size + 1).also { it[it.lastIndex] = 0x7F.toByte() }
    var threw = false
    try {
        decodeDelta(bad)
    } catch (e: IllegalArgumentException) {
        threw = e.message == "trailing data after END"
    }
    assertTrue(threw, "trailing data should be rejected")
}

fun testDecodeRejectsCopyPastVersionSize() {
    val cmds = listOf(PlacedCommand.Copy(0, 1, 2))
    var threw = false
    try {
        decodeDelta(encodeDelta(cmds, false, 2, ZERO_HASH, ZERO_HASH))
    } catch (e: IllegalArgumentException) {
        threw = e.message == "COPY extends past version size"
    }
    assertTrue(threw, "copy past version size should be rejected")
}

fun testValidatePlacedCommandsRejectsSourceOverflow() {
    val cmds = listOf(PlacedCommand.Copy(1, 0, 2))
    var threw = false
    try {
        validatePlacedCommands(cmds, 2, 2, false)
    } catch (e: IllegalArgumentException) {
        threw = e.message == "copy source out of range"
    }
    assertTrue(threw, "source overflow should be rejected")
}

fun testBinaryEncodingInplaceFlag() {
    val placed = listOf(PlacedCommand.Copy(0, 10, 5))
    val standard = encodeDelta(placed, false, 15, ZERO_HASH, ZERO_HASH)
    val inplace  = encodeDelta(placed, true,  15, ZERO_HASH, ZERO_HASH)
    assertFalse(isInplaceDelta(standard), "standard should not be inplace")
    assertTrue( isInplaceDelta(inplace),  "inplace should be inplace")
    val r1 = decodeDelta(standard)
    val r2 = decodeDelta(inplace)
    assertFalse(r1.inplace, "standard decoded inplace flag")
    assertTrue( r2.inplace, "inplace decoded inplace flag")
    assertEquals(r1.versionSize.toLong(), r2.versionSize.toLong(), "version sizes match")
}

fun testLargeCopyRoundtrip() {
    val placed  = listOf(PlacedCommand.Copy(100_000, 0, 50_000))
    val encoded = encodeDelta(placed, false, 50_000, ZERO_HASH, ZERO_HASH)
    val res = decodeDelta(encoded)
    assertEquals(1L, res.commands.size.toLong(), "command count")
    val c = res.commands[0] as PlacedCommand.Copy
    assertEquals(100_000L, c.src.toLong(), "copy src")
    assertEquals(0L, c.dst.toLong(), "copy dst")
    assertEquals(50_000L, c.length.toLong(), "copy length")
}

fun testLargeAddRoundtrip() {
    val bigData = ByteArray(256 * 4) { i -> (i and 0xFF).toByte() }
    val placed  = listOf(PlacedCommand.Add(0, bigData))
    val encoded = encodeDelta(placed, false, bigData.size, ZERO_HASH, ZERO_HASH)
    val res = decodeDelta(encoded)
    assertEquals(1L, res.commands.size.toLong(), "command count")
    val a = res.commands[0] as PlacedCommand.Add
    assertEquals(0L, a.dst.toLong(), "add dst")
    assertArrayEquals(bigData, a.data, "add data")
}

fun testBackwardExtension() {
    val block = repeatBytes(b("ABCDEFGHIJKLMNOP"), 20)
    val r = concat(b("____"), block, b("____"))
    val v = concat(b("**"), block, b("**"))
    for (algo in ALL_ALGOS)
        assertArrayEquals(v, applyDelta(r, diff(algo, r, v, opts(4))), "$algo backward extension")
}

fun testTransposition() {
    val x = repeatBytes(b("FIRST_BLOCK_DATA_"), 10)
    val y = repeatBytes(b("SECOND_BLOCK_DATA"), 10)
    val r = concat(x, y)
    val v = concat(y, x)
    for (algo in ALL_ALGOS)
        assertArrayEquals(v, applyDelta(r, diff(algo, r, v, opts(4))), "$algo transposition")
}

fun testScatteredModifications() {
    val rng = Random(42)
    val r = ByteArray(2000).also { rng.nextBytes(it) }
    val v = r.copyOf().also { arr -> repeat(100) { arr[rng.nextInt(arr.size)] = rng.nextInt(256).toByte() } }
    for (algo in ALL_ALGOS)
        assertArrayEquals(v, roundtrip(algo, r, v, 4), "$algo scattered modifications")
}

// ── in-place basics ───────────────────────────────────────────────────────────

fun testInplacePaperExample() {
    val r = b("ABCDEFGHIJKLMNOP")
    val v = b("QWIJKLMNOBCDEFGHZDEFGHIJKL")
    for (algo in ALL_ALGOS)
        for (pol in ALL_POLICIES)
            assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 2), "$algo/$pol inplace paper example")
}

fun testInplaceBinaryRoundtrip() {
    val r = repeatBytes(b("ABCDEFGHIJKLMNOPQRSTUVWXYZ"), 100)
    val v = repeatBytes(b("0123EFGHIJKLMNOPQRS456ABCDEFGHIJKL789"), 100)
    for (algo in ALL_ALGOS)
        for (pol in ALL_POLICIES)
            assertArrayEquals(v, inplaceBinaryRoundtrip(algo, r, v, pol, 4), "$algo/$pol inplace binary roundtrip")
}

fun testInplaceSimpleTransposition() {
    val x = repeatBytes(b("FIRST_BLOCK_DATA_"), 20)
    val y = repeatBytes(b("SECOND_BLOCK_DATA"), 20)
    val r = concat(x, y); val v = concat(y, x)
    for (algo in ALL_ALGOS)
        for (pol in ALL_POLICIES)
            assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4), "$algo/$pol inplace simple transposition")
}

fun testInplaceVersionLarger() {
    val r = repeatBytes(b("ABCDEFGH"), 50)
    val v = concat(repeatBytes(b("XXABCDEFGH"), 50), repeatBytes(b("YYABCDEFGH"), 50))
    for (algo in ALL_ALGOS)
        for (pol in ALL_POLICIES)
            assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4), "$algo/$pol inplace version larger")
}

fun testInplaceVersionSmaller() {
    val r = repeatBytes(b("ABCDEFGHIJKLMNOP"), 100)
    val v = repeatBytes(b("EFGHIJKL"), 50)
    for (algo in ALL_ALGOS)
        for (pol in ALL_POLICIES)
            assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4), "$algo/$pol inplace version smaller")
}

fun testInplaceIdentical() {
    val data = repeatBytes(b("The quick brown fox jumps over the lazy dog."), 10)
    for (algo in ALL_ALGOS)
        for (pol in ALL_POLICIES)
            assertArrayEquals(data, inplaceRoundtrip(algo, data, data, pol, 2), "$algo/$pol inplace identical")
}

fun testInplaceEmptyVersion() {
    val r = b("hello")
    for (algo in ALL_ALGOS) {
        val cmds = diff(algo, r, ByteArray(0), opts(2))
        val ip   = makeInplace(r, cmds, CyclePolicy.LOCALMIN)
        assertArrayEquals(ByteArray(0), applyDeltaInplace(r, ip, 0), "$algo inplace empty version")
    }
}

fun testInplaceScattered() {
    val rng = Random(99)
    val r = ByteArray(2000).also { rng.nextBytes(it) }
    val v = r.copyOf().also { arr -> repeat(100) { arr[rng.nextInt(arr.size)] = rng.nextInt(256).toByte() } }
    for (algo in ALL_ALGOS)
        for (pol in ALL_POLICIES)
            assertArrayEquals(v, inplaceBinaryRoundtrip(algo, r, v, pol, 4), "$algo/$pol inplace scattered")
}

fun testStandardNotDetectedAsInplace() {
    val r = repeatBytes(b("ABCDEFGH"), 10)
    val v = repeatBytes(b("EFGHABCD"), 10)
    val cmds  = diff(Algorithm.GREEDY, r, v, opts(2))
    val placed = placeCommands(cmds)
    val delta  = encodeDelta(placed, false, v.size, ZERO_HASH, ZERO_HASH)
    assertFalse(isInplaceDelta(delta), "standard should not be detected as inplace")
}

fun testInplaceDetected() {
    val r = repeatBytes(b("ABCDEFGH"), 10)
    val v = repeatBytes(b("EFGHABCD"), 10)
    val cmds = diff(Algorithm.GREEDY, r, v, opts(2))
    val ip   = makeInplace(r, cmds, CyclePolicy.LOCALMIN)
    val delta = encodeDelta(ip, true, v.size, ZERO_HASH, ZERO_HASH)
    assertTrue(isInplaceDelta(delta), "inplace delta should be detected")
}

// ── in-place variable-length blocks ───────────────────────────────────────────

fun testInplaceVarlenPermutation() {
    val blocks = makeBlocks(); val r = blocksRef(blocks)
    val rng = Random(2003)
    val perm = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7); shuffle(perm, rng)
    val v = concat(*perm.map { blocks[it] }.toTypedArray())
    for (algo in ALL_ALGOS)
        for (pol in ALL_POLICIES)
            assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4), "$algo/$pol varlen permutation")
}

fun testInplaceVarlenReverse() {
    val blocks = makeBlocks(); val r = blocksRef(blocks)
    val v = concat(*blocks.reversed().toTypedArray())
    for (algo in ALL_ALGOS)
        for (pol in ALL_POLICIES)
            assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4), "$algo/$pol varlen reverse")
}

fun testInplaceVarlenJunk() {
    val blocks = makeBlocks(); val r = blocksRef(blocks)
    val rng = Random(20030)
    val junk = ByteArray(300).also { rng.nextBytes(it) }
    val perm = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7); shuffle(perm, rng)
    val parts = mutableListOf<ByteArray>()
    for (i in perm) {
        parts.add(blocks[i])
        val junkLen = 50 + rng.nextInt(251)
        parts.add(junk.copyOf(minOf(junkLen, junk.size)))
    }
    val v = concat(*parts.toTypedArray())
    for (algo in ALL_ALGOS)
        for (pol in ALL_POLICIES)
            assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4), "$algo/$pol varlen junk")
}

fun testInplaceVarlenDropDup() {
    val blocks = makeBlocks(); val r = blocksRef(blocks)
    val v = concat(blocks[3], blocks[0], blocks[0], blocks[5], blocks[3])
    for (algo in ALL_ALGOS)
        for (pol in ALL_POLICIES)
            assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4), "$algo/$pol varlen drop+dup")
}

fun testInplaceVarlenDoubleSized() {
    val blocks = makeBlocks(); val r = blocksRef(blocks)
    val rng = Random(7001)
    val p1 = intArrayOf(0,1,2,3,4,5,6,7); shuffle(p1, rng)
    val p2 = intArrayOf(0,1,2,3,4,5,6,7); shuffle(p2, rng)
    val parts = (p1.map { blocks[it] } + p2.map { blocks[it] }).toTypedArray()
    val v = concat(*parts)
    for (algo in ALL_ALGOS)
        for (pol in ALL_POLICIES)
            assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4), "$algo/$pol varlen double-sized")
}

fun testInplaceVarlenSubset() {
    val blocks = makeBlocks(); val r = blocksRef(blocks)
    val v = concat(blocks[6], blocks[2])
    for (algo in ALL_ALGOS)
        for (pol in ALL_POLICIES)
            assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4), "$algo/$pol varlen subset")
}

fun testInplaceVarlenHalfBlockScramble() {
    val blocks = makeBlocks(); val r = blocksRef(blocks)
    val halves = blocks.flatMap { blk ->
        val mid = blk.size / 2
        listOf(blk.copyOfRange(0, mid), blk.copyOfRange(mid, blk.size))
    }
    val rng = Random(5555)
    val perm = IntArray(halves.size) { it }; shuffle(perm, rng)
    val v = concat(*perm.map { halves[it] }.toTypedArray())
    for (algo in ALL_ALGOS)
        for (pol in ALL_POLICIES) {
            assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4),
                "$algo/$pol half-block scramble (direct)")
            assertArrayEquals(v, inplaceBinaryRoundtrip(algo, r, v, pol, 4),
                "$algo/$pol half-block scramble (binary)")
        }
}

fun testInplaceVarlenRandomTrials() {
    val blocks = makeBlocks(); val r = blocksRef(blocks)
    val rng = Random(9999)
    val trials = Array(20) {
        val k = 3 + rng.nextInt(6)
        val indices = IntArray(8) { it }; shuffle(indices, rng)
        IntArray(k) { indices[it] }.also { shuffle(it, rng) }
    }
    for (algo in ALL_ALGOS)
        for (pol in ALL_POLICIES)
            for (t in 0 until 20) {
                val v = concat(*trials[t].map { blocks[it] }.toTypedArray())
                assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4), "$algo/$pol random trial $t")
            }
}

// ── cycle policy ──────────────────────────────────────────────────────────────

fun testLocalminPicksSmallest() {
    val blocks = makeBlocks(); val r = blocksRef(blocks)
    val v = concat(*blocks.reversed().toTypedArray())
    val cmds    = diff(Algorithm.GREEDY, r, v, opts(4))
    val ipConst = makeInplace(r, cmds, CyclePolicy.CONSTANT)
    val ipLmin  = makeInplace(r, cmds, CyclePolicy.LOCALMIN)
    val addConst = ipConst.filterIsInstance<PlacedCommand.Add>().sumOf { it.data.size.toLong() }
    val addLmin  = ipLmin.filterIsInstance<PlacedCommand.Add>().sumOf { it.data.size.toLong() }
    assertTrue(addLmin <= addConst,
        "localmin ($addLmin) should produce <= add bytes as constant ($addConst)")
}

// ── checkpointing ─────────────────────────────────────────────────────────────

fun testCorrectingCheckpointingTinyTable() {
    val r = repeatBytes(b("ABCDEFGHIJKLMNOP"), 20)
    val v = concat(r.copyOfRange(0, 160), b("XXXXYYYY"), r.copyOfRange(160, r.size))
    val o = DiffOptions(p = 16, q = 7)
    val cmds = diff(Algorithm.CORRECTING, r, v, o)
    assertArrayEquals(v, applyDelta(r, cmds), "correcting q=7 tiny table")
}

fun testCorrectingCheckpointingVariousSizes() {
    val r = ByteArray(2000) { i -> (i and 0xFF).toByte() }
    val v = ByteArray(2050)
    r.copyInto(v, 0, 0, 500)
    v.fill(0xFF.toByte(), 500, 550)
    r.copyInto(v, 550, 500, 2000)
    for (q in intArrayOf(7, 31, 101, 1009, TABLE_SIZE)) {
        val o = DiffOptions(p = 16, q = q)
        val cmds = diff(Algorithm.CORRECTING, r, v, o)
        assertArrayEquals(v, applyDelta(r, cmds), "correcting q=$q")
    }
}

// ── CRC-64/XZ ─────────────────────────────────────────────────────────────────

fun testCrc64Empty() {
    assertArrayEquals(ByteArray(8), Crc64.hash8(ByteArray(0)), "CRC64 empty")
}

fun testCrc64CheckValue() {
    val expected = hexToBytes("995dc9bbdf1939fa")
    val input    = "123456789".toByteArray(Charsets.ISO_8859_1)
    assertArrayEquals(expected, Crc64.hash8(input), "CRC64 check value")
}

// ── primality ─────────────────────────────────────────────────────────────────

fun testNextPrimeIsPrime() {
    assertTrue(isPrime(TABLE_SIZE.toLong()), "TABLE_SIZE should be prime")
    assertTrue(isPrime(nextPrime(1048574L)), "nextPrime(1048574) should be prime")
    assertEquals(1048573L, nextPrime(1048573L), "nextPrime of a prime is itself")
}

// ── inplace subcommand path ───────────────────────────────────────────────────

fun testInplaceSubcommandRoundtrip() {
    val rs = arrayOf(b("ABCDEF"), b("AAABBBCCC"), b("the quick brown fox"),
                     b("ABCDEF"), b("hello world"), ByteArray(0))
    val vs = arrayOf(b("FEDCBA"), b("CCCBBBAAA"), b("the quick brown cat"),
                     b("ABCDEF"), ByteArray(0),    b("hello world"))
    for (i in rs.indices) {
        val r = rs[i]; val v = vs[i]
        for (algo in ALL_ALGOS)
            for (pol in ALL_POLICIES) {
                val ipDelta = viaInplaceSubcommand(algo, r, v, pol, 2)
                val res = decodeDelta(ipDelta)
                val recovered = applyDeltaInplace(r, res.commands, v.size)
                assertArrayEquals(v, recovered, "$algo/$pol subcommand roundtrip case $i")
            }
    }
}

fun testInplaceSubcommandIdempotent() {
    val r = b("ABCDEFGHIJ")
    val v = b("JIHGFEDCBA")
    for (algo in ALL_ALGOS)
        for (pol in ALL_POLICIES) {
            val cmds    = diff(algo, r, v, opts(2))
            val ip      = makeInplace(r, cmds, pol)
            val ipDelta = encodeDelta(ip, true, v.size, ZERO_HASH, ZERO_HASH)
            val res     = decodeDelta(ipDelta)
            assertTrue(res.inplace, "$algo/$pol: inplace delta should be detected as inplace")
        }
}

fun testInplaceSubcommandEquivDirect() {
    val rs = arrayOf(b("ABCDEF"), b("AAABBBCCC"), b("the quick brown fox"), b("ABCDEFGHIJKLMNOP"))
    val vs = arrayOf(b("FEDCBA"), b("CCCBBBAAA"), b("the quick brown cat"), b("PONMLKJIHGFEDCBA"))
    for (i in rs.indices) {
        val r = rs[i]; val v = vs[i]
        for (algo in ALL_ALGOS)
            for (pol in ALL_POLICIES) {
                val cmds     = diff(algo, r, v, opts(2))
                val ipDirect = makeInplace(r, cmds, pol)
                val directBytes = encodeDelta(ipDirect, true, v.size, ZERO_HASH, ZERO_HASH)
                val subBytes    = viaInplaceSubcommand(algo, r, v, pol, 2)
                assertArrayEquals(directBytes, subBytes, "$algo/$pol subcommand vs direct case $i")
            }
    }
}

// ── splay tree ────────────────────────────────────────────────────────────────

fun testSplayRoundtrip() {
    val r = repeatBytes(b("ABCDEFGHIJKLMNOPQRSTUVWXYZ"), 100)
    val v = repeatBytes(b("0123EFGHIJKLMNOPQRS456ABCDEFGHIJKL789"), 100)
    for (algo in ALL_ALGOS) {
        val splOpts = DiffOptions(p = 4, useSplay = true)
        val cmds = diff(algo, r, v, splOpts)
        assertArrayEquals(v, applyDelta(r, cmds), "$algo splay roundtrip")
    }
}

// ── edge cases and boundaries ─────────────────────────────────────────────────

fun testSingleByte() {
    val one   = byteArrayOf(0x41)
    val other = byteArrayOf(0x42)
    val empty = ByteArray(0)
    for (algo in ALL_ALGOS) {
        assertArrayEquals(one,   applyDelta(one,   diff(algo, one,   one,   opts(1))), "$algo 1b same")
        assertArrayEquals(other, applyDelta(one,   diff(algo, one,   other, opts(1))), "$algo 1b differ")
        assertArrayEquals(empty, applyDelta(one,   diff(algo, one,   empty, opts(1))), "$algo 1b r=1 v=0")
        assertArrayEquals(one,   applyDelta(empty, diff(algo, empty, one,   opts(1))), "$algo 1b r=0 v=1")
    }
}

fun testBoundaryByteMutations() {
    val n = 64; val p = 4
    val r = ByteArray(n) { i -> i.toByte() }
    val vFirst  = r.copyOf().also { it[0]   = (it[0].toInt()   xor 0xFF).toByte() }
    val vLast   = r.copyOf().also { it[n-1] = (it[n-1].toInt() xor 0xFF).toByte() }
    val vAppend = r.copyOf(n + 1).also { it[n] = 0x5A }
    val vDrop   = r.copyOf(n - 1)
    for (algo in ALL_ALGOS) {
        assertArrayEquals(vFirst,  roundtrip(algo, r, vFirst,  p), "$algo byte[0] flipped")
        assertArrayEquals(vLast,   roundtrip(algo, r, vLast,   p), "$algo byte[n-1] flipped")
        assertArrayEquals(vAppend, roundtrip(algo, r, vAppend, p), "$algo one byte appended")
        assertArrayEquals(vDrop,   roundtrip(algo, r, vDrop,   p), "$algo last byte dropped")
    }
}

fun testRefShorterThanSeed() {
    val p = 8
    val v = byteArrayOf(0x10, 0x11, 0x12, 0x13, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
    for (rLen in 0 until p) {
        val r = ByteArray(rLen) { i -> (i + 1).toByte() }
        for (algo in ALL_ALGOS)
            assertArrayEquals(v, applyDelta(r, diff(algo, r, v, opts(p))),
                "$algo ref shorter than seed rLen=$rLen")
    }
}

fun testSizeSweep() {
    val p = 4
    val sizes = intArrayOf(0, 1, 2, 3, p-1, p, p+1, 2*p-1, 2*p, 2*p+1,
                           63, 64, 65, 127, 128, 129, 255, 256, 257, 511, 512, 513)
    val bigRef = ByteArray(600) { i -> (i * 3 and 0xFF).toByte() }

    for (vLen in sizes) {
        val vPrefix = bigRef.copyOf(vLen)
        for (algo in ALL_ALGOS)
            assertArrayEquals(vPrefix, roundtrip(algo, bigRef, vPrefix, p), "$algo vLen=$vLen prefix ver")
        val vNew = ByteArray(vLen) { i -> (i * 7 + 1 and 0xFF).toByte() }
        for (algo in ALL_ALGOS)
            assertArrayEquals(vNew, roundtrip(algo, bigRef, vNew, p), "$algo vLen=$vLen all-new ver")
    }
    val fixedVer = bigRef.copyOf(64)
    for (rLen in sizes) {
        val r = bigRef.copyOf(rLen)
        for (algo in ALL_ALGOS)
            assertArrayEquals(fixedVer, roundtrip(algo, r, fixedVer, p), "$algo rLen=$rLen fixed ver")
    }
}

fun testEncodingVersionSizeBoundaries() {
    val sizes = intArrayOf(0, 1, 127, 128, 255, 256, 257,
                           32767, 32768, 32769, 65535, 65536, 65537,
                           8388607, 8388608, 8388609, 16777215, 16777216, 16777217)
    for (sz in sizes) {
        val encoded = encodeDelta(emptyList(), false, sz, ZERO_HASH, ZERO_HASH)
        val res = decodeDelta(encoded)
        assertEquals(sz.toLong(), res.versionSize.toLong(), "version_size=$sz")
        assertEquals(0L, res.commands.size.toLong(), "no commands at version_size=$sz")
    }
}

fun testEncodingCommandFieldBoundaries() {
    val offsets = intArrayOf(0, 1, 127, 128, 255, 256, 257, 65535, 65536, 65537)
    for (src in offsets) {
        val cmds = listOf(PlacedCommand.Copy(src, 0, 1))
        val c = decodeDelta(encodeDelta(cmds, false, 1, ZERO_HASH, ZERO_HASH)).commands[0] as PlacedCommand.Copy
        assertEquals(src.toLong(), c.src.toLong(), "copy src=$src")
        assertEquals(0L,           c.dst.toLong(), "copy dst at src=$src")
        assertEquals(1L,           c.length.toLong(), "copy length at src=$src")
    }
    for (dst in offsets) {
        val cmds = listOf(PlacedCommand.Copy(0, dst, 1))
        val c = decodeDelta(encodeDelta(cmds, false, dst + 1, ZERO_HASH, ZERO_HASH)).commands[0] as PlacedCommand.Copy
        assertEquals(dst.toLong(), c.dst.toLong(), "copy dst=$dst")
    }
    for (len in intArrayOf(1, 127, 128, 255, 256, 257, 65535, 65536)) {
        val cmds = listOf(PlacedCommand.Copy(0, 0, len))
        val c = decodeDelta(encodeDelta(cmds, false, len, ZERO_HASH, ZERO_HASH)).commands[0] as PlacedCommand.Copy
        assertEquals(len.toLong(), c.length.toLong(), "copy length=$len")
    }
    for (dst in offsets) {
        val cmds = listOf(PlacedCommand.Add(dst, byteArrayOf(0xFF.toByte())))
        val a = decodeDelta(encodeDelta(cmds, false, dst + 1, ZERO_HASH, ZERO_HASH)).commands[0] as PlacedCommand.Add
        assertEquals(dst.toLong(), a.dst.toLong(), "add dst=$dst")
        assertArrayEquals(byteArrayOf(0xFF.toByte()), a.data, "add data at dst=$dst")
    }
}

fun testInplaceVersionOneLargerTight() {
    for (n in intArrayOf(1, 2, 3, 4, 7, 8, 15, 16, 17, 31, 32, 63, 64)) {
        val r = ByteArray(n) { i -> (i and 0xFF).toByte() }
        val v = r.copyOf(n + 1).also { it[n] = 0x5A }
        for (algo in ALL_ALGOS)
            for (pol in ALL_POLICIES)
                assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 2),
                    "$algo/$pol inplace |V|=|R|+1 n=$n")
    }
}

fun testInplaceVersionOneSmallerTight() {
    for (n in intArrayOf(2, 3, 4, 5, 8, 9, 15, 16, 17, 31, 32, 65)) {
        val r = ByteArray(n) { i -> (i and 0xFF).toByte() }
        val v = r.copyOf(n - 1)
        for (algo in ALL_ALGOS)
            for (pol in ALL_POLICIES)
                assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 2),
                    "$algo/$pol inplace |V|=|R|-1 n=$n")
    }
}

fun testInplaceVersionSameSizeTight() {
    for (n in intArrayOf(2, 4, 8, 16, 32, 64, 128, 256)) {
        val r    = ByteArray(n) { i -> (i and 0xFF).toByte() }
        val half = n / 2
        val v    = ByteArray(n)
        r.copyInto(v, 0,    half, n)
        r.copyInto(v, half, 0,   half)
        for (algo in ALL_ALGOS)
            for (pol in ALL_POLICIES)
                assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 2),
                    "$algo/$pol inplace same-size swap n=$n")
    }
}

fun testInplaceVersionOneByteMin() {
    val r     = ByteArray(64) { i -> i.toByte() }
    val vCopy = byteArrayOf(r[32])
    val vAdd  = byteArrayOf(0xAB.toByte())
    for (algo in ALL_ALGOS)
        for (pol in ALL_POLICIES) {
            assertArrayEquals(vCopy, inplaceRoundtrip(algo, r, vCopy, pol, 2),
                "$algo/$pol inplace v=1 byte (copy)")
            assertArrayEquals(vAdd, inplaceRoundtrip(algo, r, vAdd, pol, 2),
                "$algo/$pol inplace v=1 byte (add)")
        }
}

fun testSeedLengthBoundaries() {
    val r = b("ABCDEFGHIJKLMNOP")
    val v = b("QWIJKLMNOBCDEFGHZDEFGHIJKL")
    for (p in intArrayOf(1, 2, r.size, r.size + 1))
        for (algo in ALL_ALGOS)
            assertArrayEquals(v, applyDelta(r, diff(algo, r, v, opts(p))), "$algo p=$p")
    val vShort = b("QW")
    val vLong  = b("QWIJKLMNOBCDEFGHZDEFGHIJKLMNOPQRSTUVWXYZ")
    for (algo in ALL_ALGOS) {
        assertArrayEquals(vShort, applyDelta(r, diff(algo, r, vShort, opts(r.size + 1))), "$algo p>|r| short ver")
        assertArrayEquals(vLong,  applyDelta(r, diff(algo, r, vLong,  opts(r.size + 1))), "$algo p>|r| long ver")
    }
}

fun testRealDataRoundtrip() {
    try {
        val r = Files.readAllBytes(Path.of("..", "..", "README.md"))
        val v = Files.readAllBytes(Path.of("..", "..", "HOWTO.md"))
        for (algo in ALL_ALGOS) {
            assertArrayEquals(v, roundtrip(algo, r, v, 8), "$algo real-data roundtrip")
        }
    } catch (e: java.io.IOException) {
        throw AssertionError("failed to read real data: ${e.message}")
    }
}

// ── main ──────────────────────────────────────────────────────────────────────

fun main() {

    println("\n=== Standard differencing ===")
    check("paper example",                  ::testPaperExample)
    check("identical",                      ::testIdentical)
    check("completely different",           ::testCompletelyDifferent)
    check("empty version",                  ::testEmptyVersion)
    check("empty reference",                ::testEmptyReference)
    check("binary roundtrip",               ::testBinaryRoundtrip)
    check("binary encoding roundtrip",      ::testBinaryEncodingRoundtrip)
    check("decode rejects missing END",     ::testDecodeRejectsMissingEnd)
    check("decode rejects trailing data",   ::testDecodeRejectsTrailingData)
    check("decode rejects copy past size",  ::testDecodeRejectsCopyPastVersionSize)
    check("validate rejects source overflow", ::testValidatePlacedCommandsRejectsSourceOverflow)
    check("binary encoding inplace flag",   ::testBinaryEncodingInplaceFlag)
    check("large copy roundtrip",           ::testLargeCopyRoundtrip)
    check("large add roundtrip",            ::testLargeAddRoundtrip)
    check("backward extension",             ::testBackwardExtension)
    check("transposition",                  ::testTransposition)
    check("scattered modifications",        ::testScatteredModifications)
    check("real data roundtrip",            ::testRealDataRoundtrip)

    println("\n=== In-place basics ===")
    check("inplace paper example",          ::testInplacePaperExample)
    check("inplace binary roundtrip",       ::testInplaceBinaryRoundtrip)
    check("inplace simple transposition",   ::testInplaceSimpleTransposition)
    check("inplace version larger",         ::testInplaceVersionLarger)
    check("inplace version smaller",        ::testInplaceVersionSmaller)
    check("inplace identical",              ::testInplaceIdentical)
    check("inplace empty version",          ::testInplaceEmptyVersion)
    check("inplace scattered",              ::testInplaceScattered)
    check("standard not detected as inplace", ::testStandardNotDetectedAsInplace)
    check("inplace detected",               ::testInplaceDetected)

    println("\n=== In-place variable-length blocks ===")
    check("varlen permutation",             ::testInplaceVarlenPermutation)
    check("varlen reverse",                 ::testInplaceVarlenReverse)
    check("varlen junk",                    ::testInplaceVarlenJunk)
    check("varlen drop+dup",                ::testInplaceVarlenDropDup)
    check("varlen double-sized",            ::testInplaceVarlenDoubleSized)
    check("varlen subset",                  ::testInplaceVarlenSubset)
    check("varlen half-block scramble",     ::testInplaceVarlenHalfBlockScramble)
    check("varlen random trials",           ::testInplaceVarlenRandomTrials)

    println("\n=== Cycle policy ===")
    check("localmin picks smallest",        ::testLocalminPicksSmallest)

    println("\n=== Checkpointing ===")
    check("correcting tiny table (q=7)",    ::testCorrectingCheckpointingTinyTable)
    check("correcting various table sizes", ::testCorrectingCheckpointingVariousSizes)

    println("\n=== CRC-64/XZ ===")
    check("CRC64 empty input",              ::testCrc64Empty)
    check("CRC64 check value 123456789",    ::testCrc64CheckValue)

    println("\n=== Primality ===")
    check("next prime is prime",            ::testNextPrimeIsPrime)

    println("\n=== Inplace subcommand ===")
    check("inplace subcommand roundtrip",   ::testInplaceSubcommandRoundtrip)
    check("inplace subcommand idempotent",  ::testInplaceSubcommandIdempotent)
    check("inplace subcommand equiv direct",::testInplaceSubcommandEquivDirect)

    println("\n=== Splay tree ===")
    check("splay roundtrip",                ::testSplayRoundtrip)

    println("\n=== Edge cases and boundaries ===")
    check("single byte ref/ver",               ::testSingleByte)
    check("boundary byte mutations",           ::testBoundaryByteMutations)
    check("ref shorter than seed length",      ::testRefShorterThanSeed)
    check("size sweep (ver and ref sizes)",    ::testSizeSweep)
    check("encoding: version-size boundaries", ::testEncodingVersionSizeBoundaries)
    check("encoding: field boundaries",        ::testEncodingCommandFieldBoundaries)
    check("inplace: |V|=|R|+1 tight",         ::testInplaceVersionOneLargerTight)
    check("inplace: |V|=|R|-1 tight",         ::testInplaceVersionOneSmallerTight)
    check("inplace: |V|=|R| same-size swap",  ::testInplaceVersionSameSizeTight)
    check("inplace: version is 1 byte",       ::testInplaceVersionOneByteMin)
    check("seed length at boundaries",         ::testSeedLengthBoundaries)

    println("\n========================================")
    System.out.printf("Results: %d passed, %d failed (of %d)%n", pass, fail, tests)
    println("========================================")
    if (fail > 0) System.exit(1)
}
