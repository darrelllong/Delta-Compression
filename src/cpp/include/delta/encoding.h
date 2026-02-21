#pragma once

/// Unified binary delta format encode/decode.
///
/// Format:
///   Header: magic (4 bytes) + flags (1 byte) + version_size (u32 BE)
///           + src_hash (16 bytes) + dst_hash (16 bytes)
///   Commands:
///     END:  type=0
///     COPY: type=1, src:u32, dst:u32, len:u32
///     ADD:  type=2, dst:u32, len:u32, data

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <tuple>
#include <vector>

#include "delta/types.h"

namespace delta {

/// Encode placed commands to the unified binary delta format.
std::vector<uint8_t> encode_delta(
    const std::vector<PlacedCommand>& commands,
    bool inplace,
    size_t version_size,
    const std::array<uint8_t, DELTA_CRC_SIZE>& src_crc,
    const std::array<uint8_t, DELTA_CRC_SIZE>& dst_crc);

/// Decode the unified binary delta format.
/// Returns (commands, inplace, version_size, src_crc, dst_crc).
/// CRC validation is the caller's responsibility.
std::tuple<std::vector<PlacedCommand>, bool, size_t,
           std::array<uint8_t, DELTA_CRC_SIZE>,
           std::array<uint8_t, DELTA_CRC_SIZE>> decode_delta(
    std::span<const uint8_t> data);

/// Check if binary data is an in-place delta.
bool is_inplace_delta(std::span<const uint8_t> data);

} // namespace delta
