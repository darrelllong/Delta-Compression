#include "delta/algorithm.h"
#include "delta/splay.h"
#include "scan.h"

#include <algorithm>
#include <cstdio>
#include <deque>
#include <optional>

namespace delta {

namespace {

/// Checkpointing (Section 8.1): fingerprints are reduced to the range
/// [0, f_size), and only those in one residue class mod m, the checkpoints,
/// are stored or looked up.  A checkpoint f has table slot f / m, so the
/// table needs f_size / m slots however long R is.
struct Checkpoints {
    size_t cap;      ///< |C|, the table capacity.
    uint64_t f_size; ///< |F|.
    uint64_t m;      ///< ceil(|F| / |C|).
    uint64_t k;      ///< The residue class that passes.

    Checkpoints(std::span<const uint8_t> r, std::span<const uint8_t> v,
                const DiffOptions& opts) {
        const size_t p = opts.p;
        const size_t seeds = detail::seed_count(r.size(), p);
        cap = next_prime(std::min(opts.max_table, std::max(opts.q, 2 * seeds / p)));
        f_size = seeds > 0 ? next_prime(2 * seeds) : 1;
        m = (f_size + cap - 1) / cap;
        // k is taken from a seed in the middle of V, so that at least that
        // seed is a checkpoint and, if V resembles R, so are its copies in R
        // (p. 348).
        k = 0;
        if (v.size() >= p) {
            size_t mid = std::min(v.size() / 2, v.size() - p);
            k = fingerprint(v, mid, p) % f_size % m;
        }
    }
};

/// The checkpoint seeds of R: fingerprint to offset.  Of several seeds with
/// one fingerprint the first stored is kept.
class SeedTable {
public:
    SeedTable(size_t cap, bool use_splay) : use_splay_(use_splay) {
        if (!use_splay) { slots_.assign(cap, Slot{}); }
    }

    /// Stores offset under fp unless fp is present or the table is full.
    /// home is the slot where probing starts.
    void insert(uint64_t fp, size_t home, size_t offset) {
        if (use_splay_) {
            const size_t before = tree_.size();
            tree_.insert_or_get(fp, offset);
            if (tree_.size() > before) {
                ++stored_;
            } else {
                ++extra_probes_;
            }
            return;
        }
        size_t i = home;
        while (slots_[i].fp != EMPTY) {
            if (slots_[i].fp == fp) { return; }
            if (++i == slots_.size()) { i = 0; }
            ++extra_probes_;
            if (i == home) { return; }
        }
        slots_[i] = Slot{fp, offset};
        ++stored_;
    }

    /// The offset stored under fp.  Whole fingerprints are compared, so a hit
    /// can be wrong only if two different seeds have the same fingerprint.
    std::optional<size_t> find(uint64_t fp, size_t home) {
        if (use_splay_) {
            const size_t* offset = tree_.find(fp);
            return offset ? std::optional<size_t>(*offset) : std::nullopt;
        }
        size_t i = home;
        while (slots_[i].fp != EMPTY) {
            if (slots_[i].fp == fp) { return slots_[i].offset; }
            if (++i == slots_.size()) { i = 0; }
            if (i == home) { break; }
        }
        return std::nullopt;
    }

    size_t stored() const { return stored_; }

    /// Slots examined beyond the first by insert; for the splay tree, the
    /// number of seeds rejected as duplicates.
    size_t extra_probes() const { return extra_probes_; }

private:
    static constexpr uint64_t EMPTY = UINT64_MAX; // not a fingerprint: those are below 2^61

    struct Slot {
        uint64_t fp = EMPTY;
        size_t offset = 0;
    };

    bool use_splay_;
    std::vector<Slot> slots_;
    SplayTree<size_t> tree_;
    size_t stored_ = 0;
    size_t extra_probes_ = 0;
};

/// The most recent commands, held back so that a match that extends
/// backwards over them can still replace them (Sections 5.1 and 5.2).
/// Older commands are final and go to the output.
class Lookback {
public:
    Lookback(std::span<const uint8_t> v, size_t capacity, std::vector<Command>& out)
        : v_(v), capacity_(capacity), out_(out) {}

    void push_add(size_t v_begin, size_t v_end) { push({v_begin, v_end, false, 0}); }

    void push_copy(size_t v_begin, size_t v_end, size_t r_offset) {
        push({v_begin, v_end, true, r_offset});
    }

    /// Makes way for a match that begins at v_m, before the end of the last
    /// pending command.  Commands that begin at or after v_m are dropped, and
    /// an add that straddles v_m is cut off there.  A copy that straddles v_m
    /// is left whole.  Returns the position from which V is then unencoded,
    /// given that it was encoded up to v_s before.
    size_t reclaim(size_t v_m, size_t v_s) {
        size_t start = v_s;
        while (!pending_.empty()) {
            Pending& last = pending_.back();
            if (last.v_begin >= v_m) {
                start = last.v_begin;
                pending_.pop_back();
                continue;
            }
            if (last.v_end > v_m && !last.is_copy) {
                last.v_end = v_m;
                start = v_m;
            }
            break;
        }
        return start;
    }

    void flush() {
        for (const Pending& c : pending_) { emit(c); }
        pending_.clear();
    }

private:
    /// Encodes V[v_begin, v_end): as a copy from R at r_offset, or as an add.
    struct Pending {
        size_t v_begin;
        size_t v_end;
        bool is_copy;
        size_t r_offset;
    };

