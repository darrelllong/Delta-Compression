#pragma once

/// Binary delta format encode/decode (DLT\x03 small and DLT\x04 large).
///
/// DLT\x03 (small) — 25-byte header, u32 fields, COPY/ADD only.
///   magic(4)+flags(1)+version_size(u32 BE)+src_crc(8)+dst_crc(8)
///   Commands: END=0  COPY=1(src:u32,dst:u32,len:u32)  ADD=2(dst:u32,len:u32,data)
///
/// DLT\x04 (large) — 29-byte header, u64 fields, adds BIGCOPY/BIGADD/MOVE/BIGMOVE.
///   magic(4)+flags(1)+version_size(u64 BE)+src_crc(8)+dst_crc(8)
///   Commands: additionally BIGCOPY=3, BIGADD=4, MOVE=5, BIGMOVE=6

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <tuple>
#include <vector>

#include "delta/types.h"

namespace delta {

/// Encode placed commands to DLT\x03 format (u32 fields, max 4 GiB).
/// Throws DeltaError if any field exceeds UINT32_MAX or if a PlacedMove is present.
/// Use encode_delta_large for DLT\x04 (u64 fields, PlacedMove commands).
std::vector<uint8_t> encode_delta(
    const std::vector<PlacedCommand>& commands,
    bool inplace,
    size_t version_size,
    const std::array<uint8_t, DELTA_CRC_SIZE>& src_crc,
    const std::array<uint8_t, DELTA_CRC_SIZE>& dst_crc);

/// Encode placed commands to DLT\x04 format (u64 fields, PlacedMove support).
/// Per-command size selection: small (u32) or big (u64) fields based on value.
/// When force_large is true the 64-bit variant is always emitted.
std::vector<uint8_t> encode_delta_large(
    const std::vector<PlacedCommand>& commands,
    bool inplace,
    size_t version_size,
    const std::array<uint8_t, DELTA_CRC_SIZE>& src_crc,
    const std::array<uint8_t, DELTA_CRC_SIZE>& dst_crc,
    bool force_large = false);

/// Decode DLT\x03 or DLT\x04 format. Dispatches on magic bytes.
/// Returns (commands, inplace, version_size, src_crc, dst_crc).
/// CRC validation is the caller's responsibility.
std::tuple<std::vector<PlacedCommand>, bool, size_t,
           std::array<uint8_t, DELTA_CRC_SIZE>,
           std::array<uint8_t, DELTA_CRC_SIZE>> decode_delta(
    std::span<const uint8_t> data);

/// Check if binary data is an in-place delta (DLT\x03 or DLT\x04).
bool is_inplace_delta(std::span<const uint8_t> data);

} // namespace delta
