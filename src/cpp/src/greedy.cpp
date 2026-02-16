#include "delta/algorithm.h"
#include "delta/hash.h"
#include "delta/splay.h"

#include <algorithm>
#include <cstdio>
#include <numeric>
#include <optional>
#include <unordered_map>
#include <vector>

namespace delta {

/// Greedy algorithm (Section 3.1, Figure 2).
std::vector<Command> diff_greedy(
    std::span<const uint8_t> r,
    std::span<const uint8_t> v,
    size_t p,
    size_t /*q*/,
    bool verbose,
    bool use_splay,
    size_t min_copy) {

    std::vector<Command> commands;
    if (v.empty()) return commands;
    // --min-copy raises the seed length so we never fingerprint at a
    // granularity finer than the minimum copy threshold.
    if (min_copy > 0 && min_copy > p) p = min_copy;

    // Step (1): Build lookup structure for R keyed by full fingerprint.
    // Hash table (default) or splay tree (--splay).
    SplayTree<std::vector<size_t>> splay_r;
    std::unordered_map<uint64_t, std::vector<size_t>> h_r;

    if (r.size() >= p) {
        RollingHash rh(r, 0, p);
        if (use_splay) {
            splay_r.insert_or_get(rh.value(), {}).push_back(0);
        } else {
            h_r[rh.value()].push_back(0);
        }
        for (size_t a = 1; a <= r.size() - p; ++a) {
            rh.roll(r[a - 1], r[a + p - 1]);
            if (use_splay) {
                splay_r.insert_or_get(rh.value(), {}).push_back(a);
            } else {
                h_r[rh.value()].push_back(a);
            }
        }
    }

    if (verbose) {
        std::fprintf(stderr,
            "greedy: %s, |R|=%zu, |V|=%zu, seed_len=%zu\n",
            use_splay ? "splay tree" : "hash table",
            r.size(), v.size(), p);
    }

    // Step (2)
    size_t v_c = 0;
    size_t v_s = 0;

    // Rolling hash for O(1) per-position V fingerprinting.
    std::optional<RollingHash> rh_v_scan;
    size_t rh_v_pos = 0;
    if (v.size() >= p) { rh_v_scan.emplace(v, 0, p); rh_v_pos = 0; }

    for (;;) {
        // Step (3)
        if (v_c + p > v.size()) break;

        uint64_t fp_v;
        if (v_c == rh_v_pos) {
            fp_v = rh_v_scan->value();
        } else if (v_c == rh_v_pos + 1) {
            rh_v_scan->roll(v[v_c - 1], v[v_c + p - 1]);
            rh_v_pos = v_c;
            fp_v = rh_v_scan->value();
        } else {
            rh_v_scan.emplace(v, v_c, p);
            rh_v_pos = v_c;
            fp_v = rh_v_scan->value();
        }

        // Steps (4)+(5): find the longest matching substring
        size_t best_len = 0;
        size_t best_rm = 0;

        const std::vector<size_t>* offsets = nullptr;
        if (use_splay) {
            offsets = splay_r.find(fp_v);
        } else {
            auto it = h_r.find(fp_v);
            if (it != h_r.end()) offsets = &it->second;
        }

        if (offsets) {
            for (size_t r_cand : *offsets) {
                // Verify the seed actually matches
                if (std::memcmp(&r[r_cand], &v[v_c], p) != 0) continue;
                size_t ml = p;
                while (v_c + ml < v.size() && r_cand + ml < r.size()
                       && v[v_c + ml] == r[r_cand + ml]) {
                    ++ml;
                }
                if (ml > best_len) {
                    best_len = ml;
                    best_rm = r_cand;
                }
            }
        }

        if (best_len < p) {
            ++v_c;
            continue;
        }

        // Step (6): encode
        if (v_s < v_c) {
            commands.emplace_back(AddCmd{
                std::vector<uint8_t>(v.begin() + v_s, v.begin() + v_c)});
        }
        commands.emplace_back(CopyCmd{best_rm, best_len});
        v_s = v_c + best_len;

        // Step (7)
        v_c += best_len;
    }

    // Step (8)
    if (v_s < v.size()) {
        commands.emplace_back(AddCmd{
            std::vector<uint8_t>(v.begin() + v_s, v.end())});
    }

    if (verbose) {
        std::vector<size_t> copy_lens;
        size_t total_copy = 0, total_add = 0, num_copies = 0, num_adds = 0;
        for (const auto& cmd : commands) {
            if (auto* c = std::get_if<CopyCmd>(&cmd)) {
                total_copy += c->length; ++num_copies;
                copy_lens.push_back(c->length);
            } else if (auto* a = std::get_if<AddCmd>(&cmd)) {
                total_add += a->data.size(); ++num_adds;
            }
        }
        size_t total_out = total_copy + total_add;
        double copy_pct = total_out > 0
            ? static_cast<double>(total_copy) / total_out * 100.0 : 0.0;
        std::fprintf(stderr,
            "  result: %zu copies (%zu bytes), %zu adds (%zu bytes)\n"
            "  result: copy coverage %.1f%%, output %zu bytes\n",
            num_copies, total_copy, num_adds, total_add, copy_pct, total_out);
        if (!copy_lens.empty()) {
            std::sort(copy_lens.begin(), copy_lens.end());
            double mean = static_cast<double>(total_copy) / copy_lens.size();
            size_t median = copy_lens[copy_lens.size() / 2];
            std::fprintf(stderr,
                "  copies: %zu regions, min=%zu max=%zu mean=%.1f median=%zu bytes\n",
                copy_lens.size(), copy_lens.front(), copy_lens.back(),
                mean, median);
        }
    }

    return commands;
}

} // namespace delta
