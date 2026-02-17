package delta;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static delta.Types.*;

/**
 * Correcting 1.5-Pass algorithm (Section 7, Figure 8) with
 * fingerprint-based checkpointing (Section 8).
 *
 * |C| = q (hash table capacity, auto-sized from input).
 * |F| = next_prime(2 * num_R_seeds) (footprint universe, Section 8.1).
 * m  = ceil(|F| / |C|) (checkpoint spacing, p. 348).
 * k  = checkpoint class (Eq. 3, p. 348).
 */
public final class Correcting {
    private Correcting() {}

    private static final class BufEntry {
        int vStart, vEnd;
        Command cmd;
        boolean dummy;

        BufEntry(int vStart, int vEnd, Command cmd, boolean dummy) {
            this.vStart = vStart;
            this.vEnd = vEnd;
            this.cmd = cmd;
            this.dummy = dummy;
        }
    }

    public static List<Command> diff(byte[] r, byte[] v, int p, int q,
                                     int bufCap, boolean verbose,
                                     boolean useSplay, int minCopy) {
        List<Command> commands = new ArrayList<>();
        if (v.length == 0) return commands;

        if (minCopy > 0) p = Math.max(p, minCopy);

        // ── Checkpointing parameters (Section 8.1, pp. 347-348) ─────
        int numSeeds = r.length >= p ? r.length - p + 1 : 0;
        int cap = numSeeds > 0
            ? (int) Hash.nextPrime(Math.max(q, 2 * numSeeds / p))
            : (int) Hash.nextPrime(q);
        long fSize = numSeeds > 0 ? Hash.nextPrime(2 * numSeeds) : 1;
        long m = fSize <= cap ? 1 : (fSize + cap - 1) / cap;
        long k = v.length >= p
            ? Hash.fingerprint(v, v.length / 2, p) % fSize % m
            : 0;

        if (verbose) {
            long expected = m > 0 ? numSeeds / m : 0;
            long occEst = cap > 0 ? expected * 100 / cap : 0;
            System.err.printf("correcting: %s, |C|=%d |F|=%d m=%d k=%d%n" +
                "  checkpoint gap=%d bytes, expected fill ~%d (~%d%% table occupancy)%n",
                useSplay ? "splay tree" : "hash table", cap, fSize, m, k,
                m, expected, occEst);
        }

        // Step (1): Build lookup structure for R (first-found policy)
        long[] htFp = null;
        int[] htOff = null;
        boolean[] htUsed = null;
        SplayTree<long[]> splayR = null; // value = {full_fp, offset}

        if (useSplay) {
            splayR = new SplayTree<>();
        } else {
            htFp = new long[cap];
            htOff = new int[cap];
            htUsed = new boolean[cap];
        }

        Hash.RollingHash rhR = numSeeds > 0 ? new Hash.RollingHash(r, 0, p) : null;
        for (int a = 0; a < numSeeds; a++) {
            long fp;
            if (a == 0) {
                fp = rhR.value();
            } else {
                rhR.roll(r[a - 1] & 0xFF, r[a + p - 1] & 0xFF);
                fp = rhR.value();
            }
            long f = Long.remainderUnsigned(fp, fSize);
            if (f % m != k) continue; // not a checkpoint seed

            if (useSplay) {
                splayR.insertOrGet(fp, new long[]{fp, a});
            } else {
                int i = (int) (f / m);
                if (i >= cap) continue;
                if (!htUsed[i]) {
                    htFp[i] = fp;
                    htOff[i] = a;
                    htUsed[i] = true;
                }
            }
        }

        // Encoding lookback buffer (Section 5.2)
        Deque<BufEntry> buf = new ArrayDeque<>();

        // Step (2): initialize scan pointers
        int vC = 0, vS = 0;

        int vSeeds = v.length >= p ? v.length - p + 1 : 0;
        Hash.RollingHash rhV = vSeeds > 0 ? new Hash.RollingHash(v, 0, p) : null;
        int rhVPos = 0;

        while (vC + p <= v.length) {  // Step (3): check for end of V
            // Step (4): fingerprint at vC, apply checkpoint test.
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

            long fV = Long.remainderUnsigned(fpV, fSize);
            if (fV % m != k) { vC++; continue; }

            // Checkpoint passed — look up R.
            long storedFp;
            int rOffset;
            if (useSplay) {
                long[] entry = splayR.find(fpV);
                if (entry == null) { vC++; continue; }
                storedFp = entry[0];
                rOffset = (int) entry[1];
            } else {
                int i = (int) (fV / m);
                if (i >= cap || !htUsed[i]) { vC++; continue; }
                storedFp = htFp[i];
                rOffset = htOff[i];
            }

            if (storedFp != fpV) { vC++; continue; }
            if (!arrayEquals(r, rOffset, v, vC, p)) { vC++; continue; }

            // Step (5): extend match forwards and backwards
            int fwd = p;
            while (vC + fwd < v.length && rOffset + fwd < r.length
                   && v[vC + fwd] == r[rOffset + fwd]) {
                fwd++;
            }
            int bwd = 0;
            while (vC >= bwd + 1 && rOffset >= bwd + 1
                   && v[vC - bwd - 1] == r[rOffset - bwd - 1]) {
                bwd++;
            }

            int vM = vC - bwd;
            int rM = rOffset - bwd;
            int ml = bwd + fwd;
            int matchEnd = vM + ml;

            if (ml < p) { vC++; continue; }

            // Step (6): encode with correction
            if (vS <= vM) {
                // (6a) match in unencoded suffix
                if (vS < vM) {
                    if (buf.size() >= bufCap) {
                        BufEntry oldest = buf.pollFirst();
                        if (!oldest.dummy) commands.add(oldest.cmd);
                    }
                    buf.addLast(new BufEntry(vS, vM,
                        new AddCmd(Greedy.copyRange(v, vS, vM)), false));
                }
                if (buf.size() >= bufCap) {
                    BufEntry oldest = buf.pollFirst();
                    if (!oldest.dummy) commands.add(oldest.cmd);
                }
                buf.addLast(new BufEntry(vM, matchEnd,
                    new CopyCmd(rM, ml), false));
                vS = matchEnd;
            } else {
                // (6b) tail correction (Section 5.1, p. 339)
                int effectiveStart = vS;

                while (!buf.isEmpty()) {
                    BufEntry tail = buf.peekLast();
                    if (tail.dummy) { buf.pollLast(); continue; }

                    if (tail.vStart >= vM && tail.vEnd <= matchEnd) {
                        effectiveStart = Math.min(effectiveStart, tail.vStart);
                        buf.pollLast();
                        continue;
                    }

                    if (tail.vEnd > vM && tail.vStart < vM) {
                        if (tail.cmd instanceof AddCmd) {
                            int keep = vM - tail.vStart;
                            if (keep > 0) {
                                tail.cmd = new AddCmd(Greedy.copyRange(v, tail.vStart, vM));
                                tail.vEnd = vM;
                            } else {
                                buf.pollLast();
                            }
                            effectiveStart = Math.min(effectiveStart, vM);
                        }
                        break;
                    }
                    break;
                }

                int adj = effectiveStart - vM;
                int newLen = matchEnd - effectiveStart;
                if (newLen > 0) {
                    if (buf.size() >= bufCap) {
                        BufEntry oldest = buf.pollFirst();
                        if (!oldest.dummy) commands.add(oldest.cmd);
                    }
                    buf.addLast(new BufEntry(effectiveStart, matchEnd,
                        new CopyCmd(rM + adj, newLen), false));
                }
                vS = matchEnd;
            }

            // Step (7): advance past matched region
            vC = matchEnd;
        }

        // Step (8): flush buffer and trailing add
        for (BufEntry entry : buf) {
            if (!entry.dummy) commands.add(entry.cmd);
        }
        if (vS < v.length) {
            commands.add(new AddCmd(Greedy.copyRange(v, vS, v.length)));
        }

        if (verbose) Greedy.printStats(commands);
        return commands;
    }

    private static boolean arrayEquals(byte[] a, int aOff, byte[] b, int bOff, int len) {
        for (int i = 0; i < len; i++) {
            if (a[aOff + i] != b[bOff + i]) return false;
        }
        return true;
    }
}
