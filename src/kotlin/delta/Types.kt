package delta

// ── Constants (Ajtai, Burns, Fagin, Long — JACM 2002) ─────────────────────
//
// Hash parameters (Section 2.1.3):
//   p (SEED_LEN)  = minimum match length / fingerprint window
//   b (HASH_BASE) = polynomial base for Karp-Rabin hash
//   Q (HASH_MOD)  = Mersenne prime 2^61-1 for fingerprint arithmetic
//   q (TABLE_SIZE) = hash table capacity
// ─────────────────────────────────────────────────────────────────────────────

const val SEED_LEN         = 16
const val TABLE_SIZE       = 1_048_573       // largest prime < 2^20
const val MAX_TABLE_SIZE   = 1_073_741_827   // prime near 2^30; auto-sizing ceiling
const val HASH_BASE        = 263L
const val HASH_MOD         = (1L shl 61) - 1L  // Mersenne prime 2^61-1

// Binary delta format
val DELTA_MAGIC       = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 0x03)
val DELTA_MAGIC_LARGE = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 0x04)
const val DELTA_FLAG_INPLACE: Byte = 0x01
const val DELTA_CMD_END     = 0
const val DELTA_CMD_COPY    = 1
const val DELTA_CMD_ADD     = 2
const val DELTA_CMD_BIGCOPY = 3 // DLT\x04: COPY with u64 fields
const val DELTA_CMD_BIGADD  = 4 // DLT\x04: ADD with u64 dst/len header
const val DELTA_CMD_MOVE    = 5 // DLT\x04: copy from already-written output (u32)
const val DELTA_CMD_BIGMOVE = 6 // DLT\x04: MOVE with u64 fields
const val DELTA_CRC_SIZE          = 8   // CRC-64/XZ digest bytes
const val DELTA_HEADER_SIZE       = 25  // magic(4)+flags(1)+version_size(4)+crcs(16)
const val DELTA_HEADER_SIZE_LARGE = 29  // magic(4)+flags(1)+version_size(8)+crcs(16)
const val DELTA_U32_SIZE          = 4
const val DELTA_U64_SIZE          = 8
const val DELTA_COPY_PAYLOAD      = 12  // src(4)+dst(4)+len(4)
const val DELTA_ADD_HEADER        = 8   // dst(4)+len(4)
const val DELTA_BIGCOPY_PAYLOAD   = 24  // src(8)+dst(8)+len(8)
const val DELTA_BIGADD_HEADER     = 16  // dst(8)+len(8)
const val DELTA_BUF_CAP           = 256

// ── Delta commands (Section 2.1.1) ─────────────────────────────────────────

/**
 * Algorithm-level command, as produced by the diff algorithms.
 *
 * Offsets are positions in R or V at the time of the diff scan; destinations
 * are not yet assigned.  Call placeCommands (or makeInplace) to get
 * PlacedCommands ready for encoding and application.
 */
sealed class Command {
    /** Copy [length] bytes starting at [offset] in the reference R. */
    data class Copy(val offset: Int, val length: Int) : Command()
    /** Append literal bytes from V that could not be matched in R. */
    class Add(val data: ByteArray) : Command()
}

/**
 * A command with explicit source and destination byte offsets (Section 2.1.1).
 *
 * Produced by placeCommands or makeInplace; required for delta encoding and
 * for in-place or standard application.
 */
sealed class PlacedCommand {
    /** Copy [length] bytes from [src] in R (or working buffer) to [dst] in output. */
    data class Copy(val src: Int, val dst: Int, val length: Int) : PlacedCommand()
    /** Write literal bytes to [dst] in the output. */
    class Add(val dst: Int, val data: ByteArray) : PlacedCommand()
    /**
     * Copy [length] bytes from [src] in the already-written output to [dst].
     * The encoder guarantees src+length <= dst (source fully written before it is read).
     * Only valid in DLT\x04 format; use encodeDeltaLarge to encode Move commands.
     */
    data class Move(val src: Int, val dst: Int, val length: Int) : PlacedCommand()
}

// ── Enums ──────────────────────────────────────────────────────────────────

/** Differencing algorithm selection. */
enum class Algorithm {
    /** Optimal under simple cost; O(|V|·|R|) time, O(|R|) space (Section 3). */
    GREEDY,
    /** Linear time and near-constant space; concurrent scan of R and V (Section 4). */
    ONEPASS,
    /** Near-optimal, 1.5-pass; hash table with fingerprint checkpointing (Sections 7–8). */
    CORRECTING
}

/** Cycle-breaking policy for in-place reordering (Section 4.3 of Burns et al. 2003). */
enum class CyclePolicy {
    /** Break each cycle at the copy with the shortest length, minimising literal bytes added. */
    LOCALMIN,
    /** Break each cycle at the first remaining vertex; simpler but ignores copy lengths. */
    CONSTANT
}

// ── Options ────────────────────────────────────────────────────────────────

/**
 * Tuning parameters for differencing algorithms.
 *
 * @param p        Seed length: minimum match length and fingerprint window (Section 2.1.3).
 * @param q        Hash table capacity floor; algorithms auto-size upward from input length.
 * @param bufCap   Lookback buffer depth for the correcting algorithm (Section 5.2).
 * @param verbose  Print per-run statistics to stderr when true.
 * @param useSplay Use a Sleator-Tarjan splay tree instead of a hash table for R lookups.
 * @param maxTable Auto-sizing ceiling; prevents unbounded memory use on very large inputs.
 */
data class DiffOptions(
    val p:        Int     = SEED_LEN,
    val q:        Int     = TABLE_SIZE,
    val bufCap:   Int     = DELTA_BUF_CAP,
    val verbose:  Boolean = false,
    val useSplay: Boolean = false,
    val maxTable: Int     = MAX_TABLE_SIZE,
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
data class PlacedSummary(
    val numCommands:      Int,
    val numCopies:        Int,
    val numAdds:          Int,
    val copyBytes:        Long,
    val addBytes:         Long,
    val totalOutputBytes: Long,
)
