//! Conversion of a delta to one that can be applied in place (Burns, Long
//! and Stockmeyer, "In-Place Reconstruction of Version Differences", IEEE
//! TKDE 15(4), 2003).
//!
//! Applied in place, a copy reads from the buffer that other commands are
//! overwriting.  If copy i reads bytes that copy j writes, i must run
//! before j.  These constraints form a digraph on the copies, with an edge
//! from i to j.  If it is acyclic, the copies are run in a topological
//! order.  A cycle has no valid order, and is broken by replacing one of
//! its copies with an add of the same bytes, which reads nothing.  Adds run
//! after all the copies.

use std::cmp::Reverse;
use std::collections::BinaryHeap;

use crate::types::{Command, CyclePolicy, PlacedCommand};

/// A copy command and the offset in the version where it writes.
#[derive(Clone, Copy)]
struct CopyCmd {
    src: usize,
    dst: usize,
    length: usize,
}

/// What [`make_inplace`] did.
#[derive(Debug, Default)]
pub struct InplaceStats {
    /// Copies in the result.
    pub num_copies: usize,
    /// Adds in the result, including those that replaced copies.
    pub num_adds: usize,
    /// Edges in the digraph of conflicts between copies.
    pub edges: usize,
    /// Cycles broken, each by replacing one copy with an add.
    pub cycles_broken: usize,
    /// Copies replaced by adds, one for each cycle broken.
    pub copies_converted: usize,
    /// Total length of the copies replaced.
    pub bytes_converted: usize,
}

/// Builds the digraph in which i has an edge to j if copy i reads bytes
/// that copy j writes.  Returns the adjacency lists and the in-degrees.
///
/// The copies write disjoint ranges.  Sorted by destination, the ones that
/// overlap a given read range are therefore consecutive and are found by
/// binary search, in O(n log n + edges) time overall.
fn conflict_graph(copies: &[CopyCmd]) -> (Vec<Vec<usize>>, Vec<usize>) {
    let n = copies.len();
    let mut adj = vec![Vec::new(); n];
    let mut in_deg = vec![0; n];

    let mut by_dst: Vec<usize> = (0..n).collect();
    by_dst.sort_unstable_by_key(|&j| copies[j].dst);
    let starts: Vec<usize> = by_dst.iter().map(|&j| copies[j].dst).collect();

    for (i, reader) in copies.iter().enumerate() {
        let read_end = reader.src + reader.length;
        // Writes that start inside the read range overlap it.  The one
        // write that starts before the range overlaps it if it runs into it.
        let lo = starts.partition_point(|&start| start < reader.src);
        let hi = starts.partition_point(|&start| start < read_end);
        let before = lo.checked_sub(1).filter(|&k| {
            let writer = &copies[by_dst[k]];
            writer.dst + writer.length > reader.src
        });
        for k in before.into_iter().chain(lo..hi) {
            let j = by_dst[k];
            if j != i {
                adj[i].push(j);
                in_deg[j] += 1;
            }
        }
    }
    (adj, in_deg)
}

