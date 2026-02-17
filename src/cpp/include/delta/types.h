#pragma once

#include <cstddef>
#include <cstdint>
#include <span>
#include <stdexcept>
#include <string>
#include <variant>
#include <vector>

namespace delta {

// ============================================================================
// Constants (Ajtai, Burns, Fagin, Long — JACM 2002)
//
// Hash parameters (Section 2.1.3):
//   p (SEED_LEN)  = minimum match length / fingerprint window
//   b (HASH_BASE) = polynomial base for Karp-Rabin hash
//   Q (HASH_MOD)  = Mersenne prime 2^61-1 for fingerprint arithmetic
//   q (TABLE_SIZE) = hash table capacity; correcting uses checkpointing
//                    (Section 8) to fit any |R| into fixed-size table
// Delta commands: Section 2.1.1
// ============================================================================

inline constexpr size_t SEED_LEN = 16;
inline constexpr size_t TABLE_SIZE = 1048573; // largest prime < 2^20
inline constexpr uint64_t HASH_BASE = 263;
inline constexpr uint64_t HASH_MOD = (1ULL << 61) - 1; // Mersenne prime 2^61-1
inline constexpr uint8_t DELTA_MAGIC[4] = {'D', 'L', 'T', 0x01};
inline constexpr uint8_t DELTA_FLAG_INPLACE = 0x01;

// ============================================================================
// Delta Commands (Section 2.1.1)
// ============================================================================

struct CopyCmd {
    size_t offset;
    size_t length;
    bool operator==(const CopyCmd&) const = default;
};

struct AddCmd {
    std::vector<uint8_t> data;
    bool operator==(const AddCmd&) const = default;
};

/// Algorithm output: copy from reference or add literal bytes.
using Command = std::variant<CopyCmd, AddCmd>;

// ============================================================================
// Placed Commands — ready for encoding and application
// ============================================================================

struct PlacedCopy {
    size_t src;
    size_t dst;
    size_t length;
    bool operator==(const PlacedCopy&) const = default;
};

struct PlacedAdd {
    size_t dst;
    std::vector<uint8_t> data;
    bool operator==(const PlacedAdd&) const = default;
};

/// A command with explicit source and destination offsets.
using PlacedCommand = std::variant<PlacedCopy, PlacedAdd>;

// ============================================================================
// Algorithm and Policy enums
// ============================================================================

enum class Algorithm { Greedy, Onepass, Correcting };

enum class CyclePolicy { Localmin, Constant };

// ============================================================================
// Error type
// ============================================================================

class DeltaError : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

// ============================================================================
// Summary statistics
// ============================================================================

struct DeltaSummary {
    size_t num_commands;
    size_t num_copies;
    size_t num_adds;
    size_t copy_bytes;
    size_t add_bytes;
    size_t total_output_bytes;
};

DeltaSummary delta_summary(const std::vector<Command>& commands);
DeltaSummary placed_summary(const std::vector<PlacedCommand>& commands);

// ============================================================================
// Diff options — replaces positional parameter lists
// ============================================================================

struct DiffOptions {
    size_t p = SEED_LEN;
    size_t q = TABLE_SIZE;
    size_t buf_cap = 256;
    bool verbose = false;
    bool use_splay = false;
    size_t min_copy = 0;
};

} // namespace delta
