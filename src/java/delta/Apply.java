package delta;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
            if (cmd instanceof CopyCmd) size += ((CopyCmd) cmd).length;
            else if (cmd instanceof AddCmd) size += ((AddCmd) cmd).data.length;
        }
        return size;
    }

    /** Convert algorithm commands to placed commands with sequential destinations. */
    public static List<PlacedCommand> placeCommands(List<Command> commands) {
        List<PlacedCommand> placed = new ArrayList<>(commands.size());
        int dst = 0;
        for (Command cmd : commands) {
            if (cmd instanceof CopyCmd) {
                CopyCmd c = (CopyCmd) cmd;
                placed.add(new PlacedCopy(c.offset, dst, c.length));
                dst += c.length;
            } else if (cmd instanceof AddCmd) {
                AddCmd a = (AddCmd) cmd;
                placed.add(new PlacedAdd(dst, a.data));
                dst += a.data.length;
            }
        }
        return placed;
    }

    /** Apply placed commands in standard mode: read from R, write to out. */
    public static int applyPlacedTo(byte[] r, List<PlacedCommand> commands, byte[] out) {
        int maxWritten = 0;
        for (PlacedCommand cmd : commands) {
            if (cmd instanceof PlacedCopy) {
                PlacedCopy c = (PlacedCopy) cmd;
                System.arraycopy(r, c.src, out, c.dst, c.length);
                int end = c.dst + c.length;
                if (end > maxWritten) maxWritten = end;
            } else if (cmd instanceof PlacedAdd) {
                PlacedAdd a = (PlacedAdd) cmd;
                System.arraycopy(a.data, 0, out, a.dst, a.data.length);
                int end = a.dst + a.data.length;
                if (end > maxWritten) maxWritten = end;
            }
        }
        return maxWritten;
    }

    /** Apply placed commands in-place within a single buffer. */
    public static void applyPlacedInplaceTo(List<PlacedCommand> commands, byte[] buf) {
        for (PlacedCommand cmd : commands) {
            if (cmd instanceof PlacedCopy) {
                PlacedCopy c = (PlacedCopy) cmd;
                System.arraycopy(buf, c.src, buf, c.dst, c.length);
            } else if (cmd instanceof PlacedAdd) {
                PlacedAdd a = (PlacedAdd) cmd;
                System.arraycopy(a.data, 0, buf, a.dst, a.data.length);
            }
        }
    }

    /** Reconstruct version from reference + algorithm commands. */
    public static byte[] applyDelta(byte[] r, List<Command> commands) {
        byte[] out = new byte[outputSize(commands)];
        int pos = 0;
        for (Command cmd : commands) {
            if (cmd instanceof CopyCmd) {
                CopyCmd c = (CopyCmd) cmd;
                System.arraycopy(r, c.offset, out, pos, c.length);
                pos += c.length;
            } else if (cmd instanceof AddCmd) {
                AddCmd a = (AddCmd) cmd;
                System.arraycopy(a.data, 0, out, pos, a.data.length);
                pos += a.data.length;
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
            if (c instanceof PlacedCopy) return ((PlacedCopy) c).dst;
            return ((PlacedAdd) c).dst;
        }));
        List<Command> commands = new ArrayList<>(sorted.size());
        for (PlacedCommand cmd : sorted) {
            if (cmd instanceof PlacedCopy) {
                PlacedCopy c = (PlacedCopy) cmd;
                commands.add(new CopyCmd(c.src, c.length));
            } else if (cmd instanceof PlacedAdd) {
                commands.add(new AddCmd(((PlacedAdd) cmd).data));
            }
        }
        return commands;
    }

    // ── In-place reordering (Burns, Long, Stockmeyer, IEEE TKDE 2003) ──

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
        List<int[]> copies = new ArrayList<>(); // {idx, src, dst, length}
        List<PlacedAdd> adds = new ArrayList<>();
        int writePos = 0;

        for (Command cmd : commands) {
            if (cmd instanceof CopyCmd) {
                CopyCmd c = (CopyCmd) cmd;
                copies.add(new int[]{copies.size(), c.offset, writePos, c.length});
                writePos += c.length;
            } else if (cmd instanceof AddCmd) {
                AddCmd a = (AddCmd) cmd;
                adds.add(new PlacedAdd(writePos, a.data));
                writePos += a.data.length;
            }
        }

        int n = copies.size();
        if (n == 0) return new ArrayList<>(adds);

        // Step 2: build CRWI digraph
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());

        // O(n log n + E) sweep-line: sort writes by start, then for each read
        // interval binary-search into the sorted writes to find overlaps.
        Integer[] writeSorted = new Integer[n];
        for (int i = 0; i < n; i++) writeSorted[i] = i;
        Arrays.sort(writeSorted, Comparator.comparingInt(a -> copies.get(a)[2]));
        int[] writeStarts = new int[n];
        for (int k = 0; k < n; k++) writeStarts[k] = copies.get(writeSorted[k])[2];

        for (int i = 0; i < n; i++) {
            int si = copies.get(i)[1], li = copies.get(i)[3];
            int readEnd = si + li;
            // Two binary searches exploit the fact that dst intervals are
            // non-overlapping (each output byte written exactly once):
            //   lo = first write with dst >= si
            //   hi = first write with dst >= readEnd
            // Writes in [lo, hi) start within [si, readEnd) and thus always
            // overlap the read interval.  The write at lo-1 (if any) starts
            // before si; it overlaps iff its end exceeds si.
            int lo = 0, hi = n;
            { int a = 0, b = n;
              while (a < b) { int m = a + (b - a) / 2;
                if (writeStarts[m] < si) a = m + 1; else b = m; }
              lo = a; }
            { int a = lo, b = n;
              while (a < b) { int m = a + (b - a) / 2;
                if (writeStarts[m] < readEnd) a = m + 1; else b = m; }
              hi = a; }
            if (lo > 0) {
                int j = writeSorted[lo - 1];
                if (j != i) {
                    int dj = copies.get(j)[2], lj = copies.get(j)[3];
                    if (dj + lj > si) { adj.get(i).add(j); }
                }
            }
            for (int k = lo; k < hi; k++) {
                int j = writeSorted[k];
                if (j != i) { adj.get(i).add(j); }
            }
        }

        // Step 3: Kahn topological sort with Tarjan-scoped cycle breaking.
        //
        // Global Kahn preserves the cascade effect (converting a victim
        // decrements in_deg globally, potentially freeing vertices across SCC
        // boundaries).  findCycleInScc restricts DFS to one SCC via three
        // amortizations: sccId filter (no O(|SCC|) set/clear), color=2
        // persistence, and scanStart resumption.  Total: O(n+E) cycle-breaking.
        // R.E. Tarjan, SIAM J. Comput., 1(2):146-160, June 1972.
        List<List<Integer>> sccs = tarjanScc(adj, n);

        int[] inDeg = new int[n];
        for (int i = 0; i < n; i++) {
            for (int j : adj.get(i)) { inDeg[j]++; }
        }

        int[] sccId = new int[n];
        Arrays.fill(sccId, -1); // -1 = trivial (no cycle)
        List<List<Integer>> sccList = new ArrayList<>(); // non-trivial SCCs only
        int[] sccActive = new int[sccs.size()]; // live member count per SCC

        for (List<Integer> scc : sccs) {
            if (scc.size() > 1) {
                int sid = sccList.size();
                for (int v : scc) { sccId[v] = sid; }
                sccActive[sid] = scc.size();
                sccList.add(scc);
            }
        }

        boolean[] removed = new boolean[n];
        List<Integer> topoOrder = new ArrayList<>();
        int[] color = new int[n]; // 0=unvisited, 1=on-path, 2=done
        int sccPtr = 0;
        int[] scanPos = {0}; // mutable scan position within sccList.get(sccPtr)

        PriorityQueue<int[]> heap = new PriorityQueue<>(
            Comparator.<int[]>comparingInt(e -> e[0]).thenComparingInt(e -> e[1]));
        for (int i = 0; i < n; i++) {
            if (inDeg[i] == 0) heap.add(new int[]{copies.get(i)[3], i});
        }
        int processed = 0;

        while (processed < n) {
            // Drain all ready vertices.
            while (!heap.isEmpty()) {
                int[] entry = heap.poll();
                int v = entry[1];
                if (removed[v]) continue;
                removed[v] = true;
                topoOrder.add(v);
                processed++;
                if (sccId[v] != -1) { sccActive[sccId[v]]--; }
                for (int w : adj.get(v)) {
                    if (!removed[w]) {
                        inDeg[w]--;
                        if (inDeg[w] == 0)
                            heap.add(new int[]{copies.get(w)[3], w});
                    }
                }
            }

            if (processed >= n) break;

            // Kahn stalled: all remaining vertices are in CRWI cycles.
            // Choose a victim to convert from copy to add.
            int victim = -1;
            if (policy == CyclePolicy.CONSTANT) {
                for (int i = 0; i < n; i++) {
                    if (!removed[i]) { victim = i; break; }
                }
            } else { // LOCALMIN
                while (victim == -1) {
                    while (sccPtr < sccList.size() && sccActive[sccPtr] == 0) {
                        sccPtr++; scanPos[0] = 0;
                    }
                    if (sccPtr >= sccList.size()) {
                        // Safety fallback — should not happen with a correct graph.
                        for (int i = 0; i < n; i++) {
                            if (!removed[i]) { victim = i; break; }
                        }
                        break;
                    }
                    List<Integer> cycle = findCycleInScc(
                        adj, sccList.get(sccPtr), sccPtr,
                        sccId, removed, color, scanPos);
                    if (cycle != null) {
                        victim = cycle.get(0);
                        for (int v : cycle) {
                            if (copies.get(v)[3] < copies.get(victim)[3] ||
                                (copies.get(v)[3] == copies.get(victim)[3] && v < victim)) {
                                victim = v;
                            }
                        }
                    } else {
                        // SCC's remaining subgraph is acyclic; advance.
                        sccPtr++; scanPos[0] = 0;
                    }
                }
            }

            // Convert victim: materialize copy data as literal add.
            int[] ci = copies.get(victim);
            byte[] data = new byte[ci[3]];
            System.arraycopy(r, ci[1], data, 0, ci[3]);
            adds.add(new PlacedAdd(ci[2], data));
            removed[victim] = true;
            processed++;
            if (sccId[victim] != -1) { sccActive[sccId[victim]]--; }

            for (int w : adj.get(victim)) {
                if (!removed[w]) {
                    inDeg[w]--;
                    if (inDeg[w] == 0)
                        heap.add(new int[]{copies.get(w)[3], w});
                }
            }
        } // while processed < n

        // Step 4: assemble result — copies in topo order, then all adds
        List<PlacedCommand> result = new ArrayList<>();
        for (int i : topoOrder) {
            int[] ci = copies.get(i);
            result.add(new PlacedCopy(ci[1], ci[2], ci[3]));
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
        Arrays.fill(index, -1); // -1 = unvisited
        int[] lowlink = new int[n];
        boolean[] onStack = new boolean[n];
        Deque<Integer> tarjanStack = new ArrayDeque<>();
        List<List<Integer>> sccs = new ArrayList<>();
        int[] counter = {0};
        // DFS call stack: int[]{vertex, next_neighbor_index}
        Deque<int[]> callStack = new ArrayDeque<>();

        for (int start = 0; start < n; start++) {
            if (index[start] != -1) continue;

            index[start] = lowlink[start] = counter[0]++;
            onStack[start] = true;
            tarjanStack.push(start);
            callStack.push(new int[]{start, 0});

            while (!callStack.isEmpty()) {
                int[] frame = callStack.peek();
                int v = frame[0];
                int ni = frame[1];
                List<Integer> neighbors = adj.get(v);

                if (ni < neighbors.size()) {
                    int w = neighbors.get(ni);
                    frame[1]++;
                    if (index[w] == -1) {
                        // Tree edge: descend into w
                        index[w] = lowlink[w] = counter[0]++;
                        onStack[w] = true;
                        tarjanStack.push(w);
                        callStack.push(new int[]{w, 0});
                    } else if (onStack[w]) {
                        // Back-edge into current SCC
                        if (index[w] < lowlink[v]) lowlink[v] = index[w];
                    }
                } else {
                    callStack.pop();
                    if (!callStack.isEmpty()) {
                        int parent = callStack.peek()[0];
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
     *   3. scanStart[0]: outer loop resumes from last position, O(|SCC|) total.
     *
     * On cycle found: resets path (color=1) vertices to 0; color=2 intact.
     * On null (acyclic): color=2 persists (sccId filter isolates SCCs).
     */
    private static List<Integer> findCycleInScc(
            List<List<Integer>> adj, List<Integer> scc, int sid,
            int[] sccId, boolean[] removed, int[] color, int[] scanStart) {
        List<Integer> path = new ArrayList<>();
        int scan = scanStart[0];
        int sccLen = scc.size();

        while (scan < sccLen) {
            int start = scc.get(scan);
            if (removed[start] || color[start] != 0) { scan++; continue; }

            color[start] = 1;
            path.add(start);
            // Stack entries: int[]{vertex, next_neighbor_index}
            Deque<int[]> stack = new ArrayDeque<>();
            stack.push(new int[]{start, 0});

            outer:
            while (!stack.isEmpty()) {
                int[] frame = stack.peek();
                int v = frame[0];
                int ni = frame[1];
                List<Integer> neighbors = adj.get(v);
                boolean advanced = false;

                while (ni < neighbors.size()) {
                    int w = neighbors.get(ni++);
                    if (sccId[w] != sid || removed[w]) { continue; }
                    if (color[w] == 1) {
                        // Back-edge: cycle found.
                        int pos = path.indexOf(w);
                        List<Integer> cycle = new ArrayList<>(path.subList(pos, path.size()));
                        for (int u : path) { color[u] = 0; }
                        scanStart[0] = scan;
                        return cycle;
                    }
                    if (color[w] == 0) {
                        frame[1] = ni;
                        color[w] = 1;
                        path.add(w);
                        stack.push(new int[]{w, 0});
                        advanced = true;
                        continue outer;
                    }
                }
                if (!advanced) {
                    stack.pop();
                    color[v] = 2; // Fully explored — persists across calls.
                    path.remove(path.size() - 1);
                }
            }
            // start's reachable SCC-subgraph fully explored; no cycle.
            scan++;
        }

        scanStart[0] = scan;
        return null;
    }

    /** Compute summary statistics for placed commands. */
    public static PlacedSummary placedSummary(List<PlacedCommand> commands) {
        int numCopies = 0, numAdds = 0;
        long copyBytes = 0, addBytes = 0;
        for (PlacedCommand cmd : commands) {
            if (cmd instanceof PlacedCopy) {
                numCopies++;
                copyBytes += ((PlacedCopy) cmd).length;
            } else if (cmd instanceof PlacedAdd) {
                numAdds++;
                addBytes += ((PlacedAdd) cmd).data.length;
            }
        }
        return new PlacedSummary(commands.size(), numCopies, numAdds,
            copyBytes, addBytes, copyBytes + addBytes);
    }
}
