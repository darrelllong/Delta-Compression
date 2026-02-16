use crate::hash::{fingerprint, next_prime, RollingHash};
use crate::splay::SplayTree;
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
///
/// The hash table is auto-sized to max(q, num_seeds / p) so that large
/// inputs get one slot per seed-length chunk of R.  TABLE_SIZE acts as a
/// floor for small files.
pub fn diff_onepass(r: &[u8], v: &[u8], p: usize, q: usize, verbose: bool, use_splay: bool, min_copy: usize) -> Vec<Command> {
    let mut commands = Vec::new();
    if v.is_empty() {
        return commands;
    }
    // --min-copy raises the seed length so we never fingerprint at a
    // granularity finer than the minimum copy threshold.
    let p = if min_copy > 0 { p.max(min_copy) } else { p };

    // Auto-size hash table: one slot per p-byte chunk of R (floor = q).
    let num_seeds = if r.len() >= p { r.len() - p + 1 } else { 0 };
    let q = next_prime(q.max(num_seeds / p));

    if verbose {
        eprintln!(
            "onepass: {}, q={}, |R|={}, |V|={}, seed_len={}",
            if use_splay { "splay tree" } else { "hash table" },
            q, r.len(), v.len(), p
        );
    }

    // Step (1): lookup structures with version-based logical flushing.
    // Each entry stores (offset, version).

    // Hash table path
    let mut h_v_ht: Vec<Option<(u64, usize, u64)>> = if !use_splay { vec![None; q] } else { Vec::new() };
    let mut h_r_ht: Vec<Option<(u64, usize, u64)>> = if !use_splay { vec![None; q] } else { Vec::new() };

    // Splay tree path: value is (offset, version)
    let mut h_v_sp: SplayTree<(usize, u64)> = SplayTree::new();
    let mut h_r_sp: SplayTree<(usize, u64)> = SplayTree::new();

    let mut ver: u64 = 0;

    // Debug counters (verbose mode only)
    let mut dbg_positions: usize = 0;
    let mut dbg_lookups: usize = 0;
    let mut dbg_matches: usize = 0;

    // Hash table inline helpers
    #[inline]
    fn ht_get(table: &[Option<(u64, usize, u64)>], fp: u64, q: usize, ver: u64) -> Option<usize> {
        let idx = (fp % q as u64) as usize;
        if let Some((stored_fp, offset, stored_ver)) = table[idx] {
            if stored_ver == ver && stored_fp == fp {
                return Some(offset);
            }
        }
        None
    }

    #[inline]
    fn ht_put(table: &mut [Option<(u64, usize, u64)>], fp: u64, off: usize, q: usize, ver: u64) {
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

    // Rolling hashes for O(1) per-position fingerprinting.
    // Initialized lazily on first use and reinitialized after match jumps.
    let mut rh_v: Option<RollingHash> = if v.len() >= p { Some(RollingHash::new(v, 0, p)) } else { None };
    let mut rh_r: Option<RollingHash> = if r.len() >= p { Some(RollingHash::new(r, 0, p)) } else { None };
    let mut rh_v_pos: usize = 0; // position rh_v currently represents
    let mut rh_r_pos: usize = 0;

    loop {
        // Step (3)
        let can_v = v_c + p <= v.len();
        let can_r = r_c + p <= r.len();

        if !can_v && !can_r {
            break;
        }
        dbg_positions += 1;

        let fp_v = if can_v {
            if let Some(ref mut rh) = rh_v {
                if v_c == rh_v_pos {
                    // Already at the right position
                } else if v_c == rh_v_pos + 1 {
                    rh.roll(v[v_c - 1], v[v_c + p - 1]);
                    rh_v_pos = v_c;
                } else {
                    // Jump — reinitialize
                    *rh = RollingHash::new(v, v_c, p);
                    rh_v_pos = v_c;
                }
                Some(rh.value())
            } else {
                None
            }
        } else {
            None
        };
        let fp_r = if can_r {
            if let Some(ref mut rh) = rh_r {
                if r_c == rh_r_pos {
                    // Already at the right position
                } else if r_c == rh_r_pos + 1 {
                    rh.roll(r[r_c - 1], r[r_c + p - 1]);
                    rh_r_pos = r_c;
                } else {
                    *rh = RollingHash::new(r, r_c, p);
                    rh_r_pos = r_c;
                }
                Some(rh.value())
            } else {
                None
            }
        } else {
            None
        };

        // Step (4a): store offsets (retain-existing policy)
        if let Some(fp) = fp_v {
            if use_splay {
                let existing = h_v_sp.find(fp);
                if existing.is_none() || existing.unwrap().1 != ver {
                    h_v_sp.insert(fp, (v_c, ver));
                }
            } else {
                ht_put(&mut h_v_ht, fp, v_c, q, ver);
            }
        }
        if let Some(fp) = fp_r {
            if use_splay {
                let existing = h_r_sp.find(fp);
                if existing.is_none() || existing.unwrap().1 != ver {
                    h_r_sp.insert(fp, (r_c, ver));
                }
            } else {
                ht_put(&mut h_r_ht, fp, r_c, q, ver);
            }
        }

        // Step (4b): look for a matching seed in the other table
        let mut match_found = false;
        let mut r_m: usize = 0;
        let mut v_m: usize = 0;

        if let Some(fp) = fp_r {
            let v_cand = if use_splay {
                h_v_sp.find(fp).and_then(|&mut (off, v)| if v == ver { Some(off) } else { None })
            } else {
                ht_get(&h_v_ht, fp, q, ver)
            };
            if let Some(v_cand) = v_cand {
                dbg_lookups += 1;
                if r[r_c..r_c + p] == v[v_cand..v_cand + p] {
                    r_m = r_c;
                    v_m = v_cand;
                    match_found = true;
                }
            }
        }

        if !match_found {
            if let Some(fp) = fp_v {
                let r_cand = if use_splay {
                    h_r_sp.find(fp).and_then(|&mut (off, v)| if v == ver { Some(off) } else { None })
                } else {
                    ht_get(&h_r_ht, fp, q, ver)
                };
                if let Some(r_cand) = r_cand {
                    dbg_lookups += 1;
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
        dbg_matches += 1;

        // Step (5): extend match forward
        let mut ml: usize = 0;
        while v_m + ml < v.len() && r_m + ml < r.len() && v[v_m + ml] == r[r_m + ml] {
            ml += 1;
        }

        // Filter: skip matches shorter than --min-copy
        if ml < p {
            v_c += 1;
            r_c += 1;
            continue;
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
        let hit_pct = if dbg_lookups > 0 { dbg_matches as f64 / dbg_lookups as f64 * 100.0 } else { 0.0 };
        let total_out = total_copy + total_add;
        let copy_pct = if total_out > 0 { total_copy as f64 / total_out as f64 * 100.0 } else { 0.0 };
        eprintln!(
            "  scan: {} positions, {} lookups, {} matches (flushes)\n  \
             scan: hit rate {:.1}% (of lookups)",
            dbg_positions, dbg_lookups, dbg_matches, hit_pct
        );
        eprintln!(
            "  result: {} copies ({} bytes), {} adds ({} bytes)\n  \
             result: copy coverage {:.1}%, output {} bytes",
            num_copies, total_copy, num_adds, total_add,
            copy_pct, total_out
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
pub fn diff_onepass_default(r: &[u8], v: &[u8]) -> Vec<Command> {
    diff_onepass(r, v, SEED_LEN, TABLE_SIZE, false, false, 0)
}
