use crate::hash::fingerprint;
use crate::types::{Command, SEED_LEN, TABLE_SIZE};

/// One-Pass algorithm (Section 4.1, Figure 3).
///
/// Scans R and V concurrently with two hash tables (one per string).
/// Each table stores at most one offset per footprint (retain-existing
/// policy: first entry wins, later collisions are discarded).
/// Hash tables are logically flushed after each match via version counter
/// (next-match policy).
/// Time: O(np + q), space: O(q) — both constant for fixed p, q (Section 4.2).
/// Suboptimal on transpositions: cannot match blocks that appear in
/// different order between R and V (Section 4.3).
pub fn diff_onepass(r: &[u8], v: &[u8], p: usize, q: usize, verbose: bool) -> Vec<Command> {
    let mut commands = Vec::new();
    if v.is_empty() {
        return commands;
    }

    if verbose {
        eprintln!(
            "onepass: hash table q={}, |R|={}, |V|={}, seed_len={}",
            q, r.len(), v.len(), p
        );
    }

    // Step (1): hash tables with version-based logical flushing.
    // Each slot stores (full_fingerprint, offset, version).
    let mut h_v: Vec<Option<(u64, usize, u64)>> = vec![None; q];
    let mut h_r: Vec<Option<(u64, usize, u64)>> = vec![None; q];
    let mut ver: u64 = 0;

    // Inline table access functions to avoid borrow issues
    #[inline]
    fn hget(table: &[Option<(u64, usize, u64)>], fp: u64, q: usize, ver: u64) -> Option<usize> {
        let idx = (fp % q as u64) as usize;
        if let Some((stored_fp, offset, stored_ver)) = table[idx] {
            if stored_ver == ver && stored_fp == fp {
                return Some(offset);
            }
        }
        None
    }

    #[inline]
    fn hput(table: &mut [Option<(u64, usize, u64)>], fp: u64, off: usize, q: usize, ver: u64) {
        let idx = (fp % q as u64) as usize;
        if let Some((_, _, stored_ver)) = table[idx] {
            if stored_ver == ver {
                return; // retain-existing policy
            }
        }
        table[idx] = Some((fp, off, ver));
    }

    // Step (2)
    let mut r_c: usize = 0;
    let mut v_c: usize = 0;
    let mut v_s: usize = 0;

    loop {
        // Step (3)
        let can_v = v_c + p <= v.len();
        let can_r = r_c + p <= r.len();

        if !can_v && !can_r {
            break;
        }

        let fp_v = if can_v {
            Some(fingerprint(v, v_c, p))
        } else {
            None
        };
        let fp_r = if can_r {
            Some(fingerprint(r, r_c, p))
        } else {
            None
        };

        // Step (4a): store offsets (retain-existing policy)
        if let Some(fp) = fp_v {
            hput(&mut h_v, fp, v_c, q, ver);
        }
        if let Some(fp) = fp_r {
            hput(&mut h_r, fp, r_c, q, ver);
        }

        // Step (4b): look for a matching seed in the other table
        let mut match_found = false;
        let mut r_m: usize = 0;
        let mut v_m: usize = 0;

        if let Some(fp) = fp_r {
            if let Some(v_cand) = hget(&h_v, fp, q, ver) {
                if r[r_c..r_c + p] == v[v_cand..v_cand + p] {
                    r_m = r_c;
                    v_m = v_cand;
                    match_found = true;
                }
            }
        }

        if !match_found {
            if let Some(fp) = fp_v {
                if let Some(r_cand) = hget(&h_r, fp, q, ver) {
                    if v[v_c..v_c + p] == r[r_cand..r_cand + p] {
                        v_m = v_c;
                        r_m = r_cand;
                        match_found = true;
                    }
                }
            }
        }

        if !match_found {
            v_c += 1;
            r_c += 1;
            continue;
        }

        // Step (5): extend match forward
        let mut ml: usize = 0;
        while v_m + ml < v.len() && r_m + ml < r.len() && v[v_m + ml] == r[r_m + ml] {
            ml += 1;
        }

        // Step (6): encode
        if v_s < v_m {
            commands.push(Command::Add {
                data: v[v_s..v_m].to_vec(),
            });
        }
        commands.push(Command::Copy {
            offset: r_m,
            length: ml,
        });
        v_s = v_m + ml;

        // Step (7): advance pointers and flush tables
        v_c = v_m + ml;
        r_c = r_m + ml;
        ver += 1;
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
pub fn diff_onepass_default(r: &[u8], v: &[u8]) -> Vec<Command> {
    diff_onepass(r, v, SEED_LEN, TABLE_SIZE, false)
}
