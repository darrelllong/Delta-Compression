package delta

/**
 * Correcting 1.5-Pass algorithm (Section 7, Figure 8) with
 * fingerprint-based checkpointing (Section 8).
 *
 * |C| = q (hash table capacity, auto-sized from input).
 * |F| = next_prime(2 * num_R_seeds) (footprint universe, Section 8.1).
 * m  = ceil(|F| / |C|) (checkpoint spacing, p. 348).
 * k  = checkpoint class (Eq. 3, p. 348).
 */

/**
 * One entry in the correction lookback buffer (Section 5.2).
 *
 * The correcting algorithm may discover that a newly found match overlaps
 * commands already emitted.  The buffer holds the most recent bufCap tentative
 * commands so they can be trimmed or cancelled (tail correction) when a better
 * match is found.  Commands are flushed to the output list as they age out.
 *
 * @param vStart First V byte covered by this entry.
 * @param vEnd   One past the last V byte covered.
 * @param cmd    The tentative command (Add or Copy).
 * @param dummy  Reserved; always false in the current implementation.
 */
private class BufEntry(val vStart: Int, var vEnd: Int, var cmd: Command, val dummy: Boolean)

fun diffCorrecting(r: ByteArray, v: ByteArray, opts: DiffOptions): List<Command> {
    val commands = mutableListOf<Command>()
    if (v.isEmpty()) return commands

    val p        = opts.p
    val bufCap   = opts.bufCap
    val verbose  = opts.verbose
    val useSplay = opts.useSplay

    // ── Checkpointing parameters (Section 8.1, pp. 347-348) ─────────────────
    val numSeeds = if (r.size >= p) r.size - p + 1 else 0
    val maxTable = if (opts.maxTable > 0) opts.maxTable else MAX_TABLE_SIZE
    val cap = if (numSeeds > 0)
        nextPrime(minOf(maxTable.toLong(), maxOf(opts.q.toLong(), 2L * numSeeds / p))).toInt()
    else
        nextPrime(minOf(opts.q.toLong(), maxTable.toLong())).toInt()
    val fSize: Long = if (numSeeds > 0) nextPrime(2L * numSeeds) else 1L
    val m: Long     = if (fSize <= cap) 1L else (fSize + cap - 1) / cap
    val k: Long     = if (v.size >= p)
        fingerprint(v, minOf(v.size / 2, v.size - p), p) % fSize % m
    else 0L

    if (verbose) {
        val expected = if (m > 0) numSeeds / m else 0L
        val occEst   = if (cap > 0) expected * 100 / cap else 0L
        System.err.printf(
            "correcting: %s, |C|=%d |F|=%d m=%d k=%d%n" +
            "  checkpoint gap=%d bytes, expected fill ~%d (~%d%% table occupancy)%n",
            if (useSplay) "splay tree" else "hash table", cap, fSize, m, k,
            m, expected, occEst)
    }

    // Step (1): Build lookup structure for R (first-found policy)
    val htFp:   LongArray?
    val htOff:  IntArray?
    val splayR: SplayTree<LongArray>?  // value = [full_fp, offset]

    if (useSplay) {
        splayR = SplayTree()
        htFp = null; htOff = null
    } else {
        splayR = null
        htFp   = LongArray(cap) { -1L }
        htOff  = IntArray(cap)
    }

    var rhR: RollingHash? = if (numSeeds > 0) RollingHash(r, 0, p) else null
    for (a in 0 until numSeeds) {
        val fp: Long = if (a == 0) {
            rhR!!.value
        } else {
            rhR!!.roll(r[a - 1].toInt() and 0xFF, r[a + p - 1].toInt() and 0xFF)
            rhR.value
        }
        val f = fp % fSize
        if (f % m != k) continue  // not a checkpoint seed

        if (useSplay) {
            splayR!!.insertOrGet(fp, longArrayOf(fp, a.toLong()))
        } else {
            var i = (f / m).toInt()
            val i0 = i
            while (true) {
                if (htFp!![i] == -1L) { break }               // empty — store here
                if (htFp!![i] == fp) { i = -1; break }        // dup fp — skip
                if (++i == cap) { i = 0 }
                if (i == i0) { i = -1; break }                 // table full
            }
            if (i >= 0) { htFp!![i] = fp; htOff!![i] = a }
        }
    }

    // Encoding lookback buffer (Section 5.2)
    val buf = ArrayDeque<BufEntry>()

    // Step (2): initialize scan pointers
    var vC    = 0
    var vS    = 0
    val vSeeds = if (v.size >= p) v.size - p + 1 else 0
    var rhV   = if (vSeeds > 0) RollingHash(v, 0, p) else null
    var rhVPos = 0

    while (vC + p <= v.size) {  // Step (3): check for end of V
        // Step (4): fingerprint at vC, apply checkpoint test.
        val rh = rhV ?: break
        val fpV: Long = when {
            vC == rhVPos     -> rh.value
            vC == rhVPos + 1 -> {
                rh.roll(v[vC - 1].toInt() and 0xFF, v[vC + p - 1].toInt() and 0xFF)
                rhVPos = vC; rh.value
            }
            else -> {
                val newRh = RollingHash(v, vC, p); rhV = newRh; rhVPos = vC; newRh.value
            }
        }

        val fV = fpV % fSize
        if (fV % m != k) { vC++; continue }

        // Checkpoint passed — look up R.
        val storedFp: Long
        val rOffset: Int
        if (useSplay) {
            val entry = splayR!!.find(fpV)
            if (entry == null) { vC++; continue }
            storedFp = entry[0]; rOffset = entry[1].toInt()
        } else {
            var i = (fV / m).toInt()
            val i0 = i
            var found = -1
            while (true) {
                if (htFp!![i] == -1L) { break }               // empty — chain ends
                if (htFp!![i] == fpV) { found = i; break }
                if (++i == cap) { i = 0 }
                if (i == i0) { break }                         // full table — not found
            }
            if (found < 0) { vC++; continue }
            storedFp = htFp!![found]; rOffset = htOff!![found]
        }

        if (storedFp != fpV) { vC++; continue }
        if (!r.regionEquals(rOffset, v, vC, p)) { vC++; continue }

        // Step (5): extend match forwards and backwards
        var fwd = p
        while (vC + fwd < v.size && rOffset + fwd < r.size && v[vC + fwd] == r[rOffset + fwd]) fwd++
        var bwd = 0
        while (vC >= bwd + 1 && rOffset >= bwd + 1 && v[vC - bwd - 1] == r[rOffset - bwd - 1]) bwd++

        val vM = vC - bwd
        val rM = rOffset - bwd
        val ml = bwd + fwd
        val matchEnd = vM + ml

        if (ml < p) { vC++; continue }

        // Step (6): encode with correction
        if (vS <= vM) {
            // (6a) match in unencoded suffix
            if (vS < vM) {
                if (buf.size >= bufCap) {
                    val oldest = buf.removeFirst()
                    if (!oldest.dummy) commands.add(oldest.cmd)
                }
                buf.addLast(BufEntry(vS, vM, Command.Add(v.copyOfRange(vS, vM)), false))
            }
            if (buf.size >= bufCap) {
                val oldest = buf.removeFirst()
                if (!oldest.dummy) commands.add(oldest.cmd)
            }
            buf.addLast(BufEntry(vM, matchEnd, Command.Copy(rM, ml), false))
            vS = matchEnd
        } else {
            // (6b) tail correction (Section 5.1, p. 339)
            var effectiveStart = vS

            while (buf.isNotEmpty()) {
                val tail = buf.last()
                if (tail.dummy) { buf.removeLast(); continue }

                if (tail.vStart >= vM && tail.vEnd <= matchEnd) {
                    effectiveStart = minOf(effectiveStart, tail.vStart)
                    buf.removeLast(); continue
                }

                if (tail.vEnd > vM && tail.vStart < vM) {
                    if (tail.cmd is Command.Add) {
                        val keep = vM - tail.vStart
                        if (keep > 0) {
                            tail.cmd = Command.Add(v.copyOfRange(tail.vStart, vM))
                            tail.vEnd = vM
                        } else {
                            buf.removeLast()
                        }
                        effectiveStart = minOf(effectiveStart, vM)
                    }
                    break
                }
                break
            }

            val adj    = effectiveStart - vM
            val newLen = matchEnd - effectiveStart
            if (newLen > 0) {
                if (buf.size >= bufCap) {
                    val oldest = buf.removeFirst()
                    if (!oldest.dummy) commands.add(oldest.cmd)
                }
                buf.addLast(BufEntry(effectiveStart, matchEnd, Command.Copy(rM + adj, newLen), false))
            }
            vS = matchEnd
        }

        // Step (7): advance past matched region
        vC = matchEnd
    }

    // Step (8): flush buffer and trailing add
    for (entry in buf) {
        if (!entry.dummy) commands.add(entry.cmd)
    }
    if (vS < v.size) commands.add(Command.Add(v.copyOfRange(vS, v.size)))

    if (verbose) printStats(commands)
    return commands
}
