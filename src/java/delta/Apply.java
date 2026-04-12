package delta;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.PriorityQueue;

import static delta.Types.*;

/**
 * Command placement, application, and in-place reordering.
 *
 * placeCommands: assign sequential destinations (Section 2.1.1)
 * makeInplace:   CRWI digraph + topological sort (Burns et al. 2003)
 */
public final class Apply {
    private Apply() {}

    /** Compute total output size of algorithm commands. */
    public static long outputSize(List<Command> commands) {
        long size = 0;
        for (Command cmd : commands) {
            if (cmd instanceof CopyCmd c) size += c.length();
            else if (cmd instanceof AddCmd a) size += a.data().length;
        }
        return size;
    }

    /** Convert algorithm commands to placed commands with sequential destinations. */
    public static List<PlacedCommand> placeCommands(List<Command> commands) {
        List<PlacedCommand> placed = new ArrayList<>(commands.size());
        long dst = 0;
        for (Command cmd : commands) {
            if (cmd instanceof CopyCmd c) {
                placed.add(new PlacedCopy(c.offset(), dst, c.length()));
                dst += c.length();
            } else if (cmd instanceof AddCmd a) {
                placed.add(new PlacedAdd(dst, a.data()));
                dst += a.data().length;
            }
        }
        return placed;
    }

    /** Apply placed commands in standard mode: read from R, write to out. */
    public static long applyPlacedTo(byte[] r, List<PlacedCommand> commands, byte[] out) {
        long maxWritten = 0;
        for (PlacedCommand cmd : commands) {
            if (cmd instanceof PlacedCopy c) {
                System.arraycopy(r, (int) c.src(), out, (int) c.dst(), (int) c.length());
                long end = c.dst() + c.length();
                if (end > maxWritten) maxWritten = end;
            } else if (cmd instanceof PlacedAdd a) {
                System.arraycopy(a.data(), 0, out, (int) a.dst(), a.data().length);
                long end = a.dst() + a.data().length;
                if (end > maxWritten) maxWritten = end;
            } else if (cmd instanceof PlacedMove m) {
                System.arraycopy(out, (int) m.src(), out, (int) m.dst(), (int) m.length());
                long end = m.dst() + m.length();
                if (end > maxWritten) maxWritten = end;
            }
        }
        return maxWritten;
    }

    /** Apply placed commands in-place within a single buffer. */
    public static void applyPlacedInplaceTo(List<PlacedCommand> commands, byte[] buf) {
        for (PlacedCommand cmd : commands) {
            if (cmd instanceof PlacedCopy c) {
                System.arraycopy(buf, (int) c.src(), buf, (int) c.dst(), (int) c.length());
            } else if (cmd instanceof PlacedAdd a) {
                System.arraycopy(a.data(), 0, buf, (int) a.dst(), a.data().length);
            } else if (cmd instanceof PlacedMove m) {
                System.arraycopy(buf, (int) m.src(), buf, (int) m.dst(), (int) m.length());
            }
        }
    }

    /** Validate placed commands before apply so malformed deltas fail cleanly. */
    public static void validatePlacedCommands(List<PlacedCommand> commands,
                                              long referenceSize, long versionSize,
                                              boolean inplace) {
        long sourceLimit = inplace ? Math.max(referenceSize, versionSize) : referenceSize;
        for (PlacedCommand cmd : commands) {
            if (cmd instanceof PlacedCopy c) {
                validateRange(c.dst(), c.length(), versionSize, "copy destination");
                validateRange(c.src(), c.length(), sourceLimit, "copy source");
            } else if (cmd instanceof PlacedAdd a) {
                validateRange(a.dst(), a.data().length, versionSize, "add destination");
            } else if (cmd instanceof PlacedMove m) {
                validateRange(m.dst(), m.length(), versionSize, "move destination");
                if (m.src() + m.length() > m.dst())
                    throw new IllegalArgumentException(
                        "MOVE src+length > dst: encoder ordering constraint violated");
            }
        }
    }

