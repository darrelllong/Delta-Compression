#pragma once

/// Placing commands at output offsets, and carrying them out.

#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

#include "delta/types.h"

namespace delta {

/// The number of bytes the commands write.
size_t output_size(const std::vector<Command>& commands);

/// Gives each command its destination: the commands write the output front
/// to back, in order.
std::vector<PlacedCommand> place_commands(const std::vector<Command>& commands);

/// The inverse of place_commands: the commands in order of destination,
/// without the destinations.  Throws DeltaError if there is a PlacedMove,
/// which has no unplaced form.
std::vector<Command> unplace_commands(const std::vector<PlacedCommand>& placed);

/// Throws DeltaError unless every command stays within its bounds: writes
/// within version_size, and reads within reference_size, or for an in-place
/// delta within the working buffer of max(reference_size, version_size)
/// bytes.  The apply functions below do not check; call this first on
/// commands from an untrusted delta.
void validate_placed_commands(
    const std::vector<PlacedCommand>& commands,
    size_t reference_size,
    size_t version_size,
    bool inplace);

/// Carries out a standard delta: reads from r, writes to out, which must not
/// overlap r.  Returns one past the highest offset written.
size_t apply_placed_to(
    std::span<const uint8_t> r,
    const std::vector<PlacedCommand>& commands,
    std::span<uint8_t> out);

/// Carries out an in-place delta in buf, which holds R on entry and must be
/// at least as long as the longer of R and V.
void apply_placed_inplace_to(
    const std::vector<PlacedCommand>& commands,
    std::span<uint8_t> buf);

/// Returns V, given R and the commands of a differencing algorithm.
std::vector<uint8_t> apply_delta(
    std::span<const uint8_t> r,
    const std::vector<Command>& commands);

/// Returns V, given R and the commands of an in-place delta.
std::vector<uint8_t> apply_delta_inplace(
    std::span<const uint8_t> r,
    const std::vector<PlacedCommand>& commands,
    size_t version_size);

} // namespace delta
