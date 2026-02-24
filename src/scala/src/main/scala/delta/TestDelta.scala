package delta

import java.util.{Arrays => JArrays, Random}

/**
 * Integration tests for the Scala delta compression library.
 *
 * Mirrors the Java/Kotlin/Go test suites.
 *
 * Run:  scala -cp delta.jar delta.TestDelta
 * Or:   make test  (from src/scala/)
 */

// ── counters ──────────────────────────────────────────────────────────────────

var pass  = 0
var fail  = 0
var tests = 0

val allAlgos    = Algorithm.values
val allPolicies = CyclePolicy.values

// ── assertion helpers ─────────────────────────────────────────────────────────

def assertArrayEquals(expected: Array[Byte], actual: Array[Byte], msg: String = "byte arrays differ"): Unit =
  if !JArrays.equals(expected, actual) then
    throw new AssertionError(s"$msg: expected ${expected.length} bytes, got ${actual.length}")

def assertTrue(cond: Boolean, msg: String): Unit =
  if !cond then throw new AssertionError(msg)

def assertFalse(cond: Boolean, msg: String): Unit =
  if cond then throw new AssertionError(msg)

def assertEquals(expected: Long, actual: Long, msg: String): Unit =
  if expected != actual then throw new AssertionError(s"$msg: expected $expected, got $actual")

// ── test runner ───────────────────────────────────────────────────────────────

def check(name: String)(block: => Unit): Unit = {
  tests += 1
  try {
    block
    pass += 1
    System.out.printf("  ok  %s%n", name)
  } catch {
    case t: Throwable =>
      fail += 1
      val msg = t.getMessage
      System.out.printf("FAIL  %s: %s%n", name, if msg != null then msg else t.toString)
  }
}

// ── helpers ───────────────────────────────────────────────────────────────────

def opts(p: Int) = DiffOptions(p = p)

val zeroHash = new Array[Byte](deltaCrcSize)

def b(s: String): Array[Byte] = s.getBytes("ISO-8859-1")

def concat(parts: Array[Byte]*): Array[Byte] = {
  val out = new Array[Byte](parts.map(_.length).sum)
  var pos = 0
  for a <- parts do { System.arraycopy(a, 0, out, pos, a.length); pos += a.length }
  out
}

def repeatBytes(data: Array[Byte], n: Int): Array[Byte] = {
  val out = new Array[Byte](data.length * n)
  for i <- 0 until n do System.arraycopy(data, 0, out, i * data.length, data.length)
  out
}

def shuffle(arr: Array[Int], rng: Random): Unit = {
  var i = arr.length - 1
  while i > 0 do {
    val j   = rng.nextInt(i + 1)
    val tmp = arr(i); arr(i) = arr(j); arr(j) = tmp
    i -= 1
  }
}

def hexToBytes(hex: String): Array[Byte] =
  Array.tabulate(hex.length / 2)(i => Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16).toByte)

/** Standard encode path: diff → place → encode → decode → applyPlacedTo. */
def roundtrip(algo: Algorithm, r: Array[Byte], v: Array[Byte], p: Int): Array[Byte] = {
  val cmds   = diff(algo, r, v, opts(p))
  val placed = placeCommands(cmds)
  val delta  = encodeDelta(placed, false, outputSize(cmds), zeroHash, zeroHash)
  val res    = decodeDelta(delta)
  val out    = new Array[Byte](res.versionSize)
  applyPlacedTo(r, res.commands, out)
  out
}

/** In-place path: diff → makeInplace → applyDeltaInplace (no binary I/O). */
def inplaceRoundtrip(algo: Algorithm, r: Array[Byte], v: Array[Byte],
                     pol: CyclePolicy, p: Int): Array[Byte] = {
  val cmds = diff(algo, r, v, opts(p))
  val ip   = makeInplace(r, cmds, pol)
  applyDeltaInplace(r, ip, v.length)
}

/** In-place binary path: diff → makeInplace → encode → decode → applyDeltaInplace. */
def inplaceBinaryRoundtrip(algo: Algorithm, r: Array[Byte], v: Array[Byte],
                            pol: CyclePolicy, p: Int): Array[Byte] = {
  val cmds  = diff(algo, r, v, opts(p))
  val ip    = makeInplace(r, cmds, pol)
  val delta = encodeDelta(ip, true, v.length, zeroHash, zeroHash)
  val res   = decodeDelta(delta)
  applyDeltaInplace(r, res.commands, res.versionSize)
}

/** Simulate the `delta inplace` subcommand. */
def viaInplaceSubcommand(algo: Algorithm, r: Array[Byte], v: Array[Byte],
                          pol: CyclePolicy, p: Int): Array[Byte] = {
  val cmds     = diff(algo, r, v, opts(p))
  val placed   = placeCommands(cmds)
  val standard = encodeDelta(placed, false, v.length, zeroHash, zeroHash)
  val res      = decodeDelta(standard)
  assertFalse(res.inplace, "standard delta should not be flagged as inplace")
  val cmds2 = unplaceCommands(res.commands)
  val ip    = makeInplace(r, cmds2, pol)
  encodeDelta(ip, true, res.versionSize, zeroHash, zeroHash)
}

