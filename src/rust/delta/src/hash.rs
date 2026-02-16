//! Karp-Rabin rolling hash (Karp & Rabin 1987; Section 2.1.3).
//!
//! Polynomial fingerprints over the Mersenne prime 2^61-1.
//! Full 61-bit fingerprints are used for collision-free seed comparison;
//! `fp_to_index` maps them into the bounded hash table via F mod q.

use crate::types::{HASH_BASE, HASH_MOD};

/// Reduce a u128 value modulo the Mersenne prime 2^61-1.
///
/// Uses the Mersenne identity: for M = 2^61-1, x mod M = (x >> 61) + (x & M),
/// with a final correction if the result >= M. No division needed.
#[inline]
pub fn mod_mersenne(x: u128) -> u64 {
    let m = HASH_MOD as u128;
    let mut r = (x >> 61) + (x & m);
    if r >= m {
        r -= m;
    }
    // One more reduction in case the first wasn't enough (x >> 61 can be large)
    let mut r2 = (r >> 61) + (r & m);
    if r2 >= m {
        r2 -= m;
    }
    r2 as u64
}

/// Compute the Karp-Rabin fingerprint of data[offset..offset+p] (Eq. 1, Section 2.1.3).
///
/// F(X) = (x_0 * b^{p-1} + x_1 * b^{p-2} + ... + x_{p-1}) mod (2^61-1)
pub fn fingerprint(data: &[u8], offset: usize, p: usize) -> u64 {
    let mut h: u64 = 0;
    for i in 0..p {
        h = mod_mersenne(h as u128 * HASH_BASE as u128 + data[offset + i] as u128);
    }
    h
}

/// Precompute HASH_BASE^{p-1} mod HASH_MOD.
pub fn precompute_bp(p: usize) -> u64 {
    if p == 0 {
        return 1;
    }
    let mut result: u64 = 1;
    let mut base = HASH_BASE;
    let mut exp = p - 1;
    // Modular exponentiation by squaring
    while exp > 0 {
        if exp & 1 == 1 {
            result = mod_mersenne(result as u128 * base as u128);
        }
        base = mod_mersenne(base as u128 * base as u128);
        exp >>= 1;
    }
    result
}

/// Map a full fingerprint to a hash table index (F mod q, Section 2.1.3).
#[inline]
pub fn fp_to_index(fp: u64, table_size: usize) -> usize {
    (fp % table_size as u64) as usize
}

/// Rolling hash for O(1) incremental fingerprint updates.
///
/// After `new()`, call `roll()` to slide the window one byte to the right.
pub struct RollingHash {
    value: u64,
    bp: u64, // HASH_BASE^{p-1} mod HASH_MOD
}

impl RollingHash {
    /// Create a new RollingHash from data[offset..offset+p].
    pub fn new(data: &[u8], offset: usize, p: usize) -> Self {
        let bp = precompute_bp(p);
        let value = fingerprint(data, offset, p);
        RollingHash { value, bp }
    }

    /// Current fingerprint value.
    #[inline]
    pub fn value(&self) -> u64 {
        self.value
    }

    /// Slide the window: remove `old_byte` from the left, add `new_byte` to the right (Eq. 2).
    ///
    /// F(X_{r+1}) = ((F(X_r) - old_byte * b^{p-1}) * b + new_byte) mod (2^61-1)
    #[inline]
    pub fn roll(&mut self, old_byte: u8, new_byte: u8) {
        // Subtract old_byte * bp, using HASH_MOD to keep positive
        let sub = mod_mersenne(old_byte as u128 * self.bp as u128);
        let v = if self.value >= sub {
            self.value - sub
        } else {
            HASH_MOD - (sub - self.value)
        };
        // Multiply by base and add new_byte
        self.value = mod_mersenne(v as u128 * HASH_BASE as u128 + new_byte as u128);
    }

}

// ── Primality testing (for hash table auto-sizing) ───────────────────────

use rand::Rng;

