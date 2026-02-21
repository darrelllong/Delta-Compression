#pragma once

/// SHAKE128 (FIPS 202 XOF) — restricted to 16-byte output.
///
/// Implemented from scratch using Keccak-p[1600, 24] to avoid external deps.
/// Rate = 168 bytes, domain suffix = 0x1F.
///
/// LIMITATION: the squeeze step always emits exactly DELTA_HASH_SIZE (16) bytes.
/// The absorb path handles arbitrary-length input correctly.  To support longer
/// output, the squeeze in shake128_16 (and any streaming variant) would need to
/// loop — extracting up to rate bytes per keccak_f1600 call — until the
/// requested number of output bytes have been produced.

#include <array>
#include <cstddef>
#include <cstdint>

#include "delta/types.h"

namespace delta {

/// Absorb `len` bytes of input and squeeze exactly DELTA_HASH_SIZE (16) bytes.
/// See file-level limitation note for extending to longer output.
std::array<uint8_t, DELTA_HASH_SIZE> shake128_16(const uint8_t* data, size_t len);

} // namespace delta
