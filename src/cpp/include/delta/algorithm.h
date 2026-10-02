#pragma once

/// The three differencing algorithms of Ajtai et al.  Each takes a reference
/// R and a version V and returns commands that build V from R; V itself may
/// then be discarded.  With opts.verbose they report statistics on stderr.

#include <cstdint>
#include <span>
#include <vector>

#include "delta/types.h"

namespace delta {

/// The greedy algorithm (Section 3.1, Figure 2): at each position of V take
/// the longest match anywhere in R.  The result is optimal under the simple
/// cost measure (Section 3.3, Theorem 1).  Time O(|V||R|) in the worst case,
/// space O(|R|).  opts.q is not used.
std::vector<Command> diff_greedy(
    std::span<const uint8_t> r,
    std::span<const uint8_t> v,
    const DiffOptions& opts = {});

/// The one-pass algorithm (Section 4.1, Figure 3): scan R and V together,
/// take the first match found and forget everything before it.  Linear time
/// and, for a fixed table size, constant space; blocks that appear in a
/// different order in V than in R are not found (Section 4.3).  The table has
/// max(q, |R|/p) slots, rounded up to a prime.
std::vector<Command> diff_onepass(
    std::span<const uint8_t> r,
    std::span<const uint8_t> v,
    const DiffOptions& opts = {});

/// The correcting 1.5-pass algorithm (Section 7, Figure 8) with checkpointing
/// (Section 8): fingerprint R once, then scan V, extending each match in both
/// directions and replacing recent commands that a longer match makes
/// redundant.  The table has max(q, 2|R|/p) slots, at most max_table, rounded
/// up to a prime.
std::vector<Command> diff_correcting(
    std::span<const uint8_t> r,
    std::span<const uint8_t> v,
    const DiffOptions& opts = {});

/// Runs the named algorithm.
std::vector<Command> diff(
    Algorithm algo,
    std::span<const uint8_t> r,
    std::span<const uint8_t> v,
    const DiffOptions& opts = {});

/// Prints the copy and add totals and the distribution of copy lengths to
/// stderr.  The algorithms call this when opts.verbose is set.
void print_command_stats(const std::vector<Command>& commands);

} // namespace delta
