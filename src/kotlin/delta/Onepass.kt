package delta

/**
 * One-Pass algorithm (Section 4.1, Figure 3).
 *
 * Scans R and V concurrently with two hash tables (one per string).
 * Each table stores at most one offset per footprint (retain-existing
 * policy: first entry wins, later collisions are discarded).
 * Hash tables are logically flushed after each match via version counter.
 * Time: O(np + q), space: O(q).
 */
fun diffOnepass(r: ByteArray, v: ByteArray, opts: DiffOptions): List<Command> {
    val commands = mutableListOf<Command>()
    if (v.isEmpty()) return commands

    val p        = opts.p
    val useSplay = opts.useSplay
    val verbose  = opts.verbose

    // Auto-size hash table: one slot per p-byte chunk of R (floor = q).
    val numSeeds = if (r.size >= p) r.size - p + 1 else 0
    val q = nextPrime(maxOf(opts.q.toLong(), (numSeeds / p).toLong())).toInt()

    if (verbose) {
        System.err.printf("onepass: %s, q=%d, |R|=%d, |V|=%d, seed_len=%d%n",
            if (useSplay) "splay tree" else "hash table", q, r.size, v.size, p)
    }

    // Step (1): lookup structures with version-based logical flushing.
    val htVFp: LongArray?;   val htRFp: LongArray?
    val htVOff: IntArray?;   val htROff: IntArray?
    val htVVer: LongArray?;  val htRVer: LongArray?
    val spV: SplayTree<LongArray>?
    val spR: SplayTree<LongArray>?

    if (useSplay) {
        spV = SplayTree(); spR = SplayTree()
        htVFp = null; htRFp = null
        htVOff = null; htROff = null
        htVVer = null; htRVer = null
    } else {
        spV = null; spR = null
        htVFp  = LongArray(q);  htRFp  = LongArray(q)
        htVOff = IntArray(q);   htROff = IntArray(q)
        htVVer = LongArray(q) { -1L }; htRVer = LongArray(q) { -1L }
    }

    // Step (2): initialize scan pointers
    var ver    = 0L
    var rC     = 0
    var vC     = 0
    var vS     = 0
    var rhV    = if (v.size >= p) RollingHash(v, 0, p) else null
    var rhR    = if (r.size >= p) RollingHash(r, 0, p) else null
    var rhVPos = 0
    var rhRPos = 0

    while (vC + p <= v.size || rC + p <= r.size) {
        // Step (3): which streams still have seeds?
        val canV = vC + p <= v.size
        val canR = rC + p <= r.size

        var fpV = -1L; var hasFpV = false
        var fpR = -1L; var hasFpR = false

        if (canV && rhV != null) {
            val rh = rhV!!
            when {
                vC == rhVPos     -> Unit
                vC == rhVPos + 1 -> { rh.roll(v[vC - 1].toInt() and 0xFF, v[vC + p - 1].toInt() and 0xFF); rhVPos = vC }
                else             -> { rhV = RollingHash(v, vC, p); rhVPos = vC }
            }
            fpV = rhV!!.value; hasFpV = true
        }
        if (canR && rhR != null) {
            val rh = rhR!!
            when {
                rC == rhRPos     -> Unit
                rC == rhRPos + 1 -> { rh.roll(r[rC - 1].toInt() and 0xFF, r[rC + p - 1].toInt() and 0xFF); rhRPos = rC }
                else             -> { rhR = RollingHash(r, rC, p); rhRPos = rC }
            }
            fpR = rhR!!.value; hasFpR = true
        }

        // Step (4a): store offsets (retain-existing policy)
        if (hasFpV) {
            if (useSplay) {
                val existing = spV!!.find(fpV)
                if (existing == null || existing[1] != ver) spV.insert(fpV, longArrayOf(vC.toLong(), ver))
            } else {
                htPut(htVFp!!, htVOff!!, htVVer!!, fpV, vC, q, ver)
            }
        }
        if (hasFpR) {
            if (useSplay) {
                val existing = spR!!.find(fpR)
                if (existing == null || existing[1] != ver) spR.insert(fpR, longArrayOf(rC.toLong(), ver))
            } else {
                htPut(htRFp!!, htROff!!, htRVer!!, fpR, rC, q, ver)
            }
        }

        // Step (4b): look for a matching seed in the other table
        var matchFound = false
        var rM = 0; var vM = 0

        if (hasFpR) {
            val vCand: Int = if (useSplay) {
                val entry = spV!!.find(fpR)
                if (entry != null && entry[1] == ver) entry[0].toInt() else -1
            } else {
                htGet(htVFp!!, htVOff!!, htVVer!!, fpR, q, ver)
            }
            if (vCand >= 0 && r.regionEquals(rC, v, vCand, p)) {
                rM = rC; vM = vCand; matchFound = true
            }
        }
        if (!matchFound && hasFpV) {
            val rCand: Int = if (useSplay) {
                val entry = spR!!.find(fpV)
                if (entry != null && entry[1] == ver) entry[0].toInt() else -1
            } else {
                htGet(htRFp!!, htROff!!, htRVer!!, fpV, q, ver)
            }
            if (rCand >= 0 && v.regionEquals(vC, r, rCand, p)) {
                vM = vC; rM = rCand; matchFound = true
            }
        }

        if (!matchFound) { vC++; rC++; continue }

        // Step (5): extend match forward
        var ml = 0
        while (vM + ml < v.size && rM + ml < r.size && v[vM + ml] == r[rM + ml]) ml++

        if (ml < p) { vC++; rC++; continue }

        // Step (6): encode
        if (vS < vM) commands.add(Command.Add(v.copyOfRange(vS, vM)))
        commands.add(Command.Copy(rM, ml))
        vS = vM + ml

        // Step (7): advance pointers and flush tables
        vC = vM + ml
        rC = rM + ml
        ver++
    }

    // Step (8): trailing add
    if (vS < v.size) commands.add(Command.Add(v.copyOfRange(vS, v.size)))
    if (verbose) printStats(commands)
    return commands
}

/**
 * Store (fp, off) at slot fp%q, unless the slot already holds a value from
 * the current scan version (retain-existing policy: first entry wins).
 */
private fun htPut(fps: LongArray, offs: IntArray, vers: LongArray,
                  fp: Long, off: Int, q: Int, ver: Long) {
    val idx = (fp % q).toInt()
    if (vers[idx] == ver) return  // retain-existing
    fps[idx]  = fp
    offs[idx] = off
    vers[idx] = ver
}

/**
 * Return the offset stored for fp at slot fp%q if it belongs to the current
 * scan version, or -1 on a miss or stale entry.
 */
private fun htGet(fps: LongArray, offs: IntArray, vers: LongArray,
                  fp: Long, q: Int, ver: Long): Int {
    val idx = (fp % q).toInt()
    return if (vers[idx] == ver && fps[idx] == fp) offs[idx] else -1
}