/** Eight variable-length blocks with deterministic byte patterns. */
def makeBlocks(): List[Array[Byte]] = {
  val sizes = Array(200, 500, 1234, 3000, 800, 4999, 1500, 2750)
  sizes.zipWithIndex.map { case (sz, i) =>
    Array.tabulate[Byte](sz)(j => ((i * 37 + j) & 0xFF).toByte)
  }.toList
}

def blocksRef(blocks: List[Array[Byte]]): Array[Byte] = concat(blocks*)

// ── standard differencing ─────────────────────────────────────────────────────

def testPaperExample(): Unit = {
  val r = b("ABCDEFGHIJKLMNOP")
  val v = b("QWIJKLMNOBCDEFGHZDEFGHIJKL")
  for algo <- allAlgos do
    assertArrayEquals(v, applyDelta(r, diff(algo, r, v, opts(2))), s"$algo paper example")
}

def testIdentical(): Unit = {
  val data = repeatBytes(b("The quick brown fox jumps over the lazy dog."), 10)
  for algo <- allAlgos do {
    val cmds = diff(algo, data, data, opts(2))
    assertArrayEquals(data, applyDelta(data, cmds), s"$algo identical roundtrip")
    for c <- cmds do
      assertTrue(c.isInstanceOf[Command.Copy], s"$algo: identical input should produce no adds")
  }
}

def testCompletelyDifferent(): Unit = {
  val r = Array.tabulate[Byte](512)(i => (i & 0xFF).toByte)
  val v = Array.tabulate[Byte](512)(i => ((255 - (i & 0xFF)) & 0xFF).toByte)
  for algo <- allAlgos do
    assertArrayEquals(v, applyDelta(r, diff(algo, r, v, opts(2))), s"$algo completely different")
}

def testEmptyVersion(): Unit = {
  val r = b("hello")
  for algo <- allAlgos do {
    val cmds = diff(algo, r, Array.emptyByteArray, opts(2))
    assertTrue(cmds.isEmpty, s"$algo: empty version should produce no commands")
    assertArrayEquals(Array.emptyByteArray, applyDelta(r, cmds), s"$algo empty version")
  }
}

def testEmptyReference(): Unit = {
  val v = b("hello world")
  for algo <- allAlgos do
    assertArrayEquals(v, applyDelta(Array.emptyByteArray, diff(algo, Array.emptyByteArray, v, opts(2))),
      s"$algo empty reference")
}

def testBinaryRoundtrip(): Unit = {
  val r = repeatBytes(b("ABCDEFGHIJKLMNOPQRSTUVWXYZ"), 100)
  val v = repeatBytes(b("0123EFGHIJKLMNOPQRS456ABCDEFGHIJKL789"), 100)
  for algo <- allAlgos do
    assertArrayEquals(v, roundtrip(algo, r, v, 4), s"$algo binary roundtrip")
}

def testBinaryEncodingRoundtrip(): Unit = {
  val placed  = List(PlacedCommand.Add(0, Array[Byte](100, 101, 102)), PlacedCommand.Copy(888, 3, 488))
  val encoded = encodeDelta(placed, false, 491, zeroHash, zeroHash)
  val res     = decodeDelta(encoded)
  assertFalse(res.inplace, "should not be inplace")
  assertEquals(491L, res.versionSize.toLong, "version size")
  assertEquals(2L, res.commands.length.toLong, "command count")
  val a = res.commands(0).asInstanceOf[PlacedCommand.Add]
  assertEquals(0L, a.dst.toLong, "add dst")
  assertArrayEquals(Array[Byte](100, 101, 102), a.data, "add data")
  val c = res.commands(1).asInstanceOf[PlacedCommand.Copy]
  assertEquals(888L, c.src.toLong, "copy src")
  assertEquals(3L, c.dst.toLong, "copy dst")
  assertEquals(488L, c.length.toLong, "copy length")
}

def testBinaryEncodingInplaceFlag(): Unit = {
  val placed   = List(PlacedCommand.Copy(0, 10, 5))
  val standard = encodeDelta(placed, false, 15, zeroHash, zeroHash)
  val inplace  = encodeDelta(placed, true,  15, zeroHash, zeroHash)
  assertFalse(isInplaceDelta(standard), "standard should not be inplace")
  assertTrue( isInplaceDelta(inplace),  "inplace should be inplace")
  val r1 = decodeDelta(standard)
  val r2 = decodeDelta(inplace)
  assertFalse(r1.inplace, "standard decoded inplace flag")
  assertTrue( r2.inplace, "inplace decoded inplace flag")
  assertEquals(r1.versionSize.toLong, r2.versionSize.toLong, "version sizes match")
}

def testLargeCopyRoundtrip(): Unit = {
  val placed  = List(PlacedCommand.Copy(100_000, 0, 50_000))
  val encoded = encodeDelta(placed, false, 50_000, zeroHash, zeroHash)
  val res     = decodeDelta(encoded)
  assertEquals(1L, res.commands.length.toLong, "command count")
  val c = res.commands(0).asInstanceOf[PlacedCommand.Copy]
  assertEquals(100_000L, c.src.toLong, "copy src")
  assertEquals(0L, c.dst.toLong, "copy dst")
  assertEquals(50_000L, c.length.toLong, "copy length")
}

