#include "delta/hash.h"
#include "delta/types.h"

#include <random>

namespace delta {

uint64_t mod_mersenne(__uint128_t x) {
    __uint128_t m = HASH_MOD;
    __uint128_t r = (x >> 61) + (x & m);
    if (r >= m) r -= m;
    // One more reduction in case the first wasn't enough (x >> 61 can be large)
    __uint128_t r2 = (r >> 61) + (r & m);
    if (r2 >= m) r2 -= m;
    return static_cast<uint64_t>(r2);
}

uint64_t fingerprint(std::span<const uint8_t> data, size_t offset, size_t p) {
    uint64_t h = 0;
    for (size_t i = 0; i < p; ++i) {
        h = mod_mersenne(
            static_cast<__uint128_t>(h) * HASH_BASE + data[offset + i]);
    }
    return h;
}

uint64_t precompute_bp(size_t p) {
    if (p == 0) return 1;
    uint64_t result = 1;
    uint64_t base = HASH_BASE;
    size_t exp = p - 1;
    // Modular exponentiation by squaring
    while (exp > 0) {
        if (exp & 1) {
            result = mod_mersenne(
                static_cast<__uint128_t>(result) * base);
        }
        base = mod_mersenne(
            static_cast<__uint128_t>(base) * base);
        exp >>= 1;
    }
    return result;
}

RollingHash::RollingHash(std::span<const uint8_t> data, size_t offset, size_t p)
    : bp_(precompute_bp(p)), p_(p) {
    value_ = fingerprint(data, offset, p);
}

void RollingHash::roll(uint8_t old_byte, uint8_t new_byte) {
    // Subtract old_byte * bp, using HASH_MOD to keep positive
    uint64_t sub = mod_mersenne(
        static_cast<__uint128_t>(old_byte) * bp_);
    uint64_t v = (value_ >= sub) ? (value_ - sub) : (HASH_MOD - (sub - value_));
    // Multiply by base and add new_byte
    value_ = mod_mersenne(
        static_cast<__uint128_t>(v) * HASH_BASE + new_byte);
}

// ── Primality testing ────────────────────────────────────────────────────

/// Modular exponentiation: base^exp mod modulus (uses __uint128_t to avoid overflow).
static uint64_t power_mod(uint64_t base, uint64_t exp, uint64_t modulus) {
    if (modulus == 1) return 0;
    __uint128_t m = modulus;
    __uint128_t result = 1;
    __uint128_t b = static_cast<__uint128_t>(base) % m;
    while (exp > 0) {
        if (exp & 1) {
            result = result * b % m;
        }
        exp >>= 1;
        b = b * b % m;
    }
    return static_cast<uint64_t>(result);
}

/// Factor n-1 into d * 2^r, returning (d, r).
static std::pair<uint64_t, uint32_t> get_d_r(uint64_t n) {
    uint32_t r = 0;
    while (n % 2 == 0) {
        n /= 2;
        ++r;
    }
    return {n, r};
}

/// The witness loop of the Miller-Rabin probabilistic primality test.
static bool witness(uint64_t a, uint64_t n) {
    auto [d, r] = get_d_r(n - 1);
    uint64_t x = power_mod(a, d, n);
    for (uint32_t i = 0; i < r; ++i) {
        uint64_t y = power_mod(x, 2, n);
        if (y == 1 && x != 1 && x != n - 1) {
            return true;
        }
        x = y;
    }
    return x != 1;
}

bool is_prime(size_t n) {
    return is_prime_mr(n, 100);
}

bool is_prime_mr(size_t n, uint32_t k) {
    uint64_t n64 = static_cast<uint64_t>(n);
    if (n64 < 2 || (n64 != 2 && n64 % 2 == 0)) return false;
    if (n64 == 2 || n64 == 3) return true;

    thread_local std::mt19937_64 rng{std::random_device{}()};
    std::uniform_int_distribution<uint64_t> dist(2, n64 - 2);

    for (uint32_t i = 0; i < k; ++i) {
        uint64_t a = dist(rng);
        if (witness(a, n64)) return false;
    }
    return true;
}

size_t next_prime(size_t n) {
    if (n <= 2) return 2;
    size_t candidate = (n % 2 == 0) ? n + 1 : n;
    while (!is_prime(candidate)) {
        candidate += 2;
    }
    return candidate;
}

} // namespace delta
