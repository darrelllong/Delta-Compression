#include "delta/hash.h"

namespace delta {

uint64_t fingerprint(std::span<const uint8_t> data, size_t offset, size_t p) {
    uint64_t h = 0;
    for (size_t i = 0; i < p; ++i) {
        h = mod_mersenne(static_cast<__uint128_t>(h) * HASH_BASE + data[offset + i]);
    }
    return h;
}

uint64_t precompute_bp(size_t p) {
    if (p == 0) { return 1; }
    uint64_t result = 1;
    uint64_t base = HASH_BASE;
    for (size_t exp = p - 1; exp > 0; exp >>= 1) {
        if (exp & 1) {
            result = mod_mersenne(static_cast<__uint128_t>(result) * base);
        }
        base = mod_mersenne(static_cast<__uint128_t>(base) * base);
    }
    return result;
}

namespace {

uint64_t mul_mod(uint64_t a, uint64_t b, uint64_t n) {
    return static_cast<uint64_t>(static_cast<__uint128_t>(a) * b % n);
}

uint64_t power_mod(uint64_t base, uint64_t exp, uint64_t n) {
    uint64_t result = 1 % n;
    base %= n;
    for (; exp > 0; exp >>= 1) {
        if (exp & 1) { result = mul_mod(result, base, n); }
        base = mul_mod(base, base, n);
    }
    return result;
}

/// Reports whether a proves the odd number n composite.
bool witness(uint64_t a, uint64_t n) {
    // n - 1 = d * 2^r with d odd.
    uint64_t d = n - 1;
    int r = 0;
    for (; d % 2 == 0; d /= 2) { ++r; }

    uint64_t x = power_mod(a, d, n);
    for (int i = 0; i < r; ++i) {
        uint64_t y = mul_mod(x, x, n);
        if (y == 1 && x != 1 && x != n - 1) { return true; }
        x = y;
    }
    return x != 1;
}

constexpr uint64_t WITNESSES[] = {2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37};

} // namespace

bool is_prime(size_t n) {
    if (n < 2) { return false; }
    if (n < 4) { return true; }
    if (n % 2 == 0) { return false; }
    for (uint64_t a : WITNESSES) {
        if (a >= n) { break; }
        if (witness(a, n)) { return false; }
    }
    return true;
}

size_t next_prime(size_t n) {
    if (n <= 2) { return 2; }
    size_t candidate = n | 1;
    while (!is_prime(candidate)) { candidate += 2; }
    return candidate;
}

} // namespace delta