def testLargeAddRoundtrip(): Unit = {
  val bigData = Array.tabulate[Byte](256 * 4)(i => (i & 0xFF).toByte)
  val placed  = List(PlacedCommand.Add(0, bigData))
  val encoded = encodeDelta(placed, false, bigData.length, zeroHash, zeroHash)
  val res     = decodeDelta(encoded)
  assertEquals(1L, res.commands.length.toLong, "command count")
  val a = res.commands(0).asInstanceOf[PlacedCommand.Add]
  assertEquals(0L, a.dst.toLong, "add dst")
  assertArrayEquals(bigData, a.data, "add data")
}

def testBackwardExtension(): Unit = {
  val block = repeatBytes(b("ABCDEFGHIJKLMNOP"), 20)
  val r     = concat(b("____"), block, b("____"))
  val v     = concat(b("**"),   block, b("**"))
  for algo <- allAlgos do
    assertArrayEquals(v, applyDelta(r, diff(algo, r, v, opts(4))), s"$algo backward extension")
}

def testTransposition(): Unit = {
  val x = repeatBytes(b("FIRST_BLOCK_DATA_"), 10)
  val y = repeatBytes(b("SECOND_BLOCK_DATA"), 10)
  val r = concat(x, y); val v = concat(y, x)
  for algo <- allAlgos do
    assertArrayEquals(v, applyDelta(r, diff(algo, r, v, opts(4))), s"$algo transposition")
}

def testScatteredModifications(): Unit = {
  val rng = new Random(42)
  val r   = { val a = new Array[Byte](2000); rng.nextBytes(a); a }
  val v   = { val a = r.clone(); for _ <- 0 until 100 do a(rng.nextInt(a.length)) = rng.nextInt(256).toByte; a }
  for algo <- allAlgos do
    assertArrayEquals(v, roundtrip(algo, r, v, 4), s"$algo scattered modifications")
}

// ── in-place basics ───────────────────────────────────────────────────────────

def testInplacePaperExample(): Unit = {
  val r = b("ABCDEFGHIJKLMNOP")
  val v = b("QWIJKLMNOBCDEFGHZDEFGHIJKL")
  for algo <- allAlgos do for pol <- allPolicies do
    assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 2), s"$algo/$pol inplace paper example")
}

def testInplaceBinaryRoundtrip(): Unit = {
  val r = repeatBytes(b("ABCDEFGHIJKLMNOPQRSTUVWXYZ"), 100)
  val v = repeatBytes(b("0123EFGHIJKLMNOPQRS456ABCDEFGHIJKL789"), 100)
  for algo <- allAlgos do for pol <- allPolicies do
    assertArrayEquals(v, inplaceBinaryRoundtrip(algo, r, v, pol, 4), s"$algo/$pol inplace binary roundtrip")
}

def testInplaceSimpleTransposition(): Unit = {
  val x = repeatBytes(b("FIRST_BLOCK_DATA_"), 20)
  val y = repeatBytes(b("SECOND_BLOCK_DATA"), 20)
  val r = concat(x, y); val v = concat(y, x)
  for algo <- allAlgos do for pol <- allPolicies do
    assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4), s"$algo/$pol inplace simple transposition")
}

def testInplaceVersionLarger(): Unit = {
  val r = repeatBytes(b("ABCDEFGH"), 50)
  val v = concat(repeatBytes(b("XXABCDEFGH"), 50), repeatBytes(b("YYABCDEFGH"), 50))
  for algo <- allAlgos do for pol <- allPolicies do
    assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4), s"$algo/$pol inplace version larger")
}

def testInplaceVersionSmaller(): Unit = {
  val r = repeatBytes(b("ABCDEFGHIJKLMNOP"), 100)
  val v = repeatBytes(b("EFGHIJKL"), 50)
  for algo <- allAlgos do for pol <- allPolicies do
    assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4), s"$algo/$pol inplace version smaller")
}

def testInplaceIdentical(): Unit = {
  val data = repeatBytes(b("The quick brown fox jumps over the lazy dog."), 10)
  for algo <- allAlgos do for pol <- allPolicies do
    assertArrayEquals(data, inplaceRoundtrip(algo, data, data, pol, 2), s"$algo/$pol inplace identical")
}

def testInplaceEmptyVersion(): Unit = {
  val r = b("hello")
  for algo <- allAlgos do {
    val cmds = diff(algo, r, Array.emptyByteArray, opts(2))
    val ip   = makeInplace(r, cmds, CyclePolicy.Localmin)
    assertArrayEquals(Array.emptyByteArray, applyDeltaInplace(r, ip, 0), s"$algo inplace empty version")
  }
}

def testInplaceScattered(): Unit = {
  val rng = new Random(99)
  val r   = { val a = new Array[Byte](2000); rng.nextBytes(a); a }
  val v   = { val a = r.clone(); for _ <- 0 until 100 do a(rng.nextInt(a.length)) = rng.nextInt(256).toByte; a }
  for algo <- allAlgos do for pol <- allPolicies do
    assertArrayEquals(v, inplaceBinaryRoundtrip(algo, r, v, pol, 4), s"$algo/$pol inplace scattered")
}

