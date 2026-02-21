#include <catch2/catch_test_macros.hpp>
#include <delta/crc64.h>
#include <delta/hash.h>
#include <delta/types.h>

#include <array>
#include <cstdint>
#include <string>
#include <vector>

using namespace delta;

// ── mod_mersenne ─────────────────────────────────────────────────────────

TEST_CASE("mod_mersenne basic values", "[hash]") {
    CHECK(mod_mersenne(0) == 0);
    CHECK(mod_mersenne(HASH_MOD) == 0);
    CHECK(mod_mersenne(static_cast<__uint128_t>(HASH_MOD) + 1) == 1);
    CHECK(mod_mersenne(42) == 42);
}

// ── fingerprint ──────────────────────────────────────────────────────────

TEST_CASE("fingerprint is deterministic", "[hash]") {
    std::vector<uint8_t> data = {'A','B','C','D','E','F','G','H',
                                 'I','J','K','L','M','N','O','P'};
    auto fp = fingerprint(data, 0, 16);
    CHECK(fp != 0);
    CHECK(fp == fingerprint(data, 0, 16));
}

// ── rolling hash ─────────────────────────────────────────────────────────

TEST_CASE("rolling hash matches fingerprint at every offset", "[hash]") {
    std::vector<uint8_t> data = {'T','h','e',' ','q','u','i','c','k',' ',
                                 'b','r','o','w','n',' ','f','o','x',' ',
                                 'j','u','m','p','s',' ','o','v','e','r',
                                 ' ','t','h','e',' ','l','a','z','y',' ',
                                 'd','o','g','.'};
    size_t p = 8;
    RollingHash rh(data, 0, p);

    for (size_t i = 1; i <= data.size() - p; ++i) {
        rh.roll(data[i - 1], data[i + p - 1]);
        REQUIRE(rh.value() == fingerprint(data, i, p));
    }
}

// ── primality testing ────────────────────────────────────────────────────

TEST_CASE("known primes", "[hash]") {
    std::vector<size_t> primes = {
        2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47,
        53, 59, 61, 67, 71, 73, 79, 83, 89, 97, 101, 103, 107, 109, 113,
        127, 131, 137, 139, 149, 151, 157, 163, 167, 173, 179, 181, 191,
        193, 197, 199, 211, 223, 227, 229};
    for (auto p : primes) {
        CHECK(is_prime(p));
    }
}

TEST_CASE("known composites", "[hash]") {
    std::vector<size_t> composites = {
        0, 1, 4, 6, 8, 9, 10, 12, 14, 15, 16, 18, 20,
        21, 25, 27, 33, 35, 49, 51, 55, 63, 65, 77, 91,
        100, 121, 143, 169, 221, 1000, 1000000};
    for (auto c : composites) {
        CHECK_FALSE(is_prime(c));
    }
}

TEST_CASE("large primes", "[hash]") {
    CHECK(is_prime(1048573));   // largest prime < 2^20
    CHECK(is_prime(2097143));   // largest prime < 2^21
    CHECK(is_prime(104729));    // 10000th prime
}

TEST_CASE("Carmichael numbers are composite", "[hash]") {
    std::vector<size_t> carmichaels = {561, 1105, 1729, 2465, 2821, 6601, 8911};
    for (auto c : carmichaels) {
        CHECK_FALSE(is_prime(c));
    }
}

TEST_CASE("next_prime from composite", "[hash]") {
    CHECK(next_prime(8) == 11);
    CHECK(next_prime(14) == 17);
    CHECK(next_prime(100) == 101);
    CHECK(next_prime(1000) == 1009);
}

TEST_CASE("next_prime small values", "[hash]") {
    CHECK(next_prime(0) == 2);
    CHECK(next_prime(1) == 2);
    CHECK(next_prime(2) == 2);
    CHECK(next_prime(3) == 3);
}

TEST_CASE("next_prime consecutive range produces valid primes", "[hash]") {
    for (size_t n = 2; n < 500; ++n) {
        auto np = next_prime(n);
        CHECK(np >= n);
        CHECK(is_prime(np));
    }
}

TEST_CASE("TABLE_SIZE is prime", "[hash]") {
    CHECK(is_prime(TABLE_SIZE));
}

// ── CRC-64/XZ check values ────────────────────────────────────────────────

static std::string to_hex(const std::array<uint8_t, DELTA_CRC_SIZE>& h) {
    std::string s;
    s.reserve(DELTA_CRC_SIZE * 2);
    for (auto b : h) {
        char buf[3];
        std::snprintf(buf, sizeof(buf), "%02x", b);
        s += buf;
    }
    return s;
}

TEST_CASE("crc64_xz empty input is all-zeros", "[hash]") {
    // CRC-64/XZ of empty input = 0x0000000000000000.
    static const uint8_t kEmpty[1] = {};
    auto h = crc64_xz(kEmpty, 0);
    CHECK(to_hex(h) == "0000000000000000");
}

TEST_CASE("crc64_xz standard check value", "[hash]") {
    // Standard check value: CRC-64/XZ of b"123456789" = 0x995DC9BBDF1939FA.
    static const uint8_t kCheck[] = {'1','2','3','4','5','6','7','8','9'};
    auto h = crc64_xz(kCheck, sizeof(kCheck));
    CHECK(to_hex(h) == "995dc9bbdf1939fa");
}

TEST_CASE("crc64_xz output length is DELTA_CRC_SIZE", "[hash]") {
    uint8_t data[] = {0x01, 0x02, 0x03};
    auto h = crc64_xz(data, 3);
    CHECK(h.size() == DELTA_CRC_SIZE);
}

TEST_CASE("crc64_xz is deterministic", "[hash]") {
    uint8_t data[] = {'h', 'e', 'l', 'l', 'o'};
    auto h1 = crc64_xz(data, 5);
    auto h2 = crc64_xz(data, 5);
    CHECK(h1 == h2);
}

TEST_CASE("crc64_xz differs on different input", "[hash]") {
    uint8_t a[] = {'a', 'b', 'c'};
    uint8_t b[] = {'a', 'b', 'd'};
    CHECK(crc64_xz(a, 3) != crc64_xz(b, 3));
}
