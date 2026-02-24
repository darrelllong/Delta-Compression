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
def diffOnepass(r: Array[Byte], v: Array[Byte], opts: DiffOptions): List[Command] = {
  val commands = scala.collection.mutable.ListBuffer[Command]()
  if v.isEmpty then return commands.toList

  val p        = opts.p
  val useSplay = opts.useSplay
  val verbose  = opts.verbose

  // Auto-size hash table: one slot per p-byte chunk of R (floor = q).
  val numSeeds = if r.length >= p then r.length - p + 1 else 0
  val q        = nextPrime(math.max(opts.q.toLong, (numSeeds / p).toLong)).toInt

  if verbose then
    System.err.printf("onepass: %s, q=%d, |R|=%d, |V|=%d, seed_len=%d%n",
      if useSplay then "splay tree" else "hash table", q, r.length, v.length, p)

  // Step (1): lookup structures with version-based logical flushing.
  val htVFp  = if !useSplay then new Array[Long](q) else null
  val htRFp  = if !useSplay then new Array[Long](q) else null
  val htVOff = if !useSplay then new Array[Int](q)  else null
  val htROff = if !useSplay then new Array[Int](q)  else null
  val htVVer = if !useSplay then Array.fill(q)(-1L) else null
  val htRVer = if !useSplay then Array.fill(q)(-1L) else null

  // Splay trees store Array[Long](2) = [offset, version]
  val spV: SplayTree[Array[Long]] = if useSplay then new SplayTree() else null
  val spR: SplayTree[Array[Long]] = if useSplay then new SplayTree() else null

  // Step (2): initialize scan pointers
  var ver    = 0L
  var rC     = 0
  var vC     = 0
  var vS     = 0
  var rhV    = if v.length >= p then new RollingHash(v, 0, p) else null
  var rhR    = if r.length >= p then new RollingHash(r, 0, p) else null
  var rhVPos = 0
  var rhRPos = 0

  var running = true
  while running do {
    // Step (3): check for end of V and R
    val canV = vC + p <= v.length
    val canR = rC + p <= r.length
    if !canV && !canR then running = false
    else {
      var fpV = -1L; var hasFpV = false
      var fpR = -1L; var hasFpR = false

      if canV && rhV != null then {
        val rh = rhV
        if vC == rhVPos then ()
        else if vC == rhVPos + 1 then {
          rh.roll(v(vC - 1).toInt & 0xFF, v(vC + p - 1).toInt & 0xFF); rhVPos = vC
        } else {
          rhV = new RollingHash(v, vC, p); rhVPos = vC
        }
        fpV = rhV.value; hasFpV = true
      }
      if canR && rhR != null then {
        val rh = rhR
        if rC == rhRPos then ()
        else if rC == rhRPos + 1 then {
          rh.roll(r(rC - 1).toInt & 0xFF, r(rC + p - 1).toInt & 0xFF); rhRPos = rC
        } else {
          rhR = new RollingHash(r, rC, p); rhRPos = rC
        }
        fpR = rhR.value; hasFpR = true
      }

      // Step (4a): store offsets (retain-existing policy)
      if hasFpV then {
        if useSplay then {
          val existing = spV.find(fpV)
          if existing.isEmpty || existing.get(1) != ver then spV.insert(fpV, Array(vC.toLong, ver))
        } else {
          htPut(htVFp, htVOff, htVVer, fpV, vC, q, ver)
        }
      }
      if hasFpR then {
        if useSplay then {
          val existing = spR.find(fpR)
          if existing.isEmpty || existing.get(1) != ver then spR.insert(fpR, Array(rC.toLong, ver))
        } else {
          htPut(htRFp, htROff, htRVer, fpR, rC, q, ver)
        }
      }

      // Step (4b): look for a matching seed in the other table
      var matchFound = false
      var rM = 0; var vM = 0

      if hasFpR then {
        val vCand: Int =
          if useSplay then {
            val entry = spV.find(fpR)
            if entry.nonEmpty && entry.get(1) == ver then entry.get(0).toInt else -1
          } else {
            htGet(htVFp, htVOff, htVVer, fpR, q, ver)
          }
        if vCand >= 0 && r.regionEquals(rC, v, vCand, p) then {
          rM = rC; vM = vCand; matchFound = true
        }
      }
      if !matchFound && hasFpV then {
        val rCand: Int =
          if useSplay then {
            val entry = spR.find(fpV)
            if entry.nonEmpty && entry.get(1) == ver then entry.get(0).toInt else -1
          } else {
            htGet(htRFp, htROff, htRVer, fpV, q, ver)
          }
        if rCand >= 0 && v.regionEquals(vC, r, rCand, p) then {
          vM = vC; rM = rCand; matchFound = true
        }
      }

      if !matchFound then { vC += 1; rC += 1 }
      else {
        // Step (5): extend match forward
        var ml = 0
        while vM + ml < v.length && rM + ml < r.length && v(vM + ml) == r(rM + ml) do ml += 1

        if ml < p then { vC += 1; rC += 1 }
        else {
          // Step (6): encode
          if vS < vM then commands += Command.Add(v.slice(vS, vM))
          commands += Command.Copy(rM, ml)
          vS = vM + ml

          // Step (7): advance pointers and flush tables
          vC = vM + ml
          rC = rM + ml
          ver += 1
        }
      }
    }
  }

  // Step (8): trailing add
  if vS < v.length then commands += Command.Add(v.slice(vS, v.length))
  if verbose then printStats(commands.toList)
  commands.toList
}

private def htPut(fps: Array[Long], offs: Array[Int], vers: Array[Long],
                  fp: Long, off: Int, q: Int, ver: Long): Unit = {
  val idx = (fp % q).toInt
  if vers(idx) == ver then return  // retain-existing
  fps(idx)  = fp
  offs(idx) = off
  vers(idx) = ver
}

private def htGet(fps: Array[Long], offs: Array[Int], vers: Array[Long],
                  fp: Long, q: Int, ver: Long): Int = {
  val idx = (fp % q).toInt
  if vers(idx) == ver && fps(idx) == fp then offs(idx) else -1
}
