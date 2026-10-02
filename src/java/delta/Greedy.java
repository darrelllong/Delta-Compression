package delta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static delta.Types.*;

/**
 * The greedy algorithm (Section 3.1, Figure 2).
 *
 * Indexes every seed of R, then at each position of V takes the longest
 * match that any seed there offers.  The delta is optimal under the simple
 * cost measure (Section 3.3, Theorem 1).  O(|V| |R|) time in the worst
 * case, O(|R|) space.
 */
public final class Greedy {
    private Greedy() {}

    /** A growable array of offsets into R. */
    private static final class Offsets {
        int[] at = new int[4];
        int n;

        void add(int offset) {
            if (n == at.length) at = Arrays.copyOf(at, 2 * n);
            at[n++] = offset;
        }
    }

    /** Maps a fingerprint to the offsets in R of the seeds that have it, in increasing order. */
    private static final class Index {
        private final HashMap<Long, Offsets> table;
        private final SplayTree<Offsets> tree;

        Index(boolean useSplay) {
            table = useSplay ? null : new HashMap<>();
            tree = useSplay ? new SplayTree<>() : null;
        }

        void add(long fp, int offset) {
            Offsets offsets = get(fp);
            if (offsets == null) {
                offsets = new Offsets();
                if (tree != null) tree.insert(fp, offsets);
                else table.put(fp, offsets);
            }
            offsets.add(offset);
        }

        /** Returns the offsets recorded for fp, or null. */
        Offsets get(long fp) {
            return tree != null ? tree.find(fp) : table.get(fp);
        }
    }

    /** Returns commands that build v from r. */
    public static List<Command> diff(byte[] r, byte[] v, DiffOptions opts) {
        List<Command> commands = new ArrayList<>();
        if (v.length == 0) return commands;

        int p = opts.p;

        Index index = new Index(opts.useSplay);
        int numSeeds = Diff.seedCount(r, p);
        if (numSeeds > 0) {
            Hash.RollingHash rh = new Hash.RollingHash(r, 0, p);
            for (int a = 0; a < numSeeds; a++) {
                index.add(rh.seek(a), a);
            }
        }

        if (opts.verbose) {
            System.err.printf("greedy: %s, |R|=%d, |V|=%d, seed_len=%d%n",
                opts.useSplay ? "splay tree" : "hash table", r.length, v.length, p);
        }

        int vC = 0; // scan position in V
        int vS = 0; // start of the bytes of V not yet encoded
        Hash.RollingHash rhV = v.length >= p ? new Hash.RollingHash(v, 0, p) : null;

        while (vC + p <= v.length) {
            // The first of the longest matches wins, so ties go to the lowest offset in R.
            int bestOffset = -1;
            int bestLen = 0;
            Offsets candidates = index.get(rhV.seek(vC));
            if (candidates != null) {
                for (int i = 0; i < candidates.n; i++) {
                    int rOff = candidates.at[i];
                    // Equal fingerprints do not imply equal seeds.
                    if (!Diff.seedsEqual(r, rOff, v, vC, p)) continue;
                    int len = p + Diff.matchLength(v, vC + p, r, rOff + p);
                    if (len > bestLen) {
                        bestLen = len;
                        bestOffset = rOff;
                    }
                }
            }

            if (bestLen < p) {
                vC++;
                continue;
            }

            if (vS < vC) commands.add(Diff.literal(v, vS, vC));
            commands.add(new CopyCmd(bestOffset, bestLen));
            vC += bestLen;
            vS = vC;
        }

        if (vS < v.length) commands.add(Diff.literal(v, vS, v.length));

        if (opts.verbose) Diff.printStats(commands);
        return commands;
    }
}
