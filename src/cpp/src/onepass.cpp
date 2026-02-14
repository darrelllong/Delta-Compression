#include "delta/algorithm.h"
#include "delta/hash.h"

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
    bool verbose) {

    std::vector<Command> commands;
    if (v.empty()) return commands;

    // Auto-size hash table: one slot per p-byte chunk of R (floor = q).
    size_t num_seeds = (r.size() >= p) ? (r.size() - p + 1) : 0;
    q = next_prime(std::max(q, num_seeds / p));

    if (verbose) {
        std::fprintf(stderr,
            "onepass: hash table q=%zu, |R|=%zu, |V|=%zu, seed_len=%zu\n",
            q, r.size(), v.size(), p);
    }

    // Step (1): hash tables with version-based logical flushing.
    // Each slot stores (full_fingerprint, offset, version).
    using Slot = std::optional<std::tuple<uint64_t, size_t, uint64_t>>;
    std::vector<Slot> h_v(q);
    std::vector<Slot> h_r(q);
    uint64_t ver = 0;

    // Debug counters
    size_t dbg_positions = 0, dbg_lookups = 0, dbg_matches = 0;

    auto hget = [&](const std::vector<Slot>& table, uint64_t fp) -> std::optional<size_t> {
        size_t idx = static_cast<size_t>(fp % static_cast<uint64_t>(q));
        if (table[idx].has_value()) {
            auto& [sfp, off, sver] = *table[idx];
            if (sver == ver && sfp == fp) return off;
        }
        return std::nullopt;
    };

    auto hput = [&](std::vector<Slot>& table, uint64_t fp, size_t off) {
        size_t idx = static_cast<size_t>(fp % static_cast<uint64_t>(q));
        if (table[idx].has_value()) {
            auto& [sfp, soff, sver] = *table[idx];
            if (sver == ver) return; // retain-existing policy
        }
        table[idx] = std::make_tuple(fp, off, ver);
    };

    // Step (2)
    size_t r_c = 0, v_c = 0, v_s = 0;

    for (;;) {
        // Step (3)
        bool can_v = (v_c + p <= v.size());
        bool can_r = (r_c + p <= r.size());
        if (!can_v && !can_r) break;
        ++dbg_positions;

        std::optional<uint64_t> fp_v, fp_r;
        if (can_v) fp_v = fingerprint(v, v_c, p);
        if (can_r) fp_r = fingerprint(r, r_c, p);

        // Step (4a): store offsets (retain-existing policy)
        if (fp_v) hput(h_v, *fp_v, v_c);
        if (fp_r) hput(h_r, *fp_r, r_c);

        // Step (4b): look for a matching seed in the other table
        bool match_found = false;
        size_t r_m = 0, v_m = 0;

        if (fp_r) {
            if (auto v_cand = hget(h_v, *fp_r)) {
                ++dbg_lookups;
                if (std::memcmp(&r[r_c], &v[*v_cand], p) == 0) {
                    r_m = r_c;
                    v_m = *v_cand;
                    match_found = true;
                }
            }
        }

        if (!match_found && fp_v) {
            if (auto r_cand = hget(h_r, *fp_v)) {
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

    // Step (8)
    if (v_s < v.size()) {
        commands.emplace_back(AddCmd{
            std::vector<uint8_t>(v.begin() + v_s, v.end())});
    }

    if (verbose) {
        size_t total_copy = 0, total_add = 0, num_copies = 0, num_adds = 0;
        for (const auto& cmd : commands) {
            if (auto* c = std::get_if<CopyCmd>(&cmd)) {
                total_copy += c->length; ++num_copies;
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
    }

    return commands;
}

} // namespace delta
