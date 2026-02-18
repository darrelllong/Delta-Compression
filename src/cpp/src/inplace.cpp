#include "delta/inplace.h"

#include <algorithm>
#include <functional>
#include <numeric>
#include <queue>
#include <unordered_map>

namespace delta {

/// Find a cycle in the subgraph of non-removed vertices.
static std::vector<size_t> find_cycle(
    const std::vector<std::vector<size_t>>& adj,
    const std::vector<bool>& removed,
    size_t n) {

    for (size_t start = 0; start < n; ++start) {
        if (removed[start]) { continue; }

        std::unordered_map<size_t, size_t> visited;
        std::vector<size_t> path;
        size_t step = 0;

        auto curr = std::optional<size_t>(start);
        while (curr.has_value()) {
            size_t c = *curr;
            auto it = visited.find(c);
            if (it != visited.end()) {
                // Found a cycle
                return std::vector<size_t>(
                    path.begin() + it->second, path.end());
            }
            visited[c] = step;
            path.push_back(c);
            ++step;

            // Find next non-removed neighbor
            curr = std::nullopt;
            for (size_t w : adj[c]) {
                if (!removed[w]) {
                    curr = w;
                    break;
                }
            }
        }
    }
    return {}; // no cycle found
}

std::vector<PlacedCommand> make_inplace(
    std::span<const uint8_t> r,
    const std::vector<Command>& commands,
    CyclePolicy policy) {

    if (commands.empty()) { return {}; }

    // Step 1: compute write offsets for each command
    // copy_info: (index, src, dst, length)
    struct CopyInfo {
        size_t idx, src, dst, length;
    };
    std::vector<CopyInfo> copy_info;
    std::vector<std::pair<size_t, std::vector<uint8_t>>> add_info;
    size_t write_pos = 0;

    for (const auto& cmd : commands) {
        if (auto* c = std::get_if<CopyCmd>(&cmd)) {
            copy_info.push_back({copy_info.size(), c->offset, write_pos, c->length});
            write_pos += c->length;
        } else if (auto* a = std::get_if<AddCmd>(&cmd)) {
            add_info.emplace_back(write_pos, a->data);
            write_pos += a->data.size();
        }
    }

    size_t n = copy_info.size();
    if (n == 0) {
        std::vector<PlacedCommand> result;
        for (auto& [dst, data] : add_info) {
            result.emplace_back(PlacedAdd{dst, std::move(data)});
        }
        return result;
    }

    // Step 2: build CRWI digraph
    std::vector<std::vector<size_t>> adj(n);
    std::vector<size_t> in_deg(n, 0);

    // O(n log n + E) sweep-line: sort writes by start, then for each read
    // interval binary-search into the sorted writes to find overlaps.
    std::vector<size_t> write_sorted(n);
    std::iota(write_sorted.begin(), write_sorted.end(), 0);
    std::sort(write_sorted.begin(), write_sorted.end(),
        [&](size_t a, size_t b) { return copy_info[a].dst < copy_info[b].dst; });
    std::vector<size_t> write_starts(n);
    for (size_t k = 0; k < n; ++k) { write_starts[k] = copy_info[write_sorted[k]].dst; }

    for (size_t i = 0; i < n; ++i) {
        size_t read_end = copy_info[i].src + copy_info[i].length;
        // Find first write whose start >= read_end
        auto hi_it = std::lower_bound(write_starts.begin(), write_starts.end(), read_end);
        size_t hi = static_cast<size_t>(hi_it - write_starts.begin());
        for (size_t k = 0; k < hi; ++k) {
            size_t j = write_sorted[k];
            if (i == j) { continue; }
            if (copy_info[j].dst + copy_info[j].length > copy_info[i].src) {
                adj[i].push_back(j);
                ++in_deg[j];
            }
        }
    }

    // Step 3: topological sort with cycle breaking (Kahn's algorithm)
    // Priority queue keyed on copy length — always process the smallest
    // ready copy first, giving a deterministic topological ordering.
    std::vector<bool> removed(n, false);
    std::vector<size_t> topo_order;
    topo_order.reserve(n);
    std::vector<size_t> converted;

    // Min-heap: (copy_length, index) with std::greater for smallest-first.
    using HeapEntry = std::pair<size_t, size_t>;
    std::priority_queue<HeapEntry, std::vector<HeapEntry>, std::greater<>> heap;
    for (size_t i = 0; i < n; ++i) {
        if (in_deg[i] == 0) { heap.emplace(copy_info[i].length, i); }
    }
    size_t processed = 0;

    while (processed < n) {
        while (!heap.empty()) {
            auto [len, v] = heap.top();
            heap.pop();
            if (removed[v]) { continue; }
            removed[v] = true;
            topo_order.push_back(v);
            ++processed;
            for (size_t w : adj[v]) {
                if (!removed[w]) {
                    --in_deg[w];
                    if (in_deg[w] == 0) { heap.emplace(copy_info[w].length, w); }
                }
            }
        }

        if (processed >= n) { break; }

        // Cycle detected — choose a victim to convert from copy to add
        size_t victim = 0;
        switch (policy) {
        case CyclePolicy::Constant:
            for (size_t i = 0; i < n; ++i) {
                if (!removed[i]) { victim = i; break; }
            }
            break;
        case CyclePolicy::Localmin: {
            auto cycle = find_cycle(adj, removed, n);
            if (!cycle.empty()) {
                // Key on (length, index) for deterministic tie-breaking
                // — same composite key as the Kahn's PQ above.
                victim = *std::min_element(cycle.begin(), cycle.end(),
                    [&](size_t a, size_t b) {
                        return std::tie(copy_info[a].length, a)
                             < std::tie(copy_info[b].length, b);
                    });
            } else {
                for (size_t i = 0; i < n; ++i) {
                    if (!removed[i]) { victim = i; break; }
                }
            }
            break;
        }
        }

        // Convert victim: materialize its copy data as a literal add
        auto& ci = copy_info[victim];
        add_info.emplace_back(ci.dst,
            std::vector<uint8_t>(r.begin() + ci.src,
                                 r.begin() + ci.src + ci.length));
        converted.push_back(victim);
        removed[victim] = true;
        ++processed;

        for (size_t w : adj[victim]) {
            if (!removed[w]) {
                --in_deg[w];
                if (in_deg[w] == 0) { heap.emplace(copy_info[w].length, w); }
            }
        }
    }

    // Step 4: assemble result — copies in topo order, then all adds
    std::vector<PlacedCommand> result;
    for (size_t i : topo_order) {
        auto& ci = copy_info[i];
        result.emplace_back(PlacedCopy{ci.src, ci.dst, ci.length});
    }
    for (auto& [dst, data] : add_info) {
        result.emplace_back(PlacedAdd{dst, std::move(data)});
    }

    return result;
}

} // namespace delta
