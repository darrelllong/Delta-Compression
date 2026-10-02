#include "delta/inplace.h"

#include <algorithm>
#include <functional>
#include <numeric>
#include <queue>
#include <utility>

namespace delta {

namespace {

struct Copy {
    size_t src, dst, length;
};

/// adj[i] lists the copies that must run after copy i.
using Graph = std::vector<std::vector<size_t>>;

/// Builds the CRWI digraph: an edge from i to j if copy i reads bytes that
/// copy j writes.  O(n log n + E).
Graph build_crwi_digraph(const std::vector<Copy>& copies) {
    const size_t n = copies.size();
    Graph adj(n);

    // The writes are disjoint, because each byte of V is written once.
    // Sorted by start, those that overlap a given read are therefore
    // consecutive: every write that starts inside the read, and possibly the
    // one before them, if it ends inside the read.
    std::vector<size_t> by_dst(n);
    std::iota(by_dst.begin(), by_dst.end(), 0);
    std::sort(by_dst.begin(), by_dst.end(),
        [&](size_t a, size_t b) { return copies[a].dst < copies[b].dst; });
    std::vector<size_t> starts(n);
    for (size_t k = 0; k < n; ++k) { starts[k] = copies[by_dst[k]].dst; }

    for (size_t i = 0; i < n; ++i) {
        const size_t read_begin = copies[i].src;
        const size_t read_end = read_begin + copies[i].length;
        const auto lo_it = std::lower_bound(starts.begin(), starts.end(), read_begin);
        const auto hi_it = std::lower_bound(lo_it, starts.end(), read_end);
        const size_t lo = static_cast<size_t>(lo_it - starts.begin());
        const size_t hi = static_cast<size_t>(hi_it - starts.begin());
        if (lo > 0) {
            const size_t j = by_dst[lo - 1];
            if (j != i && copies[j].dst + copies[j].length > read_begin) {
                adj[i].push_back(j);
            }
        }
        for (size_t k = lo; k < hi; ++k) {
            const size_t j = by_dst[k];
            if (j != i) { adj[i].push_back(j); }
        }
    }
    return adj;
}

/// One frame of an explicit depth-first search stack.
struct Frame {
    size_t vertex;
    size_t next_edge; ///< Index into adj[vertex] of the next edge to follow.
};

/// The strongly connected components of the graph, by Tarjan's algorithm
/// (SIAM J. Comput. 1(2), 1972), without recursion.  Components come out in
/// reverse topological order.
std::vector<std::vector<size_t>> tarjan_scc(const Graph& adj) {
    const size_t n = adj.size();
    constexpr size_t UNVISITED = SIZE_MAX;
    std::vector<size_t> index(n, UNVISITED);
    std::vector<size_t> lowlink(n, 0);
    std::vector<bool> on_stack(n, false);
    std::vector<size_t> stack;
    std::vector<Frame> frames;
    std::vector<std::vector<size_t>> sccs;
    size_t next_index = 0;

    auto visit = [&](size_t v) {
        index[v] = lowlink[v] = next_index++;
        on_stack[v] = true;
        stack.push_back(v);
        frames.push_back({v, 0});
    };

    for (size_t root = 0; root < n; ++root) {
        if (index[root] != UNVISITED) { continue; }
        visit(root);
        while (!frames.empty()) {
            const size_t v = frames.back().vertex;
            if (frames.back().next_edge < adj[v].size()) {
                const size_t w = adj[v][frames.back().next_edge++];
                if (index[w] == UNVISITED) {
                    visit(w);
                } else if (on_stack[w]) {
                    lowlink[v] = std::min(lowlink[v], index[w]);
                }
                continue;
            }

            frames.pop_back();
            if (!frames.empty()) {
                const size_t parent = frames.back().vertex;
                lowlink[parent] = std::min(lowlink[parent], lowlink[v]);
            }
            if (lowlink[v] == index[v]) {
                std::vector<size_t> scc;
                size_t w;
                do {
                    w = stack.back();
                    stack.pop_back();
                    on_stack[w] = false;
                    scc.push_back(w);
                } while (w != v);
                sccs.push_back(std::move(scc));
            }
        }
    }
    return sccs;
}

/// Finds cycles among the vertices of a graph that have not been removed,
/// as vertices are removed one by one.
///
/// A cycle lies within one strongly connected component, so the components
/// of more than one vertex are searched in turn.  Removing vertices only
/// removes edges, which lets work be kept from one search to the next: a
/// vertex once found to lead to no cycle never will, and a component once
/// found to have no cycle stays that way.  Apart from retracing the path
/// that led to each cycle found, all searches together take O(n + E).
class CycleFinder {
public:
    /// Both arguments must outlive the finder.  removed[v] says whether v
    /// has been removed; the caller also reports each removal with remove().
    CycleFinder(const Graph& adj, const std::vector<bool>& removed)
        : adj_(adj), removed_(removed), scc_of_(adj.size(), NONE), state_(adj.size(), Fresh) {
        for (auto& scc : tarjan_scc(adj)) {
            if (scc.size() < 2) { continue; }
            for (size_t v : scc) { scc_of_[v] = sccs_.size(); }
            live_.push_back(scc.size());
            sccs_.push_back(std::move(scc));
        }
    }

    void remove(size_t v) {
        if (scc_of_[v] != NONE) { --live_[scc_of_[v]]; }
    }

    /// Stores in cycle the vertices of a cycle and returns true, or returns
    /// false if there is none.
    bool next(std::vector<size_t>& cycle) {
        for (; current_ < sccs_.size(); ++current_, scan_ = 0) {
            if (live_[current_] > 0 && search(cycle)) { return true; }
        }
        return false;
    }

private:
    static constexpr size_t NONE = SIZE_MAX;
    enum State : uint8_t {
        Fresh,
        OnPath,   ///< On the path of the search in progress.
        Finished, ///< Leads to no cycle.
    };

