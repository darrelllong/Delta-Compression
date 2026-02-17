package delta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static delta.Types.*;

/**
 * Greedy algorithm (Section 3.1, Figure 2).
 *
 * Finds an optimal delta encoding under the simple cost measure
 * (optimality proof: Section 3.3, Theorem 1).
 * Time: O(|V| * |R|) worst case. Space: O(|R|).
 */
public final class Greedy {
    private Greedy() {}

    public static List<Command> diff(byte[] r, byte[] v, int p, int q,
                                     boolean verbose, boolean useSplay, int minCopy) {
        List<Command> commands = new ArrayList<>();
        if (v.length == 0) return commands;

        if (minCopy > 0) p = Math.max(p, minCopy);

        // Step (1): build lookup structure for R keyed by full fingerprint.
        Map<Long, List<Integer>> hrHt = useSplay ? null : new HashMap<>();
        SplayTree<List<Integer>> hrSp = useSplay ? new SplayTree<>() : null;

        if (r.length >= p) {
            Hash.RollingHash rh = new Hash.RollingHash(r, 0, p);
            if (useSplay) {
                List<Integer> list = hrSp.insertOrGet(rh.value(), new ArrayList<>());
                list.add(0);
            } else {
                hrHt.computeIfAbsent(rh.value(), k -> new ArrayList<>()).add(0);
            }
            for (int a = 1; a <= r.length - p; a++) {
                rh.roll(r[a - 1] & 0xFF, r[a + p - 1] & 0xFF);
                if (useSplay) {
                    List<Integer> list = hrSp.insertOrGet(rh.value(), new ArrayList<>());
                    list.add(a);
                } else {
                    hrHt.computeIfAbsent(rh.value(), k -> new ArrayList<>()).add(a);
                }
            }
        }

        if (verbose) {
            System.err.printf("greedy: %s, |R|=%d, |V|=%d, seed_len=%d%n",
                useSplay ? "splay tree" : "hash table", r.length, v.length, p);
        }

        // Step (2): initialize scan pointers
        int vC = 0, vS = 0;

        Hash.RollingHash rhV = v.length >= p ? new Hash.RollingHash(v, 0, p) : null;
        int rhVPos = 0;

        while (vC + p <= v.length) {
            // Step (3): check for end of V; compute fingerprint
            long fpV;
            if (rhV == null) break;
            if (vC == rhVPos) {
                fpV = rhV.value();
            } else if (vC == rhVPos + 1) {
                rhV.roll(v[vC - 1] & 0xFF, v[vC + p - 1] & 0xFF);
                rhVPos = vC;
                fpV = rhV.value();
            } else {
                rhV = new Hash.RollingHash(v, vC, p);
                rhVPos = vC;
                fpV = rhV.value();
            }

            // Steps (4)+(5): find the longest matching substring
            int bestRm = -1;
            int bestLen = 0;

            List<Integer> offsets = useSplay ? hrSp.find(fpV) : hrHt.get(fpV);
            if (offsets != null) {
                for (int rCand : offsets) {
                    if (!Diff.arrayEquals(r, rCand, v, vC, p)) continue;
                    int ml = p;
                    while (vC + ml < v.length && rCand + ml < r.length
                           && v[vC + ml] == r[rCand + ml]) {
                        ml++;
                    }
                    if (ml > bestLen) {
                        bestLen = ml;
                        bestRm = rCand;
                    }
                }
            }

            if (bestLen < p) { vC++; continue; }

            // Step (6): encode
            if (vS < vC) {
                commands.add(new AddCmd(Diff.copyRange(v, vS, vC)));
            }
            commands.add(new CopyCmd(bestRm, bestLen));
            vS = vC + bestLen;

            // Step (7): advance past matched region
            vC += bestLen;
        }

        // Step (8): trailing add
        if (vS < v.length) {
            commands.add(new AddCmd(Diff.copyRange(v, vS, v.length)));
        }

        if (verbose) Diff.printStats(commands);
        return commands;
    }

}