/// Modular exponentiation: base^exp mod modulus (uses u128 to avoid overflow).
fn power_mod(base: u64, mut exp: u64, modulus: u64) -> u64 {
    if modulus == 1 {
        return 0;
    }
    let m = modulus as u128;
    let mut result: u128 = 1;
    let mut b: u128 = base as u128 % m;
    while exp > 0 {
        if exp & 1 == 1 {
            result = result * b % m;
        }
        exp >>= 1;
        b = b * b % m;
    }
    result as u64
}

/// Factor n into d * 2^r, returning (d, r).
fn get_d_r(mut n: u64) -> (u64, u32) {
    let mut r: u32 = 0;
    while n % 2 == 0 {
        n /= 2;
        r += 1;
    }
    (n, r)
}

/// The witness loop of the Miller-Rabin probabilistic primality test.
///
/// Returns `true` if `a` is a witness to the compositeness of `n`
/// (i.e., n is definitely composite).  Returns `false` if `a` is a
/// "liar" — n may be prime.
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

/// Miller-Rabin probabilistic primality test with confidence `k`.
///
/// Pr[false positive] <= 4^{-k}.  With the default k = 100, the
/// probability of a composite being reported as prime is < 10^{-60}.
pub fn is_prime(n: usize) -> bool {
    is_prime_mr(n, 100)
}

/// Miller-Rabin with explicit confidence parameter.
pub fn is_prime_mr(n: usize, k: u32) -> bool {
    let n = n as u64;
    if n < 2 || (n != 2 && n % 2 == 0) {
        return false;
    }
    if n == 2 || n == 3 {
        return true;
    }
    let mut rng = rand::thread_rng();
    for _ in 0..k {
        let a = rng.gen_range(2..n - 1);
        if witness(a, n) {
            return false;
        }
    }
    true
}

/// Smallest prime >= n.
///
/// Searches odd candidates upward from n.  By the prime number theorem,
/// the expected gap is O(log n), so this terminates quickly.
pub fn next_prime(n: usize) -> usize {
    if n <= 2 {
        return 2;
    }
    let mut candidate = if n % 2 == 0 { n + 1 } else { n };
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

    // ── Primality testing ────────────────────────────────────────────────

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
        assert!(witness(2, 9));    // 9 = 3^2 is composite
        assert!(witness(2, 15));
    }

    #[test]
    fn test_witness_prime() {
        // No a in [2, 13) should be a witness for the prime 13
        for a in 2..12 {
            assert!(!witness(a, 13), "a={} should not be a witness for 13", a);
        }
    }

    #[test]
    fn test_known_primes() {
        let primes = [
            2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47,
            53, 59, 61, 67, 71, 73, 79, 83, 89, 97, 101, 103, 107, 109, 113,
            127, 131, 137, 139, 149, 151, 157, 163, 167, 173, 179, 181, 191,
            193, 197, 199, 211, 223, 227, 229,
        ];
        for &p in &primes {
            assert!(is_prime(p), "{} should be prime", p);
        }
    }

    #[test]
    fn test_known_composites() {
        let composites = [
            0, 1, 4, 6, 8, 9, 10, 12, 14, 15, 16, 18, 20,
            21, 25, 27, 33, 35, 49, 51, 55, 63, 65, 77, 91,
            100, 121, 143, 169, 221, 1000, 1000000,
        ];
        for &c in &composites {
            assert!(!is_prime(c), "{} should be composite", c);
        }
    }

    #[test]
    fn test_large_primes() {
        assert!(is_prime(1048573));   // largest prime < 2^20
        assert!(is_prime(2097143));   // largest prime < 2^21
        assert!(is_prime(104729));    // 10000th prime
    }

    #[test]
    fn test_carmichael_numbers() {
        // Carmichael numbers pass the Fermat test for all bases
        // but Miller-Rabin with random witnesses catches them.
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
        // Verify next_prime produces valid primes for a range of inputs
        for n in 2..500 {
            let np = next_prime(n);
            assert!(np >= n);
            assert!(is_prime(np), "next_prime({}) = {} should be prime", n, np);
        }
    }
}
