package delta

import scala.collection.mutable

/**
 * Correcting 1.5-Pass algorithm (Section 7, Figure 8) with
 * fingerprint-based checkpointing (Section 8).
 *
 * |C| = q (hash table capacity, auto-sized from input).
 * |F| = next_prime(2 * num_R_seeds) (footprint universe, Section 8.1).
 * m  = ceil(|F| / |C|) (checkpoint spacing, p. 348).
 * k  = checkpoint class (Eq. 3, p. 348).
 */

private class BufEntry(val vStart: Int, var vEnd: Int, var cmd: Command, val dummy: Boolean)

def diffCorrecting(r: Array[Byte], v: Array[Byte], opts: DiffOptions): List[Command] = {
  val commands = mutable.ListBuffer[Command]()
  if v.isEmpty then return commands.toList

  val p        = opts.p
  val bufCap   = opts.bufCap
  val verbose  = opts.verbose
  val useSplay = opts.useSplay

  // ── Checkpointing parameters (Section 8.1, pp. 347-348) ─────────────────
  val numSeeds = if r.length >= p then r.length - p + 1 else 0
  val maxTbl   = if opts.maxTable > 0 then opts.maxTable else maxTableSize
  val cap: Int =
    if numSeeds > 0 then
      nextPrime(math.min(maxTbl.toLong, math.max(opts.q.toLong, 2L * numSeeds / p))).toInt
    else
      nextPrime(math.min(opts.q.toLong, maxTbl.toLong)).toInt
  val fSize: Long = if numSeeds > 0 then nextPrime(2L * numSeeds) else 1L
  val m: Long     = if fSize <= cap then 1L else (fSize + cap - 1) / cap
  val k: Long     =
    if v.length >= p then
      fingerprint(v, math.min(v.length / 2, v.length - p), p) % fSize % m
    else 0L

  if verbose then {
    val expected = if m > 0 then numSeeds / m else 0L
    val occEst   = if cap > 0 then expected * 100 / cap else 0L
    System.err.printf(
      "correcting: %s, |C|=%d |F|=%d m=%d k=%d%n" +
      "  checkpoint gap=%d bytes, expected fill ~%d (~%d%% table occupancy)%n",
      if useSplay then "splay tree" else "hash table", cap, fSize, m, k,
      m, expected, occEst)
  }

  // Step (1): Build lookup structure for R (first-found policy)
  val htFp:  Array[Long] = if !useSplay then Array.fill(cap)(-1L) else null
  val htOff: Array[Int]  = if !useSplay then new Array[Int](cap)  else null
  // splay value = Array[Long](2) = [full_fp, offset]
  val splayR: SplayTree[Array[Long]] = if useSplay then new SplayTree() else null

  var rhR: RollingHash = if numSeeds > 0 then new RollingHash(r, 0, p) else null
  var a = 0
  while a < numSeeds do {
    val fp: Long =
      if a == 0 then rhR.value
      else { rhR.roll(r(a - 1).toInt & 0xFF, r(a + p - 1).toInt & 0xFF); rhR.value }
    val f = fp % fSize
    if f % m == k then {
      if useSplay then {
        splayR.insertOrGet(fp, Array(fp, a.toLong))
      } else {
        var i = (f / m).toInt
        val i0 = i
        var loop = true
        while loop do {
          if htFp(i) == -1L then { loop = false }          // empty — store here
          else if htFp(i) == fp then { i = -1; loop = false } // dup fp — skip
          else {
            i += 1; if i == cap then { i = 0 }
            if i == i0 then { i = -1; loop = false }      // table full
          }
        }
        if i >= 0 then { htFp(i) = fp; htOff(i) = a }
      }
    }
    a += 1
  }

  // Encoding lookback buffer (Section 5.2)
  val buf = mutable.ArrayDeque[BufEntry]()

  // Step (2): initialize scan pointers
  var vC     = 0
  var vS     = 0
  var rhV    = if v.length >= p then new RollingHash(v, 0, p) else null
  var rhVPos = 0

  while vC + p <= v.length do {
    // Step (3): check for end of V
    val rh = rhV
    if rh == null then vC = v.length  // force exit
    else {
      // Step (4): fingerprint at vC, apply checkpoint test.
      val fpV: Long =
        if vC == rhVPos then rh.value
        else if vC == rhVPos + 1 then {
          rh.roll(v(vC - 1).toInt & 0xFF, v(vC + p - 1).toInt & 0xFF); rhVPos = vC; rh.value
        } else {
          rhV = new RollingHash(v, vC, p); rhVPos = vC; rhV.value
        }

      val fV = fpV % fSize
      if fV % m != k then vC += 1
      else {
        // Checkpoint passed — look up R.
        val lookupResult: Option[(Long, Int)] =
          if useSplay then {
            splayR.find(fpV) match {
              case Some(entry) => Some((entry(0), entry(1).toInt))
              case None        => None
            }
          } else {
            var i = (fV / m).toInt
            val i0 = i
            var found = -1
            var loop = true
            while loop do {
              if htFp(i) == -1L then { loop = false }      // empty — chain ends
              else if htFp(i) == fpV then { found = i; loop = false }
              else {
                i += 1; if i == cap then { i = 0 }
                if i == i0 then { loop = false }           // full table — not found
              }
            }
            if found < 0 then None else Some((htFp(found), htOff(found)))
          }

        lookupResult match {
          case None => vC += 1
          case Some((sfp, rOff)) =>
            if sfp != fpV || !r.regionEquals(rOff, v, vC, p) then vC += 1
            else {
              // Step (5): extend match forwards and backwards
              var fwd = p
              while vC + fwd < v.length && rOff + fwd < r.length && v(vC + fwd) == r(rOff + fwd) do fwd += 1
              var bwd = 0
              while vC >= bwd + 1 && rOff >= bwd + 1 && v(vC - bwd - 1) == r(rOff - bwd - 1) do bwd += 1

              val vM       = vC - bwd
              val rM       = rOff - bwd
              val ml       = bwd + fwd
              val matchEnd = vM + ml

              if ml < p then vC += 1
              else {
                // Step (6): encode with correction
                if vS <= vM then {
                  // (6a) match in unencoded suffix
                  if vS < vM then {
                    if buf.size >= bufCap then {
                      val oldest = buf.removeHead()
                      if !oldest.dummy then commands += oldest.cmd
                    }
                    buf += new BufEntry(vS, vM, Command.Add(v.slice(vS, vM)), false)
                  }
                  if buf.size >= bufCap then {
                    val oldest = buf.removeHead()
                    if !oldest.dummy then commands += oldest.cmd
                  }
                  buf += new BufEntry(vM, matchEnd, Command.Copy(rM, ml), false)
                  vS = matchEnd
                } else {
                  // (6b) tail correction (Section 5.1, p. 339)
                  var effectiveStart = vS
                  var correcting = true

                  while correcting && buf.nonEmpty do {
                    val tail = buf.last
                    if tail.dummy then buf.removeLast()
                    else if tail.vStart >= vM && tail.vEnd <= matchEnd then {
                      effectiveStart = math.min(effectiveStart, tail.vStart)
                      buf.removeLast()
                    } else if tail.vEnd > vM && tail.vStart < vM then {
                      tail.cmd match {
                      case _: Command.Add =>
                        val keep = vM - tail.vStart
                        if keep > 0 then {
                          tail.cmd  = Command.Add(v.slice(tail.vStart, vM))
                          tail.vEnd = vM
                        } else {
                          buf.removeLast()
                        }
                        effectiveStart = math.min(effectiveStart, vM)
                      case _ =>
                    }
                      correcting = false
                    } else correcting = false
                  }

                  val adj    = effectiveStart - vM
                  val newLen = matchEnd - effectiveStart
                  if newLen > 0 then {
                    if buf.size >= bufCap then {
                      val oldest = buf.removeHead()
                      if !oldest.dummy then commands += oldest.cmd
                    }
                    buf += new BufEntry(effectiveStart, matchEnd, Command.Copy(rM + adj, newLen), false)
                  }
                  vS = matchEnd
                }

                // Step (7): advance past matched region
                vC = matchEnd
              }
            }
        }
      }
    }
  }

  // Step (8): flush buffer and trailing add
  for entry <- buf do if !entry.dummy then commands += entry.cmd
  if vS < v.length then commands += Command.Add(v.slice(vS, v.length))

  if verbose then printStats(commands.toList)
  commands.toList
}