def testStandardNotDetectedAsInplace(): Unit = {
  val r = repeatBytes(b("ABCDEFGH"), 10)
  val v = repeatBytes(b("EFGHABCD"), 10)
  val cmds  = diff(Algorithm.Greedy, r, v, opts(2))
  val placed = placeCommands(cmds)
  val delta  = encodeDelta(placed, false, v.length, zeroHash, zeroHash)
  assertFalse(isInplaceDelta(delta), "standard should not be detected as inplace")
}

def testInplaceDetected(): Unit = {
  val r = repeatBytes(b("ABCDEFGH"), 10)
  val v = repeatBytes(b("EFGHABCD"), 10)
  val cmds  = diff(Algorithm.Greedy, r, v, opts(2))
  val ip    = makeInplace(r, cmds, CyclePolicy.Localmin)
  val delta = encodeDelta(ip, true, v.length, zeroHash, zeroHash)
  assertTrue(isInplaceDelta(delta), "inplace delta should be detected")
}

// ── in-place variable-length blocks ───────────────────────────────────────────

def testInplaceVarlenPermutation(): Unit = {
  val blocks = makeBlocks(); val r = blocksRef(blocks)
  val rng  = new Random(2003)
  val perm = Array(0, 1, 2, 3, 4, 5, 6, 7); shuffle(perm, rng)
  val v = concat(perm.map(blocks(_))*)
  for algo <- allAlgos do for pol <- allPolicies do
    assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4), s"$algo/$pol varlen permutation")
}

def testInplaceVarlenReverse(): Unit = {
  val blocks = makeBlocks(); val r = blocksRef(blocks)
  val v = concat(blocks.reverse*)
  for algo <- allAlgos do for pol <- allPolicies do
    assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4), s"$algo/$pol varlen reverse")
}

def testInplaceVarlenJunk(): Unit = {
  val blocks = makeBlocks(); val r = blocksRef(blocks)
  val rng  = new Random(20030)
  val junk = { val a = new Array[Byte](300); rng.nextBytes(a); a }
  val perm = Array(0, 1, 2, 3, 4, 5, 6, 7); shuffle(perm, rng)
  val parts = scala.collection.mutable.ListBuffer[Array[Byte]]()
  for i <- perm do {
    parts += blocks(i)
    val junkLen = 50 + rng.nextInt(251)
    parts += java.util.Arrays.copyOf(junk, math.min(junkLen, junk.length))
  }
  val v = concat(parts.toSeq*)
  for algo <- allAlgos do for pol <- allPolicies do
    assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4), s"$algo/$pol varlen junk")
}

def testInplaceVarlenDropDup(): Unit = {
  val blocks = makeBlocks(); val r = blocksRef(blocks)
  val v = concat(blocks(3), blocks(0), blocks(0), blocks(5), blocks(3))
  for algo <- allAlgos do for pol <- allPolicies do
    assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4), s"$algo/$pol varlen drop+dup")
}

def testInplaceVarlenDoubleSized(): Unit = {
  val blocks = makeBlocks(); val r = blocksRef(blocks)
  val rng = new Random(7001)
  val p1  = Array(0, 1, 2, 3, 4, 5, 6, 7); shuffle(p1, rng)
  val p2  = Array(0, 1, 2, 3, 4, 5, 6, 7); shuffle(p2, rng)
  val parts = (p1.map(blocks(_)) ++ p2.map(blocks(_))).toSeq
  val v = concat(parts*)
  for algo <- allAlgos do for pol <- allPolicies do
    assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4), s"$algo/$pol varlen double-sized")
}

def testInplaceVarlenSubset(): Unit = {
  val blocks = makeBlocks(); val r = blocksRef(blocks)
  val v = concat(blocks(6), blocks(2))
  for algo <- allAlgos do for pol <- allPolicies do
    assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4), s"$algo/$pol varlen subset")
}

def testInplaceVarlenHalfBlockScramble(): Unit = {
  val blocks = makeBlocks(); val r = blocksRef(blocks)
  val halves = blocks.flatMap { blk =>
    val mid = blk.length / 2
    List(java.util.Arrays.copyOf(blk, mid), java.util.Arrays.copyOfRange(blk, mid, blk.length))
  }
  val rng  = new Random(5555)
  val perm = Array.tabulate(halves.length)(identity); shuffle(perm, rng)
  val v    = concat(perm.map(halves(_))*)
  for algo <- allAlgos do for pol <- allPolicies do {
    assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4),
      s"$algo/$pol half-block scramble (direct)")
    assertArrayEquals(v, inplaceBinaryRoundtrip(algo, r, v, pol, 4),
      s"$algo/$pol half-block scramble (binary)")
  }
}

def testInplaceVarlenRandomTrials(): Unit = {
  val blocks = makeBlocks(); val r = blocksRef(blocks)
  val rng    = new Random(9999)
  val trials = Array.tabulate(20) { _ =>
    val k       = 3 + rng.nextInt(6)
    val indices = Array.tabulate(8)(identity); shuffle(indices, rng)
    val sel     = indices.take(k); shuffle(sel, rng); sel
  }
  for algo <- allAlgos do for pol <- allPolicies do
    for t <- 0 until 20 do {
      val v = concat(trials(t).map(blocks(_))*)
      assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 4), s"$algo/$pol random trial $t")
    }
}

