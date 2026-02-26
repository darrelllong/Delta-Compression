use std::cmp::Reverse;
use std::collections::BinaryHeap;

use crate::types::{Command, CyclePolicy, PlacedCommand};

// ── DFS color states for find_cycle_in_scc ──────────────────────────────────
const COLOR_UNVISITED: u8 = 0;
const COLOR_ON_PATH:   u8 = 1;
const COLOR_DONE:      u8 = 2;

/// Sentinel value meaning "vertex is in no non-trivial SCC".
const NO_SCC: usize = usize::MAX;

// ── Data structures ──────────────────────────────────────────────────────────

/// Source offset, destination offset, and length of one copy command.
#[derive(Clone, Copy)]
struct CopyInfo {
    src:    usize,
    dst:    usize,
    length: usize,
}

/// Non-trivial SCCs with per-SCC active counts and vertex-to-SCC mapping.
struct SccList {
    sccs:   Vec<Vec<usize>>,  // non-trivial SCCs only
    active: Vec<usize>,        // live member count per SCC
    id:     Vec<usize>,        // vertex → SCC index; NO_SCC = trivial
}

/// Statistics from in-place conversion.
#[derive(Debug, Default)]
pub struct InplaceStats {
    pub num_copies:       usize,
    pub num_adds:         usize,
    pub edges:            usize,
    pub cycles_broken:    usize,
    pub copies_converted: usize,
    pub bytes_converted:  usize,
}

// ── Tarjan SCC (unchanged) ───────────────────────────────────────────────────

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

// ── Cycle finder (unchanged interface, improved constants) ───────────────────

/// Find a cycle in the active subgraph of one SCC.
///
/// Designed for repeated calls within the same SCC between cycle breakings:
///
/// - `sid` / `scc_id`: replaces a scc_member[] bool array; O(1) per neighbor
///   check instead of O(|SCC|) set-then-clear per call.
/// - `color[]` is persistent across calls (COLOR_DONE entries from fully
///   explored vertices are not reset): total DFS work across all calls within
///   one SCC is O(|SCC| + E_SCC), not O(|SCC| × cycles_broken).
/// - `scan_start`: amortized outer-loop position; advances monotonically so
///   the total scan cost per SCC is O(|SCC|), not O(|SCC| × cycles_broken).
///
/// On cycle found: resets COLOR_ON_PATH path vertices to COLOR_UNVISITED so
/// they can be re-examined after victim removal; leaves COLOR_DONE intact.
/// On None: COLOR_DONE values persist; caller advances scc_ptr without cleanup.
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
        if removed[start] || color[start] != COLOR_UNVISITED {
            *scan_start += 1;
            continue;
        }

        color[start] = COLOR_ON_PATH;
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
                if color[w] == COLOR_ON_PATH {
                    // Back-edge: cycle found
                    let pos = path.iter().position(|&x| x == w).unwrap();
                    let cycle = path[pos..].to_vec();
                    for &u in &path {
                        color[u] = COLOR_UNVISITED;
                    }
                    return Some(cycle);
                }
                if color[w] == COLOR_UNVISITED {
                    stack.last_mut().unwrap().1 = next_ni;
                    color[w] = COLOR_ON_PATH;
                    path.push(w);
                    stack.push((w, 0));
                    advanced = true;
                    break;
                }
            }

            if !advanced {
                stack.pop();
                color[v] = COLOR_DONE; // Fully explored — persists across calls.
                path.pop();
            }
        }

        // start's entire reachable SCC-subgraph explored; no cycle.
        *scan_start += 1;
    }

    None
}

// ── Extracted helpers ────────────────────────────────────────────────────────

