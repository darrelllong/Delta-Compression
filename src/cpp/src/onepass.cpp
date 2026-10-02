#include "delta/algorithm.h"
#include "delta/splay.h"
#include "scan.h"

#include <algorithm>
#include <cstdio>
#include <optional>

namespace delta {

namespace {

/// One of the two tables of the one-pass algorithm: for each fingerprint, the
/// offset of a seed seen since the last match.
///
/// The algorithm empties both tables after every match.  Entries are stamped
/// with an epoch instead, and the caller advances the epoch, so emptying
/// costs nothing: an entry from an earlier epoch is as good as absent.
class EpochTable {
public:
    EpochTable(size_t q, bool use_splay) : use_splay_(use_splay) {
        if (!use_splay) { slots_.assign(q, Slot{}); }
    }

    /// Records the seed at offset, unless its place is already taken by an
    /// entry of this epoch: the first seed stored is retained.
    void put(uint64_t fp, size_t offset, uint64_t epoch) {
        if (use_splay_) {
            const Entry* e = tree_.find(fp);
            if (!e || e->epoch != epoch) { tree_.insert(fp, Entry{offset, epoch}); }
        } else {
            Slot& s = slots_[fp % slots_.size()];
            if (s.epoch != epoch) { s = Slot{fp, offset, epoch}; }
        }
    }

    /// The offset recorded in this epoch for a seed with fingerprint fp.
    std::optional<size_t> get(uint64_t fp, uint64_t epoch) {
        if (use_splay_) {
            const Entry* e = tree_.find(fp);
            if (e && e->epoch == epoch) { return e->offset; }
        } else {
            const Slot& s = slots_[fp % slots_.size()];
            if (s.epoch == epoch && s.fp == fp) { return s.offset; }
        }
        return std::nullopt;
    }

private:
    static constexpr uint64_t NEVER = UINT64_MAX; // epoch of an empty slot

    struct Slot {
        uint64_t fp = 0;
        size_t offset = 0;
        uint64_t epoch = NEVER;
    };
    struct Entry {
        size_t offset;
        uint64_t epoch;
    };

    bool use_splay_;
    std::vector<Slot> slots_;
    SplayTree<Entry> tree_;
};

} // namespace

std::vector<Command> diff_onepass(
    std::span<const uint8_t> r,
    std::span<const uint8_t> v,
    const DiffOptions& opts) {

    using namespace detail;

    std::vector<Command> commands;
    if (v.empty()) { return commands; }
    const size_t p = opts.p;
    const size_t q = next_prime(std::max(opts.q, seed_count(r.size(), p) / p));

    if (opts.verbose) {
        std::fprintf(stderr,
            "onepass: %s, q=%zu, |R|=%zu, |V|=%zu, seed_len=%zu\n",
            lookup_name(opts), q, r.size(), v.size(), p);
    }

    EpochTable seen_v(q, opts.use_splay);
    EpochTable seen_r(q, opts.use_splay);
    SeedScanner v_seeds(v, p);
    SeedScanner r_seeds(r, p);
    uint64_t epoch = 0;
    size_t r_c = 0; // the seeds being examined, in R and in V
    size_t v_c = 0;
    size_t v_s = 0; // start of the part of V not yet encoded
    size_t positions = 0, lookups = 0, matches = 0;

    while (v_c + p <= v.size() || r_c + p <= r.size()) {
        ++positions;
        const bool have_v = v_c + p <= v.size();
        const bool have_r = r_c + p <= r.size();
        uint64_t fp_v = 0, fp_r = 0;
        if (have_v) {
            fp_v = v_seeds.at(v_c);
            seen_v.put(fp_v, v_c, epoch);
        }
        if (have_r) {
            fp_r = r_seeds.at(r_c);
            seen_r.put(fp_r, r_c, epoch);
        }

        // Does the seed of R match one seen in V, or the seed of V one seen
        // in R?
        bool found = false;
        size_t r_m = 0, v_m = 0;
        if (have_r) {
            if (auto hit = seen_v.get(fp_r, epoch)) {
                ++lookups;
                if (seeds_equal(r, r_c, v, *hit, p)) {
                    r_m = r_c;
                    v_m = *hit;
                    found = true;
                }
            }
        }
        if (!found && have_v) {
            if (auto hit = seen_r.get(fp_v, epoch)) {
                ++lookups;
                if (seeds_equal(r, *hit, v, v_c, p)) {
                    r_m = *hit;
                    v_m = v_c;
                    found = true;
                }
            }
        }
        if (!found) {
            ++v_c;
            ++r_c;
            continue;
        }
        ++matches;

        const size_t len = extend_forward(r, r_m, v, v_m, p);
        append_add(commands, v, v_s, v_m);
        commands.emplace_back(CopyCmd{r_m, len});

        // Resume after the match in both strings, with empty tables.
        v_c = v_s = v_m + len;
        r_c = r_m + len;
        ++epoch;
    }
    append_add(commands, v, v_s, v.size());

    if (opts.verbose) {
        std::fprintf(stderr,
            "  scan: %zu positions, %zu lookups, %zu matches (flushes)\n"
            "  scan: hit rate %.1f%% (of lookups)\n",
            positions, lookups, matches, percent(matches, lookups));
        print_command_stats(commands);
    }
    return commands;
}

} // namespace delta
