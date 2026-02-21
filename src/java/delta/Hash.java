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

    // ── SHAKE128 (FIPS 202 XOF, 16-byte output) ──────────────────────

    /** Streaming SHAKE128 context.  Call update() any number of times, then finish(). */
    public static final class Shake128 {
        // 24 round constants for the iota step (FIPS 202 Table 5)
        private static final long[] RC = {
            0x0000000000000001L, 0x0000000000008082L,
            0x800000000000808AL, 0x8000000080008000L,
            0x000000000000808BL, 0x0000000080000001L,
            0x8000000080008081L, 0x8000000000008009L,
            0x000000000000008AL, 0x0000000000000088L,
            0x0000000080008009L, 0x000000008000000AL,
            0x000000008000808BL, 0x800000000000008BL,
            0x8000000000008089L, 0x8000000000008003L,
            0x8000000000008002L, 0x8000000000000080L,
            0x000000000000800AL, 0x800000008000000AL,
            0x8000000080008081L, 0x8000000000008080L,
            0x0000000080000001L, 0x8000000080008008L,
        };

        // Rotation offsets for rho step (FIPS 202 Table 2), indexed state[x + 5*y]
        private static final int[] RHO = {
             0,  1, 62, 28, 27,
            36, 44,  6, 55, 20,
             3, 10, 43, 25, 39,
            41, 45, 15, 21,  8,
            18,  2, 61, 56, 14,
        };

        private static final int RATE = 168; // SHAKE128 rate in bytes

        private final long[] state = new long[25];
        private final byte[] buf = new byte[RATE];
        private int bufLen = 0;

        public Shake128() {}

        public void update(byte[] data) { update(data, 0, data.length); }

        public void update(byte[] data, int off, int len) {
            for (int i = 0; i < len; i++) {
                buf[bufLen++] = data[off + i];
                if (bufLen == RATE) {
                    xorIntoState(state, buf, RATE);
                    keccakF1600(state);
                    bufLen = 0;
                }
            }
        }

        /** Finalize and return 16 bytes of output. */
        public byte[] finish() {
            // Pad remaining buffer (FIPS 202 Sec. 6.2)
            java.util.Arrays.fill(buf, bufLen, RATE, (byte) 0);
            buf[bufLen]      ^= 0x1F;
            buf[RATE - 1]    ^= (byte) 0x80;
            xorIntoState(state, buf, RATE);
            keccakF1600(state);
            // Squeeze 16 bytes (little-endian lanes)
            byte[] out = new byte[16];
            for (int i = 0; i < 16; i++)
                out[i] = (byte) (state[i / 8] >>> (8 * (i % 8)));
            return out;
        }

        /** Convenience: hash entire byte array, return 16 bytes. */
        public static byte[] hash16(byte[] data) {
            Shake128 ctx = new Shake128();
            ctx.update(data);
            return ctx.finish();
        }

        private static long rotl64(long x, int n) {
            return n == 0 ? x : (x << n) | (x >>> (64 - n));
        }

        private static void xorIntoState(long[] state, byte[] data, int len) {
            for (int i = 0; i < len; i++)
                state[i / 8] ^= (long) (data[i] & 0xFF) << (8 * (i % 8));
        }

        private static void keccakF1600(long[] A) {
            long[] C = new long[5], D = new long[5], B = new long[25];
            for (int rnd = 0; rnd < 24; rnd++) {
                // theta
                for (int x = 0; x < 5; x++)
                    C[x] = A[x] ^ A[x+5] ^ A[x+10] ^ A[x+15] ^ A[x+20];
                for (int x = 0; x < 5; x++)
                    D[x] = C[(x+4)%5] ^ rotl64(C[(x+1)%5], 1);
                for (int x = 0; x < 25; x++)
                    A[x] ^= D[x % 5];
                // rho + pi
                for (int y = 0; y < 5; y++)
                    for (int x = 0; x < 5; x++) {
                        int src = (x + 3*y) % 5 + 5*x;
                        B[x + 5*y] = rotl64(A[src], RHO[src]);
                    }
                // chi
                for (int y = 0; y < 5; y++)
                    for (int x = 0; x < 5; x++)
                        A[x + 5*y] = B[x + 5*y]
                                   ^ ((~B[(x+1)%5 + 5*y]) & B[(x+2)%5 + 5*y]);
                // iota
                A[0] ^= RC[rnd];
            }
        }
    }
}
