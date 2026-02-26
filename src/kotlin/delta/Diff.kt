package delta

// ── Shared utilities and algorithm dispatcher (Section 2.1.1) ─────────────

/** Compare byte subarrays for equality. */
fun ByteArray.regionEquals(aOff: Int, b: ByteArray, bOff: Int, len: Int): Boolean {
    for (i in 0 until len) {
        if (this[aOff + i] != b[bOff + i]) return false
    }
    return true
}

/** Print delta compression statistics to stderr. */
fun printStats(commands: List<Command>) {
    val copyLens = mutableListOf<Int>()
    var totalCopy = 0L
    var totalAdd  = 0L
    var numCopies = 0
    var numAdds   = 0
    for (cmd in commands) {
        when (cmd) {
            is Command.Copy -> { totalCopy += cmd.length; numCopies++; copyLens.add(cmd.length) }
            is Command.Add  -> { totalAdd  += cmd.data.size; numAdds++ }
        }
    }
    val totalOut = totalCopy + totalAdd
    val copyPct = if (totalOut > 0) totalCopy * 100.0 / totalOut else 0.0
    System.err.printf(
        "  result: %d copies (%d bytes), %d adds (%d bytes)%n" +
        "  result: copy coverage %.1f%%, output %d bytes%n",
        numCopies, totalCopy, numAdds, totalAdd, copyPct, totalOut
    )
    if (copyLens.isNotEmpty()) {
        copyLens.sort()
        val mean   = totalCopy.toDouble() / copyLens.size
        val median = copyLens[copyLens.size / 2]
        System.err.printf(
            "  copies: %d regions, min=%d max=%d mean=%.1f median=%d bytes%n",
            copyLens.size, copyLens.first(), copyLens.last(), mean, median
        )
    }
}

/** Run the selected algorithm to produce a command list for R→V. */
fun diff(algo: Algorithm, r: ByteArray, v: ByteArray, opts: DiffOptions): List<Command> =
    when (algo) {
        Algorithm.GREEDY     -> diffGreedy(r, v, opts)
        Algorithm.ONEPASS    -> diffOnepass(r, v, opts)
        Algorithm.CORRECTING -> diffCorrecting(r, v, opts)
    }

/** Run the selected algorithm with default tuning options. */
fun diffDefault(algo: Algorithm, r: ByteArray, v: ByteArray): List<Command> =
    diff(algo, r, v, DiffOptions())
