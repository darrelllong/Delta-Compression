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
inline constexpr size_t TABLE_SIZE = 1048573;       // largest prime < 2^20
inline constexpr size_t MAX_TABLE_SIZE = 1073741827; // prime near 2^30; default ceiling for auto-sizing
inline constexpr uint64_t HASH_BASE = 263;
inline constexpr uint64_t HASH_MOD = (1ULL << 61) - 1; // Mersenne prime 2^61-1
inline constexpr uint8_t DELTA_MAGIC[4]       = {'D', 'L', 'T', 0x03};
inline constexpr uint8_t DELTA_MAGIC_LARGE[4] = {'D', 'L', 'T', 0x04};
inline constexpr size_t  DELTA_MAGIC_SIZE = sizeof(DELTA_MAGIC);
inline constexpr uint8_t DELTA_FLAG_INPLACE = 0x01;
inline constexpr uint8_t DELTA_CMD_END     = 0;
inline constexpr uint8_t DELTA_CMD_COPY    = 1;
inline constexpr uint8_t DELTA_CMD_ADD     = 2;
inline constexpr uint8_t DELTA_CMD_BIGCOPY = 3; // DLT\x04: COPY with u64 fields
inline constexpr uint8_t DELTA_CMD_BIGADD  = 4; // DLT\x04: ADD with u64 dst/len header
inline constexpr uint8_t DELTA_CMD_MOVE    = 5; // DLT\x04: copy from already-written output (u32 fields)
inline constexpr uint8_t DELTA_CMD_BIGMOVE = 6; // DLT\x04: MOVE with u64 fields
inline constexpr size_t  DELTA_CRC_SIZE = 8;          // CRC-64/XZ digest
inline constexpr size_t  DELTA_HEADER_SIZE       = 25; // magic(4)+flags(1)+version_size(4)+src_crc(8)+dst_crc(8)
inline constexpr size_t  DELTA_HEADER_SIZE_LARGE = 29; // magic(4)+flags(1)+version_size(8)+src_crc(8)+dst_crc(8)
inline constexpr size_t  DELTA_U32_SIZE = 4;
inline constexpr size_t  DELTA_U64_SIZE = 8;
inline constexpr size_t  DELTA_COPY_PAYLOAD    = 12; // src(4)+dst(4)+len(4)
inline constexpr size_t  DELTA_ADD_HEADER      = 8;  // dst(4)+len(4)
inline constexpr size_t  DELTA_BIGCOPY_PAYLOAD = 24; // src(8)+dst(8)+len(8)
inline constexpr size_t  DELTA_BIGADD_HEADER   = 16; // dst(8)+len(8)
inline constexpr size_t  DELTA_BUF_CAP = 256;

// ============================================================================
// Delta Commands (Section 2.1.1)
// ============================================================================

/// Copy @p length bytes starting at @p offset in the reference R.
struct CopyCmd {
    size_t offset; ///< Byte offset of the match in R.
    size_t length; ///< Number of bytes to copy.
    bool operator==(const CopyCmd&) const = default;
};

/// Append literal bytes from V that could not be matched in R.
struct AddCmd {
    std::vector<uint8_t> data; ///< The literal bytes to append.
    bool operator==(const AddCmd&) const = default;
};

/// Algorithm output: copy from reference or add literal bytes.
using Command = std::variant<CopyCmd, AddCmd>;

// ============================================================================
// Placed Commands — ready for encoding and application
// ============================================================================

/// Copy @p length bytes from @p src in R (or the working buffer) to @p dst in the output.
struct PlacedCopy {
    size_t src;    ///< Source byte offset in the reference (or working buffer for in-place).
    size_t dst;    ///< Destination byte offset in the output.
    size_t length; ///< Number of bytes to copy.
    bool operator==(const PlacedCopy&) const = default;
};

/// Write literal bytes to @p dst in the output.
struct PlacedAdd {
    size_t dst;               ///< Destination byte offset in the output.
    std::vector<uint8_t> data; ///< The literal bytes to write.
    bool operator==(const PlacedAdd&) const = default;
};

/// Copy @p length bytes from @p src in the already-written output to @p dst.
/// The encoder guarantees src+length <= dst (source is fully written before it is read).
/// Only valid in DLT\x04 format; use encode_delta_large to encode PlacedMove commands.
struct PlacedMove {
    size_t src;    ///< Source byte offset in the already-written output buffer.
    size_t dst;    ///< Destination byte offset in the output.
    size_t length; ///< Number of bytes to copy.
    bool operator==(const PlacedMove&) const = default;
};

/// A command with explicit source and destination offsets.
using PlacedCommand = std::variant<PlacedCopy, PlacedAdd, PlacedMove>;

// ============================================================================
// Algorithm and Policy enums
// ============================================================================

/// Differencing algorithm selection.
enum class Algorithm {
    Greedy,     ///< Optimal under simple cost; O(|V|·|R|) time, O(|R|) space (Section 3).
    Onepass,    ///< Linear time and near-constant space; concurrent scan of R and V (Section 4).
    Correcting, ///< Near-optimal, 1.5-pass; hash table with fingerprint checkpointing (Sections 7–8).
};

/// Cycle-breaking policy for in-place reordering (Section 4.3 of Burns et al. 2003).
enum class CyclePolicy {
    Localmin, ///< Break each cycle at the copy with the shortest length, minimising literal bytes added.
    Constant, ///< Break each cycle at the first remaining vertex; simpler but ignores copy lengths.
};

// ============================================================================
// Error type
// ============================================================================

/// Exception thrown for invalid delta format or I/O errors.
class DeltaError : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

// ============================================================================
// Summary statistics
// ============================================================================

/// Summary statistics for a set of commands.
struct DeltaSummary {
    size_t num_commands;       ///< Total number of commands (copies + adds).
    size_t num_copies;         ///< Number of COPY commands.
    size_t num_adds;           ///< Number of ADD commands.
    size_t copy_bytes;         ///< Total bytes reproduced by COPY commands.
    size_t add_bytes;          ///< Total literal bytes in ADD commands.
    size_t total_output_bytes; ///< Reconstructed output size (= copy_bytes + add_bytes).
};

/// Compute summary statistics from algorithm-level commands.
DeltaSummary delta_summary(const std::vector<Command>& commands);
/// Compute summary statistics from placed commands.
DeltaSummary placed_summary(const std::vector<PlacedCommand>& commands);

// ============================================================================
// Diff options — replaces positional parameter lists
// ============================================================================

/// Tuning parameters for differencing algorithms.
struct DiffOptions {
    size_t p = SEED_LEN;             ///< Seed length: minimum match length and fingerprint window (Section 2.1.3).
    size_t q = TABLE_SIZE;           ///< Hash table capacity floor; algorithms auto-size upward from input length.
    size_t buf_cap = DELTA_BUF_CAP;  ///< Lookback buffer depth for the correcting algorithm (Section 5.2).
    bool verbose = false;            ///< Print per-run statistics to stderr when true.
    bool use_splay = false;          ///< Use a Sleator-Tarjan splay tree instead of a hash table for R lookups.
    size_t max_table = MAX_TABLE_SIZE; ///< Auto-sizing ceiling; prevents unbounded memory use on very large inputs.
};

} // namespace delta
