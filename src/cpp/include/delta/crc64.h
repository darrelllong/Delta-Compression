#pragma once

/// CRC-64/XZ: the ECMA-182 polynomial, reflected, with all-ones initial value
/// and final XOR.  The CRC of "123456789" is 0x995DC9BBDF1939FA.

#include <array>
#include <cstddef>
#include <cstdint>

#include "delta/types.h"

namespace delta {

/// The CRC of data[0, len), most significant byte first.
std::array<uint8_t, DELTA_CRC_SIZE> crc64_xz(const uint8_t* data, size_t len);

} // namespace delta
