use std::collections::HashMap;

use crate::hash::{fingerprint, RollingHash};
use crate::splay::SplayTree;
use crate::types::{Command, SEED_LEN, TABLE_SIZE};

/// Greedy algorithm (Section 3.1, Figure 2).
///
/// Finds an optimal delta encoding under the simple cost measure
/// (optimality proof: Section 3.3, Theorem 1).
/// Uses a chained hash table (HashMap) or splay tree storing ALL offsets
/// in R per fingerprint.
/// Time: O(|V| * |R|) worst case. Space: O(|R|).
pub fn diff_greedy(r: &[u8], v: &[u8], p: usize, _q: usize, verbose: bool, use_splay: bool, min_copy: usize) -> Vec<Command> {
    let mut commands = Vec::new();
    if v.is_empty() {
        return commands;
    }
    // --min-copy raises the seed length so we never fingerprint at a
    // granularity finer than the minimum copy threshold.
    let p = if min_copy > 0 { p.max(min_copy) } else { p };

    // Step (1): Build lookup structure for R keyed by full fingerprint.
    // Hash table (default) or splay tree (--splay).
    let mut h_r: HashMap<u64, Vec<usize>> = HashMap::new();
    let mut splay_r: SplayTree<Vec<usize>> = SplayTree::new();

    if r.len() >= p {
        let mut rh = RollingHash::new(r, 0, p);
        if use_splay {
            splay_r.insert_or_get(rh.value(), Vec::new()).push(0);
        } else {
            h_r.entry(rh.value()).or_default().push(0);
        }
        for a in 1..=(r.len() - p) {
            rh.roll(r[a - 1], r[a + p - 1]);
            if use_splay {
                splay_r.insert_or_get(rh.value(), Vec::new()).push(a);
            } else {
                h_r.entry(rh.value()).or_default().push(a);
            }
        }
    }

    if verbose {
        eprintln!(
            "greedy: {}, |R|={}, |V|={}, seed_len={}",
            if use_splay { "splay tree" } else { "hash table" },
            r.len(), v.len(), p
        );
    }

    // Step (2)
    let mut v_c: usize = 0;
    let mut v_s: usize = 0;

    // Rolling hash for O(1) per-position V fingerprinting.
    let mut rh_v: Option<RollingHash> = if v.len() >= p { Some(RollingHash::new(v, 0, p)) } else { None };
    let mut rh_v_pos: usize = 0;

    loop {
        // Step (3)
        if v_c + p > v.len() {
            break;
        }

        let fp_v = if let Some(ref mut rh) = rh_v {
            if v_c == rh_v_pos {
                rh.value()
            } else if v_c == rh_v_pos + 1 {
                rh.roll(v[v_c - 1], v[v_c + p - 1]);
                rh_v_pos = v_c;
                rh.value()
            } else {
                // Jump after match — reinitialize
                *rh = RollingHash::new(v, v_c, p);
                rh_v_pos = v_c;
                rh.value()
            }
        } else {
            break;
        };

        // Steps (4)+(5): find the longest matching substring
        let mut best_rm: Option<usize> = None;
        let mut best_len: usize = 0;

        let offsets: Option<&[usize]> = if use_splay {
            splay_r.find(fp_v).map(|v| v.as_slice())
        } else {
            h_r.get(&fp_v).map(|v| v.as_slice())
        };

        if let Some(offsets) = offsets {
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

        if best_len < p {
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

    if verbose {
        let mut copy_lens: Vec<usize> = Vec::new();
        let mut total_copy: usize = 0;
        let mut total_add: usize = 0;
        let mut num_copies: usize = 0;
        let mut num_adds: usize = 0;
        for cmd in &commands {
            match cmd {
                Command::Copy { length, .. } => {
                    total_copy += length;
                    num_copies += 1;
                    copy_lens.push(*length);
                }
                Command::Add { data } => {
                    total_add += data.len();
                    num_adds += 1;
                }
            }
        }
        let total_out = total_copy + total_add;
        let copy_pct = if total_out > 0 {
            total_copy as f64 / total_out as f64 * 100.0
        } else {
            0.0
        };
        eprintln!(
            "  result: {} copies ({} bytes), {} adds ({} bytes)\n  \
             result: copy coverage {:.1}%, output {} bytes",
            num_copies, total_copy, num_adds, total_add, copy_pct, total_out
        );
        if !copy_lens.is_empty() {
            copy_lens.sort();
            let mean = total_copy as f64 / copy_lens.len() as f64;
            let median = copy_lens[copy_lens.len() / 2];
            eprintln!(
                "  copies: {} regions, min={} max={} mean={:.1} median={} bytes",
                copy_lens.len(),
                copy_lens.first().unwrap(),
                copy_lens.last().unwrap(),
                mean,
                median
            );
        }
    }

    commands
}

/// Convenience wrapper with default parameters.
pub fn diff_greedy_default(r: &[u8], v: &[u8]) -> Vec<Command> {
    diff_greedy(r, v, SEED_LEN, TABLE_SIZE, false, false, 0)
}