// ── cycle policy ──────────────────────────────────────────────────────────────

def testLocalminPicksSmallest(): Unit = {
  val blocks = makeBlocks(); val r = blocksRef(blocks)
  val v      = concat(blocks.reverse*)
  val cmds     = diff(Algorithm.Greedy, r, v, opts(4))
  val ipConst  = makeInplace(r, cmds, CyclePolicy.Constant)
  val ipLmin   = makeInplace(r, cmds, CyclePolicy.Localmin)
  val addConst = ipConst.collect { case c: PlacedCommand.Add => c.data.length.toLong }.sum
  val addLmin  = ipLmin.collect  { case c: PlacedCommand.Add => c.data.length.toLong }.sum
  assertTrue(addLmin <= addConst,
    s"localmin ($addLmin) should produce <= add bytes as constant ($addConst)")
}

// ── checkpointing ─────────────────────────────────────────────────────────────

def testCorrectingCheckpointingTinyTable(): Unit = {
  val r = repeatBytes(b("ABCDEFGHIJKLMNOP"), 20)
  val v = concat(java.util.Arrays.copyOfRange(r, 0, 160), b("XXXXYYYY"), java.util.Arrays.copyOfRange(r, 160, r.length))
  val o = DiffOptions(p = 16, q = 7)
  val cmds = diff(Algorithm.Correcting, r, v, o)
  assertArrayEquals(v, applyDelta(r, cmds), "correcting q=7 tiny table")
}

def testCorrectingCheckpointingVariousSizes(): Unit = {
  val r = Array.tabulate[Byte](2000)(i => (i & 0xFF).toByte)
  val v = new Array[Byte](2050)
  System.arraycopy(r, 0, v, 0, 500)
  JArrays.fill(v, 500, 550, 0xFF.toByte)
  System.arraycopy(r, 500, v, 550, 1500)
  for q <- Array(7, 31, 101, 1009, tableSize) do {
    val o    = DiffOptions(p = 16, q = q)
    val cmds = diff(Algorithm.Correcting, r, v, o)
    assertArrayEquals(v, applyDelta(r, cmds), s"correcting q=$q")
  }
}

// ── CRC-64/XZ ─────────────────────────────────────────────────────────────────

def testCrc64Empty(): Unit =
  assertArrayEquals(new Array[Byte](8), Crc64.hash8(Array.emptyByteArray), "CRC64 empty")

def testCrc64CheckValue(): Unit = {
  val expected = hexToBytes("995dc9bbdf1939fa")
  val input    = "123456789".getBytes("ISO-8859-1")
  assertArrayEquals(expected, Crc64.hash8(input), "CRC64 check value")
}

// ── primality ─────────────────────────────────────────────────────────────────

def testNextPrimeIsPrime(): Unit = {
  assertTrue(isPrime(tableSize.toLong), "tableSize should be prime")
  assertTrue(isPrime(nextPrime(1048574L)), "nextPrime(1048574) should be prime")
  assertEquals(1048573L, nextPrime(1048573L), "nextPrime of a prime is itself")
}

// ── inplace subcommand path ───────────────────────────────────────────────────

def testInplaceSubcommandRoundtrip(): Unit = {
  val rs = Array(b("ABCDEF"), b("AAABBBCCC"), b("the quick brown fox"),
                 b("ABCDEF"), b("hello world"), Array.emptyByteArray)
  val vs = Array(b("FEDCBA"), b("CCCBBBAAA"), b("the quick brown cat"),
                 b("ABCDEF"), Array.emptyByteArray, b("hello world"))
  for i <- rs.indices do {
    val r = rs(i); val v = vs(i)
    for algo <- allAlgos do for pol <- allPolicies do {
      val ipDelta  = viaInplaceSubcommand(algo, r, v, pol, 2)
      val res      = decodeDelta(ipDelta)
      val recovered = applyDeltaInplace(r, res.commands, v.length)
      assertArrayEquals(v, recovered, s"$algo/$pol subcommand roundtrip case $i")
    }
  }
}

def testInplaceSubcommandIdempotent(): Unit = {
  val r = b("ABCDEFGHIJ")
  val v = b("JIHGFEDCBA")
  for algo <- allAlgos do for pol <- allPolicies do {
    val cmds    = diff(algo, r, v, opts(2))
    val ip      = makeInplace(r, cmds, pol)
    val ipDelta = encodeDelta(ip, true, v.length, zeroHash, zeroHash)
    val res     = decodeDelta(ipDelta)
    assertTrue(res.inplace, s"$algo/$pol: inplace delta should be detected as inplace")
  }
}