    void push(Pending c) {
        if (pending_.size() >= capacity_ && !pending_.empty()) {
            emit(pending_.front());
            pending_.pop_front();
        }
        pending_.push_back(c);
    }

    void emit(const Pending& c) {
        if (c.is_copy) {
            out_.emplace_back(CopyCmd{c.r_offset, c.v_end - c.v_begin});
        } else {
            detail::append_add(out_, v_, c.v_begin, c.v_end);
        }
    }

    std::span<const uint8_t> v_;
    size_t capacity_;
    std::vector<Command>& out_;
    std::deque<Pending> pending_;
};

} // namespace

std::vector<Command> diff_correcting(
    std::span<const uint8_t> r,
    std::span<const uint8_t> v,
    const DiffOptions& opts) {

    using namespace detail;
    using ull = unsigned long long;

    std::vector<Command> commands;
    if (v.empty()) { return commands; }
    const size_t p = opts.p;
    const size_t r_seed_count = seed_count(r.size(), p);
    const Checkpoints cp(r, v, opts);

    if (opts.verbose) {
        uint64_t expected = r_seed_count / cp.m;
        std::fprintf(stderr,
            "correcting: %s, |C|=%zu |F|=%llu m=%llu k=%llu\n"
            "  checkpoint gap=%llu bytes, expected fill ~%llu (~%llu%% table occupancy)\n"
            "  table memory ~%zu MB\n",
            lookup_name(opts), cp.cap, ull{cp.f_size}, ull{cp.m}, ull{cp.k},
            ull{cp.m}, ull{expected}, ull{expected * 100 / cp.cap},
            cp.cap * 16 / 1048576);
    }

    SeedTable table(cp.cap, opts.use_splay);
    size_t build_passed = 0;
    SeedScanner r_seeds(r, p);
    for (size_t a = 0; a < r_seed_count; ++a) {
        const uint64_t fp = r_seeds.at(a);
        const uint64_t f = fp % cp.f_size;
        if (f % cp.m != cp.k) { continue; }
        ++build_passed;
        table.insert(fp, f / cp.m, a);
    }

    if (opts.verbose) {
        std::fprintf(stderr,
            "  build: %zu seeds, %zu passed checkpoint (%.2f%%), "
            "%zu stored, %zu extra probes\n"
            "  build: table occupancy %zu/%zu (%.1f%%)\n",
            r_seed_count, build_passed, percent(build_passed, r_seed_count),
            table.stored(), table.extra_probes(),
            table.stored(), cp.cap, percent(table.stored(), cp.cap));
    }

    Lookback lookback(v, opts.buf_cap, commands);
    SeedScanner v_seeds(v, p);
    size_t v_c = 0; // the seed being looked up
    size_t v_s = 0; // start of the part of V not yet encoded
    size_t checkpoints = 0, matches = 0, byte_mismatches = 0;

    while (v_c + p <= v.size()) {
        const uint64_t fp = v_seeds.at(v_c);
        const uint64_t f = fp % cp.f_size;
        if (f % cp.m != cp.k) {
            ++v_c;
            continue;
        }
        ++checkpoints;

        const auto r_hit = table.find(fp, f / cp.m);
        if (!r_hit) {
            ++v_c;
            continue;
        }
        if (!seeds_equal(r, *r_hit, v, v_c, p)) {
            ++byte_mismatches;
            ++v_c;
            continue;
        }
        ++matches;

        // The match is the seed extended as far as it goes in both
        // directions: V[v_m, match_end) equals R[r_m, ...).
        const size_t fwd = extend_forward(r, *r_hit, v, v_c, p);
        size_t bwd = 0;
        while (bwd < v_c && bwd < *r_hit && v[v_c - bwd - 1] == r[*r_hit - bwd - 1]) {
            ++bwd;
        }
        const size_t v_m = v_c - bwd;
        const size_t r_m = *r_hit - bwd;
        const size_t match_end = v_c + fwd;

        if (v_s <= v_m) {
            // The match lies wholly in the unencoded part of V.
            if (v_s < v_m) { lookback.push_add(v_s, v_m); }
            lookback.push_copy(v_m, match_end, r_m);
        } else {
            // The match reaches back into the encoded part: tail correction
            // (Section 5.1, p. 339).  Take back what the match covers and
            // copy from wherever that leaves off.
            const size_t start = lookback.reclaim(v_m, v_s);
            lookback.push_copy(start, match_end, r_m + (start - v_m));
        }
        v_c = v_s = match_end;
    }

    lookback.flush();
    append_add(commands, v, v_s, v.size());

    if (opts.verbose) {
        const size_t v_seed_count = seed_count(v.size(), p);
        // A hit in the table compares whole fingerprints, so there are no
        // collisions between table slots to count; the field is always 0.
        std::fprintf(stderr,
            "  scan: %zu V positions, %zu checkpoints (%.3f%%), %zu matches\n"
            "  scan: hit rate %.1f%% (of checkpoints), "
            "fp collisions %zu, byte mismatches %zu\n",
            v_seed_count, checkpoints, percent(checkpoints, v_seed_count), matches,
            percent(matches, checkpoints), size_t{0}, byte_mismatches);
        print_command_stats(commands);
    }
    return commands;
}

} // namespace delta
