#pragma once

/// Unified binary delta format encode/decode.
///
/// Format:
///   Header: magic (4 bytes) + flags (1 byte) + version_size (u32 BE)
///   Commands:
///     END:  type=0
///     COPY: type=1, src:u32, dst:u32, len:u32
///     ADD:  type=2, dst:u32, len:u32, data

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
    size_t version_size);

/// Decode the unified binary delta format.
/// Returns (commands, inplace, version_size).
std::tuple<std::vector<PlacedCommand>, bool, size_t> decode_delta(
    std::span<const uint8_t> data);

/// Check if binary data is an in-place delta.
bool is_inplace_delta(std::span<const uint8_t> data);

} // namespace delta
