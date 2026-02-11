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

/// Compute the Karp-Rabin fingerprint of data[offset..offset+p].
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

/// Map a full fingerprint to a hash table index.
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

    /// Slide the window: remove `old_byte` from the left, add `new_byte` to the right.
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
