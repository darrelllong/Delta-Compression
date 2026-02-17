use std::cmp::Reverse;
use std::collections::BinaryHeap;

use crate::types::{Command, CyclePolicy, PlacedCommand};

/// Find a cycle in the subgraph of non-removed vertices.
///
/// Follows forward edges from each non-removed vertex until we revisit
/// one (cycle found) or reach a dead end (try next start vertex).
/// Returns a list of vertex indices forming the cycle, or None.
fn find_cycle(adj: &[Vec<usize>], removed: &[bool], n: usize) -> Option<Vec<usize>> {
    for start in 0..n {
        if removed[start] {
            continue;
        }
        let mut visited = std::collections::HashMap::new();
        let mut path = Vec::new();
        let mut curr = Some(start);
        let mut step = 0usize;

        while let Some(c) = curr {
            if visited.contains_key(&c) {
                // Found a cycle
                let cycle_idx = visited[&c];
                return Some(path[cycle_idx..].to_vec());
            }
            visited.insert(c, step);
            path.push(c);
            step += 1;

            // Find next non-removed neighbor
            let mut next_v = None;
            for &w in &adj[c] {
                if !removed[w] {
                    next_v = Some(w);
                    break;
                }
            }
            curr = next_v;
        }
    }
    None
}

/// Convert standard delta commands to in-place executable commands.
///
/// The returned commands can be applied to a buffer initialized with R
/// to reconstruct V in-place, without a separate output buffer.
///
/// Algorithm (Burns, Long, Stockmeyer, IEEE TKDE 2003):
///   1. Annotate each command with its write offset in the output
///   2. Build CRWI (Copy-Read/Write-Intersection) digraph on copy commands
///      (Section 4.2) — edge from i to j when i's read interval overlaps
///      j's write interval (i must execute before j)
///   3. Topological sort; break cycles by converting copies to adds
///      (cycle-breaking policies: Section 4.3)
///   4. Output: copies in topological order, then all adds
pub fn make_inplace(
    r: &[u8],
    commands: &[Command],
    policy: CyclePolicy,
) -> Vec<PlacedCommand> {
    if commands.is_empty() {
        return Vec::new();
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
        return add_info
            .into_iter()
            .map(|(dst, data)| PlacedCommand::Add { dst, data })
            .collect();
    }

    // Step 2: build CRWI digraph
    // Edge i -> j means i's read interval [src_i, src_i+len_i) overlaps
    // j's write interval [dst_j, dst_j+len_j), so i must execute before j.
    let mut adj: Vec<Vec<usize>> = vec![Vec::new(); n];
    let mut in_deg: Vec<usize> = vec![0; n];

    for i in 0..n {
        let (_, si, _, li) = copy_info[i];
        for j in 0..n {
            if i == j {
                continue;
            }
            let (_, _, dj, lj) = copy_info[j];
            if si < dj + lj && dj < si + li {
                adj[i].push(j);
                in_deg[j] += 1;
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
                    *cycle
                        .iter()
                        .min_by_key(|&&i| copy_info[i].3)
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

    for (dst, data) in add_info {
        result.push(PlacedCommand::Add { dst, data });
    }

    result
}
