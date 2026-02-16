use std::collections::VecDeque;

use crate::hash::{fingerprint, next_prime};
use crate::splay::SplayTree;
use crate::types::{Command, SEED_LEN, TABLE_SIZE};

/// Internal buffer entry tracking which region of V a command encodes.
struct BufEntry {
    v_start: usize,
    v_end: usize,
    cmd: Command,
    dummy: bool,
}

/// Correcting 1.5-Pass algorithm (Section 7, Figure 8) with
/// fingerprint-based checkpointing (Section 8).
///
/// The hash table is auto-sized to max(q, 2 * num_seeds / p) so that
/// checkpoint spacing m ≈ p, giving near-seed-granularity sampling.
/// TABLE_SIZE acts as a floor for small files.
///
/// |C| = q (hash table capacity, auto-sized from input).
/// |F| = next_prime(2 * num_R_seeds) (footprint universe, Section 8.1,
///       pp. 347-348: "|F| ≈ 2L").
/// m  = ceil(|F| / |C|) (checkpoint spacing, p. 348).
/// k  = checkpoint class (Eq. 3, p. 348).
///
/// A seed with fingerprint fp passes the checkpoint test iff
///     (fp % |F|) % m == k.
/// Its table index is (fp % |F|) / m  (p. 348: "i = floor(f/m)").
///
/// Step 1 (R pass): compute fingerprint at every R position, apply
/// checkpoint filter, store first-found offset per slot.
/// Steps 3-4 (V scan): compute fingerprint at every V position, apply
/// checkpoint filter, look up matching R offset.
/// Step 5: extend match both forwards and backwards (Section 7, p. 345).
/// Step 6: encode with tail correction via lookback buffer (Section 5.1).
/// Backward extension (Section 8.2, p. 349) recovers true match starts
/// that fall between checkpoint positions.
pub fn diff_correcting(
    r: &[u8],
    v: &[u8],
    p: usize,
    q: usize,
    buf_cap: usize,
    verbose: bool,
    use_splay: bool,
    min_copy: usize,
) -> Vec<Command> {
    let mut commands = Vec::new();
    if v.is_empty() {
        return commands;
    }
    let effective_min = if min_copy > 0 { min_copy } else { p };

    // ── Checkpointing parameters (Section 8.1, pp. 347-348) ─────────
    let num_seeds = if r.len() >= p { r.len() - p + 1 } else { 0 };
    // Auto-size: 2x factor for correcting's |F|=2L convention.
    let cap = if num_seeds > 0 {
        next_prime(q.max(2 * num_seeds / p))
    } else {
        next_prime(q)
    }; // |C|
    let f_size: u64 = if num_seeds > 0 {
        next_prime(2 * num_seeds) as u64 // |F|
    } else {
        1
    };
    let m: u64 = if f_size <= cap as u64 {
        1
    } else {
        (f_size + cap as u64 - 1) / cap as u64 // ceil(|F| / |C|)
    };
    // Biased k (p. 348): pick a V offset, use its footprint mod m.
    let k: u64 = if v.len() >= p {
        let fp_k = fingerprint(v, v.len() / 2, p);
        fp_k % f_size % m
    } else {
        0
    };

    if verbose {
        let expected = if m > 0 { num_seeds as u64 / m } else { 0 };
        let occ_est = if cap > 0 { expected * 100 / cap as u64 } else { 0 };
        eprintln!(
            "correcting: {}, |C|={} |F|={} m={} k={}\n  \
             checkpoint gap={} bytes, expected fill ~{} (~{}% table occupancy)\n  \
             table memory ~{} MB",
            if use_splay { "splay tree" } else { "hash table" },
            cap, f_size, m, k,
            m, expected, occ_est,
            cap * 24 / 1_048_576
        );
    }

    // Debug counters
    let mut dbg_build_passed: usize = 0;
    let mut dbg_build_stored: usize = 0;
    let mut dbg_build_skipped_collision: usize = 0;
    let mut dbg_scan_checkpoints: usize = 0;
    let mut dbg_scan_match: usize = 0;
    let mut dbg_scan_fp_mismatch: usize = 0;
    let mut dbg_scan_byte_mismatch: usize = 0;

    // ── Step (1): Build lookup structure for R (first-found policy) ──
    let mut h_r_ht: Vec<Option<(u64, usize)>> = if !use_splay { vec![None; cap] } else { Vec::new() };
    let mut h_r_sp: SplayTree<(u64, usize)> = SplayTree::new(); // (full_fp, offset)

    for a in 0..num_seeds {
        let fp = fingerprint(r, a, p);
        let f = fp % f_size;
        if f % m != k {
            continue; // not a checkpoint seed
        }
        dbg_build_passed += 1;

        if use_splay {
            // insert_or_get implements first-found policy
            let val = h_r_sp.insert_or_get(fp, (fp, a));
            if val.1 == a {
                dbg_build_stored += 1;
            } else {
                dbg_build_skipped_collision += 1;
            }
        } else {
            let i = (f / m) as usize;
            if i >= cap {
                continue; // safety
            }
            if h_r_ht[i].is_none() {
                h_r_ht[i] = Some((fp, a)); // first-found (Section 7 Step 1)
                dbg_build_stored += 1;
            } else {
                dbg_build_skipped_collision += 1;
            }
        }
    }

    if verbose {
        let passed_pct = if num_seeds > 0 {
            dbg_build_passed as f64 / num_seeds as f64 * 100.0
        } else {
            0.0
        };
        let stored_count = if use_splay { h_r_sp.len() } else { dbg_build_stored };
        let occ_pct = if cap > 0 {
            stored_count as f64 / cap as f64 * 100.0
        } else {
            0.0
        };
        eprintln!(
            "  build: {} seeds, {} passed checkpoint ({:.2}%), \
             {} stored, {} collisions\n  \
             build: table occupancy {}/{} ({:.1}%)",
            num_seeds, dbg_build_passed, passed_pct,
            dbg_build_stored, dbg_build_skipped_collision,
            stored_count, cap, occ_pct
        );
    }

    // Lookup helper
    let lookup_r = |h_r_ht: &[Option<(u64, usize)>], h_r_sp: &mut SplayTree<(u64, usize)>, fp_v: u64, f_v: u64| -> Option<(u64, usize)> {
        if use_splay {
            h_r_sp.find(fp_v).copied()
        } else {
            let i = (f_v / m) as usize;
            if i >= cap { return None; }
            h_r_ht[i]
        }
    };

    // ── Encoding lookback buffer (Section 5.2) ───────────────────────
    let mut buf: VecDeque<BufEntry> = VecDeque::new();

    let flush_buf = |buf: &mut VecDeque<BufEntry>, commands: &mut Vec<Command>| {
        for entry in buf.drain(..) {
            if !entry.dummy {
                commands.push(entry.cmd);
            }
        }
    };

    // ── Step (2) ─────────────────────────────────────────────────────
    let mut v_c: usize = 0;
    let mut v_s: usize = 0;

    loop {
        // Step (3)
        if v_c + p > v.len() {
            break;
        }

        // Step (4): generate footprint at v_c, apply checkpoint test.
        let fp_v = fingerprint(v, v_c, p);
        let f_v = fp_v % f_size;
        if f_v % m != k {
            v_c += 1;
            continue; // not a checkpoint — skip
        }

        // Checkpoint passed — look up R.
        dbg_scan_checkpoints += 1;

        let entry = lookup_r(&h_r_ht, &mut h_r_sp, fp_v, f_v);

        let r_offset = match entry {
            Some((stored_fp, offset)) if stored_fp == fp_v => {
                // Full fingerprint matches — verify bytes.
                if r[offset..offset + p] != v[v_c..v_c + p] {
                    dbg_scan_byte_mismatch += 1;
                    v_c += 1;
                    continue;
                }
                dbg_scan_match += 1;
                offset
            }
            Some(_) => {
                dbg_scan_fp_mismatch += 1;
                v_c += 1;
                continue;
            }
            None => {
                v_c += 1;
                continue;
            }
        };

        // ── Step (5): extend match forwards and backwards ────────
        // (Section 7, Step 5; Section 8.2 backward extension, p. 349)
        let mut fwd = p;
        while v_c + fwd < v.len() && r_offset + fwd < r.len() && v[v_c + fwd] == r[r_offset + fwd]
        {
            fwd += 1;
        }

        let mut bwd: usize = 0;
        while v_c >= bwd + 1
            && r_offset >= bwd + 1
            && v[v_c - bwd - 1] == r[r_offset - bwd - 1]
        {
            bwd += 1;
        }

        let v_m = v_c - bwd;
        let r_m = r_offset - bwd;
        let ml = bwd + fwd;
        let match_end = v_m + ml;

        // Filter: skip matches shorter than --min-copy
        if ml < effective_min {
            v_c += 1;
            continue;
        }

        // ── Step (6): encode with correction ─────────────────────
        if v_s <= v_m {
            // (6a) match is entirely in unencoded suffix (Section 7)
            if v_s < v_m {
                if buf.len() >= buf_cap {
                    let oldest = buf.pop_front().unwrap();
                    if !oldest.dummy {
                        commands.push(oldest.cmd);
                    }
                }
                buf.push_back(BufEntry {
                    v_start: v_s,
                    v_end: v_m,
                    cmd: Command::Add {
                        data: v[v_s..v_m].to_vec(),
                    },
                    dummy: false,
                });
            }
            if buf.len() >= buf_cap {
                let oldest = buf.pop_front().unwrap();
                if !oldest.dummy {
                    commands.push(oldest.cmd);
                }
            }
            buf.push_back(BufEntry {
                v_start: v_m,
                v_end: match_end,
                cmd: Command::Copy {
                    offset: r_m,
                    length: ml,
                },
                dummy: false,
            });
            v_s = match_end;
        } else {
            // (6b) match extends backward into encoded prefix —
            // tail correction (Section 5.1, p. 339)
            let mut effective_start = v_s;

            while let Some(tail) = buf.back() {
                if tail.dummy {
                    buf.pop_back();
                    continue;
                }

                if tail.v_start >= v_m && tail.v_end <= match_end {
                    // Wholly within new match — absorb
                    effective_start = effective_start.min(tail.v_start);
                    buf.pop_back();
                    continue;
                }

                if tail.v_end > v_m && tail.v_start < v_m {
                    if matches!(tail.cmd, Command::Add { .. }) {
                        // Partial add — trim to [v_start, v_m)
                        let keep = v_m - tail.v_start;
                        if keep > 0 {
                            let back = buf.back_mut().unwrap();
                            back.cmd = Command::Add {
                                data: v[back.v_start..v_m].to_vec(),
                            };
                            back.v_end = v_m;
                        } else {
                            buf.pop_back();
                        }
                        effective_start = effective_start.min(v_m);
                    }
                    // Partial copy — don't reclaim (Section 5.1)
                    break;
                }

                // No overlap with match
                break;
            }

            let adj = effective_start - v_m;
            let new_len = match_end - effective_start;
            if new_len > 0 {
                if buf.len() >= buf_cap {
                    let oldest = buf.pop_front().unwrap();
                    if !oldest.dummy {
                        commands.push(oldest.cmd);
                    }
                }
                buf.push_back(BufEntry {
                    v_start: effective_start,
                    v_end: match_end,
                    cmd: Command::Copy {
                        offset: r_m + adj,
                        length: new_len,
                    },
                    dummy: false,
                });
            }
            v_s = match_end;
        }

        // Step (7)
        v_c = match_end;
    }

    // ── Step (8) ─────────────────────────────────────────────────────
    flush_buf(&mut buf, &mut commands);
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
        let v_seeds = if v.len() >= p { v.len() - p + 1 } else { 0 };
        let cp_pct = if v_seeds > 0 {
            dbg_scan_checkpoints as f64 / v_seeds as f64 * 100.0
        } else {
            0.0
        };
        let hit_pct = if dbg_scan_checkpoints > 0 {
            dbg_scan_match as f64 / dbg_scan_checkpoints as f64 * 100.0
        } else {
            0.0
        };
        let total_out = total_copy + total_add;
        let copy_pct = if total_out > 0 {
            total_copy as f64 / total_out as f64 * 100.0
        } else {
            0.0
        };
        eprintln!(
            "  scan: {} V positions, {} checkpoints ({:.3}%), {} matches\n  \
             scan: hit rate {:.1}% (of checkpoints), \
             fp collisions {}, byte mismatches {}",
            v_seeds, dbg_scan_checkpoints, cp_pct, dbg_scan_match,
            hit_pct, dbg_scan_fp_mismatch, dbg_scan_byte_mismatch
        );
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
pub fn diff_correcting_default(r: &[u8], v: &[u8]) -> Vec<Command> {
    diff_correcting(r, v, SEED_LEN, TABLE_SIZE, 256, false, false, 0)
}