    /** Reconstruct version from reference + algorithm commands. */
    public static byte[] applyDelta(byte[] r, List<Command> commands) {
        long sz = outputSize(commands);
        if (sz > Integer.MAX_VALUE)
            throw new IllegalArgumentException("output size too large for JVM");
        byte[] out = new byte[(int) sz];
        int pos = 0;
        for (Command cmd : commands) {
            if (cmd instanceof CopyCmd c) {
                System.arraycopy(r, (int) c.offset(), out, pos, (int) c.length());
                pos += (int) c.length();
            } else if (cmd instanceof AddCmd a) {
                System.arraycopy(a.data(), 0, out, pos, a.data().length);
                pos += a.data().length;
            }
        }
        return out;
    }

    /** Apply placed in-place commands to a buffer initialized with R. */
    public static byte[] applyDeltaInplace(byte[] r, List<PlacedCommand> commands,
                                           long versionSize) {
        long bufSizeLong = Math.max((long) r.length, versionSize);
        if (bufSizeLong > Integer.MAX_VALUE)
            throw new IllegalArgumentException("buffer size too large for JVM");
        int bufSize = (int) bufSizeLong;
        byte[] buf = new byte[bufSize];
        System.arraycopy(r, 0, buf, 0, r.length);
        applyPlacedInplaceTo(commands, buf);
        if (buf.length != (int) versionSize) {
            return Arrays.copyOf(buf, (int) versionSize);
        }
        return buf;
    }

    private static void validateRange(long start, long len, long limit, String label) {
        if (start < 0 || len < 0 || start > limit || len > limit - start) {
            throw new IllegalArgumentException(label + " out of range");
        }
    }

    /**
     * Convert placed commands back to algorithm commands (strip destinations).
     * Commands are sorted by destination offset to recover original sequential order.
     * Throws if a PlacedMove is present (no algorithm-level equivalent).
     */
    public static List<Command> unplaceCommands(List<PlacedCommand> placed) {
        List<PlacedCommand> sorted = new ArrayList<>(placed);
        sorted.sort(Comparator.comparingLong(c -> {
            if (c instanceof PlacedCopy pc)  return pc.dst();
            if (c instanceof PlacedAdd  pa)  return pa.dst();
            return ((PlacedMove) c).dst();
        }));
        List<Command> commands = new ArrayList<>(sorted.size());
        for (PlacedCommand cmd : sorted) {
            if (cmd instanceof PlacedCopy c) commands.add(new CopyCmd(c.src(), c.length()));
            else if (cmd instanceof PlacedAdd a) commands.add(new AddCmd(a.data()));
            else throw new IllegalArgumentException("PlacedMove has no algorithm-level equivalent");
        }
        return commands;
    }

    // ── In-place reordering (Burns, Long, Stockmeyer, IEEE TKDE 2003) ──

    /** Source offset, destination offset, and length of one copy command. */
    private record CopyInfo(long src, long dst, long length) {}

    /** Non-trivial SCCs with per-SCC active counts and vertex-to-SCC mapping. */
    private record SccData(List<List<Integer>> sccs, int[] active, int[] id) {}

    /** Mutable cursor tracking which SCC and scan position pickVictim is examining. */
    private static class ScanCursor { int sccPtr = 0; int scanPos = 0; }

    /** Result of findCycleInScc: the cycle (or null) plus the updated scan position. */
    private record CycleResult(List<Integer> cycle, int newScan) {}

    // DFS color states for findCycleInScc
    private static final int COLOR_UNVISITED = 0;
    private static final int COLOR_ON_PATH   = 1;
    private static final int COLOR_DONE      = 2;

    /** Sentinel: vertex is in no non-trivial SCC. */
    private static final int NO_SCC = -1;

    /** One frame on the iterative DFS call stack: vertex and next-neighbor index. */
    private static final class DfsFrame {
        final int v; int ni;
        DfsFrame(int v) { this.v = v; this.ni = 0; }
    }

