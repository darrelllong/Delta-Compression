package delta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static delta.Types.*;

/**
 * One-Pass algorithm (Section 4.1, Figure 3).
 *
 * Scans R and V concurrently with two hash tables (one per string).
 * Each table stores at most one offset per footprint (retain-existing
 * policy: first entry wins, later collisions are discarded).
 * Hash tables are logically flushed after each match via version counter.
 * Time: O(np + q), space: O(q).
 */
public final class Onepass {
    private Onepass() {}

    public static List<Command> diff(byte[] r, byte[] v, DiffOptions opts) {
        List<Command> commands = new ArrayList<>();
        if (v.length == 0) return commands;

        int p = opts.p;
        int q = opts.q;
        boolean verbose = opts.verbose;
        boolean useSplay = opts.useSplay;

        // Auto-size hash table: one slot per p-byte chunk of R (floor = q).
        int numSeeds = r.length >= p ? r.length - p + 1 : 0;
        q = (int) Hash.nextPrime(Math.max(q, numSeeds / p));

        if (verbose) {
            System.err.printf("onepass: %s, q=%d, |R|=%d, |V|=%d, seed_len=%d%n",
                useSplay ? "splay tree" : "hash table", q, r.length, v.length, p);
        }

        // Step (1): lookup structures with version-based logical flushing.
        // Hash table entries: parallel arrays for fp, offset, version.
        long[] htVFp = null, htRFp = null;
        int[] htVOff = null, htROff = null;
        long[] htVVer = null, htRVer = null;

        SplayTree<long[]> spV = null, spR = null;

        if (useSplay) {
            spV = new SplayTree<>();
            spR = new SplayTree<>();
        } else {
            htVFp = new long[q]; htVOff = new int[q]; htVVer = new long[q];
            htRFp = new long[q]; htROff = new int[q]; htRVer = new long[q];
            Arrays.fill(htVVer, -1);
            Arrays.fill(htRVer, -1);
        }

        // Step (2): initialize scan pointers
        long ver = 0;
        int rC = 0, vC = 0, vS = 0;

        Hash.RollingHash rhV = v.length >= p ? new Hash.RollingHash(v, 0, p) : null;
        Hash.RollingHash rhR = r.length >= p ? new Hash.RollingHash(r, 0, p) : null;
        int rhVPos = 0, rhRPos = 0;

        while (true) {
            // Step (3): check for end of V and R
            boolean canV = vC + p <= v.length;
            boolean canR = rC + p <= r.length;
            if (!canV && !canR) break;

            long fpV = -1, fpR = -1;
            boolean hasFpV = false, hasFpR = false;

            if (canV && rhV != null) {
                if (vC == rhVPos) {
                    // already positioned
                } else if (vC == rhVPos + 1) {
                    rhV.roll(v[vC - 1] & 0xFF, v[vC + p - 1] & 0xFF);
                    rhVPos = vC;
                } else {
                    rhV = new Hash.RollingHash(v, vC, p);
                    rhVPos = vC;
                }
                fpV = rhV.value();
                hasFpV = true;
            }
            if (canR && rhR != null) {
                if (rC == rhRPos) {
                    // already positioned
                } else if (rC == rhRPos + 1) {
                    rhR.roll(r[rC - 1] & 0xFF, r[rC + p - 1] & 0xFF);
                    rhRPos = rC;
                } else {
                    rhR = new Hash.RollingHash(r, rC, p);
                    rhRPos = rC;
                }
                fpR = rhR.value();
                hasFpR = true;
            }

            // Step (4a): store offsets (retain-existing policy)
            if (hasFpV) {
                if (useSplay) {
                    long[] existing = spV.find(fpV);
                    if (existing == null || existing[1] != ver) {
                        spV.insert(fpV, new long[]{vC, ver});
                    }
                } else {
                    htPut(htVFp, htVOff, htVVer, fpV, vC, q, ver);
                }
            }
            if (hasFpR) {
                if (useSplay) {
                    long[] existing = spR.find(fpR);
                    if (existing == null || existing[1] != ver) {
                        spR.insert(fpR, new long[]{rC, ver});
                    }
                } else {
                    htPut(htRFp, htROff, htRVer, fpR, rC, q, ver);
                }
            }

            // Step (4b): look for a matching seed in the other table
            boolean matchFound = false;
            int rM = 0, vM = 0;

            if (hasFpR) {
                int vCand;
                if (useSplay) {
                    long[] entry = spV.find(fpR);
                    vCand = (entry != null && entry[1] == ver) ? (int) entry[0] : -1;
                } else {
                    vCand = htGet(htVFp, htVOff, htVVer, fpR, q, ver);
                }
                if (vCand >= 0 && Diff.arrayEquals(r, rC, v, vCand, p)) {
                    rM = rC;
                    vM = vCand;
                    matchFound = true;
                }
            }

            if (!matchFound && hasFpV) {
                int rCand;
                if (useSplay) {
                    long[] entry = spR.find(fpV);
                    rCand = (entry != null && entry[1] == ver) ? (int) entry[0] : -1;
                } else {
                    rCand = htGet(htRFp, htROff, htRVer, fpV, q, ver);
                }
                if (rCand >= 0 && Diff.arrayEquals(v, vC, r, rCand, p)) {
                    vM = vC;
                    rM = rCand;
                    matchFound = true;
                }
            }

            if (!matchFound) { vC++; rC++; continue; }

            // Step (5): extend match forward
            int ml = 0;
            while (vM + ml < v.length && rM + ml < r.length && v[vM + ml] == r[rM + ml]) {
                ml++;
            }

            if (ml < p) { vC++; rC++; continue; }

            // Step (6): encode
            if (vS < vM) {
                commands.add(new AddCmd(Diff.copyRange(v, vS, vM)));
            }
            commands.add(new CopyCmd(rM, ml));
            vS = vM + ml;

            // Step (7): advance pointers and flush tables
            vC = vM + ml;
            rC = rM + ml;
            ver++;
        }

        // Step (8): trailing add
        if (vS < v.length) {
            commands.add(new AddCmd(Diff.copyRange(v, vS, v.length)));
        }

        if (verbose) Diff.printStats(commands);
        return commands;
    }

    private static void htPut(long[] fps, int[] offs, long[] vers,
                               long fp, int off, int q, long ver) {
        int idx = (int) (Long.remainderUnsigned(fp, q));
        if (vers[idx] == ver) return; // retain-existing
        fps[idx] = fp;
        offs[idx] = off;
        vers[idx] = ver;
    }

    private static int htGet(long[] fps, int[] offs, long[] vers,
                              long fp, int q, long ver) {
        int idx = (int) (Long.remainderUnsigned(fp, q));
        if (vers[idx] == ver && fps[idx] == fp) return offs[idx];
        return -1;
    }

}
