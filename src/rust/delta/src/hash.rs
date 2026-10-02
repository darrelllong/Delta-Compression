//! Karp-Rabin fingerprints over the Mersenne prime 2^61 - 1 (Section 2.1.3),
//! the CRC-64/XZ checksum of the delta header, and the primality test that
//! sizes hash tables.

use crate::types::{DELTA_CRC_SIZE, HASH_BASE, HASH_MOD};

/// Reduces `x` modulo 2^61 - 1 without dividing.
///
/// Since 2^61 = 1 (mod 2^61 - 1), the high bits fold onto the low ones.
/// One fold of a u128 leaves up to 68 bits, so it is done twice.
#[inline]
pub fn mod_mersenne(x: u128) -> u64 {
    let m = HASH_MOD as u128;
    let mut r = (x >> 61) + (x & m);
    if r >= m {
        r -= m;
    }
    r = (r >> 61) + (r & m);
    if r >= m {
        r -= m;
    }
    r as u64
}

/// Returns the fingerprint of the seed `data[offset..offset + p]` (Eq. 1):
/// its bytes taken as the digits, most significant first, of a number in
/// base [`HASH_BASE`], modulo [`HASH_MOD`].
pub fn fingerprint(data: &[u8], offset: usize, p: usize) -> u64 {
    data[offset..offset + p].iter().fold(0, |h, &byte| {
        mod_mersenne(h as u128 * HASH_BASE as u128 + byte as u128)
    })
}

/// Returns `HASH_BASE^(p-1) mod HASH_MOD`, the weight of a seed's first byte.
pub fn precompute_bp(p: usize) -> u64 {
    if p == 0 {
        return 1;
    }
    let mut result: u64 = 1;
    let mut base = HASH_BASE;
    let mut exp = p - 1;
    while exp > 0 {
        if exp & 1 == 1 {
            result = mod_mersenne(result as u128 * base as u128);
        }
        base = mod_mersenne(base as u128 * base as u128);
        exp >>= 1;
    }
    result
}

/// Maps a fingerprint to a slot in a table of `table_size` slots.
#[inline]
pub fn fp_to_index(fp: u64, table_size: usize) -> usize {
    (fp % table_size as u64) as usize
}

/// The fingerprint of a seed-length window that can slide one byte at a
/// time in constant time.
pub struct RollingHash {
    value: u64,
    bp: u64, // HASH_BASE^(p-1) mod HASH_MOD
}

impl RollingHash {
    /// Returns the hash of the window `data[offset..offset + p]`.
    pub fn new(data: &[u8], offset: usize, p: usize) -> Self {
        RollingHash {
            value: fingerprint(data, offset, p),
            bp: precompute_bp(p),
        }
    }

    /// The fingerprint of the current window.
    #[inline]
    pub fn value(&self) -> u64 {
        self.value
    }

    /// Slides the window one byte to the right (Eq. 2): `old_byte` is the
    /// byte that leaves on the left, `new_byte` the one that enters.
    #[inline]
    pub fn roll(&mut self, old_byte: u8, new_byte: u8) {
        let sub = mod_mersenne(old_byte as u128 * self.bp as u128);
        let v = if self.value >= sub {
            self.value - sub
        } else {
            HASH_MOD - (sub - self.value)
        };
        self.value = mod_mersenne(v as u128 * HASH_BASE as u128 + new_byte as u128);
    }
}

/// Fingerprints the seeds of one string for a scan that mostly moves forward
/// one byte at a time and now and then jumps past a match.
pub(crate) struct SeedScanner {
    hash: RollingHash,
    /// Start of the window `hash` covers, or `NOWHERE` before the first use.
    pos: usize,
    p: usize,
}

/// Neither a seed offset nor one less than a seed offset.
const NOWHERE: usize = usize::MAX - 1;

impl SeedScanner {
    pub(crate) fn new(p: usize) -> Self {
        SeedScanner {
            hash: RollingHash {
                value: 0,
                bp: precompute_bp(p),
            },
            pos: NOWHERE,
            p,
        }
    }

