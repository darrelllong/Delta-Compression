package delta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import static delta.Types.*;

/**
 * In-place conversion (Burns, Long and Stockmeyer, IEEE TKDE 2003).
 *
 * An in-place delta rebuilds V in the buffer that holds R, so a copy must
 * run before any command that overwrites the bytes it reads.  Copies are the
 * vertices of a digraph with an edge from i to j when i reads what j writes
 * (a CRWI digraph), and they are emitted in a topological order of it.  The
 * digraph can have cycles; each is broken by turning one of its copies into
 * an add, whose bytes are taken from R now and so no longer depend on the
 * buffer.  Adds read nothing and run last.
 *
 * Among the copies that are ready, the shortest goes first, and of equally
 * short ones the first in V; this fixes the order, which is otherwise free.
 */
final class Inplace {
    private static final int NO_SCC = -1;

    // Marks for the cycle search.  DONE means that no cycle passes through
    // the vertex; removing vertices cannot create one, so the mark is kept
    // from one search to the next.
    private static final byte UNVISITED = 0, ON_PATH = 1, DONE = 2;

    // Copy i reads [src[i], src[i] + len[i]) and writes [dst[i], dst[i] + len[i]).
    private final int n;
    private final long[] src, dst, len;

    // The successors of i are adj[adjStart[i] .. adjStart[i + 1]).
    private int[] adjStart;
    private int[] adj;

    // The strongly connected components with more than one vertex, which are
    // the only places a cycle can be.  sccOf[v] indexes sccs, or is NO_SCC.
    private int[][] sccs;
    private int[] sccOf;
    private int[] active; // vertices of each component not yet removed

    private final boolean[] removed;

    // Scratch for the two depth-first searches: the path from the root, and
    // for each vertex on it the next edge to follow.
    private final int[] path;
    private final int[] nextEdge;

    // Where the search for the next cycle resumes.  Components before
    // sccCursor, and vertices of sccs[sccCursor] before scanPos, are done with.
    private final byte[] color;
    private int sccCursor, scanPos;
    private int firstLeft; // no vertex below this index remains

    private Inplace(long[] src, long[] dst, long[] len) {
        this.n = src.length;
        this.src = src;
        this.dst = dst;
        this.len = len;
        removed = new boolean[n];
        path = new int[n];
        nextEdge = new int[n];
        color = new byte[n];
    }

    /** Returns commands that build the version of {@code commands} within a buffer holding r. */
    static List<PlacedCommand> convert(byte[] r, List<Command> commands, CyclePolicy policy) {
        int numCopies = 0;
        for (Command cmd : commands) {
            if (cmd instanceof CopyCmd) numCopies++;
        }
        long[] src = new long[numCopies], dst = new long[numCopies], len = new long[numCopies];
        List<PlacedCommand> adds = new ArrayList<>();
        long writePos = 0;
        int i = 0;
        for (Command cmd : commands) {
            if (cmd instanceof CopyCmd c) {
                src[i] = c.offset();
                dst[i] = writePos;
                len[i] = c.length();
                i++;
                writePos += c.length();
            } else if (cmd instanceof AddCmd a) {
                adds.add(new PlacedAdd(writePos, a.data()));
                writePos += a.data().length;
            }
        }
        if (numCopies == 0) return adds;

        Inplace g = new Inplace(src, dst, len);
        g.buildGraph();
        g.findComponents();
        List<PlacedCommand> result = new ArrayList<>(commands.size());
        g.sort(r, policy, result, adds);
        result.addAll(adds);
        return result;
    }

