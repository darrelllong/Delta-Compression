#include "delta/crc64.h"

#include <cstring>

namespace delta {

namespace {

constexpr uint64_t POLY = 0xC96C5795D7870F42ULL; // 0x42F0E1EBA9EA3693 reflected

// Slicing-by-8 (Kounavis and Berry, Intel, 2005): table[k][b] is the CRC of
// byte b followed by k zero bytes, so eight bytes can be folded into the
// register at once.
using Tables = std::array<std::array<uint64_t, 256>, 8>;

constexpr Tables make_tables() {
    Tables t{};
    for (uint64_t i = 0; i < 256; ++i) {
        uint64_t crc = i;
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc & 1) ? (crc >> 1) ^ POLY : crc >> 1;
        }
        t[0][i] = crc;
    }
    for (size_t k = 1; k < 8; ++k) {
        for (size_t i = 0; i < 256; ++i) {
            uint64_t crc = t[k - 1][i];
            t[k][i] = t[0][crc & 0xFF] ^ (crc >> 8);
        }
    }
    return t;
}

constexpr Tables TABLE = make_tables();

uint64_t load_le64(const uint8_t* p) {
    uint64_t word;
    std::memcpy(&word, p, 8);
#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
    word = __builtin_bswap64(word);
#endif
    return word;
}

} // namespace

std::array<uint8_t, DELTA_CRC_SIZE> crc64_xz(const uint8_t* data, size_t len) {
    uint64_t crc = ~uint64_t{0};
    for (; len >= 8; data += 8, len -= 8) {
        crc ^= load_le64(data);
        crc = TABLE[7][crc & 0xFF] ^ TABLE[6][(crc >> 8) & 0xFF] ^
              TABLE[5][(crc >> 16) & 0xFF] ^ TABLE[4][(crc >> 24) & 0xFF] ^
              TABLE[3][(crc >> 32) & 0xFF] ^ TABLE[2][(crc >> 40) & 0xFF] ^
              TABLE[1][(crc >> 48) & 0xFF] ^ TABLE[0][crc >> 56];
    }
    for (; len > 0; ++data, --len) {
        crc = TABLE[0][(crc ^ *data) & 0xFF] ^ (crc >> 8);
    }
    crc = ~crc;

    std::array<uint8_t, DELTA_CRC_SIZE> out;
    for (size_t i = 0; i < DELTA_CRC_SIZE; ++i) {
        out[i] = static_cast<uint8_t>(crc >> (56 - 8 * i));
    }
    return out;
}

} // namespace delta