    /// Returns the fingerprint of `data[i..i + p]`.  Every call must pass
    /// the same `data`.
    #[inline]
    pub(crate) fn at(&mut self, data: &[u8], i: usize) -> u64 {
        if i == self.pos + 1 {
            self.hash.roll(data[i - 1], data[i + self.p - 1]);
        } else if i != self.pos {
            self.hash.value = fingerprint(data, i, self.p);
        }
        self.pos = i;
        self.hash.value
    }
}

const fn make_crc64_table() -> [u64; 256] {
    const POLY: u64 = 0xC96C5795D7870F42; // ECMA-182, reflected
    let mut table = [0u64; 256];
    let mut i = 0;
    while i < 256 {
        let mut crc = i as u64;
        let mut bit = 0;
        while bit < 8 {
            crc = if crc & 1 != 0 {
                (crc >> 1) ^ POLY
            } else {
                crc >> 1
            };
            bit += 1;
        }
        table[i] = crc;
        i += 1;
    }
    table
}

static CRC64_TABLE: [u64; 256] = make_crc64_table();

/// Returns the CRC-64/XZ of `data`, most significant byte first.
///
/// The check value, for `b"123456789"`, is `0x995DC9BBDF1939FA`.
pub fn crc64_xz(data: &[u8]) -> [u8; DELTA_CRC_SIZE] {
    let crc = data.iter().fold(u64::MAX, |crc, &byte| {
        CRC64_TABLE[((crc ^ byte as u64) & 0xFF) as usize] ^ (crc >> 8)
    });
    (crc ^ u64::MAX).to_be_bytes()
}

/// Returns `base^exp mod modulus`.
fn power_mod(base: u64, mut exp: u64, modulus: u64) -> u64 {
    if modulus == 1 {
        return 0;
    }
    let m = modulus as u128;
    let mut result: u128 = 1;
    let mut b = base as u128 % m;
    while exp > 0 {
        if exp & 1 == 1 {
            result = result * b % m;
        }
        exp >>= 1;
        b = b * b % m;
    }
    result as u64
}

/// Writes `n` as `d * 2^r` with `d` odd and returns `(d, r)`.  `n` is not 0.
fn get_d_r(n: u64) -> (u64, u32) {
    let r = n.trailing_zeros();
    (n >> r, r)
}

/// Reports whether `a` is a Miller-Rabin witness that `n` is composite.
/// A false result leaves the question open.
fn witness(a: u64, n: u64) -> bool {
    let (d, r) = get_d_r(n - 1);
    let mut x = power_mod(a, d, n);
    for _ in 0..r {
        let y = power_mod(x, 2, n);
        if y == 1 && x != 1 && x != n - 1 {
            return true;
        }
        x = y;
    }
    x != 1
}

/// With these witnesses Miller-Rabin errs for no n below
/// 318,665,857,834,031,151,167,461, which exceeds 2^64 (Sorenson and
/// Webster, Math. Comp. 86(304), 2017).
const WITNESSES: &[u64] = &[2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37];

/// Reports whether `n` is prime.  The test is deterministic.
pub fn is_prime(n: usize) -> bool {
    let n = n as u64;
    if n < 2 || (n != 2 && n & 1 == 0) {
        return false;
    }
    !WITNESSES
        .iter()
        .take_while(|&&a| a < n)
        .any(|&a| witness(a, n))
}

