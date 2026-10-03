//! The correcting 1.5-pass algorithm (Section 7) with correction
//! (Section 5) and checkpointing (Section 8).

use std::collections::VecDeque;
use std::time::Instant;

use super::{common_prefix, common_suffix, index_name, percent, print_command_stats, seed_count};
use crate::hash::{fingerprint, next_prime, SeedScanner};
use crate::splay::SplayTree;
use crate::types::{Command, DiffOptions};

/// The checkpoint test of Section 8.1 (pp. 347-348), which thins the seeds
/// of R until they fit the table.
///
/// A fingerprint `fp` has footprint `f = fp mod |F|`.  The seed is a
/// checkpoint if `f mod m == k`, and then its home slot is `f / m`, which is
/// less than |C| because `m >= |F| / |C|`.
struct Checkpoints {
    /// |F|, the size of the footprint universe: a prime near twice the
    /// number of seeds in R.
    f_size: u64,
    /// The checkpoint spacing, ceil(|F| / |C|): about one seed in `m` passes.
    m: u64,
    /// The class of footprints that pass.
    k: u64,
}

impl Checkpoints {
    /// Returns the home slot of `fp` if it is a checkpoint.
    #[inline]
    fn slot(&self, fp: u64) -> Option<usize> {
        let f = fp % self.f_size;
        (f % self.m == self.k).then_some((f / self.m) as usize)
    }
}

#[derive(Clone, Copy)]
struct Slot {
    fp: u64,
    offset: usize,
}

/// No fingerprint has this value: fingerprints are less than 2^61.
const EMPTY: u64 = u64::MAX;

/// The offset in R of the first checkpoint seed with each fingerprint.
enum SeedTable {
    /// Open addressing with linear probing from the home slot.
    Hash(Vec<Slot>),
    Splay(SplayTree<usize>),
}

/// Counts for verbose output.
#[derive(Default)]
struct BuildStats {
    passed: usize,
    stored: usize,
    /// Hash: slots probed beyond the home slot.  Splay: seeds whose
    /// fingerprint was already present.
    probes: usize,
}

impl SeedTable {
    fn new(capacity: usize, use_splay: bool) -> Self {
        if use_splay {
            SeedTable::Splay(SplayTree::new())
        } else {
            let empty = Slot {
                fp: EMPTY,
                offset: 0,
            };
            SeedTable::Hash(vec![empty; capacity])
        }
    }

    /// Records the seed at `offset`, whose home slot is `home`, unless its
    /// fingerprint is already present or the table is full.
    #[inline]
    fn insert_first(&mut self, fp: u64, home: usize, offset: usize, stats: &mut BuildStats) {
        match self {
            SeedTable::Hash(slots) => {
                let mut i = home;
                while slots[i].fp != EMPTY {
                    if slots[i].fp == fp {
                        return;
                    }
                    i += 1;
                    if i == slots.len() {
                        i = 0;
                    }
                    stats.probes += 1;
                    if i == home {
                        return;
                    }
                }
                slots[i] = Slot { fp, offset };
                stats.stored += 1;
            }
            SeedTable::Splay(tree) => {
                if *tree.insert_or_get(fp, offset) == offset {
                    stats.stored += 1;
                } else {
                    stats.probes += 1;
                }
            }
        }
    }

    /// Returns the offset recorded for `fp`, whose home slot is `home`.
    #[inline]
    fn lookup(&mut self, fp: u64, home: usize) -> Option<usize> {
        match self {
            SeedTable::Hash(slots) => {
                let mut i = home;
                while slots[i].fp != EMPTY {
                    if slots[i].fp == fp {
                        return Some(slots[i].offset);
                    }
                    i += 1;
                    if i == slots.len() {
                        i = 0;
                    }
                    if i == home {
                        break;
                    }
                }
                None
            }
            SeedTable::Splay(tree) => tree.find(fp).copied(),
        }
    }

    fn len(&self, stats: &BuildStats) -> usize {
        match self {
            SeedTable::Hash(_) => stats.stored,
            SeedTable::Splay(tree) => tree.len(),
        }
    }
}

/// A command that may still be revised, and the part of V it encodes.
struct Tentative {
    v_start: usize,
    v_end: usize,
    cmd: Command,
}