/// Build CRWI digraph on copy commands.
///
/// Edge i→j means copy i reads from a region that copy j will overwrite,
/// so i must execute before j.  O(n log n + E) sweep-line construction.
/// Returns (adj, in_deg).
fn build_crwi_digraph(
    copy_info: &[CopyInfo],
    n: usize,
    stats: &mut InplaceStats,
) -> (Vec<Vec<usize>>, Vec<usize>) {
    let mut adj:    Vec<Vec<usize>> = vec![Vec::new(); n];
    let mut in_deg: Vec<usize>      = vec![0; n];

    // Sort copy write-intervals by start; binary-search for each read interval.
    let mut write_sorted: Vec<usize> = (0..n).collect();
    write_sorted.sort_unstable_by_key(|&j| copy_info[j].dst);
    let write_starts: Vec<usize> = write_sorted.iter().map(|&j| copy_info[j].dst).collect();

    for i in 0..n {
        let src      = copy_info[i].src;
        let length   = copy_info[i].length;
        let read_end = src + length;
        // lo = first write with dst >= src; hi = first write with dst >= read_end.
        // Writes in [lo, hi) start inside [src, read_end) — they always overlap.
        // The write at lo-1 starts before src; overlaps iff its end exceeds src.
        let lo = write_starts.partition_point(|&ws| ws < src);
        let hi = write_starts.partition_point(|&ws| ws < read_end);
        if lo > 0 {
            let j = write_sorted[lo - 1];
            if j != i {
                let dj = copy_info[j].dst;
                let lj = copy_info[j].length;
                if dj + lj > src {
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
    (adj, in_deg)
}

/// Wrap tarjan_scc output into an SccList containing only non-trivial SCCs.
fn build_scc_list(adj: &[Vec<usize>], n: usize) -> SccList {
    let sccs_raw = tarjan_scc(adj, n);
    let mut id     = vec![NO_SCC; n];
    let mut sccs:   Vec<Vec<usize>> = Vec::new();
    let mut active: Vec<usize>      = Vec::new();

    for scc in &sccs_raw {
        if scc.len() > 1 {
            let sid = sccs.len();
            for &v in scc {
                id[v] = sid;
            }
            active.push(scc.len());
            sccs.push(scc.clone());
        }
    }
    SccList { sccs, active, id }
}

/// Select a victim copy to break a cycle when Kahn's algorithm stalls.
///
/// Constant: first remaining vertex.  Localmin: minimum-length copy in a cycle.
/// scc_ptr and scan_pos are advanced in place across repeated calls.
fn pick_victim(
    copy_info: &[CopyInfo],
    adj:       &[Vec<usize>],
    scc_list:  &mut SccList,
    removed:   &[bool],
    color:     &mut [u8],
    scc_ptr:   &mut usize,
    scan_pos:  &mut usize,
    policy:    CyclePolicy,
    n:         usize,
) -> usize {
    match policy {
        CyclePolicy::Constant => (0..n).find(|&i| !removed[i]).unwrap(),
        CyclePolicy::Localmin => loop {
            while *scc_ptr < scc_list.sccs.len() && scc_list.active[*scc_ptr] == 0 {
                *scc_ptr += 1;
                *scan_pos = 0;
            }
            if *scc_ptr >= scc_list.sccs.len() {
                // Safety fallback — should not happen with a correct graph.
                break (0..n).find(|&i| !removed[i]).unwrap();
            }
            let result = find_cycle_in_scc(
                adj,
                &scc_list.sccs[*scc_ptr],
                *scc_ptr,
                &scc_list.id,
                removed,
                color,
                scan_pos,
            );
            match result {
                Some(cycle) => {
                    break *cycle.iter().min_by_key(|&&i| (copy_info[i].length, i)).unwrap();
                }
                None => {
                    // This SCC's remaining subgraph is acyclic; advance.
                    *scc_ptr += 1;
                    *scan_pos = 0;
                }
            }
        },
    }
}

/// Run Kahn topological sort; when the heap stalls, call pick_victim to break
/// the cycle by materialising one copy as a literal add.
fn run_kahn(
    copy_info: &[CopyInfo],
    adj:       &[Vec<usize>],
    scc_list:  &mut SccList,
    in_deg:    &mut [usize],
    r:         &[u8],
    add_info:  &mut Vec<(usize, Vec<u8>)>,
    policy:    CyclePolicy,
    n:         usize,
    stats:     &mut InplaceStats,
) -> Vec<usize> {
    let mut removed    = vec![false; n];
    let mut topo_order = Vec::with_capacity(n);
    let mut color      = vec![COLOR_UNVISITED; n];
    let mut scc_ptr    = 0usize;
    let mut scan_pos   = 0usize;

    let mut heap: BinaryHeap<Reverse<(usize, usize)>> = BinaryHeap::new();
    for i in 0..n {
        if in_deg[i] == 0 {
            heap.push(Reverse((copy_info[i].length, i)));
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
            if scc_list.id[v] != NO_SCC {
                scc_list.active[scc_list.id[v]] -= 1;
            }
            for &w in &adj[v] {
                if !removed[w] {
                    in_deg[w] -= 1;
                    if in_deg[w] == 0 {
                        heap.push(Reverse((copy_info[w].length, w)));
                    }
                }
            }
        }

        if processed >= n {
            break;
        }

        // Kahn stalled: all remaining vertices are in CRWI cycles.
        let victim = pick_victim(
            copy_info, adj, scc_list, &removed, &mut color,
            &mut scc_ptr, &mut scan_pos, policy, n,
        );
        let ci = copy_info[victim];
        add_info.push((ci.dst, r[ci.src..ci.src + ci.length].to_vec()));
        stats.cycles_broken    += 1;
        stats.copies_converted += 1;
        stats.bytes_converted  += ci.length;
        removed[victim] = true;
        processed += 1;
        if scc_list.id[victim] != NO_SCC {
            scc_list.active[scc_list.id[victim]] -= 1;
        }
        for &w in &adj[victim] {
            if !removed[w] {
                in_deg[w] -= 1;
                if in_deg[w] == 0 {
                    heap.push(Reverse((copy_info[w].length, w)));
                }
            }
        }
    }
    topo_order
}

// ── Public entry point ───────────────────────────────────────────────────────

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
///      a cycle exists — find it and convert the minimum-length copy to an add
///   4. Output: copies in topological order, then all adds
pub fn make_inplace(
    r: &[u8],
    commands: &[Command],
    policy: CyclePolicy,
) -> (Vec<PlacedCommand>, InplaceStats) {
    let mut stats = InplaceStats::default();

    if commands.is_empty() {
        return (Vec::new(), stats);
    }

    // Step 1: compute write offsets
    let mut copy_info: Vec<CopyInfo>       = Vec::new();
    let mut add_info:  Vec<(usize, Vec<u8>)> = Vec::new();
    let mut write_pos = 0usize;

    for cmd in commands {
        match cmd {
            Command::Copy { offset, length } => {
                copy_info.push(CopyInfo { src: *offset, dst: write_pos, length: *length });
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

    // Steps 2-3: build digraph, topological sort, break cycles
    let (adj, mut in_deg) = build_crwi_digraph(&copy_info, n, &mut stats);
    let mut scc_list      = build_scc_list(&adj, n);
    let topo_order        = run_kahn(
        &copy_info, &adj, &mut scc_list, &mut in_deg,
        r, &mut add_info, policy, n, &mut stats,
    );

    // Step 4: assemble result — copies in topo order, then all adds
    stats.num_copies = topo_order.len();
    let mut result: Vec<PlacedCommand> = Vec::new();
    for &i in &topo_order {
        let ci = copy_info[i];
        result.push(PlacedCommand::Copy { src: ci.src, dst: ci.dst, length: ci.length });
    }
    for (dst, data) in add_info {
        result.push(PlacedCommand::Add { dst, data });
    }
    stats.num_adds = result.len() - stats.num_copies;

    (result, stats)
}
