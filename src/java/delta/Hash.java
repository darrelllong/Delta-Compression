package delta;

import java.math.BigInteger;
import java.util.concurrent.ThreadLocalRandom;

import static delta.Types.*;

/**
 * Karp-Rabin rolling hash (Karp &amp; Rabin 1987; Section 2.1.3).
 *
 * Polynomial fingerprints over the Mersenne prime 2^61-1.
 */
public final class Hash {
    private Hash() {}

    /** Reduce 128-bit value (hi:lo) modulo 2^61-1. */
    public static long modMersenne(long hi, long lo) {
        long P = HASH_MOD;
        long upper = (hi << 3) | (lo >>> 61);
        long lower = lo & P;
        long r = upper + lower;
        if (Long.compareUnsigned(r, P) >= 0) r -= P;
        // Second reduction
        long upper2 = r >>> 61;
        long lower2 = r & P;
        long r2 = upper2 + lower2;
        if (Long.compareUnsigned(r2, P) >= 0) r2 -= P;
        return r2;
    }

    /** (a * b) mod (2^61-1) using 128-bit intermediate. */
    public static long mulmod(long a, long b) {
        long hi = Math.multiplyHigh(a, b);
        long lo = a * b;
        return modMersenne(hi, lo);
    }

    /** Karp-Rabin fingerprint of data[offset..offset+p] (Eq. 1, Section 2.1.3). */
    public static long fingerprint(byte[] data, int offset, int p) {
        long h = 0;
        for (int i = 0; i < p; i++) {
            int b = data[offset + i] & 0xFF;
            long hi = Math.multiplyHigh(h, HASH_BASE);
            long lo = h * HASH_BASE;
            long newLo = lo + b;
            long newHi = hi;
            if (Long.compareUnsigned(newLo, lo) < 0) newHi++;
            h = modMersenne(newHi, newLo);
        }
        return h;
    }

    /** Precompute HASH_BASE^{p-1} mod HASH_MOD. */
    public static long precomputeBp(int p) {
        if (p == 0) return 1;
        long result = 1;
        long base = HASH_BASE;
        int exp = p - 1;
        while (exp > 0) {
            if ((exp & 1) == 1) result = mulmod(result, base);
            base = mulmod(base, base);
            exp >>= 1;
        }
        return result;
    }

    // ── Rolling hash ─────────────────────────────────────────────────

    /** Rolling hash for O(1) incremental fingerprint updates (Eq. 2). */
    public static final class RollingHash {
        private long value;
        private final long bp; // HASH_BASE^{p-1} mod HASH_MOD

        public RollingHash(byte[] data, int offset, int p) {
            this.bp = precomputeBp(p);
            this.value = fingerprint(data, offset, p);
        }

        public long value() { return value; }

        /** Slide window: remove oldByte from left, add newByte to right. */
        public void roll(int oldByte, int newByte) {
            long sub = mulmod(oldByte, bp);
            long v = Long.compareUnsigned(value, sub) >= 0
                ? (value - sub)
                : (HASH_MOD - (sub - value));
            long hi = Math.multiplyHigh(v, HASH_BASE);
            long lo = v * HASH_BASE;
            long newLo = lo + newByte;
            long newHi = hi;
            if (Long.compareUnsigned(newLo, lo) < 0) newHi++;
            value = modMersenne(newHi, newLo);
        }
    }

    // ── Primality testing ────────────────────────────────────────────

    /** Miller-Rabin probabilistic primality test with 100 random witnesses. */
    public static boolean isPrime(long n) {
        return isPrime(n, 100);
    }

    public static boolean isPrime(long n, int k) {
        if (n < 2) return false;
        if (n < 4) return true;
        if (n % 2 == 0) return false;

        BigInteger bn = BigInteger.valueOf(n);
        BigInteger nm1 = bn.subtract(BigInteger.ONE);
        int r = nm1.getLowestSetBit();
        BigInteger d = nm1.shiftRight(r);

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < k; i++) {
            long a = 2 + rng.nextLong(n - 3);
            BigInteger x = BigInteger.valueOf(a).modPow(d, bn);
            if (x.equals(BigInteger.ONE) || x.equals(nm1)) continue;
            boolean found = false;
            for (int j = 0; j < r - 1; j++) {
                x = x.modPow(BigInteger.TWO, bn);
                if (x.equals(nm1)) { found = true; break; }
            }
            if (!found) return false;
        }
        return true;
    }

    /** Smallest prime >= n. */
    public static long nextPrime(long n) {
        if (n <= 2) return 2;
        if (n % 2 == 0) n++;
        while (!isPrime(n)) n += 2;
        return n;
    }

    // ── CRC-64/XZ (ECMA-182 reflected) — 8-byte output ─────────────
    //
    // Reflected poly: 0xC96C5795D7870F42, Init = XorOut = 0xFFFFFFFFFFFFFFFF.
    // Check value: Crc64.hash8(b"123456789") = 0x995DC9BBDF1939FA big-endian.

    public static final class Crc64 {
        private static final long POLY = 0xC96C5795D7870F42L;
        private static final long[] TABLE;

        static {
            TABLE = new long[256];
            for (int i = 0; i < 256; i++) {
                long c = (long) i;
                for (int j = 0; j < 8; j++)
                    c = (c & 1L) != 0 ? (c >>> 1) ^ POLY : (c >>> 1);
                TABLE[i] = c;
            }
        }

        private Crc64() {}

        /** Compute CRC-64/XZ of data, return 8 bytes big-endian. */
        public static byte[] hash8(byte[] data) {
            long crc = 0xFFFFFFFFFFFFFFFFL;
            for (byte b : data)
                crc = TABLE[(int)((crc ^ b) & 0xFF)] ^ (crc >>> 8);
            crc ^= 0xFFFFFFFFFFFFFFFFL;
            byte[] out = new byte[8];
            for (int i = 0; i < 8; i++)
                out[i] = (byte) (crc >>> (56 - 8 * i));
            return out;
        }
    }
}
