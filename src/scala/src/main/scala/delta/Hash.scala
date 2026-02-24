package delta

// ── Karp-Rabin rolling hash (Karp & Rabin 1987; Section 2.1.3) ────────────

/** Reduce 128-bit value (hi:lo) modulo 2^61-1. */
def modMersenne(hi: Long, lo: Long): Long = {
  val p     = hashMod
  val upper = (hi << 3) | (lo >>> 61)
  val lower = lo & p
  var r     = upper + lower
  if r >= p then r -= p   // r < 2*p < 2^62, so always non-negative; plain >= suffices
  val upper2 = r >>> 61
  val lower2 = r & p
  var r2     = upper2 + lower2
  if r2 >= p then r2 -= p
  r2
}

/** (a * b) mod (2^61-1) using 128-bit intermediate. */
def mulmod(a: Long, b: Long): Long = {
  val hi = Math.multiplyHigh(a, b)
  val lo = a * b
  modMersenne(hi, lo)
}

/** Karp-Rabin fingerprint of data[offset..offset+p] (Eq. 1, Section 2.1.3). */
def fingerprint(data: Array[Byte], offset: Int, p: Int): Long = {
  var h = 0L
  var i = 0
  while i < p do {
    val b   = data(offset + i).toInt & 0xFF
    val hi  = Math.multiplyHigh(h, hashBase)
    val lo  = h * hashBase
    val nl  = lo + b
    val nh  = if lo < 0 && nl >= 0 then hi + 1L else hi  // carry: lo near 2^64, wraps positive
    h = modMersenne(nh, nl)
    i += 1
  }
  h
}

/** Precompute hashBase^{p-1} mod hashMod for rolling hash updates. */
def precomputeBp(p: Int): Long = {
  if p == 0 then return 1L
  var result = 1L
  var base   = hashBase
  var exp    = p - 1
  while exp > 0 do {
    if (exp & 1) == 1 then result = mulmod(result, base)
    base = mulmod(base, base)
    exp >>= 1
  }
  result
}

// ── Rolling hash ───────────────────────────────────────────────────────────

/** Rolling hash for O(1) incremental fingerprint updates (Eq. 2). */
class RollingHash(data: Array[Byte], offset: Int, p: Int) {
  var value: Long = fingerprint(data, offset, p)
  private val bp: Long = precomputeBp(p)

  /** Slide window one byte right: remove oldByte from left, add newByte to right. */
  def roll(oldByte: Int, newByte: Int): Unit = {
    val sub = mulmod(oldByte.toLong, bp)
    val v   = if value >= sub then value - sub   // both in [0, p); plain >= suffices
              else hashMod - (sub - value)
    val hi  = Math.multiplyHigh(v, hashBase)
    val lo  = v * hashBase
    val nl  = lo + newByte
    val nh  = if lo < 0 && nl >= 0 then hi + 1L else hi  // carry: same as fingerprint
    value = modMersenne(nh, nl)
  }
}

// ── Primality testing ──────────────────────────────────────────────────────

/**
 * Fixed witnesses for deterministic Miller-Rabin.
 * Sufficient for all n < 3,317,044,064,679,887,385,961,981 (> 2^81).
 * Jaeschke, Math. Comp. 61(204), 1993.
 */
private val mrWitnesses = Array[Long](2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37)

def isPrime(n: Long): Boolean = {
  if n < 2L then return false
  if n < 4L then return true
  if n % 2L == 0L then return false

  val bn  = BigInt(n)
  val nm1 = bn - 1
  var r   = 0; while !nm1.testBit(r) do r += 1  // lowest set bit = trailing zeros
  val d   = nm1 >> r

  for a <- mrWitnesses do {
    if a >= n then return true
    var x = BigInt(a).modPow(d, bn)
    if x != 1 && x != nm1 then {
      var found = false
      var j     = 0
      while !found && j < r - 1 do {
        x = x.modPow(BigInt(2), bn)
        if x == nm1 then found = true
        j += 1
      }
      if !found then return false
    }
  }
  true
}

/** Smallest prime >= n. */
def nextPrime(n: Long): Long = {
  if n <= 2L then return 2L
  var m = if n % 2L == 0L then n + 1L else n
  while !isPrime(m) do m += 2L
  m
}

// ── CRC-64/XZ (ECMA-182 reflected) — 8-byte big-endian output ─────────────
//
// Reflected poly: 0xC96C5795D7870F42, Init = XorOut = 0xFFFFFFFFFFFFFFFF.
// Check value: crc64(b"123456789") = 0x995DC9BBDF1939FA.

object Crc64 {
  // 0xC96C5795D7870F42 in signed Long two's complement
  private val poly: Long = -0x3693A86A2878F0BEL

  private val table: Array[Long] = {
    val t = new Array[Long](256)
    for i <- 0 until 256 do {
      var c = i.toLong
      for _ <- 0 until 8 do
        c = if (c & 1L) != 0L then (c >>> 1) ^ poly else c >>> 1
      t(i) = c
    }
    t
  }

  /** Compute CRC-64/XZ of data; returns 8 bytes big-endian. */
  def hash8(data: Array[Byte]): Array[Byte] = {
    var crc = -1L // 0xFFFFFFFFFFFFFFFF
    for b <- data do
      crc = table(((crc ^ b.toLong) & 0xFF).toInt) ^ (crc >>> 8)
    crc = ~crc
    val out = new Array[Byte](8)
    for i <- 0 until 8 do out(i) = (crc >>> (56 - 8 * i)).toByte
    out
  }
}
