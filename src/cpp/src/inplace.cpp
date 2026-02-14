#include "delta/inplace.h"

#include <algorithm>
#include <deque>
#include <unordered_map>

namespace delta {

/// Find a cycle in the subgraph of non-removed vertices.
static std::vector<size_t> find_cycle(
    const std::vector<std::vector<size_t>>& adj,
    const std::vector<bool>& removed,
    size_t n) {

    for (size_t start = 0; start < n; ++start) {
        if (removed[start]) continue;

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

    if (commands.empty()) return {};

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

    for (size_t i = 0; i < n; ++i) {
        for (size_t j = 0; j < n; ++j) {
            if (i == j) continue;
            // Edge i -> j: i's read [src_i, src_i+len_i) overlaps j's write [dst_j, dst_j+len_j)
            if (copy_info[i].src < copy_info[j].dst + copy_info[j].length
                && copy_info[j].dst < copy_info[i].src + copy_info[i].length) {
                adj[i].push_back(j);
                ++in_deg[j];
            }
        }
    }

    // Step 3: topological sort with cycle breaking (Kahn's algorithm)
    std::vector<bool> removed(n, false);
    std::vector<size_t> topo_order;
    topo_order.reserve(n);
    std::vector<size_t> converted;

    std::deque<size_t> queue;
    for (size_t i = 0; i < n; ++i) {
        if (in_deg[i] == 0) queue.push_back(i);
    }
    size_t processed = 0;

    while (processed < n) {
        while (!queue.empty()) {
            size_t v = queue.front();
            queue.pop_front();
            if (removed[v]) continue;
            removed[v] = true;
            topo_order.push_back(v);
            ++processed;
            for (size_t w : adj[v]) {
                if (!removed[w]) {
                    --in_deg[w];
                    if (in_deg[w] == 0) queue.push_back(w);
                }
            }
        }

        if (processed >= n) break;

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
                victim = *std::min_element(cycle.begin(), cycle.end(),
                    [&](size_t a, size_t b) {
                        return copy_info[a].length < copy_info[b].length;
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
                if (in_deg[w] == 0) queue.push_back(w);
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
