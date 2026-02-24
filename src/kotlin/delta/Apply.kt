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

/**
 * Convert standard delta commands to in-place executable commands.
 *
 * A CRWI (Copy-Read/Write-Intersection) edge i→j means copy i reads
 * from a region that copy j will overwrite, so i must execute before j.
 * When the digraph is acyclic, a topological order gives a valid serial
 * schedule.  A cycle creates a circular dependency; breaking it materializes
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
    // copies: IntArray of [idx, src, dst, length]
    val copies = mutableListOf<IntArray>()
    val adds   = mutableListOf<PlacedCommand.Add>()
    var writePos = 0

    for (cmd in commands) {
        when (cmd) {
            is Command.Copy -> {
                copies.add(intArrayOf(copies.size, cmd.offset, writePos, cmd.length))
                writePos += cmd.length
            }
            is Command.Add -> {
                adds.add(PlacedCommand.Add(writePos, cmd.data))
                writePos += cmd.data.size
            }
        }
    }

    val n = copies.size
    if (n == 0) return ArrayList(adds)

    // Step 2: build CRWI digraph
    val adj = Array(n) { mutableListOf<Int>() }

    // O(n log n + E) sweep-line: sort writes by start, then for each read
    // interval binary-search into the sorted writes to find overlaps.
    val writeSorted = Array(n) { it }
    writeSorted.sortWith(compareBy { copies[it][2] })
    val writeStarts = IntArray(n) { k -> copies[writeSorted[k]][2] }

    for (i in 0 until n) {
        val si = copies[i][1]; val li = copies[i][3]
        val readEnd = si + li

        val lo = run {
            var a = 0; var b = n
            while (a < b) { val m = a + (b - a) / 2; if (writeStarts[m] < si) a = m + 1 else b = m }
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
                val dj = copies[j][2]; val lj = copies[j][3]
                if (dj + lj > si) adj[i].add(j)
            }
        }
        for (k in lo until hi) {
            val j = writeSorted[k]
            if (j != i) adj[i].add(j)
        }
    }

    // Step 3: Kahn topological sort with Tarjan-scoped cycle breaking.
    val sccs = tarjanScc(adj, n)

    val inDeg = IntArray(n)
    for (i in 0 until n) for (j in adj[i]) inDeg[j]++

    val sccId     = IntArray(n) { -1 }  // -1 = trivial (no cycle)
    val sccList   = mutableListOf<List<Int>>()
    val sccActive = IntArray(sccs.size)

    for (scc in sccs) {
        if (scc.size > 1) {
            val sid = sccList.size
            for (v in scc) sccId[v] = sid
            sccActive[sid] = scc.size
            sccList.add(scc)
        }
    }

    val removed    = BooleanArray(n)
    val topoOrder  = mutableListOf<Int>()
    val color      = IntArray(n)  // 0=unvisited, 1=on-path, 2=done
    var sccPtr     = 0
    val scanPos    = intArrayOf(0)

    val heap = PriorityQueue<IntArray>(compareBy({ it[0] }, { it[1] }))
    for (i in 0 until n) {
        if (inDeg[i] == 0) heap.add(intArrayOf(copies[i][3], i))
    }
    var processed = 0

    while (processed < n) {
        // Drain all ready vertices.
        while (heap.isNotEmpty()) {
            val entry = heap.poll()
            val v = entry[1]
            if (removed[v]) continue
            removed[v] = true
            topoOrder.add(v)
            processed++
            if (sccId[v] != -1) sccActive[sccId[v]]--
            for (w in adj[v]) {
                if (!removed[w]) {
                    inDeg[w]--
                    if (inDeg[w] == 0) heap.add(intArrayOf(copies[w][3], w))
                }
            }
        }

        if (processed >= n) break

        // Kahn stalled: all remaining vertices are in CRWI cycles.
        var victim = -1
        if (policy == CyclePolicy.CONSTANT) {
            for (i in 0 until n) { if (!removed[i]) { victim = i; break } }
        } else { // LOCALMIN
            while (victim == -1) {
                while (sccPtr < sccList.size && sccActive[sccPtr] == 0) {
                    sccPtr++; scanPos[0] = 0
                }
                if (sccPtr >= sccList.size) {
                    for (i in 0 until n) { if (!removed[i]) { victim = i; break } }
                    break
                }
                val cycle = findCycleInScc(adj, sccList[sccPtr], sccPtr, sccId, removed, color, scanPos)
                if (cycle != null) {
                    victim = cycle[0]
                    for (v in cycle) {
                        if (copies[v][3] < copies[victim][3] ||
                            (copies[v][3] == copies[victim][3] && v < victim)) victim = v
                    }
                } else {
                    sccPtr++; scanPos[0] = 0
                }
            }
        }

        // Convert victim: materialize copy data as literal add.
        val ci = copies[victim]
        adds.add(PlacedCommand.Add(ci[2], r.copyOfRange(ci[1], ci[1] + ci[3])))
        removed[victim] = true
        processed++
        if (sccId[victim] != -1) sccActive[sccId[victim]]--

        for (w in adj[victim]) {
            if (!removed[w]) {
                inDeg[w]--
                if (inDeg[w] == 0) heap.add(intArrayOf(copies[w][3], w))
            }
        }
    }

    // Step 4: assemble result — copies in topo order, then all adds
    val result = ArrayList<PlacedCommand>(topoOrder.size + adds.size)
    for (i in topoOrder) {
        val ci = copies[i]
        result.add(PlacedCommand.Copy(ci[1], ci[2], ci[3]))
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
    val index    = IntArray(n) { -1 }  // -1 = unvisited
    val lowlink  = IntArray(n)
    val onStack  = BooleanArray(n)
    val tarjanStack = ArrayDeque<Int>()
    val sccs     = mutableListOf<List<Int>>()
    var counter  = 0
    val callStack = ArrayDeque<IntArray>()  // [vertex, next_neighbor_index]

    for (start in 0 until n) {
        if (index[start] != -1) continue

        index[start] = counter; lowlink[start] = counter++
        onStack[start] = true
        tarjanStack.addLast(start)
        callStack.addLast(intArrayOf(start, 0))

        while (callStack.isNotEmpty()) {
            val frame     = callStack.last()
            val v         = frame[0]
            var ni        = frame[1]
            val neighbors = adj[v]

            if (ni < neighbors.size) {
                val w = neighbors[ni]
                frame[1]++
                if (index[w] == -1) {
                    index[w] = counter; lowlink[w] = counter++
                    onStack[w] = true
                    tarjanStack.addLast(w)
                    callStack.addLast(intArrayOf(w, 0))
                } else if (onStack[w]) {
                    if (index[w] < lowlink[v]) lowlink[v] = index[w]
                }
            } else {
                callStack.removeLast()
                if (callStack.isNotEmpty()) {
                    val parent = callStack.last()[0]
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
 */
