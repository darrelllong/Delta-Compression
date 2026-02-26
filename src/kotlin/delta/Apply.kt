package delta

import java.util.PriorityQueue

/**
 * Command placement, application, and in-place reordering.
 *
 * placeCommands: assign sequential destinations (Section 2.1.1)
 * makeInplace:   CRWI digraph + topological sort (Burns et al. 2003)
 */

/** Compute total output size of algorithm commands. */
fun outputSize(commands: List<Command>): Int {
    var size = 0
    for (cmd in commands) {
        size += when (cmd) {
            is Command.Copy -> cmd.length
            is Command.Add  -> cmd.data.size
        }
    }
    return size
}

/** Convert algorithm commands to placed commands with sequential destinations. */
fun placeCommands(commands: List<Command>): List<PlacedCommand> {
    val placed = ArrayList<PlacedCommand>(commands.size)
    var dst = 0
    for (cmd in commands) {
        when (cmd) {
            is Command.Copy -> { placed.add(PlacedCommand.Copy(cmd.offset, dst, cmd.length)); dst += cmd.length }
            is Command.Add  -> { placed.add(PlacedCommand.Add(dst, cmd.data));                dst += cmd.data.size }
        }
    }
    return placed
}

/** Apply placed commands in standard mode: read from R, write to out. */
fun applyPlacedTo(r: ByteArray, commands: List<PlacedCommand>, out: ByteArray): Int {
    var maxWritten = 0
    for (cmd in commands) {
        when (cmd) {
            is PlacedCommand.Copy -> {
                r.copyInto(out, cmd.dst, cmd.src, cmd.src + cmd.length)
                val end = cmd.dst + cmd.length
                if (end > maxWritten) maxWritten = end
            }
            is PlacedCommand.Add -> {
                cmd.data.copyInto(out, cmd.dst)
                val end = cmd.dst + cmd.data.size
                if (end > maxWritten) maxWritten = end
            }
        }
    }
    return maxWritten
}

/** Apply placed commands in-place within a single buffer. */
fun applyPlacedInplaceTo(commands: List<PlacedCommand>, buf: ByteArray) {
    for (cmd in commands) {
        when (cmd) {
            is PlacedCommand.Copy -> buf.copyInto(buf, cmd.dst, cmd.src, cmd.src + cmd.length)
            is PlacedCommand.Add  -> cmd.data.copyInto(buf, cmd.dst)
        }
    }
}

/** Reconstruct version from reference + algorithm commands. */
fun applyDelta(r: ByteArray, commands: List<Command>): ByteArray {
    val out = ByteArray(outputSize(commands))
    var pos = 0
    for (cmd in commands) {
        when (cmd) {
            is Command.Copy -> { r.copyInto(out, pos, cmd.offset, cmd.offset + cmd.length); pos += cmd.length }
            is Command.Add  -> { cmd.data.copyInto(out, pos);                                pos += cmd.data.size }
        }
    }
    return out
}

/** Apply placed in-place commands to a buffer initialized with R. */
fun applyDeltaInplace(r: ByteArray, commands: List<PlacedCommand>, versionSize: Int): ByteArray {
    val bufSize = maxOf(r.size, versionSize)
    val buf = ByteArray(bufSize)
    r.copyInto(buf)
    applyPlacedInplaceTo(commands, buf)
    return if (buf.size != versionSize) buf.copyOf(versionSize) else buf
}

/**
 * Convert placed commands back to algorithm commands (strip destinations).
 * Commands are sorted by destination offset to recover original sequential order.
 */
fun unplaceCommands(placed: List<PlacedCommand>): List<Command> {
    val sorted = placed.sortedBy { cmd ->
        when (cmd) {
            is PlacedCommand.Copy -> cmd.dst
            is PlacedCommand.Add  -> cmd.dst
        }
    }
    return sorted.map { cmd ->
        when (cmd) {
            is PlacedCommand.Copy -> Command.Copy(cmd.src, cmd.length)
            is PlacedCommand.Add  -> Command.Add(cmd.data)
        }
    }
}

// ── In-place reordering (Burns, Long, Stockmeyer, IEEE TKDE 2003) ──

/** Source offset, destination offset, and length of one copy command. */
private data class CopyInfo(val src: Int, val dst: Int, val length: Int)

