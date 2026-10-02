#pragma once

/// In-place reconstruction (Burns, Long and Stockmeyer, "In-Place
/// Reconstruction of Version Differences", IEEE TKDE 15(4), 2003).
///
/// A delta applied in the buffer that holds R must not overwrite bytes that
/// a later copy still has to read.  Copy i must therefore run before copy j
/// whenever i reads what j writes.  These constraints form a digraph (the
/// CRWI digraph of the paper); a topological order of it is a safe schedule.
/// Where the constraints are circular there is none, and a copy on the cycle
/// is replaced by an add of the bytes it would have copied, which reads
/// nothing.  Adds run last.

#include <cstdint>
#include <span>
#include <vector>

#include "delta/types.h"

namespace delta {

/// Turns the output of a differencing algorithm into commands that rebuild V
/// in a buffer that initially holds R.  The result is deterministic: among
/// the copies that may run next, the shortest goes first, and of equals the
/// earliest in V.
std::vector<PlacedCommand> make_inplace(
    std::span<const uint8_t> r,
    const std::vector<Command>& commands,
    CyclePolicy policy);

} // namespace delta
