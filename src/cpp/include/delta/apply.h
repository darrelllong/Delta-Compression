#pragma once

/// Command placement and application.

#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

#include "delta/types.h"

namespace delta {

/// Compute the total output size of algorithm commands.
size_t output_size(const std::vector<Command>& commands);

/// Convert algorithm output to placed commands with sequential destinations.
std::vector<PlacedCommand> place_commands(const std::vector<Command>& commands);

/// Apply placed commands in standard mode: read from R, write to out.
/// Returns the number of bytes written.
size_t apply_placed_to(
    std::span<const uint8_t> r,
    const std::vector<PlacedCommand>& commands,
    std::span<uint8_t> out);

/// Apply placed commands in-place within a single buffer.
/// Uses memmove so overlapping src/dst is safe.
void apply_placed_inplace_to(
    const std::vector<PlacedCommand>& commands,
    std::span<uint8_t> buf);

/// Reconstruct the version from reference + algorithm commands.
std::vector<uint8_t> apply_delta(
    std::span<const uint8_t> r,
    const std::vector<Command>& commands);

/// Apply placed in-place commands to a buffer initialized with R.
std::vector<uint8_t> apply_delta_inplace(
    std::span<const uint8_t> r,
    const std::vector<PlacedCommand>& commands,
    size_t version_size);

} // namespace delta