    /**
     * Finds the edges in O(n log n + E).  Writes do not overlap, so in order
     * of destination the writes that meet a read are consecutive.
     */
    private void buildGraph() {
        Integer[] byDst = new Integer[n];
        for (int i = 0; i < n; i++) byDst[i] = i;
        Arrays.sort(byDst, Comparator.comparingLong(i -> dst[i]));
        long[] writeStart = new long[n];
        for (int k = 0; k < n; k++) writeStart[k] = dst[byDst[k]];

        adjStart = new int[n + 1];
        adj = new int[n];
        int edges = 0;
        for (int i = 0; i < n; i++) {
            adjStart[i] = edges;
            // Writes lo to hi - 1 start inside the read.  The one before lo
            // starts before the read and may run into it.
            int lo = lowerBound(writeStart, 0, src[i]);
            int hi = lowerBound(writeStart, lo, src[i] + len[i]);
            if (edges + hi - lo + 1 > adj.length) {
                adj = Arrays.copyOf(adj, Math.max(2 * adj.length, edges + hi - lo + 1));
            }
            if (lo > 0) {
                int j = byDst[lo - 1];
                if (j != i && dst[j] + len[j] > src[i]) adj[edges++] = j;
            }
            for (int k = lo; k < hi; k++) {
                int j = byDst[k];
                if (j != i) adj[edges++] = j;
            }
        }
        adjStart[n] = edges;
    }

    /** Returns the least k in [from, a.length] such that a[k] >= key, for sorted a. */
    private static int lowerBound(long[] a, int from, long key) {
        int lo = from, hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < key) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /**
     * Finds the strongly connected components by Tarjan's algorithm
     * (SIAM J. Comput. 1(2), 1972), without recursion.
     */
    private void findComponents() {
        int[] index = new int[n]; // order of discovery, or -1
        int[] low = new int[n];
        boolean[] onStack = new boolean[n];
        int[] stack = new int[n];
        int sp = 0, depth = 0, counter = 0;
        Arrays.fill(index, -1);
        sccOf = new int[n];
        Arrays.fill(sccOf, NO_SCC);
        List<int[]> found = new ArrayList<>();

        for (int root = 0; root < n; root++) {
            if (index[root] != -1) continue;
            int enter = root;
            for (;;) {
                if (enter >= 0) {
                    index[enter] = low[enter] = counter++;
                    onStack[enter] = true;
                    stack[sp++] = enter;
                    path[depth++] = enter;
                    nextEdge[enter] = adjStart[enter];
                    enter = -1;
                }
                int v = path[depth - 1];
                if (nextEdge[v] < adjStart[v + 1]) {
                    int w = adj[nextEdge[v]++];
                    if (index[w] == -1) {
                        enter = w;
                    } else if (onStack[w]) {
                        low[v] = Math.min(low[v], index[w]);
                    }
                    continue;
                }
                depth--;
                if (depth > 0) {
                    int parent = path[depth - 1];
                    low[parent] = Math.min(low[parent], low[v]);
                }
                if (low[v] == index[v]) {
                    // v is the root of a component: the stack from v up.
                    int base = sp;
                    do {
                        onStack[stack[--base]] = false;
                    } while (stack[base] != v);
                    if (sp - base > 1) {
                        int[] scc = new int[sp - base];
                        for (int i = 0; i < scc.length; i++) {
                            scc[i] = stack[sp - 1 - i];
                            sccOf[scc[i]] = found.size();
                        }
                        found.add(scc);
                    }
                    sp = base;
                }
                if (depth == 0) break;
            }
        }

        sccs = found.toArray(new int[0][]);
        active = new int[sccs.length];
        for (int s = 0; s < sccs.length; s++) active[s] = sccs[s].length;
    }

