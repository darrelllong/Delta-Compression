#pragma once

/// Karp-Rabin fingerprints over the Mersenne prime 2^61-1 (Section 2.1.3),
/// and the primality test used to size hash tables.

// The 128-bit products need __uint128_t, which MSVC does not have.
#if !defined(__GNUC__) && !defined(__clang__)
#  error "delta/hash.h requires GCC or Clang (__uint128_t is not available on this compiler)"
#endif

#include <cstddef>
#include <cstdint>
#include <span>

#include "delta/types.h"

namespace delta {

/// x mod 2^61-1, without division: 2^61 is congruent to 1, so the high bits
/// fold onto the low ones.  Two folds bring any 128-bit x into range.
inline uint64_t mod_mersenne(__uint128_t x) {
    const __uint128_t m = HASH_MOD;
    x = (x >> 61) + (x & m);
    if (x >= m) { x -= m; }
    x = (x >> 61) + (x & m);
    if (x >= m) { x -= m; }
    return static_cast<uint64_t>(x);
}

/// Fingerprint of data[offset, offset+p) (Eq. 1):
/// (x_0 b^{p-1} + x_1 b^{p-2} + ... + x_{p-1}) mod 2^61-1.
uint64_t fingerprint(std::span<const uint8_t> data, size_t offset, size_t p);

/// HASH_BASE^{p-1} mod HASH_MOD, the weight of the leftmost byte of a seed.
uint64_t precompute_bp(size_t p);

/// The fingerprint of the seed one byte to the right of the seed whose
/// fingerprint is fp (Eq. 2): out leaves on the left, in enters on the right.
/// bp is precompute_bp(p).
inline uint64_t roll_fingerprint(uint64_t fp, uint64_t bp, uint8_t out, uint8_t in) {
    const uint64_t sub = mod_mersenne(static_cast<__uint128_t>(out) * bp);
    const uint64_t rest = fp >= sub ? fp - sub : HASH_MOD - (sub - fp);
    return mod_mersenne(static_cast<__uint128_t>(rest) * HASH_BASE + in);
}

/// Table index of a fingerprint (F mod q, Section 2.1.3).
inline size_t fp_to_index(uint64_t fp, size_t table_size) {
    return static_cast<size_t>(fp % static_cast<uint64_t>(table_size));
}

/// The fingerprint of a p-byte window, updated in O(1) as the window slides.
class RollingHash {
public:
    /// Starts at data[offset, offset+p).
    RollingHash(std::span<const uint8_t> data, size_t offset, size_t p)
        : value_(fingerprint(data, offset, p)), bp_(precompute_bp(p)) {}

    uint64_t value() const { return value_; }

    /// Slides the window one byte: old_byte is the byte that leaves it,
    /// new_byte the byte that enters.
    void roll(uint8_t old_byte, uint8_t new_byte) {
        value_ = roll_fingerprint(value_, bp_, old_byte, new_byte);
    }

private:
    uint64_t value_;
    uint64_t bp_;
};

/// Deterministic Miller-Rabin with the first twelve primes as witnesses,
/// which is exact for every 64-bit n: the smallest composite that passes all
/// twelve exceeds 3.1e23 (Sorenson and Webster, Math. Comp. 86(304), 2017).
bool is_prime(size_t n);

/// The smallest prime >= n.
size_t next_prime(size_t n);

} // namespace delta