    /// Depth-first search of component current_ from each of its vertices,
    /// resuming at scan_.
    bool search(std::vector<size_t>& cycle) {
        const std::vector<size_t>& scc = sccs_[current_];
        std::vector<size_t> path;
        std::vector<Frame> frames;

        for (; scan_ < scc.size(); ++scan_) {
            const size_t start = scc[scan_];
            if (removed_[start] || state_[start] != Fresh) { continue; }
            state_[start] = OnPath;
            path.push_back(start);
            frames.push_back({start, 0});

            while (!frames.empty()) {
                const size_t v = frames.back().vertex;
                bool descended = false;
                while (frames.back().next_edge < adj_[v].size()) {
                    const size_t w = adj_[v][frames.back().next_edge++];
                    if (scc_of_[w] != current_ || removed_[w]) { continue; }
                    if (state_[w] == OnPath) {
                        cycle.assign(std::find(path.begin(), path.end(), w), path.end());
                        // The path is abandoned; its vertices will be
                        // searched again once the cycle is broken.
                        for (size_t u : path) { state_[u] = Fresh; }
                        return true;
                    }
                    if (state_[w] == Fresh) {
                        state_[w] = OnPath;
                        path.push_back(w);
                        frames.push_back({w, 0});
                        descended = true;
                        break;
                    }
                }
                if (!descended) {
                    state_[v] = Finished;
                    path.pop_back();
                    frames.pop_back();
                }
            }
        }
        return false;
    }

    const Graph& adj_;
    const std::vector<bool>& removed_;
    std::vector<std::vector<size_t>> sccs_; ///< Components of more than one vertex.
    std::vector<size_t> scc_of_;            ///< Index into sccs_, or NONE.
    std::vector<size_t> live_;              ///< Vertices not yet removed, per component.
    std::vector<State> state_;
    size_t current_ = 0; ///< Component being searched.
    size_t scan_ = 0;    ///< Position in it of the next start vertex.
};

struct Schedule {
    std::vector<size_t> order;     ///< Copies to perform, in a safe order.
    std::vector<size_t> converted; ///< Copies to replace by adds, in the order chosen.
};

/// Orders the copies by Kahn's algorithm (CACM 5(11), 1962), taking the
/// shortest ready copy first.  When no copy is ready the rest are all on or
/// behind cycles, and one is converted according to the policy.
Schedule schedule_copies(const std::vector<Copy>& copies, CyclePolicy policy) {
    const size_t n = copies.size();
    const Graph adj = build_crwi_digraph(copies);

    std::vector<size_t> in_degree(n, 0);
    for (const auto& successors : adj) {
        for (size_t j : successors) { ++in_degree[j]; }
    }

    std::vector<bool> removed(n, false);
    CycleFinder cycles(adj, removed);
    std::vector<size_t> cycle;
    size_t first_live = 0; // no vertex below this is live

    // Ready copies, as (length, index) so that the order is total.
    using Entry = std::pair<size_t, size_t>;
    std::priority_queue<Entry, std::vector<Entry>, std::greater<>> ready;
    for (size_t i = 0; i < n; ++i) {
        if (in_degree[i] == 0) { ready.emplace(copies[i].length, i); }
    }

    auto remove = [&](size_t v) {
        removed[v] = true;
        cycles.remove(v);
        for (size_t w : adj[v]) {
            if (!removed[w] && --in_degree[w] == 0) { ready.emplace(copies[w].length, w); }
        }
    };

    auto shorter = [&](size_t a, size_t b) {
        return std::pair(copies[a].length, a) < std::pair(copies[b].length, b);
    };

    Schedule s;
    s.order.reserve(n);
    while (s.order.size() + s.converted.size() < n) {
        while (!ready.empty()) {
            const size_t v = ready.top().second;
            ready.pop();
            s.order.push_back(v);
            remove(v);
        }
        if (s.order.size() + s.converted.size() == n) { break; }

        size_t victim;
        if (policy == CyclePolicy::Localmin && cycles.next(cycle)) {
            victim = *std::min_element(cycle.begin(), cycle.end(), shorter);
        } else {
            // Constant policy.  Localmin never comes here: a stalled sort
            // has a cycle among the live vertices.
            while (removed[first_live]) { ++first_live; }
            victim = first_live;
        }
        s.converted.push_back(victim);
        remove(victim);
    }
    return s;
}

} // namespace

std::vector<PlacedCommand> make_inplace(
    std::span<const uint8_t> r,
    const std::vector<Command>& commands,
    CyclePolicy policy) {

    std::vector<Copy> copies;
    std::vector<PlacedAdd> adds;
    size_t dst = 0;
    for (const auto& cmd : commands) {
        if (const auto* c = std::get_if<CopyCmd>(&cmd)) {
            copies.push_back({c->offset, dst, c->length});
            dst += c->length;
        } else {
            const auto& data = std::get<AddCmd>(cmd).data;
            adds.push_back({dst, data});
            dst += data.size();
        }
    }

    const Schedule s = schedule_copies(copies, policy);

    std::vector<PlacedCommand> result;
    result.reserve(commands.size());
    for (size_t i : s.order) {
        result.emplace_back(PlacedCopy{copies[i].src, copies[i].dst, copies[i].length});
    }
    for (auto& add : adds) { result.emplace_back(std::move(add)); }
    for (size_t i : s.converted) {
        const auto src = r.begin() + copies[i].src;
        result.emplace_back(PlacedAdd{copies[i].dst, {src, src + copies[i].length}});
    }
    return result;
}

} // namespace delta
