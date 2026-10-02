#pragma once

/// The binary delta format.  All integers are big-endian.
///
///   header:   magic(4) flags(1) version_size src_crc(8) dst_crc(8)
///   commands: END     0
///             COPY    1 src dst len
///             ADD     2 dst len data
///             BIGCOPY 3 src dst len      u64 fields
///             BIGADD  4 dst len data     u64 fields
///             MOVE    5 src dst len
///             BIGMOVE 6 src dst len      u64 fields
///
/// DLT\x03 has a u32 version_size and only END, COPY and ADD.  DLT\x04 has a
/// u64 version_size and all seven.  Fields are u32 unless marked.  Bit 0 of
/// flags marks an in-place delta.  The CRCs are the CRC-64/XZ of the
/// reference and of the version.

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <tuple>
#include <vector>

#include "delta/types.h"

namespace delta {

/// Encodes as DLT\x03.  Throws DeltaError if a field does not fit in 32 bits
/// or if there is a PlacedMove.
std::vector<uint8_t> encode_delta(
    const std::vector<PlacedCommand>& commands,
    bool inplace,
    size_t version_size,
    const std::array<uint8_t, DELTA_CRC_SIZE>& src_crc,
    const std::array<uint8_t, DELTA_CRC_SIZE>& dst_crc);

/// Encodes as DLT\x04.  Each command takes its 32-bit form if its fields
/// fit, unless force_large asks for the 64-bit form throughout.
std::vector<uint8_t> encode_delta_large(
    const std::vector<PlacedCommand>& commands,
    bool inplace,
    size_t version_size,
    const std::array<uint8_t, DELTA_CRC_SIZE>& src_crc,
    const std::array<uint8_t, DELTA_CRC_SIZE>& dst_crc,
    bool force_large = false);

/// Decodes either format.  Returns (commands, inplace, version_size, src_crc,
/// dst_crc).  Throws DeltaError if the data is malformed or a command writes
/// outside the version.  Sources of copies are not checked, since the size
/// of the reference is not known here, and neither are the CRCs: see
/// validate_placed_commands and crc64_xz.
std::tuple<std::vector<PlacedCommand>, bool, size_t,
           std::array<uint8_t, DELTA_CRC_SIZE>,
           std::array<uint8_t, DELTA_CRC_SIZE>> decode_delta(
    std::span<const uint8_t> data);

/// Reports whether data begins with the header of an in-place delta.
bool is_inplace_delta(std::span<const uint8_t> data);

} // namespace delta
