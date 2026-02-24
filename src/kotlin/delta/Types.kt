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
val DELTA_MAGIC = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 0x03)
const val DELTA_FLAG_INPLACE: Byte = 0x01
const val DELTA_CMD_END    = 0
const val DELTA_CMD_COPY   = 1
const val DELTA_CMD_ADD    = 2
const val DELTA_CRC_SIZE   = 8       // CRC-64/XZ digest bytes
const val DELTA_HEADER_SIZE = 25     // magic(4)+flags(1)+version_size(4)+src_crc(8)+dst_crc(8)
const val DELTA_U32_SIZE   = 4
const val DELTA_COPY_PAYLOAD = 12    // src(4) + dst(4) + len(4)
const val DELTA_ADD_HEADER  = 8      // dst(4) + len(4)
const val DELTA_BUF_CAP     = 256

// ── Delta commands (Section 2.1.1) ─────────────────────────────────────────

/** Algorithm output: copy from reference, or add literal bytes. */
sealed class Command {
    data class Copy(val offset: Int, val length: Int) : Command()
    class Add(val data: ByteArray) : Command()
}

/** A command with explicit source and destination offsets. */
sealed class PlacedCommand {
    data class Copy(val src: Int, val dst: Int, val length: Int) : PlacedCommand()
    class Add(val dst: Int, val data: ByteArray) : PlacedCommand()
}

// ── Enums ──────────────────────────────────────────────────────────────────

enum class Algorithm { GREEDY, ONEPASS, CORRECTING }

enum class CyclePolicy { LOCALMIN, CONSTANT }

// ── Options ────────────────────────────────────────────────────────────────

/** Options for differencing algorithms. All fields have sensible defaults. */
data class DiffOptions(
    val p:        Int     = SEED_LEN,
    val q:        Int     = TABLE_SIZE,
    val bufCap:   Int     = DELTA_BUF_CAP,
    val verbose:  Boolean = false,
    val useSplay: Boolean = false,
    val maxTable: Int     = MAX_TABLE_SIZE,
)

// ── Summary statistics ─────────────────────────────────────────────────────

data class PlacedSummary(
    val numCommands:      Int,
    val numCopies:        Int,
    val numAdds:          Int,
    val copyBytes:        Long,
    val addBytes:         Long,
    val totalOutputBytes: Long,
)
