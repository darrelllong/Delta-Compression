package delta

import java.math.BigInteger

// ── Karp-Rabin rolling hash (Karp & Rabin 1987; Section 2.1.3) ────────────

/** Reduce 128-bit value (hi:lo) modulo 2^61-1. */
fun modMersenne(hi: Long, lo: Long): Long {
    val p = HASH_MOD
    val upper = (hi shl 3) or (lo ushr 61)
    val lower = lo and p
    var r = upper + lower
    if (r.toULong() >= p.toULong()) r -= p
    val upper2 = r ushr 61
    val lower2 = r and p
    var r2 = upper2 + lower2
    if (r2.toULong() >= p.toULong()) r2 -= p
    return r2
}

/** (a * b) mod (2^61-1) using 128-bit intermediate. */
fun mulmod(a: Long, b: Long): Long {
    val hi = Math.multiplyHigh(a, b)
    val lo = a * b
    return modMersenne(hi, lo)
}

/** Karp-Rabin fingerprint of data[offset..offset+p] (Eq. 1, Section 2.1.3). */
fun fingerprint(data: ByteArray, offset: Int, p: Int): Long {
    var h = 0L
    for (i in 0 until p) {
        val b = data[offset + i].toInt() and 0xFF
        val hi = Math.multiplyHigh(h, HASH_BASE)
        val lo = h * HASH_BASE
        val newLo = lo + b
        val newHi = if (newLo.toULong() < lo.toULong()) hi + 1L else hi
        h = modMersenne(newHi, newLo)
    }
    return h
}

/** Precompute HASH_BASE^{p-1} mod HASH_MOD for rolling hash updates. */
fun precomputeBp(p: Int): Long {
    if (p == 0) return 1L
    var result = 1L
    var base = HASH_BASE
    var exp = p - 1
    while (exp > 0) {
        if (exp and 1 == 1) result = mulmod(result, base)
        base = mulmod(base, base)
        exp = exp shr 1
    }
    return result
}

// ── Rolling hash ───────────────────────────────────────────────────────────

/** Rolling hash for O(1) incremental fingerprint updates (Eq. 2). */
class RollingHash(data: ByteArray, offset: Int, p: Int) {
    var value: Long = fingerprint(data, offset, p)
        private set
    private val bp: Long = precomputeBp(p)

    /** Slide window one byte right: remove oldByte from left, add newByte to right. */
    fun roll(oldByte: Int, newByte: Int) {
        val sub = mulmod(oldByte.toLong(), bp)
        val v = if (value.toULong() >= sub.toULong()) value - sub
                else HASH_MOD - (sub - value)
        val hi = Math.multiplyHigh(v, HASH_BASE)
        val lo = v * HASH_BASE
        val newLo = lo + newByte
        val newHi = if (newLo.toULong() < lo.toULong()) hi + 1L else hi
        value = modMersenne(newHi, newLo)
    }
}

// ── Primality testing ──────────────────────────────────────────────────────

/**
 * Fixed witnesses for deterministic Miller-Rabin.
 * Sufficient for all n < 3,317,044,064,679,887,385,961,981 (> 2^81).
 * Jaeschke, Math. Comp. 61(204), 1993.
 */
private val MR_WITNESSES = longArrayOf(2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37)

fun isPrime(n: Long): Boolean {
    if (n < 2L) return false
    if (n < 4L) return true
    if (n % 2L == 0L) return false

    val bn  = BigInteger.valueOf(n)
    val nm1 = bn - BigInteger.ONE
    val r   = nm1.lowestSetBit
    val d   = nm1.shiftRight(r)

    for (a in MR_WITNESSES) {
        if (a >= n) break
        var x = BigInteger.valueOf(a).modPow(d, bn)
        if (x == BigInteger.ONE || x == nm1) continue
        var found = false
        for (j in 0 until r - 1) {
            x = x.modPow(BigInteger.TWO, bn)
            if (x == nm1) { found = true; break }
        }
        if (!found) return false
    }
    return true
}

/** Smallest prime >= n. */
fun nextPrime(n: Long): Long {
    if (n <= 2L) return 2L
    var m = if (n % 2L == 0L) n + 1L else n
    while (!isPrime(m)) m += 2L
    return m
}

// ── CRC-64/XZ (ECMA-182 reflected) — 8-byte big-endian output ─────────────
//
// Reflected poly: 0xC96C5795D7870F42, Init = XorOut = 0xFFFFFFFFFFFFFFFF.
// Check value: crc64(b"123456789") = 0x995DC9BBDF1939FA.

object Crc64 {
    // 0xC96C5795D7870F42 in signed Long two's complement
    private const val POLY: Long = -0x3693A86A2878F0BE

    private val table: LongArray = LongArray(256) { i ->
        var c = i.toLong()
        repeat(8) { c = if (c and 1L != 0L) (c ushr 1) xor POLY else c ushr 1 }
        c
    }

    /** Compute CRC-64/XZ of data; returns 8 bytes big-endian. */
    fun hash8(data: ByteArray): ByteArray {
        var crc = -1L  // 0xFFFFFFFFFFFFFFFF
        for (b in data)
            crc = table[((crc xor b.toLong()) and 0xFF).toInt()] xor (crc ushr 8)
        crc = crc.inv()
        return ByteArray(8) { i -> (crc ushr (56 - 8 * i)).toByte() }
    }
}