def testInplaceSubcommandEquivDirect(): Unit = {
  val rs = Array(b("ABCDEF"), b("AAABBBCCC"), b("the quick brown fox"), b("ABCDEFGHIJKLMNOP"))
  val vs = Array(b("FEDCBA"), b("CCCBBBAAA"), b("the quick brown cat"), b("PONMLKJIHGFEDCBA"))
  for i <- rs.indices do {
    val r = rs(i); val v = vs(i)
    for algo <- allAlgos do for pol <- allPolicies do {
      val cmds        = diff(algo, r, v, opts(2))
      val ipDirect    = makeInplace(r, cmds, pol)
      val directBytes = encodeDelta(ipDirect, true, v.length, zeroHash, zeroHash)
      val subBytes    = viaInplaceSubcommand(algo, r, v, pol, 2)
      assertArrayEquals(directBytes, subBytes, s"$algo/$pol subcommand vs direct case $i")
    }
  }
}

// ── splay tree ────────────────────────────────────────────────────────────────

def testSplayRoundtrip(): Unit = {
  val r = repeatBytes(b("ABCDEFGHIJKLMNOPQRSTUVWXYZ"), 100)
  val v = repeatBytes(b("0123EFGHIJKLMNOPQRS456ABCDEFGHIJKL789"), 100)
  for algo <- allAlgos do {
    val splOpts = DiffOptions(p = 4, useSplay = true)
    val cmds    = diff(algo, r, v, splOpts)
    assertArrayEquals(v, applyDelta(r, cmds), s"$algo splay roundtrip")
  }
}

// ── edge cases and boundaries ─────────────────────────────────────────────────

def testSingleByte(): Unit = {
  val one   = Array[Byte](0x41)
  val other = Array[Byte](0x42)
  val empty = Array.emptyByteArray
  for algo <- allAlgos do {
    assertArrayEquals(one,   applyDelta(one,   diff(algo, one,   one,   opts(1))), s"$algo 1b same")
    assertArrayEquals(other, applyDelta(one,   diff(algo, one,   other, opts(1))), s"$algo 1b differ")
    assertArrayEquals(empty, applyDelta(one,   diff(algo, one,   empty, opts(1))), s"$algo 1b r=1 v=0")
    assertArrayEquals(one,   applyDelta(empty, diff(algo, empty, one,   opts(1))), s"$algo 1b r=0 v=1")
  }
}

def testBoundaryByteMutations(): Unit = {
  val n = 64; val p = 4
  val r      = Array.tabulate[Byte](n)(i => i.toByte)
  val vFirst  = { val a = r.clone(); a(0)   = (a(0).toInt   ^ 0xFF).toByte; a }
  val vLast   = { val a = r.clone(); a(n-1) = (a(n-1).toInt ^ 0xFF).toByte; a }
  val vAppend = { val a = java.util.Arrays.copyOf(r, n + 1); a(n) = 0x5A; a }
  val vDrop   = java.util.Arrays.copyOf(r, n - 1)
  for algo <- allAlgos do {
    assertArrayEquals(vFirst,  roundtrip(algo, r, vFirst,  p), s"$algo byte[0] flipped")
    assertArrayEquals(vLast,   roundtrip(algo, r, vLast,   p), s"$algo byte[n-1] flipped")
    assertArrayEquals(vAppend, roundtrip(algo, r, vAppend, p), s"$algo one byte appended")
    assertArrayEquals(vDrop,   roundtrip(algo, r, vDrop,   p), s"$algo last byte dropped")
  }
}

def testRefShorterThanSeed(): Unit = {
  val p = 8
  val v = Array[Byte](0x10, 0x11, 0x12, 0x13, 0xAA.toByte, 0xBB.toByte, 0xCC.toByte, 0xDD.toByte)
  for rLen <- 0 until p do {
    val r = Array.tabulate[Byte](rLen)(i => (i + 1).toByte)
    for algo <- allAlgos do
      assertArrayEquals(v, applyDelta(r, diff(algo, r, v, opts(p))),
        s"$algo ref shorter than seed rLen=$rLen")
  }
}

def testSizeSweep(): Unit = {
  val p     = 4
  val sizes = Array(0, 1, 2, 3, p-1, p, p+1, 2*p-1, 2*p, 2*p+1,
                    63, 64, 65, 127, 128, 129, 255, 256, 257, 511, 512, 513)
  val bigRef = Array.tabulate[Byte](600)(i => (i * 3 & 0xFF).toByte)

  for vLen <- sizes do {
    val vPrefix = java.util.Arrays.copyOf(bigRef, vLen)
    for algo <- allAlgos do
      assertArrayEquals(vPrefix, roundtrip(algo, bigRef, vPrefix, p), s"$algo vLen=$vLen prefix ver")
    val vNew = Array.tabulate[Byte](vLen)(i => (i * 7 + 1 & 0xFF).toByte)
    for algo <- allAlgos do
      assertArrayEquals(vNew, roundtrip(algo, bigRef, vNew, p), s"$algo vLen=$vLen all-new ver")
  }
  val fixedVer = java.util.Arrays.copyOf(bigRef, 64)
  for rLen <- sizes do {
    val r = java.util.Arrays.copyOf(bigRef, rLen)
    for algo <- allAlgos do
      assertArrayEquals(fixedVer, roundtrip(algo, r, fixedVer, p), s"$algo rLen=$rLen fixed ver")
  }
}

