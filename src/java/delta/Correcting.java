package delta;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static delta.Types.*;

/**
 * The correcting 1.5-pass algorithm (Section 7, Figure 8) with checkpointing
 * (Section 8).
 *
 * The first pass indexes seeds of R; the second scans V, extending each match
 * in both directions.  A match that extends backwards over bytes of V already
 * encoded replaces the commands it covers, provided they are still in the
 * lookback buffer (Section 5).
 *
 * Checkpointing bounds the table for any |R|.  Fingerprints are mapped into
 * F = [0, |F|), and only the seeds whose image is congruent to k modulo m are
 * indexed or looked up, about one seed in m.  Such an image f has slot
 * f / m in a table of |C| slots, where m = ceil(|F| / |C|).
 */
public final class Correcting {
    private Correcting() {}

    /** The checkpoint seeds of R: fingerprint to offset, first offset kept. */
    private static final class Table {
        // No fingerprint is negative, so -1 marks an empty slot.
        private static final long EMPTY = -1;

        private final long[] fp;
        private final int[] offset;
        private final SplayTree<Integer> tree;

        Table(int cap, boolean useSplay) {
            if (useSplay) {
                tree = new SplayTree<>();
                fp = null;
                offset = null;
            } else {
                tree = null;
                fp = new long[cap];
                offset = new int[cap];
                Arrays.fill(fp, EMPTY);
            }
        }

        /**
         * Records that the seed with fingerprint f is at off, unless f is
         * already present.  The hash table probes linearly from slot and
         * drops the seed if it is full.
         */
        void add(long f, int slot, int off) {
            if (tree != null) {
                tree.insertOrGet(f, off);
                return;
            }
            int i = slot;
            while (fp[i] != EMPTY) {
                if (fp[i] == f) return;
                if (++i == fp.length) i = 0;
                if (i == slot) return;
            }
            fp[i] = f;
            offset[i] = off;
        }

        /** Returns the offset recorded for f, or -1. */
        int find(long f, int slot) {
            if (tree != null) {
                Integer off = tree.find(f);
                return off != null ? off : -1;
            }
            int i = slot;
            while (fp[i] != EMPTY) {
                if (fp[i] == f) return offset[i];
                if (++i == fp.length) i = 0;
                if (i == slot) break;
            }
            return -1;
        }
    }

    /** A command that may still be corrected, and the bytes of V it produces. */
    private static final class Pending {
        final int vStart;
        int vEnd; // exclusive
        Command cmd;

        Pending(int vStart, int vEnd, Command cmd) {
            this.vStart = vStart;
            this.vEnd = vEnd;
            this.cmd = cmd;
        }
    }

    /**
     * The last cap commands, oldest first (Section 5.2).  A command that
     * leaves at the front is final and goes to the output.
     */
    private static final class Lookback {
        private final ArrayDeque<Pending> buf = new ArrayDeque<>();
        private final int cap;
        private final List<Command> out;

        Lookback(int cap, List<Command> out) {
            this.cap = cap;
            this.out = out;
        }

        void push(int vStart, int vEnd, Command cmd) {
            if (buf.size() >= cap && !buf.isEmpty()) out.add(buf.pollFirst().cmd);
            buf.addLast(new Pending(vStart, vEnd, cmd));
        }

        /**
         * Makes room for a match on v[vM..vEnd) that begins before vS, the
         * end of the encoded part of V (tail correction, Section 5.1).
         * Removes the newest commands that the match covers entirely; if the
         * next one is an add that the match covers in part, cuts it back to
         * end at vM.  A copy covered in part is left whole.  Returns where
         * the copy for the match must start: the start of the bytes freed.
         */
        int correct(byte[] v, int vM, int vEnd, int vS) {
            int start = vS;
            Pending tail;
            while ((tail = buf.peekLast()) != null
                   && tail.vStart >= vM && tail.vEnd <= vEnd) {
                start = Math.min(start, tail.vStart);
                buf.pollLast();
            }
            if (tail != null && tail.vStart < vM && tail.vEnd > vM
                    && tail.cmd instanceof AddCmd) {
                tail.cmd = Diff.literal(v, tail.vStart, vM);
                tail.vEnd = vM;
                start = Math.min(start, vM);
            }
            return start;
        }