    /**
     * Kahn's topological sort (CACM 5(11), 1962), with a heap of the ready
     * copies.  When copies remain and none is ready, the rest contain a
     * cycle: one copy is appended to adds instead, which frees its
     * successors.  Appends the others to out in order.
     */
    private void sort(byte[] r, CyclePolicy policy,
                      List<PlacedCommand> out, List<PlacedCommand> adds) {
        int[] inDegree = new int[n];
        for (int e = 0; e < adjStart[n]; e++) inDegree[adj[e]]++;

        PriorityQueue<Integer> ready = new PriorityQueue<>((a, b) -> {
            int c = Long.compare(len[a], len[b]);
            return c != 0 ? c : Integer.compare(a, b);
        });
        for (int i = 0; i < n; i++) {
            if (inDegree[i] == 0) ready.add(i);
        }

        for (int left = n; left > 0; left--) {
            int v;
            if (!ready.isEmpty()) {
                v = ready.poll();
                out.add(new PlacedCopy(src[v], dst[v], len[v]));
            } else {
                v = policy == CyclePolicy.CONSTANT ? firstRemaining() : shortestOnCycle();
                byte[] data = new byte[(int) len[v]];
                System.arraycopy(r, (int) src[v], data, 0, data.length);
                adds.add(new PlacedAdd(dst[v], data));
            }
            removed[v] = true;
            if (sccOf[v] != NO_SCC) active[sccOf[v]]--;
            for (int e = adjStart[v]; e < adjStart[v + 1]; e++) {
                int w = adj[e];
                if (!removed[w] && --inDegree[w] == 0) ready.add(w);
            }
        }
    }

    /** Returns the lowest-numbered copy that remains. */
    private int firstRemaining() {
        while (removed[firstLeft]) firstLeft++;
        return firstLeft;
    }

    /**
     * Finds a cycle among the remaining copies and returns its shortest
     * copy, the lowest-numbered of the shortest.
     */
    private int shortestOnCycle() {
        for (;;) {
            while (sccCursor < sccs.length && active[sccCursor] == 0) {
                sccCursor++;
                scanPos = 0;
            }
            if (sccCursor == sccs.length) {
                // A stall implies a cycle, and a cycle lies in a component
                // not yet exhausted, so this is not reached.  Any copy that
                // remains would still be a correct choice.
                return firstRemaining();
            }
            int depth = findCycle(sccs[sccCursor], sccCursor);
            if (depth > 0) {
                int victim = path[0];
                for (int i = 1; i < depth; i++) {
                    int v = path[i];
                    if (len[v] < len[victim] || (len[v] == len[victim] && v < victim)) {
                        victim = v;
                    }
                }
                return victim;
            }
            sccCursor++;
            scanPos = 0;
        }
    }

    /**
     * Searches depth-first for a cycle among the remaining vertices of one
     * component, starting from its vertices at scanPos and after.  Leaves
     * the cycle in path[0..k) and returns k, or returns 0 if there is none.
     *
     * Over all calls for a component the work is linear in its size: a
     * vertex marked DONE is never entered again, and a start vertex that
     * yields no cycle is never tried again.
     */
    private int findCycle(int[] scc, int id) {
        for (; scanPos < scc.length; scanPos++) {
            int start = scc[scanPos];
            if (removed[start] || color[start] != UNVISITED) continue;

            int depth = 0;
            int enter = start;
            while (enter >= 0 || depth > 0) {
                if (enter >= 0) {
                    color[enter] = ON_PATH;
                    path[depth++] = enter;
                    nextEdge[enter] = adjStart[enter];
                    enter = -1;
                }
                int v = path[depth - 1];
                while (enter < 0 && nextEdge[v] < adjStart[v + 1]) {
                    int w = adj[nextEdge[v]++];
                    if (sccOf[w] != id || removed[w]) continue;
                    if (color[w] == ON_PATH) {
                        // The cycle is the path from w on.  The vertices on
                        // the path are unmarked: one of them is about to be
                        // removed, and the rest must be searched again.
                        for (int i = 0; i < depth; i++) color[path[i]] = UNVISITED;
                        int first = 0;
                        while (path[first] != w) first++;
                        System.arraycopy(path, first, path, 0, depth - first);
                        return depth - first;
                    }
                    if (color[w] == UNVISITED) enter = w;
                }
                if (enter < 0) {
                    color[v] = DONE;
                    depth--;
                }
            }
        }
        return 0;
    }
}
