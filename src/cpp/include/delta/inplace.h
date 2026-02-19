#pragma once

/// In-place delta conversion (Burns, Long, Stockmeyer — IEEE TKDE 2003).
///
/// A CRWI (Copy-Read/Write-Intersection) digraph is built over the copy
/// commands: edge i→j means copy i reads from a region that copy j will
/// overwrite, so i must execute before j.  When the digraph is acyclic, a
/// topological order provides a valid serial schedule with no conversion
/// needed.  A cycle i₁→i₂→…→iₖ→i₁ represents a circular dependency with
/// no valid schedule; breaking it materializes one copy as a literal add
/// (reading its bytes from R before the buffer is modified).
/// Kahn's topological sort + iterative-DFS cycle detection + per-cycle
/// minimum-length copy conversion.

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
