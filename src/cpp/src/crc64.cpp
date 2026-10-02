#include "delta/crc64.h"

namespace delta {

namespace {

constexpr uint64_t POLY = 0xC96C5795D7870F42ULL; // 0x42F0E1EBA9EA3693 reflected

constexpr std::array<uint64_t, 256> make_table() {
    std::array<uint64_t, 256> table{};
    for (uint64_t i = 0; i < 256; ++i) {
        uint64_t crc = i;
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc & 1) ? (crc >> 1) ^ POLY : crc >> 1;
        }
        table[i] = crc;
    }
    return table;
}

constexpr std::array<uint64_t, 256> TABLE = make_table();

} // namespace

std::array<uint8_t, DELTA_CRC_SIZE> crc64_xz(const uint8_t* data, size_t len) {
    uint64_t crc = ~uint64_t{0};
    for (size_t i = 0; i < len; ++i) {
        crc = TABLE[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
    }
    crc = ~crc;

    std::array<uint8_t, DELTA_CRC_SIZE> out;
    for (size_t i = 0; i < DELTA_CRC_SIZE; ++i) {
        out[i] = static_cast<uint8_t>(crc >> (56 - 8 * i));
    }
    return out;
}

} // namespace delta