def testEncodingVersionSizeBoundaries(): Unit = {
  val sizes = Array(0, 1, 127, 128, 255, 256, 257,
                    32767, 32768, 32769, 65535, 65536, 65537,
                    8388607, 8388608, 8388609, 16777215, 16777216, 16777217)
  for sz <- sizes do {
    val encoded = encodeDelta(Nil, false, sz, zeroHash, zeroHash)
    val res     = decodeDelta(encoded)
    assertEquals(sz.toLong, res.versionSize.toLong, s"version_size=$sz")
    assertEquals(0L, res.commands.length.toLong, s"no commands at version_size=$sz")
  }
}

def testEncodingCommandFieldBoundaries(): Unit = {
  val offsets = Array(0, 1, 127, 128, 255, 256, 257, 65535, 65536, 65537)
  for src <- offsets do {
    val cmds = List(PlacedCommand.Copy(src, 0, 1))
    val c    = decodeDelta(encodeDelta(cmds, false, 1, zeroHash, zeroHash)).commands(0).asInstanceOf[PlacedCommand.Copy]
    assertEquals(src.toLong, c.src.toLong, s"copy src=$src")
    assertEquals(0L, c.dst.toLong, s"copy dst at src=$src")
    assertEquals(1L, c.length.toLong, s"copy length at src=$src")
  }
  for dst <- offsets do {
    val cmds = List(PlacedCommand.Copy(0, dst, 1))
    val c    = decodeDelta(encodeDelta(cmds, false, dst + 1, zeroHash, zeroHash)).commands(0).asInstanceOf[PlacedCommand.Copy]
    assertEquals(dst.toLong, c.dst.toLong, s"copy dst=$dst")
  }
  for len <- Array(1, 127, 128, 255, 256, 257, 65535, 65536) do {
    val cmds = List(PlacedCommand.Copy(0, 0, len))
    val c    = decodeDelta(encodeDelta(cmds, false, len, zeroHash, zeroHash)).commands(0).asInstanceOf[PlacedCommand.Copy]
    assertEquals(len.toLong, c.length.toLong, s"copy length=$len")
  }
  for dst <- offsets do {
    val cmds = List(PlacedCommand.Add(dst, Array[Byte](0xFF.toByte)))
    val a    = decodeDelta(encodeDelta(cmds, false, dst + 1, zeroHash, zeroHash)).commands(0).asInstanceOf[PlacedCommand.Add]
    assertEquals(dst.toLong, a.dst.toLong, s"add dst=$dst")
    assertArrayEquals(Array[Byte](0xFF.toByte), a.data, s"add data at dst=$dst")
  }
}

def testInplaceVersionOneLargerTight(): Unit = {
  for n <- Array(1, 2, 3, 4, 7, 8, 15, 16, 17, 31, 32, 63, 64) do {
    val r = Array.tabulate[Byte](n)(i => (i & 0xFF).toByte)
    val v = { val a = java.util.Arrays.copyOf(r, n + 1); a(n) = 0x5A; a }
    for algo <- allAlgos do for pol <- allPolicies do
      assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 2), s"$algo/$pol inplace |V|=|R|+1 n=$n")
  }
}

def testInplaceVersionOneSmallerTight(): Unit = {
  for n <- Array(2, 3, 4, 5, 8, 9, 15, 16, 17, 31, 32, 65) do {
    val r = Array.tabulate[Byte](n)(i => (i & 0xFF).toByte)
    val v = java.util.Arrays.copyOf(r, n - 1)
    for algo <- allAlgos do for pol <- allPolicies do
      assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 2), s"$algo/$pol inplace |V|=|R|-1 n=$n")
  }
}

def testInplaceVersionSameSizeTight(): Unit = {
  for n <- Array(2, 4, 8, 16, 32, 64, 128, 256) do {
    val r    = Array.tabulate[Byte](n)(i => (i & 0xFF).toByte)
    val half = n / 2
    val v    = new Array[Byte](n)
    System.arraycopy(r, half, v, 0, half)
    System.arraycopy(r, 0, v, half, half)
    for algo <- allAlgos do for pol <- allPolicies do
      assertArrayEquals(v, inplaceRoundtrip(algo, r, v, pol, 2), s"$algo/$pol inplace same-size swap n=$n")
  }
}

def testInplaceVersionOneByteMin(): Unit = {
  val r     = Array.tabulate[Byte](64)(i => i.toByte)
  val vCopy = Array(r(32))
  val vAdd  = Array[Byte](0xAB.toByte)
  for algo <- allAlgos do for pol <- allPolicies do {
    assertArrayEquals(vCopy, inplaceRoundtrip(algo, r, vCopy, pol, 2), s"$algo/$pol inplace v=1 byte (copy)")
    assertArrayEquals(vAdd,  inplaceRoundtrip(algo, r, vAdd,  pol, 2), s"$algo/$pol inplace v=1 byte (add)")
  }
}