/** Non-trivial SCCs with per-SCC active counts and vertex-to-SCC mapping. */
private data class SccData(val sccs: List<IntArray>, val active: IntArray, val id: IntArray)

/** Mutable cursor tracking which SCC and scan position pickVictim is examining. */
private class ScanCursor(var sccPtr: Int = 0, var scanPos: Int = 0)

/** Result of findCycleInScc: the cycle (or null) plus the updated scan position. */
private data class CycleResult(val cycle: List<Int>?, val newScan: Int)

// DFS color states for findCycleInScc and tarjanScc
private const val COLOR_UNVISITED = 0
private const val COLOR_ON_PATH   = 1
private const val COLOR_DONE      = 2

/** Sentinel: vertex is in no non-trivial SCC. */
private const val NO_SCC = -1

/** One frame on the iterative DFS call stack: vertex and next-neighbor index. */
private class DfsFrame(val v: Int, var ni: Int = 0)

/**
 * Build CRWI digraph on copy commands.
 *
 * Edge i→j means copy i reads from a region that copy j will overwrite,
 * so i must execute before j.  O(n log n + E) sweep-line construction.
 */
private fun buildCrwiDigraph(copies: List<CopyInfo>, n: Int): Array<MutableList<Int>> {
    val adj = Array(n) { mutableListOf<Int>() }

    // Sort copy write-intervals by start; binary-search for each read interval.
    val writeSorted = Array(n) { it }
    writeSorted.sortWith(compareBy { copies[it].dst })
    val writeStarts = IntArray(n) { k -> copies[writeSorted[k]].dst }

    for (i in 0 until n) {
        val src = copies[i].src; val len = copies[i].length
        val readEnd = src + len
        // lo = first write with dst >= src; hi = first write with dst >= readEnd.
        // Writes in [lo, hi) start inside [src, readEnd) — they always overlap.
        // The write at lo-1 starts before src; overlaps iff its end exceeds src.
        val lo = run {
            var a = 0; var b = n
            while (a < b) { val m = a + (b - a) / 2; if (writeStarts[m] < src) a = m + 1 else b = m }
            a
        }
        val hi = run {
            var a = lo; var b = n
            while (a < b) { val m = a + (b - a) / 2; if (writeStarts[m] < readEnd) a = m + 1 else b = m }
            a
        }
        if (lo > 0) {
            val j = writeSorted[lo - 1]
            if (j != i) {
                val dj = copies[j].dst; val lj = copies[j].length
                if (dj + lj > src) adj[i].add(j)
            }
        }
        for (k in lo until hi) {
            val j = writeSorted[k]
            if (j != i) adj[i].add(j)
        }
    }
    return adj
}

/** Wrap tarjanScc output into an SccData containing only non-trivial SCCs. */
private fun buildSccList(adj: Array<MutableList<Int>>, n: Int): SccData {
    val allSccs   = tarjanScc(adj, n)
    val id        = IntArray(n) { NO_SCC }
    val sccArrays = mutableListOf<IntArray>()

    for (scc in allSccs) {
        if (scc.size > 1) {
            val sid = sccArrays.size
            for (v in scc) id[v] = sid
            sccArrays.add(scc.toIntArray())
        }
    }
    val sccs   = sccArrays.toList()
    val active = IntArray(sccs.size) { k -> sccs[k].size }
    return SccData(sccs, active, id)
}

/**
 * Select a victim copy to break a cycle when Kahn's algorithm stalls.
 *
 * Constant: first remaining vertex.  Localmin: minimum-length copy in a cycle.
 * cur.sccPtr and cur.scanPos are advanced in place across repeated calls.
 */
private fun pickVictim(
    copies: List<CopyInfo>, adj: Array<MutableList<Int>>,
    scc: SccData, removed: BooleanArray, color: IntArray,
    cur: ScanCursor, policy: CyclePolicy, n: Int
): Int {
    if (policy == CyclePolicy.CONSTANT) {
        for (i in 0 until n) { if (!removed[i]) return i }
        return -1 // unreachable: called only when processed < n
    }
    var victim = -1
    while (victim == -1) {
        while (cur.sccPtr < scc.sccs.size && scc.active[cur.sccPtr] == 0) {
            cur.sccPtr++; cur.scanPos = 0
        }
        if (cur.sccPtr >= scc.sccs.size) {
            for (i in 0 until n) { if (!removed[i]) { victim = i; break } }
        } else {
            val cr = findCycleInScc(adj, scc.sccs[cur.sccPtr], cur.sccPtr, scc.id, removed, color, cur.scanPos)
            cur.scanPos = cr.newScan
            if (cr.cycle != null) {
                victim = cr.cycle[0]
                for (v in cr.cycle) {
                    if (copies[v].length < copies[victim].length ||
                        (copies[v].length == copies[victim].length && v < victim)) victim = v
                }
            } else {
                cur.sccPtr++; cur.scanPos = 0
            }
        }
    }
    return victim
}

