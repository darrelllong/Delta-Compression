#pragma once

/// SHAKE128 (FIPS 202 XOF) — 16-byte output helper.
///
/// Implemented from scratch using Keccak-p[1600, 24] to avoid external deps.
/// Rate = 168 bytes, domain suffix = 0x1F.

#include <array>
#include <cstddef>
#include <cstdint>

#include "delta/types.h"

namespace delta {

/// Compute SHAKE128 with DELTA_HASH_SIZE (16) bytes of output (FIPS 202 XOF).
std::array<uint8_t, DELTA_HASH_SIZE> shake128_16(const uint8_t* data, size_t len);

} // namespace delta
