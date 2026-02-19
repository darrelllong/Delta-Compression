use std::cmp::Reverse;
use std::collections::BinaryHeap;

use crate::types::{Command, CyclePolicy, PlacedCommand};

/// Find a cycle in the subgraph of non-removed vertices.
///
/// Iterative DFS with three-color marking (unvisited / on-path / done).
/// A back-edge to an on-path vertex (color 1) signals a cycle.
/// Returns the cycle vertices in order, or None if the graph is acyclic.
fn find_cycle(adj: &[Vec<usize>], removed: &[bool], n: usize) -> Option<Vec<usize>> {
    let mut color = vec![0u8; n]; // 0=unvisited, 1=on current path, 2=done
    let mut path: Vec<usize> = Vec::new();

    for start in 0..n {
        if removed[start] || color[start] != 0 {
            continue;
        }

        color[start] = 1;
        path.push(start);
        // Stack entries: (vertex, index of next neighbor to visit)
        let mut stack: Vec<(usize, usize)> = vec![(start, 0)];

        while !stack.is_empty() {
            let (v, ni) = *stack.last().unwrap();
            let mut advanced = false;
            let mut next_ni = ni;

            while next_ni < adj[v].len() {
                let w = adj[v][next_ni];
                next_ni += 1;
                if removed[w] {
                    continue;
                }
                if color[w] == 1 {
                    // Back-edge: w is on the current path — cycle found.
                    let pos = path.iter().position(|&x| x == w).unwrap();
                    return Some(path[pos..].to_vec());
                }
                if color[w] == 0 {
                    // Save progress on current frame, then descend.
                    stack.last_mut().unwrap().1 = next_ni;
                    color[w] = 1;
                    path.push(w);
                    stack.push((w, 0));
                    advanced = true;
                    break;
                }
                // color[w] == 2: already fully explored, skip.
            }

            if !advanced {
                // All neighbors explored — backtrack.
                stack.pop();
                color[v] = 2;
                path.pop();
            }
        }
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
///      a cycle exists — find it and convert the minimum-length copy to an add
///      (cycle-breaking policies: Section 4.3)
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

    // Step 2: build CRWI digraph
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

    // Step 3: topological sort with cycle breaking (Kahn's algorithm)
    // Priority queue keyed on copy length — always process the smallest
    // ready copy first, giving a deterministic topological ordering.
    let mut removed = vec![false; n];
    let mut topo_order = Vec::with_capacity(n);
    let mut converted = Vec::new();

    // Min-heap: Reverse so BinaryHeap (max-heap) becomes a min-heap.
    // Key = (copy_length, index) for deterministic ordering.
    let mut heap: BinaryHeap<Reverse<(usize, usize)>> = BinaryHeap::new();
    for i in 0..n {
        if in_deg[i] == 0 {
            heap.push(Reverse((copy_info[i].3, i)));
        }
    }
    let mut processed = 0;

    while processed < n {
        while let Some(Reverse((_, v))) = heap.pop() {
            if removed[v] {
                continue;
            }
            removed[v] = true;
            topo_order.push(v);
            processed += 1;
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

        // Cycle detected — choose a victim to convert from copy to add
        let victim = match policy {
            CyclePolicy::Constant => (0..n).find(|&i| !removed[i]).unwrap(),
            CyclePolicy::Localmin => {
                if let Some(cycle) = find_cycle(&adj, &removed, n) {
                    // Key on (length, index) for deterministic tie-breaking
                    // — same composite key as the Kahn's PQ above.
                    *cycle
                        .iter()
                        .min_by_key(|&&i| (copy_info[i].3, i))
                        .unwrap()
                } else {
                    (0..n).find(|&i| !removed[i]).unwrap()
                }
            }
        };

        // Convert victim: materialize its copy data as a literal add
        let (_, src, dst, length) = copy_info[victim];
        add_info.push((dst, r[src..src + length].to_vec()));
        converted.push(victim);
        stats.cycles_broken += 1;
        stats.copies_converted += 1;
        stats.bytes_converted += length;
        removed[victim] = true;
        processed += 1;

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
