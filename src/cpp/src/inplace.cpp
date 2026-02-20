#include "delta/inplace.h"

#include <algorithm>
#include <functional>
#include <numeric>
#include <queue>

namespace delta {

/// Compute SCCs using iterative Tarjan's algorithm.
///
/// Returns SCCs in reverse topological order (sinks first); caller reverses
/// for source-first processing order.
///
/// R.E. Tarjan, "Depth-first search and linear graph algorithms,"
/// SIAM J. Comput., 1(2):146-160, June 1972.
static std::vector<std::vector<size_t>> tarjan_scc(
    const std::vector<std::vector<size_t>>& adj, size_t n) {

    std::vector<size_t> index(n, SIZE_MAX); // SIZE_MAX = unvisited
    std::vector<size_t> lowlink(n, 0);
    std::vector<bool> on_stack(n, false);
    std::vector<size_t> tarjan_stack;
    std::vector<std::vector<size_t>> sccs;
    size_t index_counter = 0;
    // DFS call stack: (vertex, next-neighbor-index)
    std::vector<std::pair<size_t, size_t>> call_stack;

    for (size_t start = 0; start < n; ++start) {
        if (index[start] != SIZE_MAX) { continue; }

        index[start] = lowlink[start] = index_counter++;
        on_stack[start] = true;
        tarjan_stack.push_back(start);
        call_stack.emplace_back(start, 0);

        while (!call_stack.empty()) {
            size_t v  = call_stack.back().first;
            size_t ni = call_stack.back().second;

            if (ni < adj[v].size()) {
                size_t w = adj[v][ni];
                call_stack.back().second++;
                if (index[w] == SIZE_MAX) {
                    // Tree edge: descend into w
                    index[w] = lowlink[w] = index_counter++;
                    on_stack[w] = true;
                    tarjan_stack.push_back(w);
                    call_stack.emplace_back(w, 0);
                } else if (on_stack[w]) {
                    // Back-edge into current SCC
                    if (index[w] < lowlink[v]) { lowlink[v] = index[w]; }
                }
            } else {
                call_stack.pop_back();
                if (!call_stack.empty()) {
                    size_t parent = call_stack.back().first;
                    if (lowlink[v] < lowlink[parent]) {
                        lowlink[parent] = lowlink[v];
                    }
                }
                if (lowlink[v] == index[v]) {
                    std::vector<size_t> scc;
                    while (true) {
                        size_t w = tarjan_stack.back();
                        tarjan_stack.pop_back();
                        on_stack[w] = false;
                        scc.push_back(w);
                        if (w == v) { break; }
                    }
                    sccs.push_back(std::move(scc));
                }
            }
        }
    }

    return sccs; // sinks first; caller reverses for source-first order
}

/// Find a cycle in the active subgraph of one SCC.
///
/// Three amortizations give O(|SCC| + E_SCC) total work per SCC:
///   1. scc_id filter: O(1) per neighbor check, no O(|SCC|) set/clear sweep.
///   2. color persistence: color=2 (fully explored) persists across calls;
///      vertex removal can only reduce edges, so color=2 is monotone-correct.
///   3. scan_start: outer loop resumes from last position, O(|SCC|) total.
///
/// On cycle found: resets path (color=1) vertices to 0; color=2 intact.
/// On false (acyclic): color=2 persists (scc_id filter isolates SCCs).
static bool find_cycle_in_scc(
    const std::vector<std::vector<size_t>>& adj,
    const std::vector<size_t>& scc,
    size_t sid,
    const std::vector<size_t>& scc_id,
    const std::vector<bool>& removed,
    std::vector<uint8_t>& color,
    size_t& scan_start,
    std::vector<size_t>& cycle_out)
{
    std::vector<size_t> path;

    while (scan_start < scc.size()) {
        size_t start = scc[scan_start];
        if (removed[start] || color[start] != 0) { ++scan_start; continue; }

        color[start] = 1;
        path.push_back(start);
        std::vector<std::pair<size_t, size_t>> stk;
        stk.emplace_back(start, 0);

        while (!stk.empty()) {
            size_t v  = stk.back().first;
            size_t ni = stk.back().second;
            bool advanced = false;

            while (ni < adj[v].size()) {
                size_t w = adj[v][ni++];
                if (scc_id[w] != sid || removed[w]) { continue; }
                if (color[w] == 1) {
                    // Back-edge: cycle found.
                    auto pos = static_cast<size_t>(
                        std::find(path.begin(), path.end(), w) - path.begin());
                    cycle_out.assign(path.begin() + pos, path.end());
                    for (size_t u : path) { color[u] = 0; }
                    return true;
                }
                if (color[w] == 0) {
                    stk.back().second = ni;
                    color[w] = 1;
                    path.push_back(w);
                    stk.emplace_back(w, 0);
                    advanced = true;
                    break;
                }
            }
            if (!advanced) {
                stk.pop_back();
                color[v] = 2; // Fully explored — persists across calls.
                path.pop_back();
            }
        }
        // start's reachable SCC-subgraph fully explored; no cycle.
        ++scan_start;
    }
    return false;
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

    // O(n log n + E) sweep-line: sort writes by start, then for each read
    // interval binary-search into the sorted writes to find overlaps.
    std::vector<size_t> write_sorted(n);
    std::iota(write_sorted.begin(), write_sorted.end(), 0);
    std::sort(write_sorted.begin(), write_sorted.end(),
        [&](size_t a, size_t b) { return copy_info[a].dst < copy_info[b].dst; });
    std::vector<size_t> write_starts(n);
    for (size_t k = 0; k < n; ++k) { write_starts[k] = copy_info[write_sorted[k]].dst; }

    for (size_t i = 0; i < n; ++i) {
        size_t si = copy_info[i].src;
        size_t read_end = si + copy_info[i].length;
        // Two binary searches exploit the fact that dst intervals are
        // non-overlapping (each output byte written exactly once):
        //   lo = first write with dst >= si
        //   hi = first write with dst >= read_end
        // Writes in [lo, hi) start within [si, read_end) and thus always
        // overlap the read interval.  The write at lo-1 (if any) starts
        // before si; it overlaps iff its end exceeds si.
        auto lo_it = std::lower_bound(write_starts.begin(), write_starts.end(), si);
        auto hi_it = std::lower_bound(lo_it, write_starts.end(), read_end);
        size_t lo = static_cast<size_t>(lo_it - write_starts.begin());
        size_t hi = static_cast<size_t>(hi_it - write_starts.begin());
        if (lo > 0) {
            size_t j = write_sorted[lo - 1];
            if (j != i && copy_info[j].dst + copy_info[j].length > si) {
                adj[i].push_back(j);
            }
        }
        for (size_t k = lo; k < hi; ++k) {
            size_t j = write_sorted[k];
            if (j != i) { adj[i].push_back(j); }
        }
    }

    // Step 3: Kahn topological sort with Tarjan-scoped cycle breaking.
    //
    // Global Kahn preserves the cascade effect (converting a victim decrements
    // in_deg globally, potentially freeing vertices across SCC boundaries).
    // find_cycle_in_scc restricts DFS to one SCC via three amortizations:
    // scc_id filter (no O(|SCC|) set/clear), color=2 persistence, scan_start.
    // Total cycle-breaking work: O(n+E).
    // R.E. Tarjan, SIAM J. Comput., 1(2):146-160, June 1972.
    auto sccs = tarjan_scc(adj, n);

    std::vector<size_t> in_deg(n, 0);
    for (size_t i = 0; i < n; ++i) {
        for (size_t j : adj[i]) { ++in_deg[j]; }
    }

    std::vector<size_t> scc_id(n, SIZE_MAX); // SIZE_MAX = trivial
    std::vector<std::vector<size_t>> scc_list; // non-trivial SCCs only
    std::vector<size_t> scc_active;            // live member count per SCC

    for (auto& scc : sccs) {
        if (scc.size() > 1) {
            size_t id = scc_list.size();
            for (size_t v : scc) { scc_id[v] = id; }
            scc_active.push_back(scc.size());
            scc_list.push_back(scc);
        }
    }

    std::vector<bool>    removed(n, false);
    std::vector<size_t>  topo_order;
    topo_order.reserve(n);
    std::vector<uint8_t> color(n, 0);
    size_t scc_ptr   = 0;
    size_t scan_pos  = 0;

    using HeapEntry = std::pair<size_t, size_t>;
    std::priority_queue<HeapEntry, std::vector<HeapEntry>, std::greater<>> heap;
    for (size_t i = 0; i < n; ++i) {
        if (in_deg[i] == 0) { heap.emplace(copy_info[i].length, i); }
    }
    size_t processed = 0;

    while (processed < n) {
        // Drain all ready vertices.
        while (!heap.empty()) {
            auto [len, v] = heap.top();
            heap.pop();
            if (removed[v]) { continue; }
            removed[v] = true;
            topo_order.push_back(v);
            ++processed;
            if (scc_id[v] != SIZE_MAX) { --scc_active[scc_id[v]]; }
            for (size_t w : adj[v]) {
                if (!removed[w] && --in_deg[w] == 0) {
                    heap.emplace(copy_info[w].length, w);
                }
            }
        }

        if (processed >= n) { break; }

        // Kahn stalled: all remaining vertices are in CRWI cycles.
        // Choose a victim to convert from copy to add.
        size_t victim = n; // invalid sentinel
        if (policy == CyclePolicy::Constant) {
            for (size_t i = 0; i < n; ++i) {
                if (!removed[i]) { victim = i; break; }
            }
        } else { // Localmin
            std::vector<size_t> cycle_buf;
            while (victim == n) {
                while (scc_ptr < scc_list.size() && scc_active[scc_ptr] == 0) {
                    ++scc_ptr; scan_pos = 0;
                }
                if (scc_ptr >= scc_list.size()) {
                    // Safety fallback — should not happen with a correct graph.
                    for (size_t i = 0; i < n; ++i) {
                        if (!removed[i]) { victim = i; break; }
                    }
                    break;
                }
                if (find_cycle_in_scc(adj, scc_list[scc_ptr], scc_ptr,
                                      scc_id, removed, color, scan_pos,
                                      cycle_buf)) {
                    victim = cycle_buf[0];
                    for (size_t v : cycle_buf) {
                        if (copy_info[v].length < copy_info[victim].length ||
                            (copy_info[v].length == copy_info[victim].length &&
                             v < victim)) {
                            victim = v;
                        }
                    }
                } else {
                    // SCC's remaining subgraph is acyclic; advance.
                    ++scc_ptr; scan_pos = 0;
                }
            }
        }

        // Convert victim: materialize its copy data as a literal add.
        auto& ci = copy_info[victim];
        add_info.emplace_back(ci.dst,
            std::vector<uint8_t>(r.begin() + ci.src,
                                 r.begin() + ci.src + ci.length));
        removed[victim] = true;
        ++processed;
        if (scc_id[victim] != SIZE_MAX) { --scc_active[scc_id[victim]]; }

        for (size_t w : adj[victim]) {
            if (!removed[w] && --in_deg[w] == 0) {
                heap.emplace(copy_info[w].length, w);
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