        void flush() {
            for (Pending e : buf) out.add(e.cmd);
            buf.clear();
        }
    }

    /**
     * Returns |C| for a reference of numSeeds seeds: two slots for every p
     * bytes of R, but at least opts.q and, with precedence, at most
     * opts.maxTable; then the next prime.
     */
    static int tableSize(int numSeeds, DiffOptions opts) {
        int maxTable = opts.maxTable > 0 ? opts.maxTable : MAX_TABLE_SIZE;
        long wanted = numSeeds > 0 ? Math.max(opts.q, 2L * numSeeds / opts.p) : opts.q;
        return (int) Hash.nextPrime(Math.min(maxTable, wanted));
    }

    /** Returns commands that build v from r. */
    public static List<Command> diff(byte[] r, byte[] v, DiffOptions opts) {
        List<Command> commands = new ArrayList<>();
        if (v.length == 0) return commands;

        int p = opts.p;
        int numSeeds = Diff.seedCount(r, p);

        // |F|: a prime of about twice the number of seeds (Section 8.1).
        int cap = tableSize(numSeeds, opts);
        long fSize = numSeeds > 0 ? Hash.nextPrime(2L * numSeeds) : 1;
        long m = fSize <= cap ? 1 : (fSize + cap - 1) / cap;
        // k is the residue of a seed of V, which favors the residues common
        // in V (p. 348).  The paper takes a random seed; this takes the one
        // in the middle of V, so that the delta is reproducible.
        long k = v.length >= p
            ? Hash.fingerprint(v, Math.min(v.length / 2, v.length - p), p) % fSize % m
            : 0;

        if (opts.verbose) {
            long expected = numSeeds / m;
            System.err.printf("correcting: %s, |C|=%d |F|=%d m=%d k=%d%n" +
                "  checkpoint gap=%d bytes, expected fill ~%d (~%d%% table occupancy)%n",
                opts.useSplay ? "splay tree" : "hash table", cap, fSize, m, k,
                m, expected, expected * 100 / cap);
        }

        Table table = new Table(cap, opts.useSplay);
        if (numSeeds > 0) {
            Hash.RollingHash rh = new Hash.RollingHash(r, 0, p);
            for (int a = 0; a < numSeeds; a++) {
                long fp = rh.seek(a);
                long f = fp % fSize;
                if (f % m == k) table.add(fp, (int) (f / m), a);
            }
        }

        Lookback lookback = new Lookback(opts.bufCap, commands);
        int vC = 0; // scan position in V
        int vS = 0; // end of the encoded part of V
        Hash.RollingHash rhV = v.length >= p ? new Hash.RollingHash(v, 0, p) : null;

        while (vC + p <= v.length) {
            long fp = rhV.seek(vC);
            long f = fp % fSize;
            int rOff = f % m == k ? table.find(fp, (int) (f / m)) : -1;
            // Equal fingerprints do not imply equal seeds.
            if (rOff < 0 || !Diff.seedsEqual(r, rOff, v, vC, p)) {
                vC++;
                continue;
            }

            int vEnd = vC + p + Diff.matchLength(v, vC + p, r, rOff + p);
            int back = 0;
            while (back < vC && back < rOff && v[vC - back - 1] == r[rOff - back - 1]) {
                back++;
            }
            int vM = vC - back;
            int rM = rOff - back;

            if (vM >= vS) {
                if (vS < vM) lookback.push(vS, vM, Diff.literal(v, vS, vM));
                lookback.push(vM, vEnd, new CopyCmd(rM, vEnd - vM));
            } else {
                int start = lookback.correct(v, vM, vEnd, vS);
                lookback.push(start, vEnd, new CopyCmd(rM + (start - vM), vEnd - start));
            }
            vS = vEnd;
            vC = vEnd;
        }

        lookback.flush();
        if (vS < v.length) commands.add(Diff.literal(v, vS, v.length));

        if (opts.verbose) Diff.printStats(commands);
        return commands;
    }
}
