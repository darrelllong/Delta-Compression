use super::{common_prefix, index_name, percent, print_command_stats, seed_count};
use crate::hash::{next_prime, SeedScanner};
use crate::splay::SplayTree;
use crate::types::{Command, DiffOptions};

/// One seed remembered from one of the two strings.
#[derive(Clone, Copy)]
struct Slot {
    fp: u64,
    offset: usize,
    /// The table generation the slot was written in; see `SeedTable`.
    version: u64,
}

/// No scan produces this many matches, so it marks a slot never written.
const NO_VERSION: u64 = u64::MAX;

/// The seeds seen in one string since the last match, at most one per
/// fingerprint: the first one seen is kept (Section 4.1).
///
/// Figure 3 flushes both tables after every match.  Clearing them would
/// cost O(q) each time, so instead every entry carries the number of
/// matches made when it was written, its version, and an entry from an
/// earlier version counts as absent.
enum SeedTable {
    /// Direct-mapped on `fp % len`.  Two fingerprints with the same index
    /// contend for one slot, and the earlier one keeps it.
    Hash(Vec<Slot>),
    /// Fingerprint to (offset, version).
    Splay(SplayTree<(usize, u64)>),
}

impl SeedTable {
    fn new(q: usize, use_splay: bool) -> Self {
        if use_splay {
            SeedTable::Splay(SplayTree::new())
        } else {
            let empty = Slot {
                fp: 0,
                offset: 0,
                version: NO_VERSION,
            };
            SeedTable::Hash(vec![empty; q])
        }
    }

    /// Records the seed at `offset` unless one is already recorded in its
    /// place for this version.
    #[inline]
    fn store(&mut self, fp: u64, offset: usize, version: u64) {
        match self {
            SeedTable::Hash(slots) => {
                let i = (fp % slots.len() as u64) as usize;
                if slots[i].version != version {
                    slots[i] = Slot {
                        fp,
                        offset,
                        version,
                    };
                }
            }
            SeedTable::Splay(tree) => {
                let entry = tree.insert_or_get(fp, (offset, version));
                if entry.1 != version {
                    *entry = (offset, version);
                }
            }
        }
    }

    /// Returns the offset recorded for `fp` in this version.
    #[inline]
    fn lookup(&mut self, fp: u64, version: u64) -> Option<usize> {
        match self {
            SeedTable::Hash(slots) => {
                let slot = &slots[(fp % slots.len() as u64) as usize];
                (slot.version == version && slot.fp == fp).then_some(slot.offset)
            }
            SeedTable::Splay(tree) => match tree.find(fp) {
                Some(&mut (offset, ver)) if ver == version => Some(offset),
                _ => None,
            },
        }
    }
}

/// The one-pass algorithm (Section 4.1, Figure 3).
///
/// It scans R and V together, remembering the seeds of each in a table of
/// its own and looking each new seed up in the other string's table.  On a
/// match it extends the match forward, encodes it, forgets every seed, and
/// resumes after the match in both strings.  Time is linear and space is
/// O(q) (Section 4.2).  Because it never looks back, it cannot match blocks
/// that occur in a different order in R and V (Section 4.3).
///
/// The tables have a prime number of slots, at least `opts.q` and at least
/// one for every `p` bytes of R.
pub fn diff_onepass(r: &[u8], v: &[u8], opts: &DiffOptions) -> Vec<Command> {
    let p = opts.p;
    let mut commands = Vec::new();
    if v.is_empty() {
        return commands;
    }

    let q = next_prime(opts.q.max(seed_count(r.len(), p) / p));
    if opts.verbose {
        eprintln!(
            "onepass: {}, q={}, |R|={}, |V|={}, seed_len={}",
            index_name(opts),
            q,
            r.len(),
            v.len(),
            p
        );
    }

    let mut seen_v = SeedTable::new(q, opts.use_splay);
    let mut seen_r = SeedTable::new(q, opts.use_splay);
    let mut scan_v = SeedScanner::new(p);
    let mut scan_r = SeedScanner::new(p);
    let mut version = 0; // the number of matches so far
    let mut r_c = 0; // current position in R
    let mut v_c = 0; // current position in V
    let mut v_s = 0; // start of the part of V not yet encoded

    // Verbose statistics.
    let mut positions = 0;
    let mut lookups = 0;

    loop {
        let fp_v = (v_c + p <= v.len()).then(|| scan_v.at(v, v_c));
        let fp_r = (r_c + p <= r.len()).then(|| scan_r.at(r, r_c));
        if fp_v.is_none() && fp_r.is_none() {
            break;
        }
        positions += 1;

        if let Some(fp) = fp_v {
            seen_v.store(fp, v_c, version);
        }
        if let Some(fp) = fp_r {
            seen_r.store(fp, r_c, version);
        }

        // Both seeds are stored before either is looked up, so that the
        // seeds at v_c and r_c can match each other.  The seed of R is
        // tried first.
        let mut found = None;
        if let Some(fp) = fp_r {
            if let Some(v_m) = seen_v.lookup(fp, version) {
                lookups += 1;
                if r[r_c..r_c + p] == v[v_m..v_m + p] {
                    found = Some((r_c, v_m));
                }
            }
        }
        if found.is_none() {
            if let Some(fp) = fp_v {
                if let Some(r_m) = seen_r.lookup(fp, version) {
                    lookups += 1;
                    if v[v_c..v_c + p] == r[r_m..r_m + p] {
                        found = Some((r_m, v_c));
                    }
                }
            }
        }
        let Some((r_m, v_m)) = found else {
            v_c += 1;
            r_c += 1;
            continue;
        };

        let length = common_prefix(&v[v_m..], &r[r_m..]);
        if v_s < v_m {
            commands.push(Command::Add {
                data: v[v_s..v_m].to_vec(),
            });
        }
        commands.push(Command::Copy {
            offset: r_m,
            length,
        });
        v_c = v_m + length;
        r_c = r_m + length;
        v_s = v_c;
        version += 1;
    }

    if v_s < v.len() {
        commands.push(Command::Add {
            data: v[v_s..].to_vec(),
        });
    }

    if opts.verbose {
        // Every match advances the version, so the two counts are equal.
        let matches = version as usize;
        eprintln!(
            "  scan: {} positions, {} lookups, {} matches (flushes)\n  \
             scan: hit rate {:.1}% (of lookups)",
            positions,
            lookups,
            matches,
            percent(matches, lookups)
        );
        print_command_stats(&commands);
    }
    commands
}

/// Runs [`diff_onepass`] with the default options.
pub fn diff_onepass_default(r: &[u8], v: &[u8]) -> Vec<Command> {
    diff_onepass(r, v, &DiffOptions::default())
}
