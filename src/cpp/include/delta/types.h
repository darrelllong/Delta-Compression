#pragma once

/// Commands, constants and options shared by the whole library.
///
/// Section numbers refer to Ajtai, Burns, Fagin, Long and Stockmeyer,
/// "Compactly Encoding Unstructured Inputs with Differential Compression",
/// JACM 49(3), 2002, unless another paper is named.

#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <variant>
#include <vector>

namespace delta {

// Fingerprinting (Section 2.1.3).
inline constexpr size_t SEED_LEN = 16;                 // p: fingerprint window and minimum match length
inline constexpr uint64_t HASH_BASE = 263;             // b: polynomial base
inline constexpr uint64_t HASH_MOD = (1ULL << 61) - 1; // the Mersenne prime 2^61-1
inline constexpr size_t TABLE_SIZE = 1048573;          // q: default table floor, the largest prime below 2^20
inline constexpr size_t MAX_TABLE_SIZE = 1073741827;   // default table ceiling, a prime near 2^30
inline constexpr size_t DELTA_BUF_CAP = 256;           // default depth of the correcting lookback buffer

// Wire format.  DLT\x03 has u32 fields and COPY and ADD only; DLT\x04 has a
// u64 version size and adds the BIG and MOVE commands.
inline constexpr uint8_t DELTA_MAGIC[4]       = {'D', 'L', 'T', 0x03};
inline constexpr uint8_t DELTA_MAGIC_LARGE[4] = {'D', 'L', 'T', 0x04};
inline constexpr size_t  DELTA_MAGIC_SIZE = sizeof(DELTA_MAGIC);
inline constexpr uint8_t DELTA_FLAG_INPLACE = 0x01;
inline constexpr uint8_t DELTA_CMD_END     = 0;
inline constexpr uint8_t DELTA_CMD_COPY    = 1;
inline constexpr uint8_t DELTA_CMD_ADD     = 2;
inline constexpr uint8_t DELTA_CMD_BIGCOPY = 3; // COPY with u64 fields
inline constexpr uint8_t DELTA_CMD_BIGADD  = 4; // ADD with u64 dst and length
inline constexpr uint8_t DELTA_CMD_MOVE    = 5; // copy within the output, u32 fields
inline constexpr uint8_t DELTA_CMD_BIGMOVE = 6; // MOVE with u64 fields
inline constexpr size_t  DELTA_CRC_SIZE = 8;
inline constexpr size_t  DELTA_HEADER_SIZE       = 25; // magic(4) flags(1) version_size(4) src_crc(8) dst_crc(8)
inline constexpr size_t  DELTA_HEADER_SIZE_LARGE = 29; // the same with version_size(8)
inline constexpr size_t  DELTA_U32_SIZE = 4;
inline constexpr size_t  DELTA_U64_SIZE = 8;
inline constexpr size_t  DELTA_COPY_PAYLOAD    = 12; // src(4) dst(4) len(4)
inline constexpr size_t  DELTA_ADD_HEADER      = 8;  // dst(4) len(4)
inline constexpr size_t  DELTA_BIGCOPY_PAYLOAD = 24; // src(8) dst(8) len(8)
inline constexpr size_t  DELTA_BIGADD_HEADER   = 16; // dst(8) len(8)

/// Copy R[offset, offset+length) to the output (Section 2.1.1).
struct CopyCmd {
    size_t offset;
    size_t length;
    bool operator==(const CopyCmd&) const = default;
};

/// Append literal bytes to the output (Section 2.1.1).
struct AddCmd {
    std::vector<uint8_t> data;
    bool operator==(const AddCmd&) const = default;
};

/// What the differencing algorithms produce: a sequence of commands that
/// writes V front to back.
using Command = std::variant<CopyCmd, AddCmd>;

/// Copy length bytes from src to dst.  In a standard delta src is an offset
/// in R; in an in-place delta it is an offset in the buffer being rewritten.
struct PlacedCopy {
    size_t src;
    size_t dst;
    size_t length;
    bool operator==(const PlacedCopy&) const = default;
};

/// Write literal bytes at dst.
struct PlacedAdd {
    size_t dst;
    std::vector<uint8_t> data;
    bool operator==(const PlacedAdd&) const = default;
};

/// Copy length bytes from src to dst, both offsets in the output.  The source
/// must already be written and must not overlap the destination:
/// src + length <= dst.  DLT\x04 only.
struct PlacedMove {
    size_t src;
    size_t dst;
    size_t length;
    bool operator==(const PlacedMove&) const = default;
};

/// A command that names its destination, so that commands can be reordered.
using PlacedCommand = std::variant<PlacedCopy, PlacedAdd, PlacedMove>;

enum class Algorithm {
    Greedy,     ///< Optimal; O(|V||R|) time, O(|R|) space (Section 3).
    Onepass,    ///< Linear time, constant space; misses transpositions (Section 4).
    Correcting, ///< 1.5 passes with checkpointing; finds transpositions (Sections 7-8).
};

/// How make_inplace chooses the copy to convert to an add when copies depend
/// on one another in a cycle (Burns, Long and Stockmeyer, TKDE 2003,
/// Section 4.3).
enum class CyclePolicy {
    Localmin, ///< The shortest copy on a cycle, which adds the fewest literal bytes.
    Constant, ///< The first copy not yet scheduled; no cycle is searched for.
};

/// Thrown for a malformed delta and for values the format cannot hold.
class DeltaError : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

struct DeltaSummary {
    size_t num_commands;
    size_t num_copies;         ///< Moves count as copies.
    size_t num_adds;
    size_t copy_bytes;
    size_t add_bytes;
    size_t total_output_bytes; ///< copy_bytes + add_bytes.
};

DeltaSummary delta_summary(const std::vector<Command>& commands);
DeltaSummary placed_summary(const std::vector<PlacedCommand>& commands);

struct DiffOptions {
    size_t p = SEED_LEN;       ///< Seed length; must be at least 1.
    size_t q = TABLE_SIZE;     ///< Table size floor; onepass and correcting grow the table with |R|.
    size_t buf_cap = DELTA_BUF_CAP; ///< Commands the correcting algorithm can still revise (Section 5.2).
    bool verbose = false;      ///< Print statistics to stderr.
    bool use_splay = false;    ///< Look fingerprints up in a splay tree instead of a hash table.
    size_t max_table = MAX_TABLE_SIZE; ///< Table size ceiling for correcting.
};

} // namespace delta
