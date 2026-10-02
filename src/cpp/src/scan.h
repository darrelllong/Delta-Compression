#pragma once

// Pieces shared by the three differencing algorithms.  Not installed.

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <span>
#include <vector>

#include "delta/hash.h"
#include "delta/types.h"

namespace delta::detail {

/// The number of seeds, that is p-byte substrings, of a string.
inline size_t seed_count(size_t length, size_t p) {
    return length >= p ? length - p + 1 : 0;
}

/// Fingerprints of the seeds of one string, for a scan that usually moves
/// forward one byte at a time.
class SeedScanner {
public:
    SeedScanner(std::span<const uint8_t> data, size_t p)
        : data_(data), p_(p), bp_(precompute_bp(p)) {}

    /// The fingerprint of the seed at pos, which must lie within the data.
    /// O(1) if pos is where the previous call was or one past it, else O(p).
    uint64_t at(size_t pos) {
        if (pos != pos_) {
            fp_ = pos == pos_ + 1
                ? roll_fingerprint(fp_, bp_, data_[pos - 1], data_[pos + p_ - 1])
                : fingerprint(data_, pos, p_);
            pos_ = pos;
        }
        return fp_;
    }

private:
    std::span<const uint8_t> data_;
    size_t p_;
    uint64_t bp_;
    uint64_t fp_ = 0;
    // Before the first call: a value that no seed position equals or follows.
    size_t pos_ = SIZE_MAX - 1;
};

/// Reports whether the p-byte seeds at r[r_pos] and v[v_pos] are equal.
/// Equal fingerprints do not guarantee it.
inline bool seeds_equal(std::span<const uint8_t> r, size_t r_pos,
                        std::span<const uint8_t> v, size_t v_pos, size_t p) {
    return std::memcmp(&r[r_pos], &v[v_pos], p) == 0;
}

/// The length of the longest common prefix of r[r_pos..] and v[v_pos..],
/// given that the first len bytes are already known to match.
inline size_t extend_forward(std::span<const uint8_t> r, size_t r_pos,
                             std::span<const uint8_t> v, size_t v_pos, size_t len) {
    while (r_pos + len < r.size() && v_pos + len < v.size()
           && r[r_pos + len] == v[v_pos + len]) {
        ++len;
    }
    return len;
}

/// Appends an add of v[begin, end), if that range is not empty.
inline void append_add(std::vector<Command>& commands, std::span<const uint8_t> v,
                       size_t begin, size_t end) {
    if (begin < end) {
        commands.emplace_back(AddCmd{{v.begin() + begin, v.begin() + end}});
    }
}

inline const char* lookup_name(const DiffOptions& opts) {
    return opts.use_splay ? "splay tree" : "hash table";
}

inline double percent(size_t part, size_t whole) {
    return whole > 0 ? static_cast<double>(part) / whole * 100.0 : 0.0;
}

} // namespace delta::detail
