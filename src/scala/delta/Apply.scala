package delta

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
      Array.copy(r, c.src, out, c.dst, c.length)
      val end = c.dst + c.length
      if end > maxWritten then maxWritten = end
    case c: PlacedCommand.Add =>
      c.data.copyToArray(out, c.dst)
      val end = c.dst + c.data.length
      if end > maxWritten then maxWritten = end
  }
  maxWritten
}

/** Apply placed commands in-place within a single buffer. */
def applyPlacedInplaceTo(commands: List[PlacedCommand], buf: Array[Byte]): Unit =
  for cmd <- commands do cmd match {
    case c: PlacedCommand.Copy => Array.copy(buf, c.src, buf, c.dst, c.length)
    case c: PlacedCommand.Add  => c.data.copyToArray(buf, c.dst)
  }

/** Validate placed commands before apply so malformed deltas fail cleanly. */
def validatePlacedCommands(commands: List[PlacedCommand], referenceSize: Int,
                           versionSize: Int, inplace: Boolean): Unit = {
  val sourceLimit = if inplace then math.max(referenceSize, versionSize) else referenceSize
  for cmd <- commands do cmd match {
    case c: PlacedCommand.Copy =>
      validateRange(c.dst, c.length, versionSize, "copy destination")
      validateRange(c.src, c.length, sourceLimit, "copy source")
    case c: PlacedCommand.Add =>
      validateRange(c.dst, c.data.length, versionSize, "add destination")
  }
}

/** Reconstruct version from reference + algorithm commands. */
def applyDelta(r: Array[Byte], commands: List[Command]): Array[Byte] = {
  val out = new Array[Byte](outputSize(commands))
  var pos = 0
  for cmd <- commands do cmd match {
    case c: Command.Copy =>
      Array.copy(r, c.offset, out, pos, c.length); pos += c.length
    case c: Command.Add =>
      c.data.copyToArray(out, pos); pos += c.data.length
  }
  out
}

/** Apply placed in-place commands to a buffer initialized with R. */
def applyDeltaInplace(r: Array[Byte], commands: List[PlacedCommand], versionSize: Int): Array[Byte] = {
  val bufSize = math.max(r.length, versionSize)
  val buf     = new Array[Byte](bufSize)
  r.copyToArray(buf)
  applyPlacedInplaceTo(commands, buf)
  if buf.length != versionSize then buf.take(versionSize) else buf
}

private def validateRange(start: Int, len: Int, limit: Int, label: String): Unit =
  if start < 0 || len < 0 || start > limit || len > limit - start then
    throw new IllegalArgumentException(s"$label out of range")

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

/** Source offset, destination offset, and length of one copy command. */
private case class CopyInfo(src: Int, dst: Int, length: Int)

/** Non-trivial SCCs with per-SCC active counts and vertex-to-SCC mapping. */
private case class SccList(sccs: Array[Array[Int]], active: Array[Int], id: Array[Int])

/** Mutable cursor tracking which SCC and scan position pickVictim is examining. */
private class ScanCursor(var sccPtr: Int = 0, var scanPos: Int = 0)

// DFS color states for findCycleInScc
private val ColorUnvisited = 0
private val ColorOnPath    = 1
private val ColorDone      = 2

/** Sentinel: vertex is in no non-trivial SCC. */
private val NoScc = -1

/** One frame on the iterative DFS call stack: vertex and next-neighbor index. */
private case class DfsFrame(v: Int, var ni: Int)

/**
 * Build CRWI digraph on copy commands.
 *
 * Edge i→j means copy i reads from a region that copy j will overwrite,
 * so i must execute before j.  O(n log n + E) sweep-line construction.
 */
private def buildCrwiDigraph(copies: Array[CopyInfo], n: Int): Array[mutable.ListBuffer[Int]] = {
  val adj = Array.fill(n)(mutable.ListBuffer[Int]())

  // Sort copy write-intervals by start; binary-search for each read interval.
  val writeSorted = Array.tabulate(n)(identity)
  writeSorted.sortInPlaceWith((a, b) => copies(a).dst < copies(b).dst)
  val writeStarts = Array.tabulate(n)(k => copies(writeSorted(k)).dst)

  for i <- 0 until n do {
    val src = copies(i).src; val len = copies(i).length
    val readEnd = src + len

    // lo = first write with dst >= src; hi = first write with dst >= readEnd.
    // Writes in [lo, hi) start inside [src, readEnd) — they always overlap.
    // The write at lo-1 starts before src; overlaps iff its end exceeds src.
    val lo = {
      var a = 0; var b = n
      while a < b do { val m = a + (b - a) / 2; if writeStarts(m) < src then a = m + 1 else b = m }
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
        val dj = copies(j).dst; val lj = copies(j).length
        if dj + lj > src then adj(i) += j
      }
    }
    for k <- lo until hi do {
      val j = writeSorted(k)
      if j != i then adj(i) += j
    }
  }
  adj
}

