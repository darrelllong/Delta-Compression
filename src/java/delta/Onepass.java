package delta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static delta.Types.*;

/**
 * The one-pass algorithm (Section 4.1, Figure 3).
 *
 * Scans R and V together, one position of each per step, recording the seeds
 * of each in its own table and looking each new seed up in the other's.
 * After a match both scans jump past it and both tables are emptied, so the
 * algorithm never matches backwards: of two blocks that changed places
 * between R and V it finds one (Section 4.3).  O(n) time and O(q) space for
 * fixed p.
 */
public final class Onepass {
    private Onepass() {}

    /**
     * The seeds of one input seen since the last match, keyed by fingerprint.
     *
     * The first seed recorded for a key is kept and later ones are dropped.
     * Each entry carries the version it was recorded under, and an entry of
     * an older version counts as absent; advancing the version therefore
     * empties the table in O(1).
     *
     * A hash table has one entry per slot, so a seed is also dropped if a
     * seed with a different fingerprint holds its slot; the splay tree has
     * an entry for every fingerprint.
     */
    private static final class Table {
        private final int q;
        private final long[] fp;
        private final int[] offset;
        private final long[] version;
        private final SplayTree<long[]> tree; // values are {offset, version}

        Table(int q, boolean useSplay) {
            this.q = q;
            if (useSplay) {
                tree = new SplayTree<>();
                fp = null;
                offset = null;
                version = null;
            } else {
                tree = null;
                fp = new long[q];
                offset = new int[q];
                version = new long[q];
                Arrays.fill(version, -1); // versions count up from 0
            }
        }

        void put(long f, int off, long ver) {
            if (tree != null) {
                long[] e = tree.find(f);
                if (e == null) {
                    tree.insert(f, new long[] {off, ver});
                } else if (e[1] != ver) {
                    e[0] = off;
                    e[1] = ver;
                }
                return;
            }
            int i = (int) (f % q);
            if (version[i] != ver) {
                fp[i] = f;
                offset[i] = off;
                version[i] = ver;
            }
        }

        /** Returns the offset recorded for f under ver, or -1. */
        int get(long f, long ver) {
            if (tree != null) {
                long[] e = tree.find(f);
                return e != null && e[1] == ver ? (int) e[0] : -1;
            }
            int i = (int) (f % q);
            return version[i] == ver && fp[i] == f ? offset[i] : -1;
        }
    }

    /** Returns commands that build v from r. */
    public static List<Command> diff(byte[] r, byte[] v, DiffOptions opts) {
        List<Command> commands = new ArrayList<>();
        if (v.length == 0) return commands;

        int p = opts.p;

        // One slot for every p bytes of R, but at least opts.q.
        int q = (int) Hash.nextPrime(Math.max(opts.q, Diff.seedCount(r, p) / p));

        if (opts.verbose) {
            System.err.printf("onepass: %s, q=%d, |R|=%d, |V|=%d, seed_len=%d%n",
                opts.useSplay ? "splay tree" : "hash table", q, r.length, v.length, p);
        }

        Table seenV = new Table(q, opts.useSplay);
        Table seenR = new Table(q, opts.useSplay);
        long ver = 0;

        int rC = 0, vC = 0; // scan positions
        int vS = 0;         // start of the bytes of V not yet encoded
        Hash.RollingHash rhV = v.length >= p ? new Hash.RollingHash(v, 0, p) : null;
        Hash.RollingHash rhR = r.length >= p ? new Hash.RollingHash(r, 0, p) : null;

        for (;;) {
            boolean canV = vC + p <= v.length;
            boolean canR = rC + p <= r.length;
            if (!canV && !canR) break;

            long fpV = canV ? rhV.seek(vC) : 0;
            long fpR = canR ? rhR.seek(rC) : 0;

            // Both seeds are recorded before either lookup, so that seeds at
            // the same position of R and V can match each other.
            if (canV) seenV.put(fpV, vC, ver);
            if (canR) seenR.put(fpR, rC, ver);

            // The seed of R is tried first.  Equal fingerprints do not imply
            // equal seeds.
            int rM = -1, vM = -1;
            if (canR) {
                int cand = seenV.get(fpR, ver);
                if (cand >= 0 && Diff.seedsEqual(r, rC, v, cand, p)) {
                    rM = rC;
                    vM = cand;
                }
            }
            if (rM < 0 && canV) {
                int cand = seenR.get(fpV, ver);
                if (cand >= 0 && Diff.seedsEqual(v, vC, r, cand, p)) {
                    rM = cand;
                    vM = vC;
                }
            }
            if (rM < 0) {
                vC++;
                rC++;
                continue;
            }

            // The match is extended forwards only.
            int len = p + Diff.matchLength(v, vM + p, r, rM + p);
            if (vS < vM) commands.add(Diff.literal(v, vS, vM));
            commands.add(new CopyCmd(rM, len));
            vC = vM + len;
            rC = rM + len;
            vS = vC;
            ver++;
        }

        if (vS < v.length) commands.add(Diff.literal(v, vS, v.length));

        if (opts.verbose) Diff.printStats(commands);
        return commands;
    }
}
