#pragma once

/// In-place delta conversion (Burns, Long, Stockmeyer — IEEE TKDE 2003).
///
/// CRWI digraph + topological sort + cycle breaking.

#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

#include "delta/types.h"

namespace delta {

/// Convert standard delta commands to in-place executable commands.
///
/// The returned commands can be applied to a buffer initialized with R
/// to reconstruct V in-place, without a separate output buffer.
std::vector<PlacedCommand> make_inplace(
    std::span<const uint8_t> r,
    const std::vector<Command>& commands,
    CyclePolicy policy);

} // namespace delta