/** Wrap tarjanScc output into an SccList containing only non-trivial SCCs. */
private def buildSccList(adj: Array[mutable.ListBuffer[Int]], n: Int): SccList = {
  val allSccs   = tarjanScc(adj, n)
  val id        = Array.fill(n)(NoScc)
  val sccArrays = mutable.ArrayBuffer[Array[Int]]()

  for scc <- allSccs do if scc.length > 1 then {
    val sid = sccArrays.length
    for v <- scc do id(v) = sid
    sccArrays += scc.toArray
  }

  val sccs   = sccArrays.toArray
  val active = Array.tabulate(sccs.length)(k => sccs(k).length)
  SccList(sccs, active, id)
}

/**
 * Select a victim copy to break a cycle when Kahn's algorithm stalls.
 *
 * Constant: first remaining vertex.  Localmin: minimum-length copy in a cycle.
 * cur.sccPtr and cur.scanPos are advanced in place across repeated calls.
 */
private def pickVictim(
  copies:  Array[CopyInfo],
  adj:     Array[mutable.ListBuffer[Int]],
  sl:      SccList,
  removed: Array[Boolean],
  color:   Array[Int],
  cur:     ScanCursor,
  policy:  CyclePolicy,
  n:       Int
): Int = {
  if policy == CyclePolicy.Constant then {
    var victim = -1; var i = 0
    while victim == -1 && i < n do { if !removed(i) then victim = i; i += 1 }
    victim
  } else {
    var victim = -1
    while victim == -1 do {
      while cur.sccPtr < sl.sccs.length && sl.active(cur.sccPtr) == 0 do {
        cur.sccPtr += 1; cur.scanPos = 0
      }
      if cur.sccPtr >= sl.sccs.length then {
        var i = 0
        while victim == -1 && i < n do { if !removed(i) then victim = i; i += 1 }
      } else {
        val (cycleOpt, newScan) =
          findCycleInScc(adj, sl.sccs(cur.sccPtr), cur.sccPtr, sl.id, removed, color, cur.scanPos)
        cur.scanPos = newScan
        cycleOpt match {
          case Some(cycle) =>
            victim = cycle.head
            for v <- cycle do
              if copies(v).length < copies(victim).length ||
                 (copies(v).length == copies(victim).length && v < victim) then victim = v
          case None =>
            cur.sccPtr += 1; cur.scanPos = 0
        }
      }
    }
    victim
  }
}

/**
 * Run Kahn topological sort; when the heap stalls, call pickVictim to break
 * the cycle by materialising one copy as a literal add.
 */
