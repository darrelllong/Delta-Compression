#pragma once

/// Differential compression algorithms (Ajtai et al. JACM 2002).

#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

#include "delta/types.h"

namespace delta {

/// Greedy algorithm (Section 3.1, Figure 2).
///
/// Finds an optimal delta encoding under the simple cost measure
/// (optimality proof: Section 3.3, Theorem 1).
/// Time: O(|V| * |R|) worst case. Space: O(|R|).
///
/// q and verbose are accepted for API consistency but have no effect.
std::vector<Command> diff_greedy(
    std::span<const uint8_t> r,
    std::span<const uint8_t> v,
    size_t p = SEED_LEN,
    size_t q = TABLE_SIZE,
    bool verbose = false);

/// One-Pass algorithm (Section 4.1, Figure 3).
///
/// Scans R and V concurrently. Time: O(np + q), space: O(q).
/// Auto-sizes hash table to max(q, num_seeds/p).
std::vector<Command> diff_onepass(
    std::span<const uint8_t> r,
    std::span<const uint8_t> v,
    size_t p = SEED_LEN,
    size_t q = TABLE_SIZE,
    bool verbose = false);

/// Correcting 1.5-Pass algorithm (Section 7, Figure 8) with
/// fingerprint-based checkpointing (Section 8).
///
/// Auto-sizes hash table to max(q, 2*num_seeds/p).
std::vector<Command> diff_correcting(
    std::span<const uint8_t> r,
    std::span<const uint8_t> v,
    size_t p = SEED_LEN,
    size_t q = TABLE_SIZE,
    size_t buf_cap = 256,
    bool verbose = false);

/// Dispatcher: call the appropriate algorithm by enum.
std::vector<Command> diff(
    Algorithm algo,
    std::span<const uint8_t> r,
    std::span<const uint8_t> v,
    size_t p = SEED_LEN,
    size_t q = TABLE_SIZE,
    bool verbose = false);

} // namespace delta
