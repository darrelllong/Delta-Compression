use std::cmp::Reverse;
use std::collections::BinaryHeap;

use crate::types::{Command, CyclePolicy, PlacedCommand};

/// Compute SCCs using iterative Tarjan's algorithm.
///
/// Returns SCCs in reverse topological order (sinks first); caller reverses
/// for source-first processing order.
///
/// R.E. Tarjan, "Depth-first search and linear graph algorithms,"
/// SIAM J. Comput., 1(2):146-160, June 1972.
fn tarjan_scc(adj: &[Vec<usize>], n: usize) -> Vec<Vec<usize>> {
    let mut index_counter = 0usize;
    let mut index = vec![usize::MAX; n]; // MAX = unvisited
    let mut lowlink = vec![0usize; n];
    let mut on_stack = vec![false; n];
    let mut tarjan_stack: Vec<usize> = Vec::new();
    let mut sccs: Vec<Vec<usize>> = Vec::new();
    // DFS call stack: (vertex, next_neighbor_index)
    let mut call_stack: Vec<(usize, usize)> = Vec::new();

    for start in 0..n {
        if index[start] != usize::MAX {
            continue;
        }

        index[start] = index_counter;
        lowlink[start] = index_counter;
        index_counter += 1;
        on_stack[start] = true;
        tarjan_stack.push(start);
        call_stack.push((start, 0));

        while let Some(&(v, ni)) = call_stack.last() {
            if ni < adj[v].len() {
                let w = adj[v][ni];
                call_stack.last_mut().unwrap().1 += 1;
                if index[w] == usize::MAX {
                    // Tree edge: descend into w
                    index[w] = index_counter;
                    lowlink[w] = index_counter;
                    index_counter += 1;
                    on_stack[w] = true;
                    tarjan_stack.push(w);
                    call_stack.push((w, 0));
                } else if on_stack[w] {
                    // Back-edge into current SCC
                    if index[w] < lowlink[v] {
                        lowlink[v] = index[w];
                    }
                }
            } else {
                call_stack.pop();
                if let Some(&(parent, _)) = call_stack.last() {
                    if lowlink[v] < lowlink[parent] {
                        lowlink[parent] = lowlink[v];
                    }
                }
                if lowlink[v] == index[v] {
                    let mut scc = Vec::new();
                    loop {
                        let w = tarjan_stack.pop().unwrap();
                        on_stack[w] = false;
                        scc.push(w);
                        if w == v {
                            break;
                        }
                    }
                    sccs.push(scc);
                }
            }
        }
    }

    sccs // sinks first; caller reverses for source-first order
}

/// Find a cycle in the active subgraph of one SCC.
///
/// Designed for repeated calls within the same SCC between cycle breakings:
///
/// - `sid` / `scc_id`: replaces a scc_member[] bool array; O(1) per neighbor
///   check instead of O(|SCC|) set-then-clear per call.
/// - `color[]` is persistent across calls (color=2 entries from fully explored
///   vertices are not reset): total DFS work across all calls within one SCC
///   is O(|SCC| + E_SCC), not O(|SCC| × cycles_broken).
/// - `scan_start`: amortized outer-loop position; advances monotonically so
///   the total scan cost per SCC is O(|SCC|), not O(|SCC| × cycles_broken).
///
/// On cycle found: resets color=1 path vertices to 0 (so they can be
/// re-examined after victim removal), leaves color=2 intact.
/// On None: color=2 values persist; caller advances scc_ptr without cleanup
/// (vertices in other SCCs are filtered by `scc_id`, so no interference).
///
/// Stability of color=2: removing a vertex can only reduce edges, never
/// introduce new cycles.  A vertex fully explored with no cycle reachable
/// (color=2) remains cycle-free after any removal.  color=2 is monotone.
fn find_cycle_in_scc(
    adj: &[Vec<usize>],
    scc: &[usize],
    sid: usize,
    scc_id: &[usize],
    removed: &[bool],
    color: &mut [u8],
    scan_start: &mut usize,
) -> Option<Vec<usize>> {
    let mut path: Vec<usize> = Vec::new();

    while *scan_start < scc.len() {
        let start = scc[*scan_start];
        if removed[start] || color[start] != 0 {
            *scan_start += 1;
            continue;
        }

        color[start] = 1;
        path.push(start);
        let mut stack: Vec<(usize, usize)> = vec![(start, 0)];

        while !stack.is_empty() {
            let (v, ni) = *stack.last().unwrap();
            let mut next_ni = ni;
            let mut advanced = false;

            while next_ni < adj[v].len() {
                let w = adj[v][next_ni];
                next_ni += 1;
                if scc_id[w] != sid || removed[w] {
                    continue;
                }
                if color[w] == 1 {
                    // Back-edge: cycle found
                    let pos = path.iter().position(|&x| x == w).unwrap();
                    let cycle = path[pos..].to_vec();
                    // Reset path vertices to 0; color=2 for others persists.
                    for &u in &path {
                        color[u] = 0;
                    }
                    return Some(cycle);
                }
                if color[w] == 0 {
                    stack.last_mut().unwrap().1 = next_ni;
                    color[w] = 1;
                    path.push(w);
                    stack.push((w, 0));
                    advanced = true;
                    break;
                }
            }

            if !advanced {
                stack.pop();
                color[v] = 2; // Fully explored — persists across calls.
                path.pop();
            }
        }

        // start's entire reachable SCC-subgraph explored; no cycle.
        *scan_start += 1;
    }

    None
}