/// Returns the strongly connected components of the graph, each before any
/// component that has an edge into it.
///
/// This is Tarjan's algorithm (SIAM J. Comput. 1(2), 1972) with an explicit
/// stack in place of recursion, which a long chain of copies would exhaust.
fn tarjan_scc(adj: &[Vec<usize>]) -> Vec<Vec<usize>> {
    const UNVISITED: usize = usize::MAX;
    let n = adj.len();
    let mut next_index = 0;
    let mut index = vec![UNVISITED; n];
    let mut lowlink = vec![0; n];
    let mut on_stack = vec![false; n];
    let mut stack = Vec::new();
    let mut sccs = Vec::new();
    // The depth-first search: each frame is a vertex and the number of its
    // edges already followed.
    let mut dfs: Vec<(usize, usize)> = Vec::new();

    for start in 0..n {
        if index[start] != UNVISITED {
            continue;
        }
        dfs.push((start, 0));
        while let Some(&mut (v, ref mut followed)) = dfs.last_mut() {
            if index[v] == UNVISITED {
                index[v] = next_index;
                lowlink[v] = next_index;
                next_index += 1;
                on_stack[v] = true;
                stack.push(v);
            }
            if let Some(&w) = adj[v].get(*followed) {
                *followed += 1;
                if index[w] == UNVISITED {
                    dfs.push((w, 0));
                } else if on_stack[w] {
                    lowlink[v] = lowlink[v].min(index[w]);
                }
                continue;
            }
            dfs.pop();
            if let Some(&(parent, _)) = dfs.last() {
                lowlink[parent] = lowlink[parent].min(lowlink[v]);
            }
            if lowlink[v] == index[v] {
                let mut scc = Vec::new();
                loop {
                    let w = stack.pop().expect("v is on the stack");
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
    sccs
}

#[derive(Clone, Copy, PartialEq)]
enum Color {
    Unvisited,
    OnPath,
    /// No cycle passes through the vertex.  Removing vertices cannot create
    /// one, so the color is permanent.
    Done,
}

/// Finds cycles among the copies that remain, for the life of one
/// conversion.
///
/// A cycle lies within a strongly connected component of more than one
/// vertex, so only those are searched, one at a time.  A search never
/// revisits a vertex colored `Done`, but it does walk again the path that
/// led to the cycle before, so the searches together are not linear in the
/// worst case.  The paper's bound for this policy is O(|V|^2) (Section 4.5).
struct CycleFinder {
    /// The components with more than one vertex.
    sccs: Vec<Vec<usize>>,
    /// The index in `sccs` of each vertex's component; `NO_SCC` if the
    /// vertex is a component by itself.
    scc_of: Vec<usize>,
    color: Vec<Color>,
    /// The component being searched.
    current: usize,
    /// Vertices of the current component before this position are removed
    /// or `Done`.
    scan: usize,
}

const NO_SCC: usize = usize::MAX;

impl CycleFinder {
    fn new(adj: &[Vec<usize>]) -> Self {
        let mut sccs = tarjan_scc(adj);
        sccs.retain(|scc| scc.len() > 1);
        let mut scc_of = vec![NO_SCC; adj.len()];
        for (id, scc) in sccs.iter().enumerate() {
            for &v in scc {
                scc_of[v] = id;
            }
        }
        CycleFinder {
            sccs,
            scc_of,
            color: vec![Color::Unvisited; adj.len()],
            current: 0,
            scan: 0,
        }
    }

    /// Returns the vertices of a cycle among those not removed.
    ///
    /// # Panics
    ///
    /// Panics if there is no cycle.
    fn next_cycle(&mut self, adj: &[Vec<usize>], removed: &[bool]) -> Vec<usize> {
        loop {
            assert!(self.current < self.sccs.len(), "no cycle remains");
            if let Some(cycle) = self.cycle_in_current(adj, removed) {
                return cycle;
            }
            self.current += 1;
            self.scan = 0;
        }
    }

    /// Searches the current component depth-first for an edge back to a
    /// vertex on the search path.
    fn cycle_in_current(&mut self, adj: &[Vec<usize>], removed: &[bool]) -> Option<Vec<usize>> {
        let scc = &self.sccs[self.current];
        let mut path = Vec::new();
        // For each vertex on the path, the number of its edges followed.
        let mut followed: Vec<usize> = Vec::new();

        while let Some(&start) = scc.get(self.scan) {
            if removed[start] || self.color[start] != Color::Unvisited {
                self.scan += 1;
                continue;
            }
            self.color[start] = Color::OnPath;
            path.push(start);
            followed.push(0);

            while let (Some(&v), Some(next)) = (path.last(), followed.last_mut()) {
                let Some(&w) = adj[v].get(*next) else {
                    self.color[v] = Color::Done;
                    path.pop();
                    followed.pop();
                    continue;
                };
                *next += 1;
                if self.scc_of[w] != self.current || removed[w] {
                    continue;
                }
                match self.color[w] {
                    Color::OnPath => {
                        // The path is searched again after the caller has
                        // removed a vertex of the cycle.
                        for &u in &path {
                            self.color[u] = Color::Unvisited;
                        }
                        let first = path.iter().position(|&u| u == w).expect("w is on the path");
                        return Some(path.split_off(first));
                    }
                    Color::Unvisited => {
                        self.color[w] = Color::OnPath;
                        path.push(w);
                        followed.push(0);
                    }
                    Color::Done => {}
                }
            }
            self.scan += 1;
        }
        None
    }
}

/// Kahn's topological sort of the copies (Comm. ACM 5(11), 1962), which
/// breaks a cycle whenever no copy is free to run.
struct Scheduler<'a> {
    copies: &'a [CopyCmd],
    adj: &'a [Vec<usize>],
    /// For each copy, the number of unremoved copies that must precede it.
    in_deg: Vec<usize>,
    removed: Vec<bool>,
    /// The copies free to run, keyed by (length, index).  The total order
    /// makes the result deterministic.
    ready: BinaryHeap<Reverse<(usize, usize)>>,
}

impl Scheduler<'_> {
    fn remove(&mut self, v: usize) {
        self.removed[v] = true;
        for &w in &self.adj[v] {
            if !self.removed[w] {
                self.in_deg[w] -= 1;
                if self.in_deg[w] == 0 {
                    self.ready.push(Reverse((self.copies[w].length, w)));
                }
            }
        }
    }

    /// Returns the copies to run, in order, and the copies to replace with
    /// adds.
    fn run(mut self, policy: CyclePolicy) -> (Vec<usize>, Vec<usize>) {
        let n = self.copies.len();
        let mut order = Vec::with_capacity(n);
        let mut victims = Vec::new();
        let mut cycles = None;

        for (v, &deg) in self.in_deg.iter().enumerate() {
            if deg == 0 {
                self.ready.push(Reverse((self.copies[v].length, v)));
            }
        }
        loop {
            while let Some(Reverse((_, v))) = self.ready.pop() {
                order.push(v);
                self.remove(v);
            }
            if order.len() + victims.len() == n {
                return (order, victims);
            }
            // Every copy that remains waits on another, so some of them
            // form a cycle.
            let victim = match policy {
                CyclePolicy::Constant => self.removed.iter().position(|&gone| !gone),
                CyclePolicy::Localmin => cycles
                    .get_or_insert_with(|| CycleFinder::new(self.adj))
                    .next_cycle(self.adj, &self.removed)
                    .into_iter()
                    .min_by_key(|&v| (self.copies[v].length, v)),
            }
            .expect("a copy remains");
            victims.push(victim);
            self.remove(victim);
        }
    }
}

/// Converts the output of a differencing algorithm to commands that
/// reconstruct the version in the buffer that holds the reference `r`.
///
/// The result lists the copies in an order in which each reads its source
/// before any command overwrites it, and then the adds.  Each cycle among
/// the copies costs one copy, chosen by `policy`, which becomes an add of
/// the bytes it would have copied from `r`.
pub fn make_inplace(
    r: &[u8],
    commands: &[Command],
    policy: CyclePolicy,
) -> (Vec<PlacedCommand>, InplaceStats) {
    let mut copies = Vec::new();
    let mut adds = Vec::new();
    let mut dst = 0;
    for cmd in commands {
        match cmd {
            Command::Copy { offset, length } => {
                copies.push(CopyCmd {
                    src: *offset,
                    dst,
                    length: *length,
                });
                dst += length;
            }
            Command::Add { data } => {
                adds.push(PlacedCommand::Add {
                    dst,
                    data: data.clone(),
                });
                dst += data.len();
            }
        }
    }

    let (adj, in_deg) = conflict_graph(&copies);
    let edges = in_deg.iter().sum();
    let scheduler = Scheduler {
        copies: &copies,
        adj: &adj,
        in_deg,
        removed: vec![false; copies.len()],
        ready: BinaryHeap::new(),
    };
    let (order, victims) = scheduler.run(policy);

    let stats = InplaceStats {
        num_copies: order.len(),
        num_adds: adds.len() + victims.len(),
        edges,
        cycles_broken: victims.len(),
        copies_converted: victims.len(),
        bytes_converted: victims.iter().map(|&i| copies[i].length).sum(),
    };
    let mut result = Vec::with_capacity(commands.len());
    result.extend(order.into_iter().map(|i| {
        let CopyCmd { src, dst, length } = copies[i];
        PlacedCommand::Copy { src, dst, length }
    }));
    result.append(&mut adds);
    result.extend(victims.into_iter().map(|i| {
        let CopyCmd { src, dst, length } = copies[i];
        PlacedCommand::Add {
            dst,
            data: r[src..src + length].to_vec(),
        }
    }));
    (result, stats)
}
