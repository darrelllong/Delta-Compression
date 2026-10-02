#include "delta/algorithm.h"
#include "delta/splay.h"
#include "scan.h"

#include <cstdio>
#include <unordered_map>

namespace delta {

namespace {

/// For each fingerprint, the offsets of the seeds of R that have it, in
/// increasing order.
class OffsetIndex {
public:
    explicit OffsetIndex(bool use_splay) : use_splay_(use_splay) {}

    void add(uint64_t fp, size_t offset) {
        if (use_splay_) {
            tree_.insert_or_get(fp, {}).push_back(offset);
        } else {
            map_[fp].push_back(offset);
        }
    }

    const std::vector<size_t>* find(uint64_t fp) {
        if (use_splay_) { return tree_.find(fp); }
        auto it = map_.find(fp);
        return it != map_.end() ? &it->second : nullptr;
    }

private:
    bool use_splay_;
    std::unordered_map<uint64_t, std::vector<size_t>> map_;
    SplayTree<std::vector<size_t>> tree_;
};

} // namespace

std::vector<Command> diff_greedy(
    std::span<const uint8_t> r,
    std::span<const uint8_t> v,
    const DiffOptions& opts) {

    using namespace detail;

    std::vector<Command> commands;
    if (v.empty()) { return commands; }
    const size_t p = opts.p;

    OffsetIndex index(opts.use_splay);
    SeedScanner r_seeds(r, p);
    for (size_t a = 0, n = seed_count(r.size(), p); a < n; ++a) {
        index.add(r_seeds.at(a), a);
    }

    if (opts.verbose) {
        std::fprintf(stderr,
            "greedy: %s, |R|=%zu, |V|=%zu, seed_len=%zu\n",
            lookup_name(opts), r.size(), v.size(), p);
    }

    SeedScanner v_seeds(v, p);
    size_t v_c = 0; // the seed being looked up
    size_t v_s = 0; // start of the part of V not yet encoded

    while (v_c + p <= v.size()) {
        // The longest match at v_c; among equals, the leftmost in R.
        size_t best_len = 0;
        size_t best_r = 0;
        if (const auto* offsets = index.find(v_seeds.at(v_c))) {
            for (size_t r_m : *offsets) {
                if (!seeds_equal(r, r_m, v, v_c, p)) { continue; }
                size_t len = extend_forward(r, r_m, v, v_c, p);
                if (len > best_len) {
                    best_len = len;
                    best_r = r_m;
                }
            }
        }
        if (best_len < p) {
            ++v_c;
            continue;
        }

        append_add(commands, v, v_s, v_c);
        commands.emplace_back(CopyCmd{best_r, best_len});
        v_c += best_len;
        v_s = v_c;
    }
    append_add(commands, v, v_s, v.size());

    if (opts.verbose) { print_command_stats(commands); }
    return commands;
}

} // namespace delta
