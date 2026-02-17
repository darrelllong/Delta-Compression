#pragma once

/// Differential compression algorithms (Ajtai et al. JACM 2002).

#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

#include "delta/types.h"

namespace delta {

/// Print shared verbose stats (result/copies summary) to stderr.
void print_command_stats(const std::vector<Command>& commands);

/// Greedy algorithm (Section 3.1, Figure 2).
///
/// Finds an optimal delta encoding under the simple cost measure
/// (optimality proof: Section 3.3, Theorem 1).
/// Time: O(|V| * |R|) worst case. Space: O(|R|).
std::vector<Command> diff_greedy(
    std::span<const uint8_t> r,
    std::span<const uint8_t> v,
    const DiffOptions& opts = {});

/// One-Pass algorithm (Section 4.1, Figure 3).
///
/// Scans R and V concurrently. Time: O(np + q), space: O(q).
/// Auto-sizes hash table to max(q, num_seeds/p).
std::vector<Command> diff_onepass(
    std::span<const uint8_t> r,
    std::span<const uint8_t> v,
    const DiffOptions& opts = {});

/// Correcting 1.5-Pass algorithm (Section 7, Figure 8) with
/// fingerprint-based checkpointing (Section 8).
///
/// Auto-sizes hash table to max(q, 2*num_seeds/p).
std::vector<Command> diff_correcting(
    std::span<const uint8_t> r,
    std::span<const uint8_t> v,
    const DiffOptions& opts = {});

/// Dispatcher: call the appropriate algorithm by enum.
std::vector<Command> diff(
    Algorithm algo,
    std::span<const uint8_t> r,
    std::span<const uint8_t> v,
    const DiffOptions& opts = {});

} // namespace delta
