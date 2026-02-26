package delta

// ── Constants (Ajtai, Burns, Fagin, Long — JACM 2002) ─────────────────────
//
// Hash parameters (Section 2.1.3):
//   p (seedLen)   = minimum match length / fingerprint window
//   b (hashBase)  = polynomial base for Karp-Rabin hash
//   Q (hashMod)   = Mersenne prime 2^61-1 for fingerprint arithmetic
//   q (tableSize) = hash table capacity
// ─────────────────────────────────────────────────────────────────────────────

val seedLen      = 16
val tableSize    = 1_048_573       // largest prime < 2^20
val maxTableSize = 1_073_741_827   // prime near 2^30; auto-sizing ceiling
val hashBase     = 263L
val hashMod      = (1L << 61) - 1L // Mersenne prime 2^61-1

// Binary delta format
val deltaMagic       = Array[Byte]('D'.toByte, 'L'.toByte, 'T'.toByte, 0x03)
val deltaFlagInplace: Byte = 0x01
val deltaCmdEnd      = 0
val deltaCmdCopy     = 1
val deltaCmdAdd      = 2
val deltaCrcSize     = 8      // CRC-64/XZ digest bytes
val deltaHeaderSize  = 25     // magic(4)+flags(1)+version_size(4)+src_crc(8)+dst_crc(8)
val deltaU32Size     = 4
val deltaCopyPayload = 12     // src(4) + dst(4) + len(4)
val deltaAddHeader   = 8      // dst(4) + len(4)
val deltaBufCap      = 256

// ── Delta commands (Section 2.1.1) ─────────────────────────────────────────

/**
 * Algorithm-level command, as produced by the diff algorithms.
 *
 * Offsets are positions in R or V at the time of the diff scan; destinations
 * are not yet assigned.  Call placeCommands (or makeInplace) to get
 * PlacedCommands ready for encoding and application.
 */
sealed trait Command
object Command:
  /** Copy length bytes starting at offset in the reference R. */
  case class Copy(offset: Int, length: Int) extends Command
  /** Append literal bytes from V that could not be matched in R. */
  case class Add(data: Array[Byte]) extends Command

/**
 * A command with explicit source and destination byte offsets (Section 2.1.1).
 *
 * Produced by placeCommands or makeInplace; required for delta encoding and
 * for in-place or standard application.
 */
sealed trait PlacedCommand
object PlacedCommand:
  /** Copy length bytes from src in R (or the working buffer) to dst in the output. */
  case class Copy(src: Int, dst: Int, length: Int) extends PlacedCommand
  /** Write literal bytes to dst in the output. */
  case class Add(dst: Int, data: Array[Byte]) extends PlacedCommand

// ── Enums ──────────────────────────────────────────────────────────────────

/** Differencing algorithm selection. */
enum Algorithm:
  /** Optimal under simple cost; O(|V|·|R|) time, O(|R|) space (Section 3). */
  case Greedy
  /** Linear time and near-constant space; concurrent scan of R and V (Section 4). */
  case Onepass
  /** Near-optimal, 1.5-pass; hash table with fingerprint checkpointing (Sections 7–8). */
  case Correcting

/** Cycle-breaking policy for in-place reordering (Section 4.3 of Burns et al. 2003). */
enum CyclePolicy:
  /** Break each cycle at the copy with the shortest length, minimising literal bytes added. */
  case Localmin
  /** Break each cycle at the first remaining vertex; simpler but ignores copy lengths. */
  case Constant

// ── Options ────────────────────────────────────────────────────────────────

/**
 * Tuning parameters for differencing algorithms.
 *
 * @param p        Seed length: minimum match length and fingerprint window (Section 2.1.3).
 * @param q        Hash table capacity floor; algorithms auto-size upward from input length.
 * @param bufCap   Lookback buffer depth for the correcting algorithm (Section 5.2).
 * @param verbose  Print per-run statistics (table size, timing, copy coverage) to stderr.
 * @param useSplay Use a Sleator-Tarjan splay tree instead of a hash table for R lookups.
 * @param maxTable Auto-sizing ceiling; prevents unbounded memory use on very large inputs.
 */
case class DiffOptions(
  p:        Int     = seedLen,
  q:        Int     = tableSize,
  bufCap:   Int     = deltaBufCap,
  verbose:  Boolean = false,
  useSplay: Boolean = false,
  maxTable: Int     = maxTableSize,
)

// ── Summary statistics ─────────────────────────────────────────────────────

/**
 * Summary statistics for a set of placed commands.
 *
 * @param numCommands      Total number of commands (copies + adds).
 * @param numCopies        Number of COPY commands.
 * @param numAdds          Number of ADD commands.
 * @param copyBytes        Total bytes reproduced by COPY commands.
 * @param addBytes         Total literal bytes in ADD commands.
 * @param totalOutputBytes Reconstructed output size (= copyBytes + addBytes).
 */
case class PlacedSummary(
  numCommands:      Int,
  numCopies:        Int,
  numAdds:          Int,
  copyBytes:        Long,
  addBytes:         Long,
  totalOutputBytes: Long,
)
