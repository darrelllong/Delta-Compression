use std::collections::HashMap;

use super::{common_prefix, index_name, print_command_stats, seed_count};
use crate::hash::SeedScanner;
use crate::splay::SplayTree;
use crate::types::{Command, DiffOptions};

/// Every offset in R at which a seed with a given fingerprint begins, in
/// increasing order.
enum SeedIndex {
    Hash(HashMap<u64, Vec<usize>>),
    Splay(SplayTree<Vec<usize>>),
}

impl SeedIndex {
    fn build(r: &[u8], p: usize, use_splay: bool) -> Self {
        let mut index = if use_splay {
            SeedIndex::Splay(SplayTree::new())
        } else {
            SeedIndex::Hash(HashMap::new())
        };
        let mut scan = SeedScanner::new(p);
        for offset in 0..seed_count(r.len(), p) {
            let fp = scan.at(r, offset);
            match &mut index {
                SeedIndex::Hash(map) => map.entry(fp).or_default().push(offset),
                SeedIndex::Splay(tree) => tree.insert_or_get(fp, Vec::new()).push(offset),
            }
        }
        index
    }

    fn offsets(&mut self, fp: u64) -> &[usize] {
        match self {
            SeedIndex::Hash(map) => map.get(&fp).map_or(&[][..], Vec::as_slice),
            SeedIndex::Splay(tree) => tree.find(fp).map_or(&[][..], |offsets| offsets.as_slice()),
        }
    }
}

/// The greedy algorithm (Section 3.1, Figure 2).
///
/// At each position of V it takes the longest match that begins anywhere in
/// R, which is optimal under the simple cost measure (Section 3.3,
/// Theorem 1).  It indexes every seed of R, so it takes O(|R|) space and
/// O(|V| |R|) time in the worst case.
pub fn diff_greedy(r: &[u8], v: &[u8], opts: &DiffOptions) -> Vec<Command> {
    let p = opts.p;
    let mut commands = Vec::new();
    if v.is_empty() {
        return commands;
    }

    let mut index = SeedIndex::build(r, p, opts.use_splay);
    if opts.verbose {
        eprintln!(
            "greedy: {}, |R|={}, |V|={}, seed_len={}",
            index_name(opts),
            r.len(),
            v.len(),
            p
        );
    }

    let mut scan = SeedScanner::new(p);
    let mut v_c = 0; // current position in V
    let mut v_s = 0; // start of the part of V not yet encoded
    while v_c + p <= v.len() {
        let seed = &v[v_c..v_c + p];
        let mut best_len = 0;
        let mut best_offset = 0;
        for &r_m in index.offsets(scan.at(v, v_c)) {
            // Equal fingerprints do not imply equal seeds.
            if r[r_m..r_m + p] != *seed {
                continue;
            }
            let len = p + common_prefix(&v[v_c + p..], &r[r_m + p..]);
            if len > best_len {
                best_len = len;
                best_offset = r_m;
            }
        }
        if best_len < p {
            v_c += 1;
            continue;
        }

        if v_s < v_c {
            commands.push(Command::Add {
                data: v[v_s..v_c].to_vec(),
            });
        }
        commands.push(Command::Copy {
            offset: best_offset,
            length: best_len,
        });
        v_c += best_len;
        v_s = v_c;
    }

    if v_s < v.len() {
        commands.push(Command::Add {
            data: v[v_s..].to_vec(),
        });
    }

    if opts.verbose {
        print_command_stats(&commands);
    }
    commands
}

/// Runs [`diff_greedy`] with the default options.
pub fn diff_greedy_default(r: &[u8], v: &[u8]) -> Vec<Command> {
    diff_greedy(r, v, &DiffOptions::default())
}