    /**
     * Build CRWI digraph on copy commands.
     *
     * Edge i→j means copy i reads from a region that copy j will overwrite,
     * so i must execute before j.  O(n log n + E) sweep-line construction.
     */
    private static List<List<Integer>> buildCrwiDigraph(List<CopyInfo> copies, int n) {
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());

        Integer[] writeSorted = new Integer[n];
        for (int i = 0; i < n; i++) writeSorted[i] = i;
        Arrays.sort(writeSorted, Comparator.comparingLong(a -> copies.get(a).dst()));
        long[] writeStarts = new long[n];
        for (int k = 0; k < n; k++) writeStarts[k] = copies.get(writeSorted[k]).dst();

        for (int i = 0; i < n; i++) {
            long src = copies.get(i).src(), len = copies.get(i).length();
            long readEnd = src + len;
            int lo = 0; { int a = 0, b = n;
                while (a < b) { int m = a + (b - a) / 2;
                    if (writeStarts[m] < src) a = m + 1; else b = m; }
                lo = a; }
            int hi = 0; { int a = lo, b = n;
                while (a < b) { int m = a + (b - a) / 2;
                    if (writeStarts[m] < readEnd) a = m + 1; else b = m; }
                hi = a; }
            if (lo > 0) {
                int j = writeSorted[lo - 1];
                if (j != i) {
                    long dj = copies.get(j).dst(), lj = copies.get(j).length();
                    if (dj + lj > src) adj.get(i).add(j);
                }
            }
            for (int k = lo; k < hi; k++) {
                int j = writeSorted[k];
                if (j != i) adj.get(i).add(j);
            }
        }
        return adj;
    }

    /** Wrap tarjanScc output into an SccData containing only non-trivial SCCs. */
    private static SccData buildSccList(List<List<Integer>> adj, int n) {
        List<List<Integer>> allSccs = tarjanScc(adj, n);
        int[] id = new int[n];
        Arrays.fill(id, NO_SCC);
        List<List<Integer>> sccs = new ArrayList<>();

        for (List<Integer> scc : allSccs) {
            if (scc.size() > 1) {
                int sid = sccs.size();
                for (int v : scc) id[v] = sid;
                sccs.add(scc);
            }
        }
        int[] active = new int[sccs.size()];
        for (int k = 0; k < sccs.size(); k++) active[k] = sccs.get(k).size();
        return new SccData(sccs, active, id);
    }

    private static int pickVictim(List<CopyInfo> copies, List<List<Integer>> adj,
            SccData scc, boolean[] removed, int[] color, ScanCursor cur,
            CyclePolicy policy, int n) {
        if (policy == CyclePolicy.CONSTANT) {
            for (int i = 0; i < n; i++) { if (!removed[i]) return i; }
            return -1;
        }
        int victim = -1;
        while (victim == -1) {
            while (cur.sccPtr < scc.sccs().size() && scc.active()[cur.sccPtr] == 0) {
                cur.sccPtr++; cur.scanPos = 0;
            }
            if (cur.sccPtr >= scc.sccs().size()) {
                for (int i = 0; i < n; i++) { if (!removed[i]) { victim = i; break; } }
            } else {
                CycleResult cr = findCycleInScc(
                    adj, scc.sccs().get(cur.sccPtr), cur.sccPtr, scc.id(), removed, color, cur.scanPos);
                cur.scanPos = cr.newScan();
                if (cr.cycle() != null) {
                    victim = cr.cycle().get(0);
                    for (int v : cr.cycle()) {
                        if (copies.get(v).length() < copies.get(victim).length() ||
                            (copies.get(v).length() == copies.get(victim).length() && v < victim)) {
                            victim = v;
                        }
                    }
                } else {
                    cur.sccPtr++; cur.scanPos = 0;
                }
            }
        }
        return victim;
    }

    private static List<Integer> runKahn(List<CopyInfo> copies, List<List<Integer>> adj,
            SccData scc, byte[] r, List<PlacedAdd> adds, CyclePolicy policy, int n) {
        int[] inDeg = new int[n];
        for (int i = 0; i < n; i++) for (int j : adj.get(i)) inDeg[j]++;

        boolean[] removed  = new boolean[n];
        List<Integer> topo = new ArrayList<>();
        int[] color        = new int[n];
        ScanCursor cursor  = new ScanCursor();

        PriorityQueue<long[]> heap = new PriorityQueue<>((a, b) -> {
            int cmp = Long.compare(a[0], b[0]);
            return cmp != 0 ? cmp : Long.compare(a[1], b[1]);
        });
        for (int i = 0; i < n; i++) {
            if (inDeg[i] == 0) heap.add(new long[]{copies.get(i).length(), i});
        }
        int processed = 0;

        while (processed < n) {
            while (!heap.isEmpty()) {
                long[] entry = heap.poll();
                int v = (int) entry[1];
                if (removed[v]) continue;
                removed[v] = true;
                topo.add(v);
                processed++;
                if (scc.id()[v] != NO_SCC) scc.active()[scc.id()[v]]--;
                for (int w : adj.get(v)) {
                    if (!removed[w]) {
                        inDeg[w]--;
                        if (inDeg[w] == 0) heap.add(new long[]{copies.get(w).length(), w});
                    }
                }
            }

            if (processed >= n) break;

            int victim = pickVictim(copies, adj, scc, removed, color, cursor, policy, n);
            CopyInfo ci = copies.get(victim);
            int len = (int) ci.length();
            byte[] data = new byte[len];
            System.arraycopy(r, (int) ci.src(), data, 0, len);
            adds.add(new PlacedAdd(ci.dst(), data));
            removed[victim] = true;
            processed++;
            if (scc.id()[victim] != NO_SCC) scc.active()[scc.id()[victim]]--;
            for (int w : adj.get(victim)) {
                if (!removed[w]) {
                    inDeg[w]--;
                    if (inDeg[w] == 0) heap.add(new long[]{copies.get(w).length(), w});
                }
            }
        }
        return topo;
    }

    /**
     * Convert standard delta commands to in-place executable commands.
     *
     * Algorithm (Burns, Long, Stockmeyer, IEEE TKDE 2003):
     *   1. Annotate each command with its write offset
     *   2. Build CRWI digraph on copy commands (Section 4.2)
     *   3. Topological sort (Kahn); when heap empties with remaining nodes,
     *      find the cycle and convert the minimum-length copy to an add
     *   4. Output: copies in topological order, then all adds
     */
    public static List<PlacedCommand> makeInplace(byte[] r, List<Command> commands,
                                                   CyclePolicy policy) {
        if (commands.isEmpty()) return new ArrayList<>();

        List<CopyInfo> copies = new ArrayList<>();
        List<PlacedAdd> adds  = new ArrayList<>();
        long writePos = 0;
        for (Command cmd : commands) {
            if (cmd instanceof CopyCmd c) {
                copies.add(new CopyInfo(c.offset(), writePos, c.length()));
                writePos += c.length();
            } else if (cmd instanceof AddCmd a) {
                adds.add(new PlacedAdd(writePos, a.data()));
                writePos += a.data().length;
            }
        }
        int n = copies.size();
        if (n == 0) return new ArrayList<>(adds);

        List<List<Integer>> adj = buildCrwiDigraph(copies, n);
        SccData scc             = buildSccList(adj, n);
        List<Integer> topoOrder = runKahn(copies, adj, scc, r, adds, policy, n);

        List<PlacedCommand> result = new ArrayList<>();
        for (int i : topoOrder) {
            CopyInfo ci = copies.get(i);
            result.add(new PlacedCopy(ci.src(), ci.dst(), ci.length()));
        }
        result.addAll(adds);
        return result;
    }

    private static List<List<Integer>> tarjanScc(List<List<Integer>> adj, int n) {
        int[] index = new int[n];
        Arrays.fill(index, NO_SCC);
        int[] lowlink = new int[n];
        boolean[] onStack = new boolean[n];
        Deque<Integer> tarjanStack = new ArrayDeque<>();
        List<List<Integer>> sccs = new ArrayList<>();
        int counter = 0;
        Deque<DfsFrame> callStack = new ArrayDeque<>();

        for (int start = 0; start < n; start++) {
            if (index[start] != NO_SCC) continue;

            index[start] = lowlink[start] = counter++;
            onStack[start] = true;
            tarjanStack.push(start);
            callStack.push(new DfsFrame(start));

            while (!callStack.isEmpty()) {
                DfsFrame frame = callStack.peek();
                int v = frame.v;
                List<Integer> neighbors = adj.get(v);

                if (frame.ni < neighbors.size()) {
                    int w = neighbors.get(frame.ni++);
                    if (index[w] == NO_SCC) {
                        index[w] = lowlink[w] = counter++;
                        onStack[w] = true;
                        tarjanStack.push(w);
                        callStack.push(new DfsFrame(w));
                    } else if (onStack[w]) {
                        if (index[w] < lowlink[v]) lowlink[v] = index[w];
                    }
                } else {
                    callStack.pop();
                    if (!callStack.isEmpty()) {
                        int parent = callStack.peek().v;
                        if (lowlink[v] < lowlink[parent])
                            lowlink[parent] = lowlink[v];
                    }
                    if (lowlink[v] == index[v]) {
                        List<Integer> scc = new ArrayList<>();
                        int w;
                        do {
                            w = tarjanStack.pop();
                            onStack[w] = false;
                            scc.add(w);
                        } while (w != v);
                        sccs.add(scc);
                    }
                }
            }
        }
        return sccs;
    }

    private static CycleResult findCycleInScc(
            List<List<Integer>> adj, List<Integer> scc, int sid,
            int[] sccId, boolean[] removed, int[] color, int scanStart) {
        List<Integer> path = new ArrayList<>();
        int scan   = scanStart;
        int sccLen = scc.size();

        while (scan < sccLen) {
            int start = scc.get(scan);
            if (removed[start] || color[start] != COLOR_UNVISITED) { scan++; continue; }

            color[start] = COLOR_ON_PATH;
            path.add(start);
            Deque<DfsFrame> stack = new ArrayDeque<>();
            stack.push(new DfsFrame(start));

            outer:
            while (!stack.isEmpty()) {
                DfsFrame frame = stack.peek();
                int v = frame.v;
                List<Integer> neighbors = adj.get(v);
                boolean advanced = false;

                while (frame.ni < neighbors.size()) {
                    int w = neighbors.get(frame.ni++);
                    if (sccId[w] != sid || removed[w]) { continue; }
                    if (color[w] == COLOR_ON_PATH) {
                        int pos = path.indexOf(w);
                        List<Integer> cycle = new ArrayList<>(path.subList(pos, path.size()));
                        for (int u : path) { color[u] = COLOR_UNVISITED; }
                        return new CycleResult(cycle, scan);
                    }
                    if (color[w] == COLOR_UNVISITED) {
                        color[w] = COLOR_ON_PATH;
                        path.add(w);
                        stack.push(new DfsFrame(w));
                        advanced = true;
                        continue outer;
                    }
                }
                if (!advanced) {
                    stack.pop();
                    color[v] = COLOR_DONE;
                    path.remove(path.size() - 1);
                }
            }
            scan++;
        }

        return new CycleResult(null, scan);
    }

    /** Compute summary statistics for placed commands. */
    public static PlacedSummary placedSummary(List<PlacedCommand> commands) {
        int numCopies = 0, numAdds = 0;
        long copyBytes = 0, addBytes = 0;
        for (PlacedCommand cmd : commands) {
            if (cmd instanceof PlacedCopy c) {
                numCopies++;
                copyBytes += c.length();
            } else if (cmd instanceof PlacedAdd a) {
                numAdds++;
                addBytes += a.data().length;
            } else if (cmd instanceof PlacedMove m) {
                numCopies++;
                copyBytes += m.length();
            }
        }
        return new PlacedSummary(commands.size(), numCopies, numAdds,
            copyBytes, addBytes, copyBytes + addBytes);
    }
}
