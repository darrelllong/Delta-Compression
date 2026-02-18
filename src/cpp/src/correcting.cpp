#include "delta/algorithm.h"
#include "delta/hash.h"
#include "delta/splay.h"

#include <algorithm>
#include <cstdio>
#include <deque>
#include <optional>

namespace delta {

/// Internal buffer entry tracking which region of V a command encodes.
struct BufEntry {
    size_t v_start;
    size_t v_end;
    Command cmd;
    bool dummy;
};

/// Correcting 1.5-Pass algorithm (Section 7, Figure 8) with
/// fingerprint-based checkpointing (Section 8).
std::vector<Command> diff_correcting(
    std::span<const uint8_t> r,
    std::span<const uint8_t> v,
    const DiffOptions& opts) {

    auto p = opts.p;
    auto q = opts.q;
    size_t buf_cap = opts.buf_cap;
    bool verbose = opts.verbose;
    bool use_splay = opts.use_splay;
    size_t min_copy = opts.min_copy;

    std::vector<Command> commands;
    if (v.empty()) { return commands; }
    // --min-copy raises the seed length so we never fingerprint at a
    // granularity finer than the minimum copy threshold.
    if (min_copy > 0 && min_copy > p) { p = min_copy; }

    // ── Checkpointing parameters (Section 8.1, pp. 347-348) ─────────
    size_t num_seeds = (r.size() >= p) ? (r.size() - p + 1) : 0;
    // Auto-size: 2x factor for correcting's |F|=2L convention.
    size_t cap = (num_seeds > 0)
        ? next_prime(std::max(q, 2 * num_seeds / p))
        : next_prime(q); // |C|
    uint64_t f_size = (num_seeds > 0)
        ? static_cast<uint64_t>(next_prime(2 * num_seeds))
        : 1; // |F|
    uint64_t m = (f_size <= static_cast<uint64_t>(cap))
        ? 1
        : (f_size + static_cast<uint64_t>(cap) - 1) / static_cast<uint64_t>(cap); // ceil(|F| / |C|)
    // Biased k (p. 348): pick a V offset, use its footprint mod m.
    uint64_t k = 0;
    if (v.size() >= p) {
        uint64_t fp_k = fingerprint(v, v.size() / 2, p);
        k = fp_k % f_size % m;
    }

    if (verbose) {
        uint64_t expected = (m > 0) ? static_cast<uint64_t>(num_seeds) / m : 0;
        uint64_t occ_est = (cap > 0) ? expected * 100 / static_cast<uint64_t>(cap) : 0;
        std::fprintf(stderr,
            "correcting: %s, |C|=%zu |F|=%llu m=%llu k=%llu\n"
            "  checkpoint gap=%llu bytes, expected fill ~%llu (~%llu%% table occupancy)\n"
            "  table memory ~%zu MB\n",
            use_splay ? "splay tree" : "hash table",
            cap, (unsigned long long)f_size, (unsigned long long)m,
            (unsigned long long)k, (unsigned long long)m,
            (unsigned long long)expected, (unsigned long long)occ_est,
            cap * 24 / 1048576);
    }

    // Debug counters
    size_t dbg_build_passed = 0, dbg_build_stored = 0, dbg_build_skipped_collision = 0;
    size_t dbg_scan_checkpoints = 0, dbg_scan_match = 0;
    size_t dbg_scan_fp_mismatch = 0, dbg_scan_byte_mismatch = 0;

    // Step (1): Build lookup structure for R (first-found policy)
    using HSlot = std::optional<std::pair<uint64_t, size_t>>;
    std::vector<HSlot> h_r_ht;
    SplayTree<std::pair<uint64_t, size_t>> h_r_sp; // (full_fp, offset)

    if (!use_splay) {
        h_r_ht.resize(cap);
    }

    std::optional<RollingHash> rh_build;
    if (num_seeds > 0) { rh_build.emplace(r, 0, p); }
    for (size_t a = 0; a < num_seeds; ++a) {
        uint64_t fp;
        if (a == 0) {
            fp = rh_build->value();
        } else {
            rh_build->roll(r[a - 1], r[a + p - 1]);
            fp = rh_build->value();
        }
        uint64_t f = fp % f_size;
        if (f % m != k) { continue; } // not a checkpoint seed
        ++dbg_build_passed;

        if (use_splay) {
            // insert_or_get implements first-found policy
            auto& val = h_r_sp.insert_or_get(fp, std::make_pair(fp, a));
            if (val.second == a) {
                ++dbg_build_stored;
            } else {
                ++dbg_build_skipped_collision;
            }
        } else {
            size_t i = static_cast<size_t>(f / m);
            if (i >= cap) { continue; } // safety
            if (!h_r_ht[i].has_value()) {
                h_r_ht[i] = std::make_pair(fp, a); // first-found (Section 7 Step 1)
                ++dbg_build_stored;
            } else {
                ++dbg_build_skipped_collision;
            }
        }
    }

    if (verbose) {
        double passed_pct = (num_seeds > 0)
            ? static_cast<double>(dbg_build_passed) / num_seeds * 100.0 : 0.0;
        size_t stored_count = use_splay ? h_r_sp.size() : dbg_build_stored;
        double occ_pct = (cap > 0)
            ? static_cast<double>(stored_count) / cap * 100.0 : 0.0;
        std::fprintf(stderr,
            "  build: %zu seeds, %zu passed checkpoint (%.2f%%), "
            "%zu stored, %zu collisions\n"
            "  build: table occupancy %zu/%zu (%.1f%%)\n",
            num_seeds, dbg_build_passed, passed_pct,
            dbg_build_stored, dbg_build_skipped_collision,
            stored_count, cap, occ_pct);
    }

    // Lookup helper: returns (full_fp, offset) pair if found, nullopt otherwise.
    auto lookup_r = [&](uint64_t fp_v, uint64_t f_v)
        -> std::optional<std::pair<uint64_t, size_t>> {
        if (use_splay) {
            auto* val = h_r_sp.find(fp_v);
            if (val) { return *val; }
            return std::nullopt;
        } else {
            size_t i = static_cast<size_t>(f_v / m);
            if (i >= cap) { return std::nullopt; }
            if (h_r_ht[i].has_value()) { return *h_r_ht[i]; }
            return std::nullopt;
        }
    };

    // ── Encoding lookback buffer (Section 5.2) ───────────────────────
    std::deque<BufEntry> buf;

    auto flush_buf = [&]() {
        for (auto& entry : buf) {
            if (!entry.dummy) {
                commands.push_back(std::move(entry.cmd));
            }
        }
        buf.clear();
    };

    // Step (2): initialize scan pointers
    size_t v_c = 0;
    size_t v_s = 0;

    // Rolling hash for O(1) per-position V fingerprinting.
    std::optional<RollingHash> rh_v_scan;
    size_t rh_v_pos = 0;
    if (v.size() >= p) { rh_v_scan.emplace(v, 0, p); rh_v_pos = 0; }

    for (;;) {
        // Step (3): check for end of V
        if (v_c + p > v.size()) { break; }

        // Step (4): generate footprint at v_c, apply checkpoint test.
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
        uint64_t f_v = fp_v % f_size;
        if (f_v % m != k) {
            ++v_c;
            continue; // not a checkpoint — skip
        }

        // Checkpoint passed — look up R.
        ++dbg_scan_checkpoints;

        auto entry = lookup_r(fp_v, f_v);
        size_t r_offset;

        if (entry.has_value()) {
            auto& [stored_fp, offset] = *entry;
            if (stored_fp == fp_v) {
                // Full fingerprint matches — verify bytes.
                if (std::memcmp(&r[offset], &v[v_c], p) != 0) {
                    ++dbg_scan_byte_mismatch;
                    ++v_c;
                    continue;
                }
                ++dbg_scan_match;
                r_offset = offset;
            } else {
                ++dbg_scan_fp_mismatch;
                ++v_c;
                continue;
            }
        } else {
            ++v_c;
            continue;
        }

        // Step (5): extend match forwards and backwards
        size_t fwd = p;
        while (v_c + fwd < v.size() && r_offset + fwd < r.size()
               && v[v_c + fwd] == r[r_offset + fwd]) {
            ++fwd;
        }

        size_t bwd = 0;
        while (v_c >= bwd + 1 && r_offset >= bwd + 1
               && v[v_c - bwd - 1] == r[r_offset - bwd - 1]) {
            ++bwd;
        }

        size_t v_m = v_c - bwd;
        size_t r_m = r_offset - bwd;
        size_t ml = bwd + fwd;
        size_t match_end = v_m + ml;

        // Filter: skip matches shorter than --min-copy
        if (ml < p) {
            ++v_c;
            continue;
        }

        // Step (6): encode with correction
        if (v_s <= v_m) {
            // (6a) match is entirely in unencoded suffix (Section 7)
            if (v_s < v_m) {
                if (buf.size() >= buf_cap) {
                    auto oldest = std::move(buf.front());
                    buf.pop_front();
                    if (!oldest.dummy) { commands.push_back(std::move(oldest.cmd)); }
                }
                buf.push_back(BufEntry{
                    v_s, v_m,
                    AddCmd{std::vector<uint8_t>(v.begin() + v_s, v.begin() + v_m)},
                    false});
            }
            if (buf.size() >= buf_cap) {
                auto oldest = std::move(buf.front());
                buf.pop_front();
                if (!oldest.dummy) { commands.push_back(std::move(oldest.cmd)); }
            }
            buf.push_back(BufEntry{
                v_m, match_end,
                CopyCmd{r_m, ml},
                false});
            v_s = match_end;
        } else {
            // (6b) match extends backward into encoded prefix —
            // tail correction (Section 5.1, p. 339)
            size_t effective_start = v_s;

            while (!buf.empty()) {
                auto& tail = buf.back();
                if (tail.dummy) {
                    buf.pop_back();
                    continue;
                }

                if (tail.v_start >= v_m && tail.v_end <= match_end) {
                    // Wholly within new match — absorb
                    effective_start = std::min(effective_start, tail.v_start);
                    buf.pop_back();
                    continue;
                }

                if (tail.v_end > v_m && tail.v_start < v_m) {
                    if (std::holds_alternative<AddCmd>(tail.cmd)) {
                        // Partial add — trim to [v_start, v_m)
                        size_t keep = v_m - tail.v_start;
                        if (keep > 0) {
                            tail.cmd = AddCmd{std::vector<uint8_t>(
                                v.begin() + tail.v_start,
                                v.begin() + v_m)};
                            tail.v_end = v_m;
                        } else {
                            buf.pop_back();
                        }
                        effective_start = std::min(effective_start, v_m);
                    }
                    // Partial copy — don't reclaim (Section 5.1)
                    break;
                }

                // No overlap with match
                break;
            }

            size_t adj = effective_start - v_m;
            size_t new_len = match_end - effective_start;
            if (new_len > 0) {
                if (buf.size() >= buf_cap) {
                    auto oldest = std::move(buf.front());
                    buf.pop_front();
                    if (!oldest.dummy) { commands.push_back(std::move(oldest.cmd)); }
                }
                buf.push_back(BufEntry{
                    effective_start, match_end,
                    CopyCmd{r_m + adj, new_len},
                    false});
            }
            v_s = match_end;
        }

        // Step (7): advance past matched region
        v_c = match_end;
    }

    // Step (8): flush buffer and trailing add
    flush_buf();
    if (v_s < v.size()) {
        commands.emplace_back(AddCmd{
            std::vector<uint8_t>(v.begin() + v_s, v.end())});
    }

    if (verbose) {
        size_t v_seeds = (v.size() >= p) ? (v.size() - p + 1) : 0;
        double cp_pct = (v_seeds > 0)
            ? static_cast<double>(dbg_scan_checkpoints) / v_seeds * 100.0 : 0.0;
        double hit_pct = (dbg_scan_checkpoints > 0)
            ? static_cast<double>(dbg_scan_match) / dbg_scan_checkpoints * 100.0 : 0.0;
        std::fprintf(stderr,
            "  scan: %zu V positions, %zu checkpoints (%.3f%%), %zu matches\n"
            "  scan: hit rate %.1f%% (of checkpoints), "
            "fp collisions %zu, byte mismatches %zu\n",
            v_seeds, dbg_scan_checkpoints, cp_pct, dbg_scan_match,
            hit_pct, dbg_scan_fp_mismatch, dbg_scan_byte_mismatch);
        print_command_stats(commands);
    }

    return commands;
}

/// Dispatcher
std::vector<Command> diff(
    Algorithm algo,
    std::span<const uint8_t> r,
    std::span<const uint8_t> v,
    const DiffOptions& opts) {

    switch (algo) {
    case Algorithm::Greedy:
        return diff_greedy(r, v, opts);
    case Algorithm::Onepass:
        return diff_onepass(r, v, opts);
    case Algorithm::Correcting:
        return diff_correcting(r, v, opts);
    }
    __builtin_unreachable();
}

/// Shared verbose stats: result summary + copy length distribution.
void print_command_stats(const std::vector<Command>& commands) {
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

} // namespace delta
