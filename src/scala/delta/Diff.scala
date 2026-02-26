package delta

// ── Shared utilities and algorithm dispatcher (Section 2.1.1) ─────────────

/** Compare byte subarrays for equality. */
extension (a: Array[Byte])
  def regionEquals(aOff: Int, b: Array[Byte], bOff: Int, len: Int): Boolean = {
    var i = 0
    while i < len do {
      if a(aOff + i) != b(bOff + i) then return false
      i += 1
    }
    true
  }

/** Print delta compression statistics to stderr. */
def printStats(commands: List[Command]): Unit = {
  val copyLens = scala.collection.mutable.ArrayBuffer[Int]()
  var totalCopy = 0L
  var totalAdd  = 0L
  var numCopies = 0
  var numAdds   = 0
  for cmd <- commands do cmd match {
    case c: Command.Copy => totalCopy += c.length; numCopies += 1; copyLens += c.length
    case c: Command.Add  => totalAdd  += c.data.length; numAdds += 1
  }
  val totalOut = totalCopy + totalAdd
  val copyPct  = if totalOut > 0 then totalCopy * 100.0 / totalOut else 0.0
  System.err.printf(
    "  result: %d copies (%d bytes), %d adds (%d bytes)%n" +
    "  result: copy coverage %.1f%%, output %d bytes%n",
    numCopies, totalCopy, numAdds, totalAdd, copyPct, totalOut)
  if copyLens.nonEmpty then {
    copyLens.sortInPlace()
    val mean   = totalCopy.toDouble / copyLens.size
    val median = copyLens(copyLens.size / 2)
    System.err.printf(
      "  copies: %d regions, min=%d max=%d mean=%.1f median=%d bytes%n",
      copyLens.size, copyLens.head, copyLens.last, mean, median)
  }
}

/** Run the selected algorithm to produce a command list for R→V. */
def diff(algo: Algorithm, r: Array[Byte], v: Array[Byte], opts: DiffOptions): List[Command] =
  algo match {
    case Algorithm.Greedy     => diffGreedy(r, v, opts)
    case Algorithm.Onepass    => diffOnepass(r, v, opts)
    case Algorithm.Correcting => diffCorrecting(r, v, opts)
  }

/** Run the selected algorithm with default tuning options. */
def diffDefault(algo: Algorithm, r: Array[Byte], v: Array[Byte]): List[Command] =
  diff(algo, r, v, DiffOptions())