def testSeedLengthBoundaries(): Unit = {
  val r = b("ABCDEFGHIJKLMNOP")
  val v = b("QWIJKLMNOBCDEFGHZDEFGHIJKL")
  for p <- Array(1, 2, r.length, r.length + 1) do
    for algo <- allAlgos do
      assertArrayEquals(v, applyDelta(r, diff(algo, r, v, opts(p))), s"$algo p=$p")
  val vShort = b("QW")
  val vLong  = b("QWIJKLMNOBCDEFGHZDEFGHIJKLMNOPQRSTUVWXYZ")
  for algo <- allAlgos do {
    assertArrayEquals(vShort, applyDelta(r, diff(algo, r, vShort, opts(r.length + 1))), s"$algo p>|r| short ver")
    assertArrayEquals(vLong,  applyDelta(r, diff(algo, r, vLong,  opts(r.length + 1))), s"$algo p>|r| long ver")
  }
}

// ── main ──────────────────────────────────────────────────────────────────────

@main def TestDelta(): Unit = {

  println("\n=== Standard differencing ===")
  check("paper example")                 { testPaperExample() }
  check("identical")                     { testIdentical() }
  check("completely different")          { testCompletelyDifferent() }
  check("empty version")                 { testEmptyVersion() }
  check("empty reference")               { testEmptyReference() }
  check("binary roundtrip")              { testBinaryRoundtrip() }
  check("binary encoding roundtrip")     { testBinaryEncodingRoundtrip() }
  check("binary encoding inplace flag")  { testBinaryEncodingInplaceFlag() }
  check("large copy roundtrip")          { testLargeCopyRoundtrip() }
  check("large add roundtrip")           { testLargeAddRoundtrip() }
  check("backward extension")            { testBackwardExtension() }
  check("transposition")                 { testTransposition() }
  check("scattered modifications")       { testScatteredModifications() }

  println("\n=== In-place basics ===")
  check("inplace paper example")         { testInplacePaperExample() }
  check("inplace binary roundtrip")      { testInplaceBinaryRoundtrip() }
  check("inplace simple transposition")  { testInplaceSimpleTransposition() }
  check("inplace version larger")        { testInplaceVersionLarger() }
  check("inplace version smaller")       { testInplaceVersionSmaller() }
  check("inplace identical")             { testInplaceIdentical() }
  check("inplace empty version")         { testInplaceEmptyVersion() }
  check("inplace scattered")             { testInplaceScattered() }
  check("standard not detected as inplace") { testStandardNotDetectedAsInplace() }
  check("inplace detected")              { testInplaceDetected() }

  println("\n=== In-place variable-length blocks ===")
  check("varlen permutation")            { testInplaceVarlenPermutation() }
  check("varlen reverse")                { testInplaceVarlenReverse() }
  check("varlen junk")                   { testInplaceVarlenJunk() }
  check("varlen drop+dup")               { testInplaceVarlenDropDup() }
  check("varlen double-sized")           { testInplaceVarlenDoubleSized() }
  check("varlen subset")                 { testInplaceVarlenSubset() }
  check("varlen half-block scramble")    { testInplaceVarlenHalfBlockScramble() }
  check("varlen random trials")          { testInplaceVarlenRandomTrials() }

  println("\n=== Cycle policy ===")
  check("localmin picks smallest")       { testLocalminPicksSmallest() }

  println("\n=== Checkpointing ===")
  check("correcting tiny table (q=7)")   { testCorrectingCheckpointingTinyTable() }
  check("correcting various table sizes"){ testCorrectingCheckpointingVariousSizes() }

  println("\n=== CRC-64/XZ ===")
  check("CRC64 empty input")             { testCrc64Empty() }
  check("CRC64 check value 123456789")   { testCrc64CheckValue() }

  println("\n=== Primality ===")
  check("next prime is prime")           { testNextPrimeIsPrime() }

  println("\n=== Inplace subcommand ===")
  check("inplace subcommand roundtrip")  { testInplaceSubcommandRoundtrip() }
  check("inplace subcommand idempotent") { testInplaceSubcommandIdempotent() }
  check("inplace subcommand equiv direct") { testInplaceSubcommandEquivDirect() }

  println("\n=== Splay tree ===")
  check("splay roundtrip")               { testSplayRoundtrip() }

  println("\n=== Edge cases and boundaries ===")
  check("single byte ref/ver")               { testSingleByte() }
  check("boundary byte mutations")           { testBoundaryByteMutations() }
  check("ref shorter than seed length")      { testRefShorterThanSeed() }
  check("size sweep (ver and ref sizes)")    { testSizeSweep() }
  check("encoding: version-size boundaries") { testEncodingVersionSizeBoundaries() }
  check("encoding: field boundaries")        { testEncodingCommandFieldBoundaries() }
  check("inplace: |V|=|R|+1 tight")         { testInplaceVersionOneLargerTight() }
  check("inplace: |V|=|R|-1 tight")         { testInplaceVersionOneSmallerTight() }
  check("inplace: |V|=|R| same-size swap")  { testInplaceVersionSameSizeTight() }
  check("inplace: version is 1 byte")       { testInplaceVersionOneByteMin() }
  check("seed length at boundaries")         { testSeedLengthBoundaries() }

  println("\n========================================")
  System.out.printf("Results: %d passed, %d failed (of %d)%n", pass, fail, tests)
  println("========================================")
  if fail > 0 then sys.exit(1)
}
