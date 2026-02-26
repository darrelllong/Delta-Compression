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
    public static int outputSize(List<Command> commands) {
        int size = 0;
        for (Command cmd : commands) {
            if (cmd instanceof CopyCmd c) size += c.length();
            else if (cmd instanceof AddCmd a) size += a.data().length;
        }
        return size;
    }

    /** Convert algorithm commands to placed commands with sequential destinations. */
    public static List<PlacedCommand> placeCommands(List<Command> commands) {
        List<PlacedCommand> placed = new ArrayList<>(commands.size());
        int dst = 0;
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
    public static int applyPlacedTo(byte[] r, List<PlacedCommand> commands, byte[] out) {
        int maxWritten = 0;
        for (PlacedCommand cmd : commands) {
            if (cmd instanceof PlacedCopy c) {
                System.arraycopy(r, c.src(), out, c.dst(), c.length());
                int end = c.dst() + c.length();
                if (end > maxWritten) maxWritten = end;
            } else if (cmd instanceof PlacedAdd a) {
                System.arraycopy(a.data(), 0, out, a.dst(), a.data().length);
                int end = a.dst() + a.data().length;
                if (end > maxWritten) maxWritten = end;
            }
        }
        return maxWritten;
    }

    /** Apply placed commands in-place within a single buffer. */
    public static void applyPlacedInplaceTo(List<PlacedCommand> commands, byte[] buf) {
        for (PlacedCommand cmd : commands) {
            if (cmd instanceof PlacedCopy c) {
                System.arraycopy(buf, c.src(), buf, c.dst(), c.length());
            } else if (cmd instanceof PlacedAdd a) {
                System.arraycopy(a.data(), 0, buf, a.dst(), a.data().length);
            }
        }
    }

    /** Reconstruct version from reference + algorithm commands. */
    public static byte[] applyDelta(byte[] r, List<Command> commands) {
        byte[] out = new byte[outputSize(commands)];
        int pos = 0;
        for (Command cmd : commands) {
            if (cmd instanceof CopyCmd c) {
                System.arraycopy(r, c.offset(), out, pos, c.length());
                pos += c.length();
            } else if (cmd instanceof AddCmd a) {
                System.arraycopy(a.data(), 0, out, pos, a.data().length);
                pos += a.data().length;
            }
        }
        return out;
    }

    /** Apply placed in-place commands to a buffer initialized with R. */
    public static byte[] applyDeltaInplace(byte[] r, List<PlacedCommand> commands,
                                           int versionSize) {
        int bufSize = Math.max(r.length, versionSize);
        byte[] buf = new byte[bufSize];
        System.arraycopy(r, 0, buf, 0, r.length);
        applyPlacedInplaceTo(commands, buf);
        if (buf.length != versionSize) {
            return Arrays.copyOf(buf, versionSize);
        }
        return buf;
    }

    /**
     * Convert placed commands back to algorithm commands (strip destinations).
     * Commands are sorted by destination offset to recover original sequential order.
     */
    public static List<Command> unplaceCommands(List<PlacedCommand> placed) {
        List<PlacedCommand> sorted = new ArrayList<>(placed);
        sorted.sort(Comparator.comparingInt(c -> {
            if (c instanceof PlacedCopy pc) return pc.dst();
            else return ((PlacedAdd) c).dst();
        }));
        List<Command> commands = new ArrayList<>(sorted.size());
        for (PlacedCommand cmd : sorted) {
            if (cmd instanceof PlacedCopy c) commands.add(new CopyCmd(c.src(), c.length()));
            else if (cmd instanceof PlacedAdd a) commands.add(new AddCmd(a.data()));
        }
        return commands;
    }

    // ── In-place reordering (Burns, Long, Stockmeyer, IEEE TKDE 2003) ──

    /** Source offset, destination offset, and length of one copy command. */
    private record CopyInfo(int src, int dst, int length) {}

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

        // Sort copy write-intervals by start; binary-search for each read interval.
        Integer[] writeSorted = new Integer[n];
        for (int i = 0; i < n; i++) writeSorted[i] = i;
        Arrays.sort(writeSorted, Comparator.comparingInt(a -> copies.get(a).dst()));
        int[] writeStarts = new int[n];
        for (int k = 0; k < n; k++) writeStarts[k] = copies.get(writeSorted[k]).dst();

        for (int i = 0; i < n; i++) {
            int src = copies.get(i).src(), len = copies.get(i).length();
            int readEnd = src + len;
            // lo = first write with dst >= src; hi = first write with dst >= readEnd.
            // Writes in [lo, hi) start inside [src, readEnd) — they always overlap.
            // The write at lo-1 starts before src; overlaps iff its end exceeds src.
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
                    int dj = copies.get(j).dst(), lj = copies.get(j).length();
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

    /**
     * Select a victim copy to break a cycle when Kahn's algorithm stalls.
     *
     * Constant: first remaining vertex.  Localmin: minimum-length copy in a cycle.
     * cur.sccPtr and cur.scanPos are advanced in place across repeated calls.
     */
    private static int pickVictim(List<CopyInfo> copies, List<List<Integer>> adj,
            SccData scc, boolean[] removed, int[] color, ScanCursor cur,
            CyclePolicy policy, int n) {
        if (policy == CyclePolicy.CONSTANT) {
            for (int i = 0; i < n; i++) { if (!removed[i]) return i; }
            return -1; // unreachable: called only when processed < n
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

    /**
     * Run Kahn topological sort; when the heap stalls, call pickVictim to break
     * the cycle by materialising one copy as a literal add.
     */
    private static List<Integer> runKahn(List<CopyInfo> copies, List<List<Integer>> adj,
            SccData scc, byte[] r, List<PlacedAdd> adds, CyclePolicy policy, int n) {
        int[] inDeg = new int[n];
        for (int i = 0; i < n; i++) for (int j : adj.get(i)) inDeg[j]++;

        boolean[] removed  = new boolean[n];
        List<Integer> topo = new ArrayList<>();
        int[] color        = new int[n];   // 0=unvisited, 1=on-path, 2=done
        ScanCursor cursor  = new ScanCursor();

        PriorityQueue<int[]> heap = new PriorityQueue<>(
            Comparator.<int[]>comparingInt(e -> e[0]).thenComparingInt(e -> e[1]));
        for (int i = 0; i < n; i++) {
            if (inDeg[i] == 0) heap.add(new int[]{copies.get(i).length(), i});
        }
        int processed = 0;

        while (processed < n) {
            while (!heap.isEmpty()) {
                int[] entry = heap.poll();
                int v = entry[1];
                if (removed[v]) continue;
                removed[v] = true;
                topo.add(v);
                processed++;
                if (scc.id()[v] != NO_SCC) scc.active()[scc.id()[v]]--;
                for (int w : adj.get(v)) {
                    if (!removed[w]) {
                        inDeg[w]--;
                        if (inDeg[w] == 0) heap.add(new int[]{copies.get(w).length(), w});
                    }
                }
            }

            if (processed >= n) break;

            int victim = pickVictim(copies, adj, scc, removed, color, cursor, policy, n);
            CopyInfo ci = copies.get(victim);
            byte[] data = new byte[ci.length()];
            System.arraycopy(r, ci.src(), data, 0, ci.length());
            adds.add(new PlacedAdd(ci.dst(), data));
            removed[victim] = true;
            processed++;
            if (scc.id()[victim] != NO_SCC) scc.active()[scc.id()[victim]]--;
            for (int w : adj.get(victim)) {
                if (!removed[w]) {
                    inDeg[w]--;
                    if (inDeg[w] == 0) heap.add(new int[]{copies.get(w).length(), w});
                }
            }
        }
        return topo;
    }

    /**
     * Convert standard delta commands to in-place executable commands.
     *
     * A CRWI (Copy-Read/Write-Intersection) edge i→j means copy i reads
     * from a region that copy j will overwrite, so i must execute before j.
     * When the digraph is acyclic, a topological order gives a valid serial
     * schedule and no conversion is needed.  A cycle i₁→i₂→…→iₖ→i₁ creates
     * a circular dependency with no valid schedule; breaking it materializes
     * one copy as a literal add (reading source bytes from R before they are
     * overwritten).
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

        // Step 1: compute write offsets
        List<CopyInfo> copies = new ArrayList<>();
        List<PlacedAdd> adds  = new ArrayList<>();
        int writePos = 0;
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

        // Steps 2-3: build digraph, topological sort, break cycles
        List<List<Integer>> adj = buildCrwiDigraph(copies, n);
        SccData scc             = buildSccList(adj, n);
        List<Integer> topoOrder = runKahn(copies, adj, scc, r, adds, policy, n);

        // Step 4: assemble result — copies in topo order, then all adds
        List<PlacedCommand> result = new ArrayList<>();
        for (int i : topoOrder) {
            CopyInfo ci = copies.get(i);
            result.add(new PlacedCopy(ci.src(), ci.dst(), ci.length()));
        }
        result.addAll(adds);
        return result;
    }

    /**
     * Compute SCCs using iterative Tarjan's algorithm.
     *
     * Returns SCCs in reverse topological order (sinks first); caller
     * reverses for source-first processing order.
     *
     * R.E. Tarjan, "Depth-first search and linear graph algorithms,"
     * SIAM Journal on Computing, 1(2):146-160, June 1972.
     */
    private static List<List<Integer>> tarjanScc(List<List<Integer>> adj, int n) {
        int[] index = new int[n];
        Arrays.fill(index, NO_SCC); // NO_SCC = unvisited
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
                        // Tree edge: descend into w
                        index[w] = lowlink[w] = counter++;
                        onStack[w] = true;
                        tarjanStack.push(w);
                        callStack.push(new DfsFrame(w));
                    } else if (onStack[w]) {
                        // Back-edge into current SCC
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
        return sccs; // sinks first; caller reverses for source-first order
    }

    /**
     * Find a cycle in the active subgraph of one SCC.
     *
     * Three amortizations give O(|SCC| + E_SCC) total work per SCC:
     *   1. sccId filter: O(1) per neighbor check, no O(|SCC|) set/clear sweep.
     *   2. color persistence: color=2 (fully explored) persists across calls;
     *      vertex removal can only reduce edges, so color=2 is monotone-correct.
     *   3. scanStart: outer loop resumes from last position, O(|SCC|) total.
     *
     * Returns a CycleResult whose cycle is non-null on cycle found (path color=1
     * vertices reset to 0), or null when the SCC subgraph is acyclic.  newScan
     * is the updated scan position for the next call.
     */
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
                        // Back-edge: cycle found.
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
                    color[v] = COLOR_DONE; // Fully explored — persists across calls.
                    path.remove(path.size() - 1);
                }
            }
            // start's reachable SCC-subgraph fully explored; no cycle.
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
            }
        }
        return new PlacedSummary(commands.size(), numCopies, numAdds,
            copyBytes, addBytes, copyBytes + addBytes);
    }
}