private def runKahn(
  copies: Array[CopyInfo],
  adj:    Array[mutable.ListBuffer[Int]],
  sl:     SccList,
  r:      Array[Byte],
  adds:   mutable.ArrayBuffer[PlacedCommand.Add],
  policy: CyclePolicy,
  n:      Int
): List[Int] = {
  val inDeg     = new Array[Int](n)
  for i <- 0 until n do for j <- adj(i) do inDeg(j) += 1

  val removed   = new Array[Boolean](n)
  val topoOrder = mutable.ListBuffer[Int]()
  val color  = new Array[Int](n)   // ColorUnvisited initially
  val cursor = new ScanCursor()

  given Ordering[Array[Int]] = Ordering.by((a: Array[Int]) => (a(0), a(1))).reverse
  val heap = mutable.PriorityQueue.empty[Array[Int]]
  for i <- 0 until n do if inDeg(i) == 0 then heap.enqueue(Array(copies(i).length, i))
  var processed = 0

  while processed < n do {
    while heap.nonEmpty do {
      val entry = heap.dequeue()
      val v     = entry(1)
      if !removed(v) then {
        removed(v) = true
        topoOrder += v
        processed += 1
        if sl.id(v) != NoScc then sl.active(sl.id(v)) -= 1
        for w <- adj(v) do if !removed(w) then {
          inDeg(w) -= 1
          if inDeg(w) == 0 then heap.enqueue(Array(copies(w).length, w))
        }
      }
    }

    if processed < n then {
      val victim = pickVictim(copies, adj, sl, removed, color, cursor, policy, n)  // cursor mutated
      val ci     = copies(victim)
      adds += PlacedCommand.Add(ci.dst, r.slice(ci.src, ci.src + ci.length))
      removed(victim) = true
      processed += 1
      if sl.id(victim) != NoScc then sl.active(sl.id(victim)) -= 1
      for w <- adj(victim) do if !removed(w) then {
        inDeg(w) -= 1
        if inDeg(w) == 0 then heap.enqueue(Array(copies(w).length, w))
      }
    }
  }
  topoOrder.toList
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
  val copyBuf  = mutable.ArrayBuffer[CopyInfo]()
  val adds     = mutable.ArrayBuffer[PlacedCommand.Add]()
  var writePos = 0
  for cmd <- commands do cmd match {
    case c: Command.Copy => copyBuf += CopyInfo(c.offset, writePos, c.length); writePos += c.length
    case c: Command.Add  => adds += PlacedCommand.Add(writePos, c.data);       writePos += c.data.length
  }
  val copies = copyBuf.toArray
  val n      = copies.length
  if n == 0 then return adds.toList

  // Steps 2-3: build digraph, topological sort, break cycles
  val adj       = buildCrwiDigraph(copies, n)
  val sl        = buildSccList(adj, n)
  val topoOrder = runKahn(copies, adj, sl, r, adds, policy, n)

  // Step 4: assemble result — copies in topo order, then all adds
  val result = mutable.ListBuffer[PlacedCommand]()
  for i <- topoOrder do result += PlacedCommand.Copy(copies(i).src, copies(i).dst, copies(i).length)
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
  val index     = Array.fill(n)(NoScc)  // NoScc = unvisited
  val lowlink   = new Array[Int](n)
  val onStack   = new Array[Boolean](n)
  val tStack    = mutable.ArrayDeque[Int]()
  val sccs      = mutable.ListBuffer[List[Int]]()
  var counter   = 0
  val callStack = mutable.ArrayDeque[DfsFrame]()

  for start <- 0 until n do if index(start) == NoScc then {
    index(start) = counter; lowlink(start) = counter; counter += 1
    onStack(start) = true
    tStack += start
    callStack += DfsFrame(start, 0)

    while callStack.nonEmpty do {
      val frame     = callStack.last
      val v         = frame.v
      val neighbors = adj(v)

      if frame.ni < neighbors.length then {
        val w = neighbors(frame.ni)
        frame.ni += 1
        if index(w) == NoScc then {
          index(w) = counter; lowlink(w) = counter; counter += 1
          onStack(w) = true
          tStack += w
          callStack += DfsFrame(w, 0)
        } else if onStack(w) then {
          if index(w) < lowlink(v) then lowlink(v) = index(w)
        }
      } else {
        callStack.removeLast()
        if callStack.nonEmpty then {
          val parent = callStack.last.v
          if lowlink(v) < lowlink(parent) then lowlink(parent) = lowlink(v)
        }
        if lowlink(v) == index(v) then {
          val scc = mutable.ListBuffer[Int]()
          var w   = NoScc
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
 *
 * Returns (Some(cycle), newScan) on success, (None, newScan) when the SCC
 * subgraph is acyclic.  The caller stores newScan for the next call.
 */
private def findCycleInScc(
  adj:       Array[mutable.ListBuffer[Int]],
  scc:       Array[Int],
  sid:       Int,
  sccId:     Array[Int],
  removed:   Array[Boolean],
  color:     Array[Int],
  scanStart: Int
): (Option[List[Int]], Int) = {
  val path   = mutable.ArrayBuffer[Int]()
  var scan   = scanStart
  val sccLen = scc.length

  while scan < sccLen do {
    val start = scc(scan)
    if removed(start) || color(start) != ColorUnvisited then scan += 1
    else {
      color(start) = ColorOnPath
      path += start
      val stack = mutable.ArrayDeque[DfsFrame]()
      stack += DfsFrame(start, 0)

      var found: Option[List[Int]] = None
      while stack.nonEmpty && found.isEmpty do {
        val frame     = stack.last
        val v         = frame.v
        val neighbors = adj(v)
        var advanced  = false

        while !advanced && frame.ni < neighbors.length do {
          val w = neighbors(frame.ni)
          frame.ni += 1
          if sccId(w) == sid && !removed(w) then {
            if color(w) == ColorOnPath then {
              val pos   = path.indexOf(w)
              val cycle = path.slice(pos, path.length).toList
              for u <- path do color(u) = ColorUnvisited
              found = Some(cycle)
              advanced = true
            } else if color(w) == ColorUnvisited then {
              color(w) = ColorOnPath
              path += w
              stack += DfsFrame(w, 0)
              advanced = true
            }
          }
        }

        if !advanced && found.isEmpty then {
          stack.removeLast()
          color(v) = ColorDone  // Fully explored — persists across calls.
          if path.nonEmpty && path.last == v then path.trimEnd(1)
        }
      }

      if found.nonEmpty then return (found, scan)
      scan += 1
    }
  }

  (None, scan)
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
