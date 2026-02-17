package delta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     * Algorithm:
     *   1. Annotate each command with its write offset
     *   2. Build CRWI digraph on copy commands (Section 4.2)
     *   3. Topological sort; break cycles by converting copies to adds
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
        int[] inDeg = new int[n];
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
            // Binary search: find first writeStarts[k] >= readEnd
            int lo = 0, hi = n;
            while (lo < hi) {
                int mid = lo + (hi - lo) / 2;
                if (writeStarts[mid] < readEnd) lo = mid + 1;
                else hi = mid;
            }
            for (int k = 0; k < lo; k++) {
                int j = writeSorted[k];
                if (i == j) continue;
                int dj = copies.get(j)[2], lj = copies.get(j)[3];
                if (dj + lj > si) {
                    adj.get(i).add(j);
                    inDeg[j]++;
                }
            }
        }

        // Step 3: topological sort with cycle breaking (Kahn's algorithm)
        // Priority queue keyed on copy length — always process the smallest
        // ready copy first, giving a deterministic topological ordering.
        boolean[] removed = new boolean[n];
        List<Integer> topoOrder = new ArrayList<>();

        // Min-heap: compare by (copy_length, index) for deterministic ordering.
        PriorityQueue<int[]> heap = new PriorityQueue<>(
            Comparator.<int[]>comparingInt(e -> e[0]).thenComparingInt(e -> e[1]));
        for (int i = 0; i < n; i++) {
            if (inDeg[i] == 0) heap.add(new int[]{copies.get(i)[3], i});
        }

        int processed = 0;
        while (processed < n) {
            while (!heap.isEmpty()) {
                int[] entry = heap.poll();
                int v = entry[1];
                if (removed[v]) continue;
                removed[v] = true;
                topoOrder.add(v);
                processed++;
                for (int w : adj.get(v)) {
                    if (!removed[w]) {
                        inDeg[w]--;
                        if (inDeg[w] == 0) heap.add(new int[]{copies.get(w)[3], w});
                    }
                }
            }

            if (processed >= n) break;

            // Cycle detected — choose a victim
            int victim;
            if (policy == CyclePolicy.CONSTANT) {
                victim = -1;
                for (int i = 0; i < n; i++) {
                    if (!removed[i]) { victim = i; break; }
                }
            } else {
                // LOCALMIN: find cycle, pick smallest copy
                List<Integer> cycle = findCycle(adj, removed, n);
                if (cycle != null) {
                    victim = cycle.get(0);
                    int minLen = copies.get(victim)[3];
                    for (int idx : cycle) {
                        if (copies.get(idx)[3] < minLen) {
                            minLen = copies.get(idx)[3];
                            victim = idx;
                        }
                    }
                } else {
                    victim = -1;
                    for (int i = 0; i < n; i++) {
                        if (!removed[i]) { victim = i; break; }
                    }
                }
            }

            // Convert victim: materialize copy data as literal add
            int[] ci = copies.get(victim);
            byte[] data = new byte[ci[3]];
            System.arraycopy(r, ci[1], data, 0, ci[3]);
            adds.add(new PlacedAdd(ci[2], data));
            removed[victim] = true;
            processed++;

            for (int w : adj.get(victim)) {
                if (!removed[w]) {
                    inDeg[w]--;
                    if (inDeg[w] == 0) heap.add(new int[]{copies.get(w)[3], w});
                }
            }
        }

        // Step 4: assemble result — copies in topo order, then all adds
        List<PlacedCommand> result = new ArrayList<>();
        for (int i : topoOrder) {
            int[] ci = copies.get(i);
            result.add(new PlacedCopy(ci[1], ci[2], ci[3]));
        }
        result.addAll(adds);
        return result;
    }

    /** Find a cycle in the subgraph of non-removed vertices. */
    private static List<Integer> findCycle(List<List<Integer>> adj,
                                           boolean[] removed, int n) {
        for (int start = 0; start < n; start++) {
            if (removed[start]) continue;
            Map<Integer, Integer> visited = new HashMap<>();
            List<Integer> path = new ArrayList<>();
            Integer curr = start;
            int step = 0;

            while (curr != null) {
                if (visited.containsKey(curr)) {
                    int cycleIdx = visited.get(curr);
                    return path.subList(cycleIdx, path.size());
                }
                visited.put(curr, step);
                path.add(curr);
                step++;

                Integer next = null;
                for (int w : adj.get(curr)) {
                    if (!removed[w]) { next = w; break; }
                }
                curr = next;
            }
        }
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
