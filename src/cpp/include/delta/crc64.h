#pragma once

/// CRC-64/XZ (ECMA-182 reflected) — 8-byte output.
///
/// Reflected poly: 0xC96C5795D7870F42 (canonical normal form: 0x42F0E1EBA9EA3693).
/// Init = XorOut = 0xFFFFFFFFFFFFFFFF; RefIn = RefOut = true.
///
/// Check value: crc64_xz(b"123456789") = 0x995DC9BBDF1939FA.
/// Empty input: crc64_xz(b"") = 0x0000000000000000.

#include <array>
#include <cstddef>
#include <cstdint>

#include "delta/types.h"

namespace delta {

/// Compute CRC-64/XZ of data[0..len]; returns DELTA_CRC_SIZE bytes big-endian.
std::array<uint8_t, DELTA_CRC_SIZE> crc64_xz(const uint8_t* data, size_t len);

} // namespace delta
