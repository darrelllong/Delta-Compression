package delta

/**
 * Greedy algorithm (Section 3.1, Figure 2).
 *
 * Finds an optimal delta encoding under the simple cost measure
 * (optimality proof: Section 3.3, Theorem 1).
 * Time: O(|V| * |R|) worst case. Space: O(|R|).
 */
fun diffGreedy(r: ByteArray, v: ByteArray, opts: DiffOptions): List<Command> {
    val commands = mutableListOf<Command>()
    if (v.isEmpty()) return commands

    val p        = opts.p
    val verbose  = opts.verbose
    val useSplay = opts.useSplay

    // Step (1): build lookup structure for R keyed by full fingerprint.
    val hrHt: HashMap<Long, MutableList<Int>>? = if (!useSplay) HashMap() else null
    val hrSp: SplayTree<MutableList<Int>>?     = if (useSplay)  SplayTree() else null

    if (r.size >= p) {
        val rh = RollingHash(r, 0, p)
        if (useSplay) hrSp!!.insertOrGet(rh.value, mutableListOf()).add(0)
        else          hrHt!!.getOrPut(rh.value) { mutableListOf() }.add(0)
        for (a in 1..r.size - p) {
            rh.roll(r[a - 1].toInt() and 0xFF, r[a + p - 1].toInt() and 0xFF)
            if (useSplay) hrSp!!.insertOrGet(rh.value, mutableListOf()).add(a)
            else          hrHt!!.getOrPut(rh.value) { mutableListOf() }.add(a)
        }
    }

    if (verbose) {
        System.err.printf("greedy: %s, |R|=%d, |V|=%d, seed_len=%d%n",
            if (useSplay) "splay tree" else "hash table", r.size, v.size, p)
    }

    // Step (2): initialize scan pointers
    var vC    = 0
    var vS    = 0
    var rhV   = if (v.size >= p) RollingHash(v, 0, p) else null
    var rhVPos = 0

    while (vC + p <= v.size) {
        // Step (3): compute fingerprint at vC
        val rh = rhV ?: break
        val fpV: Long = when {
            vC == rhVPos     -> rh.value
            vC == rhVPos + 1 -> {
                rh.roll(v[vC - 1].toInt() and 0xFF, v[vC + p - 1].toInt() and 0xFF)
                rhVPos = vC
                rh.value
            }
            else -> {
                val newRh = RollingHash(v, vC, p)
                rhV = newRh
                rhVPos = vC
                newRh.value
            }
        }

        // Steps (4)+(5): find the longest matching substring
        var bestRm  = -1
        var bestLen = 0
        val offsets: List<Int>? = if (useSplay) hrSp!!.find(fpV) else hrHt!![fpV]
        if (offsets != null) {
            for (rCand in offsets) {
                if (!r.regionEquals(rCand, v, vC, p)) continue
                var ml = p
                while (vC + ml < v.size && rCand + ml < r.size && v[vC + ml] == r[rCand + ml]) ml++
                if (ml > bestLen) { bestLen = ml; bestRm = rCand }
            }
        }

        if (bestLen < p) { vC++; continue }

        // Step (6): encode
        if (vS < vC) commands.add(Command.Add(v.copyOfRange(vS, vC)))
        commands.add(Command.Copy(bestRm, bestLen))
        vS = vC + bestLen

        // Step (7): advance past matched region
        vC += bestLen
    }

    // Step (8): trailing add
    if (vS < v.size) commands.add(Command.Add(v.copyOfRange(vS, v.size)))
    if (verbose) printStats(commands)
    return commands
}
