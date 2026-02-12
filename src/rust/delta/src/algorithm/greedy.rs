use std::collections::HashMap;

use crate::hash::{fingerprint, RollingHash};
use crate::types::{Command, SEED_LEN, TABLE_SIZE};

/// Greedy algorithm (Section 3.1, Figure 2).
///
/// Finds an optimal delta encoding under the simple cost measure
/// (optimality proof: Section 3.3, Theorem 1).
/// Uses a chained hash table (HashMap) storing ALL offsets in R per
/// fingerprint (Section 3.1: hash table stores a chain of all matching offsets).
/// Time: O(|V| * |R|) worst case. Space: O(|R|).
pub fn diff_greedy(r: &[u8], v: &[u8], p: usize, _q: usize, _verbose: bool) -> Vec<Command> {
    let mut commands = Vec::new();
    if v.is_empty() {
        return commands;
    }

    // Step (1): Build chained hash table for R keyed by full fingerprint
    let mut h_r: HashMap<u64, Vec<usize>> = HashMap::new();
    if r.len() >= p {
        let mut rh = RollingHash::new(r, 0, p);
        h_r.entry(rh.value()).or_default().push(0);
        for a in 1..=(r.len() - p) {
            rh.roll(r[a - 1], r[a + p - 1]);
            h_r.entry(rh.value()).or_default().push(a);
        }
    }

    // Step (2)
    let mut v_c: usize = 0;
    let mut v_s: usize = 0;

    loop {
        // Step (3)
        if v_c + p > v.len() {
            break;
        }

        let fp_v = fingerprint(v, v_c, p);

        // Steps (4)+(5): find the longest matching substring
        let mut best_rm: Option<usize> = None;
        let mut best_len: usize = 0;

        if let Some(offsets) = h_r.get(&fp_v) {
            for &r_cand in offsets {
                // Verify the seed actually matches
                if r[r_cand..r_cand + p] != v[v_c..v_c + p] {
                    continue;
                }
                let mut ml = p;
                while v_c + ml < v.len() && r_cand + ml < r.len() && v[v_c + ml] == r[r_cand + ml]
                {
                    ml += 1;
                }
                if ml > best_len {
                    best_len = ml;
                    best_rm = Some(r_cand);
                }
            }
        }

        if best_len == 0 {
            v_c += 1;
            continue;
        }

        // Step (6): encode
        if v_s < v_c {
            commands.push(Command::Add {
                data: v[v_s..v_c].to_vec(),
            });
        }
        commands.push(Command::Copy {
            offset: best_rm.unwrap(),
            length: best_len,
        });
        v_s = v_c + best_len;

        // Step (7)
        v_c += best_len;
    }

    // Step (8)
    if v_s < v.len() {
        commands.push(Command::Add {
            data: v[v_s..].to_vec(),
        });
    }

    commands
}

/// Convenience wrapper with default parameters.
pub fn diff_greedy_default(r: &[u8], v: &[u8]) -> Vec<Command> {
    diff_greedy(r, v, SEED_LEN, TABLE_SIZE, false)
}
