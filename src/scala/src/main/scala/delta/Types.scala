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

/** Algorithm output: copy from reference, or add literal bytes. */
sealed trait Command
object Command:
  case class Copy(offset: Int, length: Int) extends Command
  case class Add(data: Array[Byte]) extends Command

/** A command with explicit source and destination offsets. */
sealed trait PlacedCommand
object PlacedCommand:
  case class Copy(src: Int, dst: Int, length: Int) extends PlacedCommand
  case class Add(dst: Int, data: Array[Byte]) extends PlacedCommand

// ── Enums ──────────────────────────────────────────────────────────────────

enum Algorithm:
  case Greedy, Onepass, Correcting

enum CyclePolicy:
  case Localmin, Constant

// ── Options ────────────────────────────────────────────────────────────────

/** Options for differencing algorithms. All fields have sensible defaults. */
case class DiffOptions(
  p:        Int     = seedLen,
  q:        Int     = tableSize,
  bufCap:   Int     = deltaBufCap,
  verbose:  Boolean = false,
  useSplay: Boolean = false,
  maxTable: Int     = maxTableSize,
)

// ── Summary statistics ─────────────────────────────────────────────────────

case class PlacedSummary(
  numCommands:      Int,
  numCopies:        Int,
  numAdds:          Int,
  copyBytes:        Long,
  addBytes:         Long,
  totalOutputBytes: Long,
)
