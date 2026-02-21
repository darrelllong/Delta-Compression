#include "delta/crc64.h"

#include <bit>
#include <cstdint>

namespace delta {

namespace {

// Build the 256-entry CRC-64/XZ lookup table at first call.
// Reflected poly: 0xC96C5795D7870F42 (normal form: 0x42F0E1EBA9EA3693).
const uint64_t* crc_table() {
    static uint64_t table[256] = {};
    static bool initialised = false;
    if (!initialised) {
        const uint64_t poly = 0xC96C5795D7870F42ULL;
        for (int i = 0; i < 256; ++i) {
            uint64_t crc = static_cast<uint64_t>(i);
            for (int j = 0; j < 8; ++j) {
                crc = (crc & 1) ? (crc >> 1) ^ poly : (crc >> 1);
            }
            table[i] = crc;
        }
        initialised = true;
    }
    return table;
}

} // anonymous namespace

std::array<uint8_t, DELTA_CRC_SIZE> crc64_xz(const uint8_t* data, size_t len) {
    const uint64_t* t = crc_table();
    uint64_t crc = 0xFFFFFFFFFFFFFFFFULL;
    for (size_t i = 0; i < len; ++i) {
        crc = t[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
    }
    uint64_t val = crc ^ 0xFFFFFFFFFFFFFFFFULL;

    // Store big-endian
    std::array<uint8_t, DELTA_CRC_SIZE> out;
    for (size_t i = 0; i < DELTA_CRC_SIZE; ++i) {
        out[i] = static_cast<uint8_t>((val >> (56 - 8 * i)) & 0xFF);
    }
    return out;
}

} // namespace delta
