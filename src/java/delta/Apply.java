package delta;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static delta.Types.*;

/**
 * Command placement, application, and in-place reordering.
 *
 * place_commands: assign sequential destinations (Section 2.1.1)
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
        if (commands.isEmpty()) return List.of();

        // Step 1: compute write offsets
        record CopyInfo(int idx, int src, int dst, int length) {}
        List<CopyInfo> copies = new ArrayList<>();
        List<PlacedAdd> adds = new ArrayList<>();
        int writePos = 0;

        for (Command cmd : commands) {
            if (cmd instanceof CopyCmd c) {
                copies.add(new CopyInfo(copies.size(), c.offset(), writePos, c.length()));
                writePos += c.length();
            } else if (cmd instanceof AddCmd a) {
                adds.add(new PlacedAdd(writePos, a.data()));
                writePos += a.data().length;
            }
        }

        int n = copies.size();
        if (n == 0) return new ArrayList<>(adds);

        // Step 2: build CRWI digraph
        List<List<Integer>> adj = new ArrayList<>();
        int[] inDeg = new int[n];
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());

        for (int i = 0; i < n; i++) {
            CopyInfo ci = copies.get(i);
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                CopyInfo cj = copies.get(j);
                // i's read [src_i, src_i+len_i) overlaps j's write [dst_j, dst_j+len_j)
                if (ci.src < cj.dst + cj.length && cj.dst < ci.src + ci.length) {
                    adj.get(i).add(j);
                    inDeg[j]++;
                }
            }
        }

        // Step 3: topological sort with cycle breaking (Kahn's algorithm)
        boolean[] removed = new boolean[n];
        List<Integer> topoOrder = new ArrayList<>();

        Deque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (inDeg[i] == 0) queue.add(i);
        }

        int processed = 0;
        while (processed < n) {
            while (!queue.isEmpty()) {
                int v = queue.poll();
                if (removed[v]) continue;
                removed[v] = true;
                topoOrder.add(v);
                processed++;
                for (int w : adj.get(v)) {
                    if (!removed[w]) {
                        inDeg[w]--;
                        if (inDeg[w] == 0) queue.add(w);
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
                    int minLen = copies.get(victim).length;
                    for (int idx : cycle) {
                        if (copies.get(idx).length < minLen) {
                            minLen = copies.get(idx).length;
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
            CopyInfo ci = copies.get(victim);
            byte[] data = new byte[ci.length];
            System.arraycopy(r, ci.src, data, 0, ci.length);
            adds.add(new PlacedAdd(ci.dst, data));
            removed[victim] = true;
            processed++;

            for (int w : adj.get(victim)) {
                if (!removed[w]) {
                    inDeg[w]--;
                    if (inDeg[w] == 0) queue.add(w);
                }
            }
        }

        // Step 4: assemble result — copies in topo order, then all adds
        List<PlacedCommand> result = new ArrayList<>();
        for (int i : topoOrder) {
            CopyInfo ci = copies.get(i);
            result.add(new PlacedCopy(ci.src, ci.dst, ci.length));
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
