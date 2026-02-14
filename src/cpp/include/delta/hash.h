#pragma once

/// Karp-Rabin rolling hash (Karp & Rabin 1987; Section 2.1.3).
///
/// Polynomial fingerprints over the Mersenne prime 2^61-1.
/// Full 61-bit fingerprints are used for collision-free seed comparison;
/// fp_to_index maps them into the bounded hash table via F mod q.

#include <cstddef>
#include <cstdint>
#include <span>

namespace delta {

/// Reduce a 128-bit value modulo the Mersenne prime 2^61-1.
///
/// Uses the Mersenne identity: for M = 2^61-1, x mod M = (x >> 61) + (x & M),
/// with a final correction if the result >= M. No division needed.
uint64_t mod_mersenne(__uint128_t x);

/// Compute the Karp-Rabin fingerprint of data[offset..offset+p] (Eq. 1, Section 2.1.3).
///
/// F(X) = (x_0 * b^{p-1} + x_1 * b^{p-2} + ... + x_{p-1}) mod (2^61-1)
uint64_t fingerprint(std::span<const uint8_t> data, size_t offset, size_t p);

/// Precompute HASH_BASE^{p-1} mod HASH_MOD.
uint64_t precompute_bp(size_t p);

/// Map a full fingerprint to a hash table index (F mod q, Section 2.1.3).
inline size_t fp_to_index(uint64_t fp, size_t table_size) {
    return static_cast<size_t>(fp % static_cast<uint64_t>(table_size));
}

/// Rolling hash for O(1) incremental fingerprint updates.
class RollingHash {
public:
    /// Create a new RollingHash from data[offset..offset+p].
    RollingHash(std::span<const uint8_t> data, size_t offset, size_t p);

    /// Current fingerprint value.
    uint64_t value() const { return value_; }

    /// Slide the window: remove old_byte from the left, add new_byte to the right (Eq. 2).
    void roll(uint8_t old_byte, uint8_t new_byte);

private:
    uint64_t value_;
    uint64_t bp_; // HASH_BASE^{p-1} mod HASH_MOD
    size_t p_;
};

// ── Primality testing (for hash table auto-sizing) ───────────────────────

/// Miller-Rabin probabilistic primality test with confidence k=100.
bool is_prime(size_t n);

/// Miller-Rabin with explicit confidence parameter.
bool is_prime_mr(size_t n, uint32_t k);

/// Smallest prime >= n.
size_t next_prime(size_t n);

} // namespace delta
