use std::collections::VecDeque;

use crate::hash::{fingerprint, next_prime};
use crate::types::{Command, SEED_LEN, TABLE_SIZE};

/// Internal buffer entry tracking which region of V a command encodes.
struct BufEntry {
    v_start: usize,
    v_end: usize,
    cmd: Command,
    dummy: bool,
}

/// Correcting 1.5-Pass algorithm (Section 7, Figure 8).
///
/// Pass 1: index the reference string using first-found policy (Section 6:
///   first-found stores the first offset seen for each footprint, vs.
///   onepass's retain-existing which keeps the earliest offset).
///   Tables are never flushed — all of R is indexed before scanning V.
/// Pass 2: scan V, extend matches both forwards AND backwards from the seed,
///   and use tail correction (Section 5.1) to fix suboptimal earlier
///   encodings via an encoding lookback buffer (Section 5.2).
/// Time: linear in practice. Space: O(q + buffer_capacity).
///
/// Checkpointing (Section 8, pp. 347-349): the hash table has |C| = q
///   entries (the user's memory budget).  The footprint modulus |F| ~ 2|R|
///   controls which seeds enter the table: only seeds whose footprint
///   satisfies f ≡ k (mod m) where m = ceil(|F|/|C|) are stored or
///   looked up.  This gives ~|C|/2 occupied slots regardless of |R|.
///   Backward extension (Section 5.1) recovers match starts that fall
///   between checkpoint positions (Section 8.2, p. 349).
pub fn diff_correcting(
    r: &[u8],
    v: &[u8],
    p: usize,
    q: usize,
    buf_cap: usize,
    verbose: bool,
) -> Vec<Command> {
    let mut commands = Vec::new();
    if v.is_empty() {
        return commands;
    }

    // Checkpointing (Section 8.1, pp. 347-348).
    // |C| = q (hash table capacity, user's memory budget).
    // |F| ~ 2|R| (footprint modulus) controls checkpoint density.
    // m = ceil(|F|/|C|) is the checkpoint stride; only seeds whose
    // footprint satisfies f % m == 0 enter the table.
    // When |F| <= |C|, m = 1 and every seed is a checkpoint.
    let cap = q; // |C|
    let num_seeds = if r.len() >= p { r.len() - p + 1 } else { 0 };
    let fp_mod = if num_seeds > 0 { next_prime(2 * num_seeds) } else { 1 }; // |F|
    let m = std::cmp::max(1, (fp_mod + cap - 1) / cap); // ceil(|F|/|C|)
    let k: u64 = 0;

    if verbose {
        let expected_fill = if m > 0 { num_seeds / m } else { 0 };
        eprintln!(
            "correcting: |C|={} |F|={} m={} k={}\n  \
             checkpoint gap={} bytes, expected fill ~{} (~{}% table occupancy)\n  \
             table memory ~{} MB",
            cap, fp_mod, m, k,
            m, expected_fill, if cap > 0 { 100 * expected_fill / cap } else { 0 },
            cap * 24 / 1_048_576
        );
    }

    // Step (1): build hash table for R (first-found policy)
    // Slot stores (full_fingerprint, offset) for collision-free lookup.
    let mut h_r: Vec<Option<(u64, usize)>> = vec![None; cap];
    if r.len() >= p {
        for a in 0..=(r.len() - p) {
            let fp = fingerprint(r, a, p);
            let idx = if m <= 1 {
                (fp % cap as u64) as usize
            } else {
                let f = fp % fp_mod as u64;
                if f % m as u64 != k {
                    continue;
                }
                (f / m as u64) as usize
            };
            if h_r[idx].is_none() {
                h_r[idx] = Some((fp, a));
            }
        }
    }

    // Encoding lookback buffer (Section 5.2)
    let mut buf: VecDeque<BufEntry> = VecDeque::new();

    let flush_buf = |buf: &mut VecDeque<BufEntry>, commands: &mut Vec<Command>| {
        for entry in buf.drain(..) {
            if !entry.dummy {
                commands.push(entry.cmd);
            }
        }
    };

    // Step (2)
    let mut v_c: usize = 0;
    let mut v_s: usize = 0;

    loop {
        // Step (3)
        if v_c + p > v.len() {
            break;
        }

        let fp_v = fingerprint(v, v_c, p);

        // Step (4): look up footprint in R's table (checkpoint filter)
        let idx = if m <= 1 {
            (fp_v % cap as u64) as usize
        } else {
            let f_v = fp_v % fp_mod as u64;
            if f_v % m as u64 != k {
                v_c += 1;
                continue;
            }
            (f_v / m as u64) as usize
        };
        let entry = h_r[idx];
        let r_cand = match entry {
            Some((stored_fp, offset)) if stored_fp == fp_v => {
                if r[offset..offset + p] != v[v_c..v_c + p] {
                    v_c += 1;
                    continue;
                }
                offset
            }
            _ => {
                v_c += 1;
                continue;
            }
        };

        // Step (5): extend match forwards and backwards
        let mut fwd = p;
        while v_c + fwd < v.len() && r_cand + fwd < r.len() && v[v_c + fwd] == r[r_cand + fwd] {
            fwd += 1;
        }

        let mut bwd: usize = 0;
        while v_c >= bwd + 1
            && r_cand >= bwd + 1
            && v[v_c - bwd - 1] == r[r_cand - bwd - 1]
        {
            bwd += 1;
        }

        let v_m = v_c - bwd;
        let r_m = r_cand - bwd;
        let ml = bwd + fwd;
        let match_end = v_m + ml;

        // Step (6): encode with correction
        if v_s <= v_m {
            // (6a) match is entirely in unencoded suffix
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
            // (6b) match extends backward into encoded prefix — tail correction
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
                            // Need to modify the back entry
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

    // Step (8)
    flush_buf(&mut buf, &mut commands);
    if v_s < v.len() {
        commands.push(Command::Add {
            data: v[v_s..].to_vec(),
        });
    }

    commands
}

/// Convenience wrapper with default parameters.
pub fn diff_correcting_default(r: &[u8], v: &[u8]) -> Vec<Command> {
    diff_correcting(r, v, SEED_LEN, TABLE_SIZE, 256, false)
}
