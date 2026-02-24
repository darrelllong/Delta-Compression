package delta

import scala.collection.mutable

/**
 * Greedy algorithm (Section 3.1, Figure 2).
 *
 * Finds an optimal delta encoding under the simple cost measure
 * (optimality proof: Section 3.3, Theorem 1).
 * Time: O(|V| * |R|) worst case. Space: O(|R|).
 */
def diffGreedy(r: Array[Byte], v: Array[Byte], opts: DiffOptions): List[Command] = {
  val commands = mutable.ListBuffer[Command]()
  if v.isEmpty then return commands.toList

  val p        = opts.p
  val verbose  = opts.verbose
  val useSplay = opts.useSplay

  // Step (1): build lookup structure for R keyed by full fingerprint.
  val hrHt: mutable.HashMap[Long, mutable.ArrayBuffer[Int]] =
    if !useSplay then mutable.HashMap() else null
  val hrSp: SplayTree[mutable.ArrayBuffer[Int]] =
    if useSplay then new SplayTree() else null

  if r.length >= p then {
    val rh = new RollingHash(r, 0, p)
    if useSplay then hrSp.insertOrGet(rh.value, mutable.ArrayBuffer.empty) += 0
    else hrHt.getOrElseUpdate(rh.value, mutable.ArrayBuffer.empty) += 0
    var a = 1
    while a <= r.length - p do {
      rh.roll(r(a - 1).toInt & 0xFF, r(a + p - 1).toInt & 0xFF)
      if useSplay then hrSp.insertOrGet(rh.value, mutable.ArrayBuffer.empty) += a
      else hrHt.getOrElseUpdate(rh.value, mutable.ArrayBuffer.empty) += a
      a += 1
    }
  }

  if verbose then
    System.err.printf("greedy: %s, |R|=%d, |V|=%d, seed_len=%d%n",
      if useSplay then "splay tree" else "hash table", r.length, v.length, p)

  // Step (2): initialize scan pointers
  var vC     = 0
  var vS     = 0
  var rhV    = if v.length >= p then new RollingHash(v, 0, p) else null
  var rhVPos = 0

  while vC + p <= v.length do {
    // Step (3): compute fingerprint at vC
    val rh = rhV
    if rh == null then vC = v.length  // force exit
    else {
      val fpV: Long =
        if vC == rhVPos then rh.value
        else if vC == rhVPos + 1 then {
          rh.roll(v(vC - 1).toInt & 0xFF, v(vC + p - 1).toInt & 0xFF)
          rhVPos = vC
          rh.value
        } else {
          rhV = new RollingHash(v, vC, p)
          rhVPos = vC
          rhV.value
        }

      // Steps (4)+(5): find the longest matching substring
      var bestRm  = -1
      var bestLen = 0
      val offsets: mutable.ArrayBuffer[Int] =
        if useSplay then hrSp.find(fpV).orNull
        else hrHt.getOrElse(fpV, null)

      if offsets != null then {
        var oi = 0
        while oi < offsets.length do {
          val rCand = offsets(oi)
          if r.regionEquals(rCand, v, vC, p) then {
            var ml = p
            while vC + ml < v.length && rCand + ml < r.length && v(vC + ml) == r(rCand + ml) do ml += 1
            if ml > bestLen then { bestLen = ml; bestRm = rCand }
          }
          oi += 1
        }
      }

      if bestLen < p then vC += 1
      else {
        // Step (6): encode
        if vS < vC then commands += Command.Add(java.util.Arrays.copyOfRange(v, vS, vC))
        commands += Command.Copy(bestRm, bestLen)
        vS = vC + bestLen
        // Step (7): advance past matched region
        vC += bestLen
      }
    }
  }

  // Step (8): trailing add
  if vS < v.length then commands += Command.Add(java.util.Arrays.copyOfRange(v, vS, v.length))
  if verbose then printStats(commands.toList)
  commands.toList
}
