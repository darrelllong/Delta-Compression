package delta

import java.util.{PriorityQueue => JPQ}
import scala.collection.mutable

/**
 * Command placement, application, and in-place reordering.
 *
 * placeCommands: assign sequential destinations (Section 2.1.1)
 * makeInplace:   CRWI digraph + topological sort (Burns et al. 2003)
 */

/** Compute total output size of algorithm commands. */
def outputSize(commands: List[Command]): Int = {
  var size = 0
  for cmd <- commands do cmd match {
    case c: Command.Copy => size += c.length
    case c: Command.Add  => size += c.data.length
  }
  size
}

/** Convert algorithm commands to placed commands with sequential destinations. */
def placeCommands(commands: List[Command]): List[PlacedCommand] = {
  val placed = mutable.ListBuffer[PlacedCommand]()
  var dst    = 0
  for cmd <- commands do cmd match {
    case c: Command.Copy => placed += PlacedCommand.Copy(c.offset, dst, c.length); dst += c.length
    case c: Command.Add  => placed += PlacedCommand.Add(dst, c.data);              dst += c.data.length
  }
  placed.toList
}

/** Apply placed commands in standard mode: read from R, write to out. */
def applyPlacedTo(r: Array[Byte], commands: List[PlacedCommand], out: Array[Byte]): Int = {
  var maxWritten = 0
  for cmd <- commands do cmd match {
    case c: PlacedCommand.Copy =>
      System.arraycopy(r, c.src, out, c.dst, c.length)
      val end = c.dst + c.length
      if end > maxWritten then maxWritten = end
    case c: PlacedCommand.Add =>
      System.arraycopy(c.data, 0, out, c.dst, c.data.length)
      val end = c.dst + c.data.length
      if end > maxWritten then maxWritten = end
  }
  maxWritten
}

/** Apply placed commands in-place within a single buffer. */
def applyPlacedInplaceTo(commands: List[PlacedCommand], buf: Array[Byte]): Unit =
  for cmd <- commands do cmd match {
    case c: PlacedCommand.Copy => System.arraycopy(buf, c.src, buf, c.dst, c.length)
    case c: PlacedCommand.Add  => System.arraycopy(c.data, 0, buf, c.dst, c.data.length)
  }

/** Reconstruct version from reference + algorithm commands. */
def applyDelta(r: Array[Byte], commands: List[Command]): Array[Byte] = {
  val out = new Array[Byte](outputSize(commands))
  var pos = 0
  for cmd <- commands do cmd match {
    case c: Command.Copy =>
      System.arraycopy(r, c.offset, out, pos, c.length); pos += c.length
    case c: Command.Add =>
      System.arraycopy(c.data, 0, out, pos, c.data.length); pos += c.data.length
  }
  out
}

/** Apply placed in-place commands to a buffer initialized with R. */
def applyDeltaInplace(r: Array[Byte], commands: List[PlacedCommand], versionSize: Int): Array[Byte] = {
  val bufSize = math.max(r.length, versionSize)
  val buf     = new Array[Byte](bufSize)
  System.arraycopy(r, 0, buf, 0, r.length)
  applyPlacedInplaceTo(commands, buf)
  if buf.length != versionSize then java.util.Arrays.copyOf(buf, versionSize) else buf
}

/**
 * Convert placed commands back to algorithm commands (strip destinations).
 * Commands are sorted by destination offset to recover original sequential order.
 */
