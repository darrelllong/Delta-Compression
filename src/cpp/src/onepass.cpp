#include "delta/algorithm.h"
#include "delta/hash.h"
#include "delta/splay.h"

#include <algorithm>
#include <cstdio>
#include <optional>
#include <tuple>
#include <vector>

namespace delta {

/// One-Pass algorithm (Section 4.1, Figure 3).
std::vector<Command> diff_onepass(
    std::span<const uint8_t> r,
    std::span<const uint8_t> v,
    size_t p,
    size_t q,
    bool verbose,
    bool use_splay,
    size_t min_copy) {

    std::vector<Command> commands;
    if (v.empty()) return commands;
    // --min-copy raises the seed length so we never fingerprint at a
    // granularity finer than the minimum copy threshold.
    if (min_copy > 0 && min_copy > p) p = min_copy;

    // Auto-size hash table: one slot per p-byte chunk of R (floor = q).
    size_t num_seeds = (r.size() >= p) ? (r.size() - p + 1) : 0;
    q = next_prime(std::max(q, num_seeds / p));

    if (verbose) {
        std::fprintf(stderr,
            "onepass: %s, q=%zu, |R|=%zu, |V|=%zu, seed_len=%zu\n",
            use_splay ? "splay tree" : "hash table",
            q, r.size(), v.size(), p);
    }

    // Step (1): lookup structures with version-based logical flushing.
    // Each entry stores (offset, version).
    using SlotVal = std::pair<size_t, uint64_t>; // (offset, version)

    // Hash table path
    using Slot = std::optional<std::tuple<uint64_t, size_t, uint64_t>>;
    std::vector<Slot> h_v_ht, h_r_ht;

    // Splay tree path
    SplayTree<SlotVal> h_v_sp, h_r_sp;

    if (!use_splay) {
        h_v_ht.resize(q);
        h_r_ht.resize(q);
    }

    uint64_t ver = 0;

    // Debug counters
    size_t dbg_positions = 0, dbg_lookups = 0, dbg_matches = 0;

    // Lookup/store lambdas that dispatch to either data structure.
    auto hget = [&](bool is_v_table, uint64_t fp) -> std::optional<size_t> {
        if (use_splay) {
            auto& tree = is_v_table ? h_v_sp : h_r_sp;
            auto* val = tree.find(fp);
            if (val && val->second == ver) return val->first;
            return std::nullopt;
        } else {
            auto& table = is_v_table ? h_v_ht : h_r_ht;
            size_t idx = static_cast<size_t>(fp % static_cast<uint64_t>(q));
            if (table[idx].has_value()) {
                auto& [sfp, off, sver] = *table[idx];
                if (sver == ver && sfp == fp) return off;
            }
            return std::nullopt;
        }
    };

    auto hput = [&](bool is_v_table, uint64_t fp, size_t off) {
        if (use_splay) {
            auto& tree = is_v_table ? h_v_sp : h_r_sp;
            auto* existing = tree.find(fp);
            if (existing && existing->second == ver) return; // retain-existing
            tree.insert(fp, SlotVal{off, ver});
        } else {
            auto& table = is_v_table ? h_v_ht : h_r_ht;
            size_t idx = static_cast<size_t>(fp % static_cast<uint64_t>(q));
            if (table[idx].has_value()) {
                auto& [sfp, soff, sver] = *table[idx];
                if (sver == ver) return; // retain-existing policy
            }
            table[idx] = std::make_tuple(fp, off, ver);
        }
    };

    // Step (2): initialize scan pointers
    size_t r_c = 0, v_c = 0, v_s = 0;

    // Rolling hashes for O(1) per-position fingerprinting.
    std::optional<RollingHash> rh_v, rh_r;
    size_t rh_v_pos = 0, rh_r_pos = 0;
    if (v.size() >= p) { rh_v.emplace(v, 0, p); rh_v_pos = 0; }
    if (r.size() >= p) { rh_r.emplace(r, 0, p); rh_r_pos = 0; }

    for (;;) {
        // Step (3): check for end of V and R
        bool can_v = (v_c + p <= v.size());
        bool can_r = (r_c + p <= r.size());
        if (!can_v && !can_r) break;
        ++dbg_positions;

        std::optional<uint64_t> fp_v, fp_r;
        if (can_v && rh_v) {
            if (v_c == rh_v_pos) {
                // already positioned
            } else if (v_c == rh_v_pos + 1) {
                rh_v->roll(v[v_c - 1], v[v_c + p - 1]);
                rh_v_pos = v_c;
            } else {
                rh_v.emplace(v, v_c, p);
                rh_v_pos = v_c;
            }
            fp_v = rh_v->value();
        }
        if (can_r && rh_r) {
            if (r_c == rh_r_pos) {
                // already positioned
            } else if (r_c == rh_r_pos + 1) {
                rh_r->roll(r[r_c - 1], r[r_c + p - 1]);
                rh_r_pos = r_c;
            } else {
                rh_r.emplace(r, r_c, p);
                rh_r_pos = r_c;
            }
            fp_r = rh_r->value();
        }

        // Step (4a): store offsets (retain-existing policy)
        if (fp_v) hput(true, *fp_v, v_c);
        if (fp_r) hput(false, *fp_r, r_c);

        // Step (4b): look for a matching seed in the other table
        bool match_found = false;
        size_t r_m = 0, v_m = 0;

        if (fp_r) {
            if (auto v_cand = hget(true, *fp_r)) {
                ++dbg_lookups;
                if (std::memcmp(&r[r_c], &v[*v_cand], p) == 0) {
                    r_m = r_c;
                    v_m = *v_cand;
                    match_found = true;
                }
            }
        }

        if (!match_found && fp_v) {
            if (auto r_cand = hget(false, *fp_v)) {
                ++dbg_lookups;
                if (std::memcmp(&v[v_c], &r[*r_cand], p) == 0) {
                    v_m = v_c;
                    r_m = *r_cand;
                    match_found = true;
                }
            }
        }

        if (!match_found) {
            ++v_c;
            ++r_c;
            continue;
        }
        ++dbg_matches;

        // Step (5): extend match forward
        size_t ml = 0;
        while (v_m + ml < v.size() && r_m + ml < r.size()
               && v[v_m + ml] == r[r_m + ml]) {
            ++ml;
        }

        // Filter: skip matches shorter than --min-copy
        if (ml < p) {
            ++v_c;
            ++r_c;
            continue;
        }

        // Step (6): encode
        if (v_s < v_m) {
            commands.emplace_back(AddCmd{
                std::vector<uint8_t>(v.begin() + v_s, v.begin() + v_m)});
        }
        commands.emplace_back(CopyCmd{r_m, ml});
        v_s = v_m + ml;

        // Step (7): advance pointers and flush tables
        v_c = v_m + ml;
        r_c = r_m + ml;
        ++ver;
    }

    // Step (8): trailing add
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
        double hit_pct = dbg_lookups > 0
            ? static_cast<double>(dbg_matches) / dbg_lookups * 100.0 : 0.0;
        size_t total_out = total_copy + total_add;
        double copy_pct = total_out > 0
            ? static_cast<double>(total_copy) / total_out * 100.0 : 0.0;
        std::fprintf(stderr,
            "  scan: %zu positions, %zu lookups, %zu matches (flushes)\n"
            "  scan: hit rate %.1f%% (of lookups)\n",
            dbg_positions, dbg_lookups, dbg_matches, hit_pct);
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