private fun findCycleInScc(
    adj: Array<MutableList<Int>>, scc: List<Int>, sid: Int,
    sccId: IntArray, removed: BooleanArray, color: IntArray, scanStart: IntArray
): List<Int>? {
    val path = mutableListOf<Int>()
    var scan = scanStart[0]
    val sccLen = scc.size

    while (scan < sccLen) {
        val start = scc[scan]
        if (removed[start] || color[start] != 0) { scan++; continue }

        color[start] = 1
        path.add(start)
        val stack = ArrayDeque<IntArray>()  // [vertex, next_neighbor_index]
        stack.addLast(intArrayOf(start, 0))

        outer@ while (stack.isNotEmpty()) {
            val frame = stack.last()
            val v     = frame[0]
            var ni    = frame[1]
            val neighbors = adj[v]

            while (ni < neighbors.size) {
                val w = neighbors[ni++]
                if (sccId[w] != sid || removed[w]) continue
                if (color[w] == 1) {
                    val pos   = path.indexOf(w)
                    val cycle = path.subList(pos, path.size).toList()
                    for (u in path) color[u] = 0
                    scanStart[0] = scan
                    return cycle
                }
                if (color[w] == 0) {
                    frame[1] = ni
                    color[w] = 1
                    path.add(w)
                    stack.addLast(intArrayOf(w, 0))
                    continue@outer
                }
            }
            stack.removeLast()
            color[v] = 2  // Fully explored — persists across calls.
            path.removeAt(path.size - 1)
        }
        scan++
    }

    scanStart[0] = scan
    return null
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