def unplaceCommands(placed: List[PlacedCommand]): List[Command] = {
  val sorted = placed.sortBy {
    case c: PlacedCommand.Copy => c.dst
    case c: PlacedCommand.Add  => c.dst
  }
  sorted.map {
    case c: PlacedCommand.Copy => Command.Copy(c.src, c.length)
    case c: PlacedCommand.Add  => Command.Add(c.data)
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
def makeInplace(r: Array[Byte], commands: List[Command], policy: CyclePolicy): List[PlacedCommand] = {
  if commands.isEmpty then return Nil

  // Step 1: compute write offsets
  // copies: Array[Int] of [idx, src, dst, length]
  val copies = mutable.ArrayBuffer[Array[Int]]()
  val adds   = mutable.ArrayBuffer[PlacedCommand.Add]()
  var writePos = 0

  for cmd <- commands do cmd match {
    case c: Command.Copy =>
      copies += Array(copies.length, c.offset, writePos, c.length)
      writePos += c.length
    case c: Command.Add =>
      adds += PlacedCommand.Add(writePos, c.data)
      writePos += c.data.length
  }

  val n = copies.length
  if n == 0 then return adds.toList

  // Step 2: build CRWI digraph
  val adj = Array.fill(n)(mutable.ListBuffer[Int]())

  // O(n log n + E) sweep-line: sort writes by start, then for each read
  // interval binary-search into the sorted writes to find overlaps.
  val writeSorted = Array.tabulate(n)(identity)
  writeSorted.sortInPlaceWith((a, b) => copies(a)(2) < copies(b)(2))
  val writeStarts = Array.tabulate(n)(k => copies(writeSorted(k))(2))

  for i <- 0 until n do {
    val si = copies(i)(1); val li = copies(i)(3)
    val readEnd = si + li

    val lo = {
      var a = 0; var b = n
      while a < b do { val m = a + (b - a) / 2; if writeStarts(m) < si then a = m + 1 else b = m }
      a
    }
    val hi = {
      var a = lo; var b = n
      while a < b do { val m = a + (b - a) / 2; if writeStarts(m) < readEnd then a = m + 1 else b = m }
      a
    }
    if lo > 0 then {
      val j = writeSorted(lo - 1)
      if j != i then {
        val dj = copies(j)(2); val lj = copies(j)(3)
        if dj + lj > si then adj(i) += j
      }
    }
    for k <- lo until hi do {
      val j = writeSorted(k)
      if j != i then adj(i) += j
    }
  }

  // Step 3: Kahn topological sort with Tarjan-scoped cycle breaking.
  val sccs = tarjanScc(adj, n)

  val inDeg     = new Array[Int](n)
  for i <- 0 until n do for j <- adj(i) do inDeg(j) += 1

  val sccId     = Array.fill(n)(-1)  // -1 = trivial (no cycle)
  val sccList   = mutable.ArrayBuffer[List[Int]]()
  val sccActive = new Array[Int](sccs.length)  // sized to sccs count

  for scc <- sccs do if scc.length > 1 then {
    val sid = sccList.length
    for v <- scc do sccId(v) = sid
    sccActive(sid) = scc.length
    sccList += scc
  }

  val removed   = new Array[Boolean](n)
  val topoOrder = mutable.ListBuffer[Int]()
  val color     = new Array[Int](n)  // 0=unvisited, 1=on-path, 2=done
  var sccPtr    = 0
  val scanPos   = Array(0)

  // Heap: Array[Int](2) = [length, index], min-heap by (length, index)
  val heap = new JPQ[Array[Int]]((a, b) => {
    val c = Integer.compare(a(0), b(0))
    if c != 0 then c else Integer.compare(a(1), b(1))
  })
  for i <- 0 until n do if inDeg(i) == 0 then heap.add(Array(copies(i)(3), i))
  var processed = 0

  while processed < n do {
    // Drain all ready vertices.
    while !heap.isEmpty do {
      val entry = heap.poll()
      val v     = entry(1)
      if !removed(v) then {
        removed(v) = true
        topoOrder += v
        processed += 1
        if sccId(v) != -1 then sccActive(sccId(v)) -= 1
        for w <- adj(v) do if !removed(w) then {
          inDeg(w) -= 1
          if inDeg(w) == 0 then heap.add(Array(copies(w)(3), w))
        }
      }
    }

    if processed < n then {
      // Kahn stalled: all remaining vertices are in CRWI cycles.
      var victim = -1
      if policy == CyclePolicy.Constant then {
        var i = 0
        while victim == -1 && i < n do { if !removed(i) then victim = i; i += 1 }
      } else { // Localmin
        var searching = true
        while searching && victim == -1 do {
          while sccPtr < sccList.length && sccActive(sccPtr) == 0 do {
            sccPtr += 1; scanPos(0) = 0
          }
          if sccPtr >= sccList.length then {
            var i = 0
            while victim == -1 && i < n do { if !removed(i) then victim = i; i += 1 }
            searching = false
          } else {
            findCycleInScc(adj, sccList(sccPtr), sccPtr, sccId, removed, color, scanPos) match {
              case Some(cycle) =>
                victim = cycle.head
                for v <- cycle do {
                  if copies(v)(3) < copies(victim)(3) ||
                     (copies(v)(3) == copies(victim)(3) && v < victim) then victim = v
                }
                searching = false
              case None =>
                sccPtr += 1; scanPos(0) = 0
            }
          }
        }
      }

      // Convert victim: materialize copy data as literal add.
      val ci = copies(victim)
      adds += PlacedCommand.Add(ci(2), java.util.Arrays.copyOfRange(r, ci(1), ci(1) + ci(3)))
      removed(victim) = true
      processed += 1
      if sccId(victim) != -1 then sccActive(sccId(victim)) -= 1

      for w <- adj(victim) do if !removed(w) then {
        inDeg(w) -= 1
        if inDeg(w) == 0 then heap.add(Array(copies(w)(3), w))
      }
    }
  }

  // Step 4: assemble result — copies in topo order, then all adds
  val result = mutable.ListBuffer[PlacedCommand]()
  for i <- topoOrder do {
    val ci = copies(i)
    result += PlacedCommand.Copy(ci(1), ci(2), ci(3))
  }
  result.appendAll(adds)
  result.toList
}

/**
 * Compute SCCs using iterative Tarjan's algorithm.
 *
 * Returns SCCs in reverse topological order (sinks first).
 * R.E. Tarjan, SIAM Journal on Computing, 1(2):146-160, June 1972.
 */
private def tarjanScc(adj: Array[mutable.ListBuffer[Int]], n: Int): List[List[Int]] = {
  val index    = Array.fill(n)(-1)  // -1 = unvisited
  val lowlink  = new Array[Int](n)
  val onStack  = new Array[Boolean](n)
  val tStack   = mutable.ArrayDeque[Int]()
  val sccs     = mutable.ListBuffer[List[Int]]()
  var counter  = 0
  val callStack = mutable.ArrayDeque[Array[Int]]()  // [vertex, next_neighbor_index]

  for start <- 0 until n do if index(start) == -1 then {
    index(start)  = counter; lowlink(start) = counter; counter += 1
    onStack(start) = true
    tStack += start
    callStack += Array(start, 0)

    while callStack.nonEmpty do {
      val frame     = callStack.last
      val v         = frame(0)
      val neighbors = adj(v)

      if frame(1) < neighbors.length then {
        val w = neighbors(frame(1))
        frame(1) += 1
        if index(w) == -1 then {
          index(w)  = counter; lowlink(w) = counter; counter += 1
          onStack(w) = true
          tStack += w
          callStack += Array(w, 0)
        } else if onStack(w) then {
          if index(w) < lowlink(v) then lowlink(v) = index(w)
        }
      } else {
        callStack.removeLast()
        if callStack.nonEmpty then {
          val parent = callStack.last(0)
          if lowlink(v) < lowlink(parent) then lowlink(parent) = lowlink(v)
        }
        if lowlink(v) == index(v) then {
          val scc = mutable.ListBuffer[Int]()
          var w   = -1
          while w != v do {
            w = tStack.removeLast(); onStack(w) = false; scc += w
          }
          sccs += scc.toList
        }
      }
    }
  }
  sccs.toList
}

/**
 * Find a cycle in the active subgraph of one SCC.
 * Three amortizations give O(|SCC| + E_SCC) total work per SCC.
 */
private def findCycleInScc(
  adj:       Array[mutable.ListBuffer[Int]],
  scc:       List[Int],
  sid:       Int,
  sccId:     Array[Int],
  removed:   Array[Boolean],
  color:     Array[Int],
  scanStart: Array[Int]
): Option[List[Int]] = {
  val path   = mutable.ArrayBuffer[Int]()
  var scan   = scanStart(0)
  val sccArr = scc.toArray
  val sccLen = sccArr.length

  while scan < sccLen do {
    val start = sccArr(scan)
    if removed(start) || color(start) != 0 then scan += 1
    else {
      color(start) = 1
      path += start
      val stack = mutable.ArrayDeque[Array[Int]]()  // [vertex, next_neighbor_index]
      stack += Array(start, 0)

      var found: Option[List[Int]] = None
      while stack.nonEmpty && found.isEmpty do {
        val frame     = stack.last
        val v         = frame(0)
        val neighbors = adj(v)
        var advanced  = false

        while !advanced && frame(1) < neighbors.length do {
          val w = neighbors(frame(1))
          frame(1) += 1
          if sccId(w) == sid && !removed(w) then {
            if color(w) == 1 then {
              val pos   = path.indexOf(w)
              val cycle = path.slice(pos, path.length).toList
              for u <- path do color(u) = 0
              scanStart(0) = scan
              found = Some(cycle)
              advanced = true
            } else if color(w) == 0 then {
              color(w) = 1
              path += w
              stack += Array(w, 0)
              advanced = true
            }
          }
        }

        if !advanced && found.isEmpty then {
          stack.removeLast()
          color(v) = 2  // Fully explored — persists across calls.
          if path.nonEmpty && path.last == v then path.trimEnd(1)
        }
      }

      if found.nonEmpty then return found
      scan += 1
    }
  }

  scanStart(0) = scan
  None
}

/** Compute summary statistics for placed commands. */
def placedSummary(commands: List[PlacedCommand]): PlacedSummary = {
  var numCopies = 0; var numAdds = 0
  var copyBytes = 0L; var addBytes = 0L
  for cmd <- commands do cmd match {
    case c: PlacedCommand.Copy => numCopies += 1; copyBytes += c.length
    case c: PlacedCommand.Add  => numAdds   += 1; addBytes  += c.data.length
  }
  PlacedSummary(commands.length, numCopies, numAdds, copyBytes, addBytes, copyBytes + addBytes)
}