/// The most recent commands, held back so that a later match that extends
/// backward over them can replace them (Section 5.2).  The oldest command
/// becomes final when a new one arrives and the buffer is full.
struct Lookback {
    buf: VecDeque<Tentative>,
    cap: usize,
}

impl Lookback {
    fn push(&mut self, entry: Tentative, out: &mut Vec<Command>) {
        if self.buf.len() >= self.cap {
            if let Some(oldest) = self.buf.pop_front() {
                out.push(oldest.cmd);
            }
        }
        self.buf.push_back(entry);
    }

    /// Gives up to a match covering `v[v_m..match_end]` the commands that
    /// encode part of that range, working back from the newest (tail
    /// correction, Section 5.1, p. 339).  An add may be cut short; a copy is
    /// either wholly inside the match and dropped, or left alone.  Returns
    /// the offset in V from which the match must now be encoded, given that
    /// everything before `v_s` was encoded.
    fn reclaim(&mut self, v_m: usize, match_end: usize, v_s: usize) -> usize {
        let mut start = v_s;
        while let Some(tail) = self.buf.back_mut() {
            if tail.v_start >= v_m && tail.v_end <= match_end {
                start = start.min(tail.v_start);
                self.buf.pop_back();
                continue;
            }
            if tail.v_start < v_m && v_m < tail.v_end {
                if let Command::Add { data } = &mut tail.cmd {
                    data.truncate(v_m - tail.v_start);
                    tail.v_end = v_m;
                    start = start.min(v_m);
                }
            }
            break;
        }
        start
    }
}

