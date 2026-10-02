package delta;

import java.math.BigInteger;

import static delta.Types.*;

/**
 * Karp-Rabin fingerprints over the Mersenne prime 2^61 - 1 (Section 2.1.3),
 * primes for sizing hash tables, and CRC-64/XZ.
 *
 * A fingerprint is always in [0, HASH_MOD), so it is non-negative as a long.
 */
public final class Hash {
    private Hash() {}

    /**
     * Reduces the unsigned 128-bit value hi:lo modulo 2^61 - 1.
     * Requires hi &lt; 2^58, which holds for a product of two residues.
     */
    public static long modMersenne(long hi, long lo) {
        // 2^61 = 1 (mod 2^61 - 1), so the value is congruent to the sum of
        // its high part (bits 61 and up) and its low 61 bits.
        long x = ((hi << 3) | (lo >>> 61)) + (lo & HASH_MOD);
        if (x >= HASH_MOD) x -= HASH_MOD;
        x = (x >>> 61) + (x & HASH_MOD);
        if (x >= HASH_MOD) x -= HASH_MOD;
        return x;
    }

    /** Returns a * b mod 2^61 - 1, for residues a and b. */
    public static long mulmod(long a, long b) {
        return modMersenne(Math.multiplyHigh(a, b), a * b);
    }

    /** Returns (h * HASH_BASE + b) mod 2^61 - 1, for a residue h and a byte b in [0, 255]. */
    private static long append(long h, int b) {
        long hi = Math.multiplyHigh(h, HASH_BASE);
        long lo = h * HASH_BASE;
        long sum = lo + b;
        if (Long.compareUnsigned(sum, lo) < 0) hi++;
        return modMersenne(hi, sum);
    }

    /** Returns the fingerprint of the p bytes at data[offset] (Eq. 1). */
    public static long fingerprint(byte[] data, int offset, int p) {
        long h = 0;
        for (int i = 0; i < p; i++) {
            h = append(h, data[offset + i] & 0xFF);
        }
        return h;
    }

    /** Returns HASH_BASE^(p-1) mod 2^61 - 1, the weight of a window's first byte. */
    public static long precomputeBp(int p) {
        long result = 1;
        long base = HASH_BASE;
        for (int exp = p - 1; exp > 0; exp >>= 1) {
            if ((exp & 1) == 1) result = mulmod(result, base);
            base = mulmod(base, base);
        }
        return result;
    }

    /** The fingerprint of a p-byte window that moves over one array (Eq. 2). */
    public static final class RollingHash {
        private final byte[] data;
        private final int p;
        private final long bp; // HASH_BASE^(p-1): the weight of the window's first byte
        private int pos;       // where the window starts
        private long value;

        /** Places the window at data[offset].  Requires offset + p &lt;= data.length. */
        public RollingHash(byte[] data, int offset, int p) {
            this.data = data;
            this.p = p;
            this.bp = precomputeBp(p);
            this.pos = offset;
            this.value = fingerprint(data, offset, p);
        }

        /** Returns the fingerprint of the window. */
        public long value() { return value; }

        /**
         * Moves the window one byte to the right, given the byte that leaves
         * and the byte that enters, each in [0, 255].
         */
        public void roll(int oldByte, int newByte) {
            long sub = mulmod(oldByte, bp);
            long rest = value >= sub ? value - sub : HASH_MOD - (sub - value);
            value = append(rest, newByte);
            pos++;
        }

        /**
         * Moves the window to data[target] and returns its fingerprint.
         * A move of one byte to the right costs O(1); any other move costs O(p).
         */
        long seek(int target) {
            if (target == pos + 1) {
                roll(data[pos] & 0xFF, data[pos + p] & 0xFF);
            } else if (target != pos) {
                value = fingerprint(data, target, p);
                pos = target;
            }
            return value;
        }
    }

    /**
     * These witnesses make Miller-Rabin deterministic for every n below
     * 3.18e23 (Sorenson and Webster, Math. Comp. 86(304), 2017), which
     * covers every long.
     */
    private static final long[] MR_WITNESSES = {
        2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37
    };

    /** Reports whether n is prime, by the Miller-Rabin test. */
    public static boolean isPrime(long n) {
        if (n < 2) return false;
        if (n < 4) return true;
        if (n % 2 == 0) return false;

        // n - 1 = 2^r * d with d odd.  n passes for witness a if a^d = 1 or
        // a^(2^j * d) = -1 (mod n) for some j < r.
        BigInteger bn = BigInteger.valueOf(n);
        BigInteger nm1 = bn.subtract(BigInteger.ONE);
        int r = nm1.getLowestSetBit();
        BigInteger d = nm1.shiftRight(r);

        for (long a : MR_WITNESSES) {
            if (a >= n) break;
            BigInteger x = BigInteger.valueOf(a).modPow(d, bn);
            if (x.equals(BigInteger.ONE)) continue;
            int j = 0;
            while (!x.equals(nm1)) {
                if (++j == r) return false;
                x = x.multiply(x).mod(bn);
            }
        }
        return true;
    }

    /** Returns the smallest prime that is at least n. */
    public static long nextPrime(long n) {
        if (n <= 2) return 2;
        if (n % 2 == 0) n++;
        while (!isPrime(n)) n += 2;
        return n;
    }

    /**
     * CRC-64/XZ: the ECMA-182 polynomial, reflected, with initial value and
     * final XOR of all ones.  The check value for "123456789" is
     * 0x995DC9BBDF1939FA.
     */
    public static final class Crc64 {
        private Crc64() {}

        private static final long POLY = 0xC96C5795D7870F42L;
        private static final long[] TABLE = new long[256];

        static {
            for (int i = 0; i < 256; i++) {
                long c = i;
                for (int j = 0; j < 8; j++) {
                    c = (c & 1) != 0 ? (c >>> 1) ^ POLY : c >>> 1;
                }
                TABLE[i] = c;
            }
        }

        /** Returns the CRC of data as 8 bytes, most significant first. */
        public static byte[] hash8(byte[] data) {
            long crc = ~0L;
            for (byte b : data) {
                crc = TABLE[(int) ((crc ^ b) & 0xFF)] ^ (crc >>> 8);
            }
            crc = ~crc;
            byte[] out = new byte[DELTA_CRC_SIZE];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) (crc >>> (56 - 8 * i));
            }
            return out;
        }
    }
}