/// Statistics from in-place conversion.
#[derive(Debug, Default)]
pub struct InplaceStats {
    pub num_copies: usize,
    pub num_adds: usize,
    pub edges: usize,
    pub cycles_broken: usize,
    pub copies_converted: usize,
    pub bytes_converted: usize,
}

/// Convert standard delta commands to in-place executable commands.
///
/// The returned commands can be applied to a buffer initialized with R
/// to reconstruct V in-place, without a separate output buffer.
///
/// Why overlaps don't always require add conversion:
/// When copy i reads from `[src_i, src_i+len_i)` and copy j writes to
/// `[dst_j, dst_j+len_j)`, and these intervals overlap, copy i MUST execute
/// before j overwrites its source data.  This ordering constraint is an edge
/// i→j in the CRWI (Copy-Read/Write-Intersection) digraph.  When the graph
/// is acyclic, a topological order gives a valid serial schedule — no
/// conversion needed.  A cycle i₁→i₂→…→iₖ→i₁ creates a circular dependency
/// with no valid schedule; breaking it materializes one copy as a literal add
/// (saving source bytes from R before the buffer is modified).
///
/// Algorithm (Burns, Long, Stockmeyer, IEEE TKDE 2003):
///   1. Annotate each command with its write offset in the output
///   2. Build CRWI digraph: edge i→j iff i's read interval intersects j's
///      write interval (Section 4.2)
///   3. Topological sort (Kahn); when the heap empties with nodes remaining,
///      a cycle exists — find it (restricted to the stalled SCC via Tarjan
///      pre-decomposition) and convert the minimum-length copy to an add
///      (cycle-breaking policies: Section 4.3)
///   4. Output: copies in topological order, then all adds
///
/// Tarjan SCC pre-decomposition (R.E. Tarjan, SIAM J. Comput., 1(2):146-160,
/// June 1972) runs once in O(n+E), identifying which vertices participate in
/// cycles.  When Kahn stalls, find_cycle_in_scc restricts the DFS to the
/// stalled SCC.  Three amortization techniques give O(n+E) total cycle-
/// breaking work regardless of SCC size or cycle count:
///
///   1. scc_id filter (vs scc_member[]): O(1) per neighbor, no O(|SCC|)
///      set/clear array sweep per stall.
///   2. color=2 persistence: fully-explored vertices are never re-explored;
///      removal can only reduce edges, so color=2 is monotone-correct.
///   3. scan_start: outer DFS loop resumes from last position instead of
///      scanning from scc[0] each call; O(|SCC|) total per SCC.
///
/// Combined: O(n+E + Σ|SCCᵢ|) = O(n+E) cycle-breaking work.
pub fn make_inplace(
    r: &[u8],
    commands: &[Command],
    policy: CyclePolicy,
) -> (Vec<PlacedCommand>, InplaceStats) {
    let mut stats = InplaceStats::default();

    if commands.is_empty() {
        return (Vec::new(), stats);
    }

    // Step 1: compute write offsets for each command
    // copy_info: (index, src, dst, length)
    let mut copy_info: Vec<(usize, usize, usize, usize)> = Vec::new();
    let mut add_info: Vec<(usize, Vec<u8>)> = Vec::new();
    let mut write_pos: usize = 0;

    for cmd in commands {
        match cmd {
            Command::Copy { offset, length } => {
                copy_info.push((copy_info.len(), *offset, write_pos, *length));
                write_pos += length;
            }
            Command::Add { data } => {
                add_info.push((write_pos, data.clone()));
                write_pos += data.len();
            }
        }
    }

    let n = copy_info.len();
    if n == 0 {
        stats.num_adds = add_info.len();
        return (
            add_info
                .into_iter()
                .map(|(dst, data)| PlacedCommand::Add { dst, data })
                .collect(),
            stats,
        );
    }

    // Step 2: build CRWI digraph and global in-degree array
    // Edge i -> j means i's read interval [src_i, src_i+len_i) overlaps
    // j's write interval [dst_j, dst_j+len_j), so i must execute before j.
    let mut adj: Vec<Vec<usize>> = vec![Vec::new(); n];
    let mut in_deg: Vec<usize> = vec![0; n];

    // O(n log n + E) sweep-line: sort writes by start, then for each read
    // interval binary-search into the sorted writes to find overlaps.
    let mut write_sorted: Vec<usize> = (0..n).collect();
    write_sorted.sort_unstable_by_key(|&j| copy_info[j].2);
    let write_starts: Vec<usize> = write_sorted.iter().map(|&j| copy_info[j].2).collect();

    for i in 0..n {
        let (_, si, _, li) = copy_info[i];
        let read_end = si + li;
        // Two binary searches exploit the fact that dst intervals are
        // non-overlapping (each output byte written exactly once):
        //   lo = first write with dst >= si
        //   hi = first write with dst >= read_end
        // Writes in [lo, hi) start within [si, read_end) and thus always
        // overlap the read interval.  The write at lo-1 (if any) starts
        // before si; it overlaps iff its end exceeds si.
        let lo = write_starts.partition_point(|&ws| ws < si);
        let hi = write_starts.partition_point(|&ws| ws < read_end);
        if lo > 0 {
            let j = write_sorted[lo - 1];
            if j != i {
                let (_, _, dj, lj) = copy_info[j];
                if dj + lj > si {
                    adj[i].push(j);
                    in_deg[j] += 1;
                    stats.edges += 1;
                }
            }
        }
        for k in lo..hi {
            let j = write_sorted[k];
            if j != i {
                adj[i].push(j);
                in_deg[j] += 1;
                stats.edges += 1;
            }
        }
    }

    // Step 3: Kahn topological sort with Tarjan-scoped cycle breaking.
    //
    // Tarjan SCC pre-decomposition identifies which vertices are in cycles
    // (non-trivial SCCs).  When Kahn stalls, find_cycle_in_scc restricts
    // the cycle search to the stalled SCC's vertices, giving O(|SCC| + E_SCC)
    // amortized work per SCC instead of O(n + E) per stall.  The global Kahn
    // heap and in_deg array handle all vertices uniformly, preserving the
    // cascade effect: converting a victim decrements in_deg globally,
    // potentially freeing vertices across SCC boundaries without extra
    // conversions.

    // Tarjan SCC: identify cyclic vertices and their SCC membership.
    let sccs = tarjan_scc(&adj, n);

    let mut scc_id = vec![usize::MAX; n]; // MAX = trivial (no cycle)
    let mut scc_list: Vec<Vec<usize>> = Vec::new(); // non-trivial SCCs only
    let mut scc_active: Vec<usize> = Vec::new(); // live member count per SCC

    for scc in &sccs {
        if scc.len() > 1 {
            let id = scc_list.len();
            for &v in scc {
                scc_id[v] = id;
            }
            scc_active.push(scc.len());
            scc_list.push(scc.clone());
        }
    }

    // Global Kahn min-heap: (copy_length, index) for deterministic order.
    let mut removed = vec![false; n];
    let mut topo_order: Vec<usize> = Vec::with_capacity(n);

    // color[]: DFS state for find_cycle_in_scc.  Persists across calls within
    // the same SCC (color=2 = fully explored, monotone-correct under removal).
    let mut color = vec![0u8; n];

    // scc_ptr indexes into scc_list.  Advances monotonically: O(|scc_list|)
    // total.  scan_pos is the amortized outer-loop start within scc_list[scc_ptr].
    let mut scc_ptr = 0usize;
    let mut scan_pos = 0usize;

    let mut heap: BinaryHeap<Reverse<(usize, usize)>> = BinaryHeap::new();
    for i in 0..n {
        if in_deg[i] == 0 {
            heap.push(Reverse((copy_info[i].3, i)));
        }
    }
    let mut processed = 0;

    while processed < n {
        // Drain all ready vertices.
        while let Some(Reverse((_, v))) = heap.pop() {
            if removed[v] {
                continue;
            }
            removed[v] = true;
            topo_order.push(v);
            processed += 1;
            // Maintain scc_active so the scc_ptr skip is O(1).
            if scc_id[v] != usize::MAX {
                scc_active[scc_id[v]] -= 1;
            }
            for &w in &adj[v] {
                if !removed[w] {
                    in_deg[w] -= 1;
                    if in_deg[w] == 0 {
                        heap.push(Reverse((copy_info[w].3, w)));
                    }
                }
            }
        }

        if processed >= n {
            break;
        }

        // Kahn stalled: all remaining vertices are in CRWI cycles.
        // Choose a victim to convert from copy to add.
        let victim = match policy {
            CyclePolicy::Constant => (0..n).find(|&i| !removed[i]).unwrap(),
            CyclePolicy::Localmin => {
                // Advance scc_ptr past SCCs whose members are all removed.
                // scc_active[id] == 0 means all live members were freed by
                // Kahn or earlier conversions; this SCC needs no more work.
                // scc_ptr advances O(|scc_list|) total across all stalls.
                loop {
                    while scc_ptr < scc_list.len() && scc_active[scc_ptr] == 0 {
                        scc_ptr += 1;
                        scan_pos = 0;
                    }
                    if scc_ptr >= scc_list.len() {
                        // Safety fallback — should not happen with a correct graph.
                        break (0..n).find(|&i| !removed[i]).unwrap();
                    }
                    let result = find_cycle_in_scc(
                        &adj,
                        &scc_list[scc_ptr],
                        scc_ptr,
                        &scc_id,
                        &removed,
                        &mut color,
                        &mut scan_pos,
                    );
                    match result {
                        Some(cycle) => {
                            break *cycle
                                .iter()
                                .min_by_key(|&&i| (copy_info[i].3, i))
                                .unwrap();
                        }
                        None => {
                            // This SCC's remaining subgraph is acyclic (all
                            // cycles already broken); advance to next SCC.
                            // color=2 values for this SCC's members persist
                            // harmlessly (other SCCs use scc_id filter).
                            scc_ptr += 1;
                            scan_pos = 0;
                        }
                    }
                }
            }
        };

        // Convert victim: materialize its copy data as a literal add.
        let (_, src, dst, length) = copy_info[victim];
        add_info.push((dst, r[src..src + length].to_vec()));
        stats.cycles_broken += 1;
        stats.copies_converted += 1;
        stats.bytes_converted += length;
        removed[victim] = true;
        processed += 1;
        // Maintain scc_active (victim's color was reset to 0 by path reset).
        if scc_id[victim] != usize::MAX {
            scc_active[scc_id[victim]] -= 1;
        }

        for &w in &adj[victim] {
            if !removed[w] {
                in_deg[w] -= 1;
                if in_deg[w] == 0 {
                    heap.push(Reverse((copy_info[w].3, w)));
                }
            }
        }
    }

    // Step 4: assemble result — copies in topo order, then all adds
    let mut result: Vec<PlacedCommand> = Vec::new();

    for &i in &topo_order {
        let (_, src, dst, length) = copy_info[i];
        result.push(PlacedCommand::Copy { src, dst, length });
    }

    stats.num_copies = topo_order.len();

    for (dst, data) in add_info {
        result.push(PlacedCommand::Add { dst, data });
    }

    stats.num_adds = result.len() - stats.num_copies;

    (result, stats)
}