/// The correcting 1.5-pass algorithm (Section 7, Figure 8) with
/// checkpointing (Section 8).
///
/// The first pass indexes R, keeping the first seed found for each
/// fingerprint that passes the checkpoint test.  The second scans V; where
/// a checkpoint seed of V matches one in R, it extends the match both
/// forward and backward.  The backward extension recovers the start of a
/// match that lies between checkpoints (Section 8.2, p. 349), and may run
/// over bytes of V already encoded, in which case the earlier commands are
/// corrected (Section 5.1).
///
/// The table has |C| slots: a prime, at least `opts.q` and at least two for
/// every `p` bytes of R, which makes the checkpoint spacing about `p`.  The
/// first prime at or above `opts.max_table` caps it, and the spacing grows
/// to fit.
pub fn diff_correcting(r: &[u8], v: &[u8], opts: &DiffOptions) -> Vec<Command> {
    let p = opts.p;
    let mut commands = Vec::new();
    if v.is_empty() {
        return commands;
    }

    let num_seeds = seed_count(r.len(), p);
    let cap = next_prime(opts.max_table.min(if num_seeds > 0 {
        opts.q.max(2 * num_seeds / p)
    } else {
        opts.q
    }));
    let f_size = if num_seeds > 0 {
        next_prime(2 * num_seeds) as u64
    } else {
        1
    };
    let m = f_size.div_ceil(cap as u64);
    // k is the class of a seed of V, which biases the choice toward a class
    // that occurs in V (p. 348).  The paper takes a seed at random; the one
    // in the middle of V makes the delta reproducible.
    let k = if v.len() >= p {
        fingerprint(v, (v.len() / 2).min(v.len() - p), p) % f_size % m
    } else {
        0
    };
    let checkpoints = Checkpoints { f_size, m, k };

    if opts.verbose {
        let expected = num_seeds as u64 / m;
        eprintln!(
            "correcting: {}, |C|={} |F|={} m={} k={}\n  \
             checkpoint gap={} bytes, expected fill ~{} (~{}% table occupancy)\n  \
             table memory ~{} MB",
            index_name(opts),
            cap,
            f_size,
            m,
            k,
            m,
            expected,
            expected * 100 / cap as u64,
            cap * std::mem::size_of::<Slot>() / 1_048_576
        );
    }

    let mut table = SeedTable::new(cap, opts.use_splay);
    let build_start = Instant::now();
    let mut build = BuildStats::default();
    let mut scan_r = SeedScanner::new(p);
    for offset in 0..num_seeds {
        let fp = scan_r.at(r, offset);
        if let Some(home) = checkpoints.slot(fp) {
            build.passed += 1;
            table.insert_first(fp, home, offset, &mut build);
        }
    }

    if opts.verbose {
        let stored = table.len(&build);
        eprintln!(
            "  build: {} seeds, {} passed checkpoint ({:.2}%), \
             {} stored, {} extra probes\n  \
             build: table occupancy {}/{} ({:.1}%), elapsed {:.2}s",
            num_seeds,
            build.passed,
            percent(build.passed, num_seeds),
            build.stored,
            build.probes,
            stored,
            cap,
            percent(stored, cap),
            build_start.elapsed().as_secs_f64()
        );
    }

    let mut lookback = Lookback {
        buf: VecDeque::new(),
        cap: opts.buf_cap,
    };
    let mut scan_v = SeedScanner::new(p);
    let mut v_c = 0; // current position in V
    let mut v_s = 0; // start of the part of V not yet encoded

    // Verbose statistics.
    let mut scan_checkpoints = 0;
    let mut scan_matches = 0;
    let mut byte_mismatches = 0;

    while v_c + p <= v.len() {
        let fp = scan_v.at(v, v_c);
        let Some(home) = checkpoints.slot(fp) else {
            v_c += 1;
            continue;
        };
        scan_checkpoints += 1;
        let Some(r_offset) = table.lookup(fp, home) else {
            v_c += 1;
            continue;
        };
        // Equal fingerprints do not imply equal seeds.
        if r[r_offset..r_offset + p] != v[v_c..v_c + p] {
            byte_mismatches += 1;
            v_c += 1;
            continue;
        }
        scan_matches += 1;

        let fwd = p + common_prefix(&v[v_c + p..], &r[r_offset + p..]);
        let bwd = common_suffix(&v[..v_c], &r[..r_offset]);
        let v_m = v_c - bwd;
        let r_m = r_offset - bwd;
        let match_end = v_c + fwd;

        if v_s <= v_m {
            // The match lies wholly in the part of V not yet encoded.
            if v_s < v_m {
                lookback.push(
                    Tentative {
                        v_start: v_s,
                        v_end: v_m,
                        cmd: Command::Add {
                            data: v[v_s..v_m].to_vec(),
                        },
                    },
                    &mut commands,
                );
            }
            lookback.push(
                Tentative {
                    v_start: v_m,
                    v_end: match_end,
                    cmd: Command::Copy {
                        offset: r_m,
                        length: match_end - v_m,
                    },
                },
                &mut commands,
            );
        } else {
            // The match reaches back into the encoded part.  Encode as much
            // of it as the buffered commands will give up.
            let start = lookback.reclaim(v_m, match_end, v_s);
            if start < match_end {
                lookback.push(
                    Tentative {
                        v_start: start,
                        v_end: match_end,
                        cmd: Command::Copy {
                            offset: r_m + (start - v_m),
                            length: match_end - start,
                        },
                    },
                    &mut commands,
                );
            }
        }
        v_s = match_end;
        v_c = match_end;
    }

    commands.extend(lookback.buf.into_iter().map(|entry| entry.cmd));
    if v_s < v.len() {
        commands.push(Command::Add {
            data: v[v_s..].to_vec(),
        });
    }

    if opts.verbose {
        let v_seeds = seed_count(v.len(), p);
        // The table is searched by full fingerprint, so a lookup never
        // returns a seed with a different one; the count is always 0.
        let fp_collisions = 0;
        eprintln!(
            "  scan: {} V positions, {} checkpoints ({:.3}%), {} matches\n  \
             scan: hit rate {:.1}% (of checkpoints), \
             fp collisions {}, byte mismatches {}",
            v_seeds,
            scan_checkpoints,
            percent(scan_checkpoints, v_seeds),
            scan_matches,
            percent(scan_matches, scan_checkpoints),
            fp_collisions,
            byte_mismatches
        );
        print_command_stats(&commands);
    }
    commands
}

/// Runs [`diff_correcting`] with the default options.
pub fn diff_correcting_default(r: &[u8], v: &[u8]) -> Vec<Command> {
    diff_correcting(r, v, &DiffOptions::default())
}