/// Returns the smallest prime that is at least `n`.
pub fn next_prime(n: usize) -> usize {
    if n <= 2 {
        return 2;
    }
    let mut candidate = n | 1;
    while !is_prime(candidate) {
        candidate += 2;
    }
    candidate
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_mod_mersenne_basic() {
        assert_eq!(mod_mersenne(0), 0);
        assert_eq!(mod_mersenne(HASH_MOD as u128), 0);
        assert_eq!(mod_mersenne(HASH_MOD as u128 + 1), 1);
        assert_eq!(mod_mersenne(42), 42);
    }

    #[test]
    fn test_fingerprint_deterministic() {
        let data = b"ABCDEFGHIJKLMNOP";
        let fp = fingerprint(data, 0, 16);
        assert_ne!(fp, 0);
        assert_eq!(fp, fingerprint(data, 0, 16));
    }

    #[test]
    fn test_rolling_hash_full_scan() {
        let data = b"The quick brown fox jumps over the lazy dog.";
        let p = 8;
        let mut rh = RollingHash::new(data, 0, p);

        for i in 1..=(data.len() - p) {
            rh.roll(data[i - 1], data[i + p - 1]);
            assert_eq!(
                rh.value(),
                fingerprint(data, i, p),
                "mismatch at offset {}",
                i
            );
        }
    }

    #[test]
    fn test_seed_scanner_rolls_and_jumps() {
        let data = b"The quick brown fox jumps over the lazy dog.";
        let p = 8;
        let mut scan = SeedScanner::new(p);
        for i in [0, 0, 1, 2, 3, 20, 21, 21, 5, 6, data.len() - p] {
            assert_eq!(scan.at(data, i), fingerprint(data, i, p), "offset {}", i);
        }
    }

    #[test]
    fn test_get_d_r() {
        assert_eq!(get_d_r(8), (1, 3));
        assert_eq!(get_d_r(15), (15, 0));
        let (d, r) = get_d_r(12);
        assert_eq!(d, 3);
        assert_eq!(r, 2);
        assert_eq!(d * (1u64 << r), 12);
    }

    #[test]
    fn test_witness_composite() {
        assert!(witness(2, 9));
        assert!(witness(2, 15));
    }

    #[test]
    fn test_witness_prime() {
        for a in 2..12 {
            assert!(!witness(a, 13), "a={} should not be a witness for 13", a);
        }
    }

    #[test]
    fn test_known_primes() {
        let primes = [
            2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53, 59, 61, 67, 71, 73, 79, 83,
            89, 97, 101, 103, 107, 109, 113, 127, 131, 137, 139, 149, 151, 157, 163, 167, 173, 179,
            181, 191, 193, 197, 199, 211, 223, 227, 229,
        ];
        for &p in &primes {
            assert!(is_prime(p), "{} should be prime", p);
        }
    }

    #[test]
    fn test_known_composites() {
        let composites = [
            0, 1, 4, 6, 8, 9, 10, 12, 14, 15, 16, 18, 20, 21, 25, 27, 33, 35, 49, 51, 55, 63, 65,
            77, 91, 100, 121, 143, 169, 221, 1000, 1000000,
        ];
        for &c in &composites {
            assert!(!is_prime(c), "{} should be composite", c);
        }
    }

    #[test]
    fn test_large_primes() {
        assert!(is_prime(1048573)); // largest prime < 2^20
        assert!(is_prime(2097143)); // largest prime < 2^21
        assert!(is_prime(104729)); // 10000th prime
    }

    #[test]
    fn test_carmichael_numbers() {
        // These pass the Fermat test for every base coprime to them.
        let carmichaels = [561, 1105, 1729, 2465, 2821, 6601, 8911];
        for &c in &carmichaels {
            assert!(!is_prime(c), "Carmichael number {} should be composite", c);
        }
    }

    #[test]
    fn test_next_prime_composite() {
        assert_eq!(next_prime(8), 11);
        assert_eq!(next_prime(14), 17);
        assert_eq!(next_prime(100), 101);
        assert_eq!(next_prime(1000), 1009);
    }

    #[test]
    fn test_next_prime_small() {
        assert_eq!(next_prime(0), 2);
        assert_eq!(next_prime(1), 2);
        assert_eq!(next_prime(2), 2);
        assert_eq!(next_prime(3), 3);
    }

    #[test]
    fn test_next_prime_consecutive() {
        for n in 2..500 {
            let np = next_prime(n);
            assert!(np >= n);
            assert!(is_prime(np), "next_prime({}) = {} should be prime", n, np);
        }
    }

    #[test]
    fn test_crc64_empty() {
        assert_eq!(crc64_xz(b""), [0u8; 8]);
    }

    #[test]
    fn test_crc64_check_value() {
        let expected: [u8; 8] = [0x99, 0x5D, 0xC9, 0xBB, 0xDF, 0x19, 0x39, 0xFA];
        assert_eq!(crc64_xz(b"123456789"), expected);
    }
}
