#include "delta/algorithm.h"
#include "delta/hash.h"

#include <unordered_map>

namespace delta {

/// Greedy algorithm (Section 3.1, Figure 2).
std::vector<Command> diff_greedy(
    std::span<const uint8_t> r,
    std::span<const uint8_t> v,
    size_t p,
    size_t /*q*/,
    bool /*verbose*/) {

    std::vector<Command> commands;
    if (v.empty()) return commands;

    // Step (1): Build chained hash table for R keyed by full fingerprint
    std::unordered_map<uint64_t, std::vector<size_t>> h_r;
    if (r.size() >= p) {
        RollingHash rh(r, 0, p);
        h_r[rh.value()].push_back(0);
        for (size_t a = 1; a <= r.size() - p; ++a) {
            rh.roll(r[a - 1], r[a + p - 1]);
            h_r[rh.value()].push_back(a);
        }
    }

    // Step (2)
    size_t v_c = 0;
    size_t v_s = 0;

    for (;;) {
        // Step (3)
        if (v_c + p > v.size()) break;

        uint64_t fp_v = fingerprint(v, v_c, p);

        // Steps (4)+(5): find the longest matching substring
        size_t best_len = 0;
        size_t best_rm = 0;

        auto it = h_r.find(fp_v);
        if (it != h_r.end()) {
            for (size_t r_cand : it->second) {
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

        if (best_len == 0) {
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

    return commands;
}

} // namespace delta