/**
 * Run Kahn topological sort; when the heap stalls, call pickVictim to break
 * the cycle by materialising one copy as a literal add.
 */
private fun runKahn(
    copies: List<CopyInfo>, adj: Array<MutableList<Int>>,
    scc: SccData, r: ByteArray, adds: MutableList<PlacedCommand.Add>,
    policy: CyclePolicy, n: Int
): List<Int> {
    val inDeg = IntArray(n)
    for (i in 0 until n) for (j in adj[i]) inDeg[j]++

    val removed = BooleanArray(n)
    val topo    = mutableListOf<Int>()
    val color   = IntArray(n)          // COLOR_UNVISITED initially
    val cursor  = ScanCursor()

    val heap = PriorityQueue<IntArray>(compareBy({ it[0] }, { it[1] }))
    for (i in 0 until n) {
        if (inDeg[i] == 0) heap.add(intArrayOf(copies[i].length, i))
    }
    var processed = 0

    while (processed < n) {
        while (heap.isNotEmpty()) {
            val entry = heap.poll()
            val v = entry[1]
            if (removed[v]) continue
            removed[v] = true
            topo.add(v)
            processed++
            if (scc.id[v] != NO_SCC) scc.active[scc.id[v]]--
            for (w in adj[v]) {
                if (!removed[w]) {
                    inDeg[w]--
                    if (inDeg[w] == 0) heap.add(intArrayOf(copies[w].length, w))
                }
            }
        }

        if (processed >= n) break

        val victim = pickVictim(copies, adj, scc, removed, color, cursor, policy, n)
        val ci = copies[victim]
        adds.add(PlacedCommand.Add(ci.dst, r.copyOfRange(ci.src, ci.src + ci.length)))
        removed[victim] = true
        processed++
        if (scc.id[victim] != NO_SCC) scc.active[scc.id[victim]]--
        for (w in adj[victim]) {
            if (!removed[w]) {
                inDeg[w]--
                if (inDeg[w] == 0) heap.add(intArrayOf(copies[w].length, w))
            }
        }
    }
    return topo
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
fun makeInplace(r: ByteArray, commands: List<Command>, policy: CyclePolicy): List<PlacedCommand> {
    if (commands.isEmpty()) return emptyList()

    // Step 1: compute write offsets
    val copyBuf = mutableListOf<CopyInfo>()
    val adds    = mutableListOf<PlacedCommand.Add>()
    var writePos = 0
    for (cmd in commands) {
        when (cmd) {
            is Command.Copy -> { copyBuf.add(CopyInfo(cmd.offset, writePos, cmd.length)); writePos += cmd.length }
            is Command.Add  -> { adds.add(PlacedCommand.Add(writePos, cmd.data));          writePos += cmd.data.size }
        }
    }
    val copies = copyBuf.toList()
    val n      = copies.size
    if (n == 0) return ArrayList(adds)

    // Steps 2-3: build digraph, topological sort, break cycles
    val adj       = buildCrwiDigraph(copies, n)
    val scc       = buildSccList(adj, n)
    val topoOrder = runKahn(copies, adj, scc, r, adds, policy, n)

    // Step 4: assemble result — copies in topo order, then all adds
    val result = ArrayList<PlacedCommand>(topoOrder.size + adds.size)
    for (i in topoOrder) {
        val ci = copies[i]
        result.add(PlacedCommand.Copy(ci.src, ci.dst, ci.length))
    }
    result.addAll(adds)
    return result
}

/**
 * Compute SCCs using iterative Tarjan's algorithm.
 *
 * Returns SCCs in reverse topological order (sinks first).
 * R.E. Tarjan, SIAM Journal on Computing, 1(2):146-160, June 1972.
 */
private fun tarjanScc(adj: Array<MutableList<Int>>, n: Int): List<List<Int>> {
    val index    = IntArray(n) { NO_SCC }  // NO_SCC = unvisited
    val lowlink  = IntArray(n)
    val onStack  = BooleanArray(n)
    val tarjanStack = ArrayDeque<Int>()
    val sccs     = mutableListOf<List<Int>>()
    var counter  = 0
    val callStack = ArrayDeque<DfsFrame>()

    for (start in 0 until n) {
        if (index[start] != NO_SCC) continue

        index[start] = counter; lowlink[start] = counter++
        onStack[start] = true
        tarjanStack.addLast(start)
        callStack.addLast(DfsFrame(start))

        while (callStack.isNotEmpty()) {
            val frame     = callStack.last()
            val v         = frame.v
            val neighbors = adj[v]

            if (frame.ni < neighbors.size) {
                val w = neighbors[frame.ni++]
                if (index[w] == NO_SCC) {
                    index[w] = counter; lowlink[w] = counter++
                    onStack[w] = true
                    tarjanStack.addLast(w)
                    callStack.addLast(DfsFrame(w))
                } else if (onStack[w]) {
                    if (index[w] < lowlink[v]) lowlink[v] = index[w]
                }
            } else {
                callStack.removeLast()
                if (callStack.isNotEmpty()) {
                    val parent = callStack.last().v
                    if (lowlink[v] < lowlink[parent]) lowlink[parent] = lowlink[v]
                }
                if (lowlink[v] == index[v]) {
                    val scc = mutableListOf<Int>()
                    var w: Int
                    do { w = tarjanStack.removeLast(); onStack[w] = false; scc.add(w) } while (w != v)
                    sccs.add(scc)
                }
            }
        }
    }
    return sccs
}

/**
 * Find a cycle in the active subgraph of one SCC.
 * Three amortizations give O(|SCC| + E_SCC) total work per SCC.
 *
 * Returns a CycleResult whose cycle is non-null on cycle found (path color=1
 * vertices reset to 0), or null when the SCC subgraph is acyclic.  newScan
 * is the updated scan position for the next call.
 */
private fun findCycleInScc(
    adj: Array<MutableList<Int>>, scc: IntArray, sid: Int,
    sccId: IntArray, removed: BooleanArray, color: IntArray, scanStart: Int
): CycleResult {
    val path = mutableListOf<Int>()
    var scan = scanStart
    val sccLen = scc.size

    while (scan < sccLen) {
        val start = scc[scan]
        if (removed[start] || color[start] != COLOR_UNVISITED) { scan++; continue }

        color[start] = COLOR_ON_PATH
        path.add(start)
        val stack = ArrayDeque<DfsFrame>()
        stack.addLast(DfsFrame(start))

        outer@ while (stack.isNotEmpty()) {
            val frame     = stack.last()
            val v         = frame.v
            val neighbors = adj[v]

            while (frame.ni < neighbors.size) {
                val w = neighbors[frame.ni++]
                if (sccId[w] != sid || removed[w]) continue
                if (color[w] == COLOR_ON_PATH) {
                    // Back-edge: cycle found.
                    val pos   = path.indexOf(w)
                    val cycle = path.subList(pos, path.size).toList()
                    for (u in path) color[u] = COLOR_UNVISITED
                    return CycleResult(cycle, scan)
                }
                if (color[w] == COLOR_UNVISITED) {
                    color[w] = COLOR_ON_PATH
                    path.add(w)
                    stack.addLast(DfsFrame(w))
                    continue@outer
                }
            }
            stack.removeLast()
            color[v] = COLOR_DONE  // Fully explored — persists across calls.
            path.removeAt(path.size - 1)
        }
        scan++
    }

    return CycleResult(null, scan)
}

/** Compute summary statistics for placed commands. */
fun placedSummary(commands: List<PlacedCommand>): PlacedSummary {
    var numCopies = 0; var numAdds = 0
    var copyBytes = 0L; var addBytes = 0L
    for (cmd in commands) {
        when (cmd) {
            is PlacedCommand.Copy -> { numCopies++; copyBytes += cmd.length }
            is PlacedCommand.Add  -> { numAdds++;   addBytes  += cmd.data.size }
        }
    }
    return PlacedSummary(commands.size, numCopies, numAdds, copyBytes, addBytes, copyBytes + addBytes)
}
