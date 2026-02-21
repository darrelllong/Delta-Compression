/// Keccak-p[1600, 24] permutation + SHAKE128 sponge (FIPS 202).
///
/// References:
///   NIST FIPS 202 (SHA-3 Standard), https://doi.org/10.6028/NIST.FIPS.202
///   Keccak reference: https://keccak.team/keccak_specs_summary.html
///
/// SHAKE128 parameters:
///   Rate r = 1344 bits = 168 bytes
///   Capacity c = 256 bits
///   Domain suffix = 0x1F
///   Output length = 16 bytes (DELTA_HASH_SIZE)

#include "delta/sha3.h"

#include <array>
#include <cstring>

namespace delta {

// ── Keccak-p[1600, 24] ───────────────────────────────────────────────────

// 24 round constants for the ι (iota) step (FIPS 202 Table 5).
static const uint64_t RC[24] = {
    0x0000000000000001ULL, 0x0000000000008082ULL,
    0x800000000000808AULL, 0x8000000080008000ULL,
    0x000000000000808BULL, 0x0000000080000001ULL,
    0x8000000080008081ULL, 0x8000000000008009ULL,
    0x000000000000008AULL, 0x0000000000000088ULL,
    0x0000000080008009ULL, 0x000000008000000AULL,
    0x000000008000808BULL, 0x800000000000008BULL,
    0x8000000000008089ULL, 0x8000000000008003ULL,
    0x8000000000008002ULL, 0x8000000000000080ULL,
    0x000000000000800AULL, 0x800000008000000AULL,
    0x8000000080008081ULL, 0x8000000000008080ULL,
    0x0000000080000001ULL, 0x8000000080008008ULL,
};

// Rotation offsets for the ρ (rho) step (FIPS 202 Table 2).
// Indexed as state[x + 5*y]; entry [0] = 0 for lane (0,0).
static const int RHO[25] = {
     0,  1, 62, 28, 27,
    36, 44,  6, 55, 20,
     3, 10, 43, 25, 39,
    41, 45, 15, 21,  8,
    18,  2, 61, 56, 14,
};

static inline uint64_t rotl64(uint64_t x, int n) {
    if (n == 0) return x;
    return (x << n) | (x >> (64 - n));
}

static void keccak_f1600(uint64_t A[25]) {
    for (int rnd = 0; rnd < 24; ++rnd) {
        // θ (theta): A[i] ^= D[i%5] where D[x] = C[(x+4)%5] ^ rot(C[(x+1)%5], 1)
        uint64_t C[5];
        for (int x = 0; x < 5; ++x)
            C[x] = A[x] ^ A[x+5] ^ A[x+10] ^ A[x+15] ^ A[x+20];
        uint64_t D[5];
        for (int x = 0; x < 5; ++x)
            D[x] = C[(x+4)%5] ^ rotl64(C[(x+1)%5], 1);
        for (int i = 0; i < 25; ++i)
            A[i] ^= D[i%5];

        // ρ (rho) + π (pi): B[x + 5*y] = rot(A[(x+3y)%5 + 5*x], rho[(x+3y)%5 + 5*x])
        // π maps output (x,y) to input (x+3y mod 5, x).
        uint64_t B[25];
        for (int y = 0; y < 5; ++y)
            for (int x = 0; x < 5; ++x) {
                int src = (x + 3*y) % 5 + 5*x;
                B[x + 5*y] = rotl64(A[src], RHO[src]);
            }

        // χ (chi): A[x + 5*y] = B[x + 5*y] ^ (~B[(x+1)%5 + 5*y] & B[(x+2)%5 + 5*y])
        for (int y = 0; y < 5; ++y)
            for (int x = 0; x < 5; ++x)
                A[x + 5*y] = B[x + 5*y]
                             ^ ((~B[(x+1)%5 + 5*y]) & B[(x+2)%5 + 5*y]);

        // ι (iota): A[0] ^= RC[rnd]
        A[0] ^= RC[rnd];
    }
}

// ── SHAKE128 sponge ───────────────────────────────────────────────────────

// Rate for SHAKE128 = 1344 bits = 168 bytes.
static const size_t SHAKE128_RATE = 168;

// XOR len bytes of data into the Keccak state (little-endian 64-bit lanes).
static void xor_into_state(uint64_t state[25], const uint8_t* data, size_t len) {
    for (size_t i = 0; i < len; ++i)
        state[i / 8] ^= static_cast<uint64_t>(data[i]) << (8 * (i % 8));
}

// Extract len bytes from the Keccak state into out (little-endian lanes).
static void extract_from_state(const uint64_t state[25], uint8_t* out, size_t len) {
    for (size_t i = 0; i < len; ++i)
        out[i] = static_cast<uint8_t>(state[i / 8] >> (8 * (i % 8)));
}

std::array<uint8_t, DELTA_HASH_SIZE> shake128_16(const uint8_t* data, size_t len) {
    uint64_t state[25] = {};

    // Absorb full rate-sized blocks.
    size_t pos = 0;
    while (pos + SHAKE128_RATE <= len) {
        xor_into_state(state, data + pos, SHAKE128_RATE);
        keccak_f1600(state);
        pos += SHAKE128_RATE;
    }

    // Final block: copy remainder, apply SHAKE128 multi-rate padding.
    // Padding = 0x1F ... 0x80 (FIPS 202 Section 6.2).
    uint8_t last[SHAKE128_RATE] = {};
    size_t rem = len - pos;
    if (rem > 0) std::memcpy(last, data + pos, rem); // guard: data may be null when len=0
    last[rem]              ^= 0x1F;  // SHAKE128 domain separator
    last[SHAKE128_RATE - 1] ^= 0x80; // multi-rate padding final bit
    xor_into_state(state, last, SHAKE128_RATE);
    keccak_f1600(state);

    // Squeeze exactly DELTA_HASH_SIZE (16) bytes from the first rate block.
    // 16 < SHAKE128_RATE (168), so one permutation is always sufficient here.
    // To support d > SHAKE128_RATE output bytes, loop: extract up to
    // SHAKE128_RATE bytes, call keccak_f1600 again, repeat.
    std::array<uint8_t, DELTA_HASH_SIZE> out;
    extract_from_state(state, out.data(), DELTA_HASH_SIZE);
    return out;
}

} // namespace delta
