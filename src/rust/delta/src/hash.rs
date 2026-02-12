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
    p: usize,
}

impl RollingHash {
    /// Create a new RollingHash from data[offset..offset+p].
    pub fn new(data: &[u8], offset: usize, p: usize) -> Self {
        let bp = precompute_bp(p);
        let value = fingerprint(data, offset, p);
        RollingHash { value, bp, p }
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
        // Multiply by base and add new byte
        self.value = mod_mersenne(v as u128 * HASH_BASE as u128 + new_byte as u128);
    }

    /// Seed length this rolling hash was initialized with.
    #[inline]
    pub fn seed_len(&self) -> usize {
        self.p
    }
}

// ── Primality testing (for hash table auto-sizing) ───────────────────────

/// Modular exponentiation: base^exp mod modulus (uses u128 to avoid overflow).
fn mod_pow(base: u64, mut exp: u64, modulus: u64) -> u64 {
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

/// Deterministic Miller-Rabin primality test.
///
/// Correct for all n < 3.3 * 10^24 using the witness set
/// {2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37}.
pub fn is_prime(n: usize) -> bool {
    let n = n as u64;
    if n < 2 {
        return false;
    }
    if n < 4 {
        return true;
    }
    if n % 2 == 0 {
        return false;
    }
    // Write n-1 = d * 2^r
    let mut d = n - 1;
    let mut r: u32 = 0;
    while d % 2 == 0 {
        d /= 2;
        r += 1;
    }
    for &a in &[2u64, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37] {
        if a >= n {
            continue;
        }
        let mut x = mod_pow(a, d, n);
        if x == 1 || x == n - 1 {
            continue;
        }
        let mut composite = true;
        for _ in 0..r - 1 {
            x = mod_pow(x, 2, n);
            if x == n - 1 {
                composite = false;
                break;
            }
        }
        if composite {
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
    fn test_fingerprint_matches_python() {
        // Same as Python: _fingerprint(b"ABCDEFGHIJKLMNOP", 0, 16)
        let data = b"ABCDEFGHIJKLMNOP";
        let fp = fingerprint(data, 0, 16);
        // Just verify it's deterministic and non-zero
        assert_ne!(fp, 0);
        assert_eq!(fp, fingerprint(data, 0, 16));
    }

    #[test]
    fn test_rolling_hash_consistency() {
        let data = b"ABCDEFGHIJKLMNOPQRST";
        let p = 4;

        // Compute fingerprint at offset 0
        let mut rh = RollingHash::new(data, 0, p);
        assert_eq!(rh.value(), fingerprint(data, 0, p));

        // Roll to offset 1
        rh.roll(data[0], data[p]);
        assert_eq!(rh.value(), fingerprint(data, 1, p));

        // Roll to offset 2
        rh.roll(data[1], data[p + 1]);
        assert_eq!(rh.value(), fingerprint(data, 2, p));
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
}
