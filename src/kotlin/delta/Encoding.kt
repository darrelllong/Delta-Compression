package delta

/**
 * Binary delta format encode/decode (DLT\x03 small and DLT\x04 large).
 *
 * DLT\x03 (small) — 25-byte header, u32 fields, COPY/ADD only:
 *   magic(4)+flags(1)+version_size(u32 BE)+src_crc(8)+dst_crc(8)
 *
 * DLT\x04 (large) — 29-byte header, u64 fields, adds BIGCOPY/BIGADD/MOVE/BIGMOVE:
 *   magic(4)+flags(1)+version_size(u64 BE)+src_crc(8)+dst_crc(8)
 */

/**
 * Decoded delta file content.
 *
 * @param commands    The placed commands to execute during apply.
 * @param inplace     True if the delta uses the in-place format.
 * @param versionSize Byte length of the reconstructed version.
 * @param srcCrc      CRC-64/XZ of the reference (8 bytes big-endian).
 * @param dstCrc      CRC-64/XZ of the version (8 bytes big-endian).
 */
class DecodeResult(
    val commands: List<PlacedCommand>,
    val inplace: Boolean,
    val versionSize: Int,
    val srcCrc: ByteArray,
    val dstCrc: ByteArray,
)

/**
 * Encode placed commands to DLT\x03 format (u32 fields, max 4 GiB).
 * Throws if a PlacedCommand.Move is present.
 * Use [encodeDeltaLarge] for DLT\x04 (u64 fields, Move support).
 */
fun encodeDelta(commands: List<PlacedCommand>, inplace: Boolean,
                versionSize: Int, srcCrc: ByteArray, dstCrc: ByteArray): ByteArray {
    var est = DELTA_HEADER_SIZE + 1
    for (cmd in commands) {
        est += when (cmd) {
            is PlacedCommand.Copy -> 1 + DELTA_COPY_PAYLOAD
            is PlacedCommand.Add  -> 1 + DELTA_ADD_HEADER + cmd.data.size
            is PlacedCommand.Move -> throw IllegalArgumentException(
                "PlacedCommand.Move requires DLT\\x04 format; use encodeDeltaLarge")
        }
    }
    val out = ByteArray(est)
    var pos = 0

    DELTA_MAGIC.copyInto(out, pos); pos += DELTA_MAGIC.size
    out[pos++] = if (inplace) DELTA_FLAG_INPLACE else 0
    putU32BE(out, pos, versionSize); pos += DELTA_U32_SIZE
    srcCrc.copyInto(out, pos); pos += DELTA_CRC_SIZE
    dstCrc.copyInto(out, pos); pos += DELTA_CRC_SIZE

    for (cmd in commands) {
        when (cmd) {
            is PlacedCommand.Copy -> {
                out[pos++] = DELTA_CMD_COPY.toByte()
                putU32BE(out, pos, cmd.src);    pos += DELTA_U32_SIZE
                putU32BE(out, pos, cmd.dst);    pos += DELTA_U32_SIZE
                putU32BE(out, pos, cmd.length); pos += DELTA_U32_SIZE
            }
            is PlacedCommand.Add -> {
                out[pos++] = DELTA_CMD_ADD.toByte()
                putU32BE(out, pos, cmd.dst);       pos += DELTA_U32_SIZE
                putU32BE(out, pos, cmd.data.size); pos += DELTA_U32_SIZE
                cmd.data.copyInto(out, pos);       pos += cmd.data.size
            }
            is PlacedCommand.Move -> throw IllegalArgumentException(
                "PlacedCommand.Move requires DLT\\x04 format; use encodeDeltaLarge")
        }
    }

    out[pos++] = DELTA_CMD_END.toByte()
    return if (pos != out.size) out.copyOf(pos) else out
}

/**
 * Encode placed commands to DLT\x04 format (u64 fields, Move support).
 * Kotlin/JVM Int fields always fit in u32, so COPY/ADD/MOVE variants are emitted.
 */
fun encodeDeltaLarge(commands: List<PlacedCommand>, inplace: Boolean,
                     versionSize: Int, srcCrc: ByteArray, dstCrc: ByteArray): ByteArray {
    var est = DELTA_HEADER_SIZE_LARGE + 1
    for (cmd in commands) {
        est += when (cmd) {
            is PlacedCommand.Copy -> 1 + DELTA_COPY_PAYLOAD
            is PlacedCommand.Add  -> 1 + DELTA_ADD_HEADER + cmd.data.size
            is PlacedCommand.Move -> 1 + DELTA_COPY_PAYLOAD
        }
    }
    val out = ByteArray(est)
    var pos = 0

    DELTA_MAGIC_LARGE.copyInto(out, pos); pos += DELTA_MAGIC_LARGE.size
    out[pos++] = if (inplace) DELTA_FLAG_INPLACE else 0
    putU64BE(out, pos, Integer.toUnsignedLong(versionSize)); pos += DELTA_U64_SIZE
    srcCrc.copyInto(out, pos); pos += DELTA_CRC_SIZE
    dstCrc.copyInto(out, pos); pos += DELTA_CRC_SIZE

    for (cmd in commands) {
        when (cmd) {
            is PlacedCommand.Copy -> {
                // Kotlin/JVM Int ≤ Integer.MAX_VALUE ≤ UINT32_MAX; always emit COPY form
                out[pos++] = DELTA_CMD_COPY.toByte()
                putU32BE(out, pos, cmd.src);    pos += DELTA_U32_SIZE
                putU32BE(out, pos, cmd.dst);    pos += DELTA_U32_SIZE
                putU32BE(out, pos, cmd.length); pos += DELTA_U32_SIZE
            }
            is PlacedCommand.Add -> {
                out[pos++] = DELTA_CMD_ADD.toByte()
                putU32BE(out, pos, cmd.dst);       pos += DELTA_U32_SIZE
                putU32BE(out, pos, cmd.data.size); pos += DELTA_U32_SIZE
                cmd.data.copyInto(out, pos);       pos += cmd.data.size
            }
            is PlacedCommand.Move -> {
                out[pos++] = DELTA_CMD_MOVE.toByte()
                putU32BE(out, pos, cmd.src);    pos += DELTA_U32_SIZE
                putU32BE(out, pos, cmd.dst);    pos += DELTA_U32_SIZE
                putU32BE(out, pos, cmd.length); pos += DELTA_U32_SIZE
            }
        }
    }

    out[pos++] = DELTA_CMD_END.toByte()
    return if (pos != out.size) out.copyOf(pos) else out
}

/**
 * Decode DLT\x03 or DLT\x04 format. Dispatches on magic bytes.
 * CRC validation is the caller's responsibility.
 */
fun decodeDelta(data: ByteArray): DecodeResult {
    if (data.size < DELTA_MAGIC.size) throw IllegalArgumentException("not a delta file")
    return when {
        matchesMagic(data, DELTA_MAGIC)       -> decodeSmall(data)
        matchesMagic(data, DELTA_MAGIC_LARGE) -> decodeLarge(data)
        else -> throw IllegalArgumentException("not a delta file")
    }
}

/** Check if binary data is an in-place delta (DLT\x03 or DLT\x04). */
fun isInplaceDelta(data: ByteArray): Boolean {
    if (data.size < DELTA_MAGIC.size + 1) return false
    return (matchesMagic(data, DELTA_MAGIC) || matchesMagic(data, DELTA_MAGIC_LARGE)) &&
        (data[DELTA_MAGIC.size].toInt() and DELTA_FLAG_INPLACE.toInt()) != 0
}

// ── Private decoders ──────────────────────────────────────────────────────

private fun decodeSmall(data: ByteArray): DecodeResult {
    if (data.size < DELTA_HEADER_SIZE) throw IllegalArgumentException("not a delta file")

    val inplace     = (data[DELTA_MAGIC.size].toInt() and DELTA_FLAG_INPLACE.toInt()) != 0
    val versionSize = getU32BE(data, DELTA_MAGIC.size + 1)
    val crcOff      = DELTA_MAGIC.size + 1 + DELTA_U32_SIZE
    val srcCrc      = data.copyOfRange(crcOff, crcOff + DELTA_CRC_SIZE)
    val dstCrc      = data.copyOfRange(crcOff + DELTA_CRC_SIZE, crcOff + 2 * DELTA_CRC_SIZE)
    var pos         = DELTA_HEADER_SIZE
    val commands    = mutableListOf<PlacedCommand>()
    var sawEnd      = false

    while (pos < data.size && !sawEnd) {
        val t = data[pos++].toInt() and 0xFF
        if (t == DELTA_CMD_END) { sawEnd = true; continue }
        when (t) {
            DELTA_CMD_COPY -> {
                if (pos + DELTA_COPY_PAYLOAD > data.size) throw IllegalArgumentException("unexpected EOF")
                val src = getU32BE(data, pos); pos += DELTA_U32_SIZE
                val dst = getU32BE(data, pos); pos += DELTA_U32_SIZE
                val len = getU32BE(data, pos); pos += DELTA_U32_SIZE
                validatePlacedRange(dst, len, versionSize, "COPY")
                commands.add(PlacedCommand.Copy(src, dst, len))
            }
            DELTA_CMD_ADD -> {
                if (pos + DELTA_ADD_HEADER > data.size) throw IllegalArgumentException("unexpected EOF")
                val dst = getU32BE(data, pos); pos += DELTA_U32_SIZE
                val len = getU32BE(data, pos); pos += DELTA_U32_SIZE
                if (pos + len > data.size) throw IllegalArgumentException("unexpected EOF")
                validatePlacedRange(dst, len, versionSize, "ADD")
                val payload = data.copyOfRange(pos, pos + len); pos += len
                commands.add(PlacedCommand.Add(dst, payload))
            }
            DELTA_CMD_BIGCOPY, DELTA_CMD_BIGADD, DELTA_CMD_MOVE, DELTA_CMD_BIGMOVE ->
                throw IllegalArgumentException("command type $t requires DLT\\x04 format")
            else -> throw IllegalArgumentException("unknown command type: $t")
        }
    }
    if (!sawEnd) throw IllegalArgumentException("missing END command")
    if (pos != data.size) throw IllegalArgumentException("trailing data after END")
    return DecodeResult(commands, inplace, versionSize, srcCrc, dstCrc)
}

private fun decodeLarge(data: ByteArray): DecodeResult {
    if (data.size < DELTA_HEADER_SIZE_LARGE) throw IllegalArgumentException("not a delta file")

    val inplace     = (data[DELTA_MAGIC.size].toInt() and DELTA_FLAG_INPLACE.toInt()) != 0
    val vsLong      = getU64Long(data, DELTA_MAGIC.size + 1)
    val versionSize = checkFitsInt(vsLong, "version_size")
    val crcOff      = DELTA_MAGIC.size + 1 + DELTA_U64_SIZE
    val srcCrc      = data.copyOfRange(crcOff, crcOff + DELTA_CRC_SIZE)
    val dstCrc      = data.copyOfRange(crcOff + DELTA_CRC_SIZE, crcOff + 2 * DELTA_CRC_SIZE)
    var pos         = DELTA_HEADER_SIZE_LARGE
    val commands    = mutableListOf<PlacedCommand>()
    var sawEnd      = false

    while (pos < data.size && !sawEnd) {
        val t = data[pos++].toInt() and 0xFF
        if (t == DELTA_CMD_END) { sawEnd = true; continue }
        when (t) {
            DELTA_CMD_COPY -> {
                if (pos + DELTA_COPY_PAYLOAD > data.size) throw IllegalArgumentException("unexpected EOF")
                val src = getU32BE(data, pos); pos += DELTA_U32_SIZE
                val dst = getU32BE(data, pos); pos += DELTA_U32_SIZE
                val len = getU32BE(data, pos); pos += DELTA_U32_SIZE
                validatePlacedRange(dst, len, versionSize, "COPY")
                commands.add(PlacedCommand.Copy(src, dst, len))
            }
            DELTA_CMD_ADD -> {
                if (pos + DELTA_ADD_HEADER > data.size) throw IllegalArgumentException("unexpected EOF")
                val dst = getU32BE(data, pos); pos += DELTA_U32_SIZE
                val len = getU32BE(data, pos); pos += DELTA_U32_SIZE
                if (pos + len > data.size) throw IllegalArgumentException("unexpected EOF")
                validatePlacedRange(dst, len, versionSize, "ADD")
                val payload = data.copyOfRange(pos, pos + len); pos += len
                commands.add(PlacedCommand.Add(dst, payload))
            }
            DELTA_CMD_BIGCOPY -> {
                if (pos + DELTA_BIGCOPY_PAYLOAD > data.size) throw IllegalArgumentException("unexpected EOF")
                val src = checkFitsInt(getU64Long(data, pos), "BIGCOPY src"); pos += DELTA_U64_SIZE
                val dst = checkFitsInt(getU64Long(data, pos), "BIGCOPY dst"); pos += DELTA_U64_SIZE
                val len = checkFitsInt(getU64Long(data, pos), "BIGCOPY length"); pos += DELTA_U64_SIZE
                validatePlacedRange(dst, len, versionSize, "BIGCOPY")
                commands.add(PlacedCommand.Copy(src, dst, len))
            }
            DELTA_CMD_BIGADD -> {
                if (pos + DELTA_BIGADD_HEADER > data.size) throw IllegalArgumentException("unexpected EOF")
                val dst = checkFitsInt(getU64Long(data, pos), "BIGADD dst"); pos += DELTA_U64_SIZE
                val len = checkFitsInt(getU64Long(data, pos), "BIGADD length"); pos += DELTA_U64_SIZE
                if (pos + len > data.size) throw IllegalArgumentException("unexpected EOF")
                validatePlacedRange(dst, len, versionSize, "BIGADD")
                val payload = data.copyOfRange(pos, pos + len); pos += len
                commands.add(PlacedCommand.Add(dst, payload))
            }
            DELTA_CMD_MOVE -> {
                if (pos + DELTA_COPY_PAYLOAD > data.size) throw IllegalArgumentException("unexpected EOF")
                val src = getU32BE(data, pos); pos += DELTA_U32_SIZE
                val dst = getU32BE(data, pos); pos += DELTA_U32_SIZE
                val len = getU32BE(data, pos); pos += DELTA_U32_SIZE
                if (src < 0) throw IllegalArgumentException("MOVE src field exceeds Int.MAX_VALUE")
                validatePlacedRange(dst, len, versionSize, "MOVE")
                if (src.toLong() + len.toLong() > dst.toLong())
                    throw IllegalArgumentException("MOVE src+length > dst: encoder ordering constraint violated")
                commands.add(PlacedCommand.Move(src, dst, len))
            }
            DELTA_CMD_BIGMOVE -> {
                if (pos + DELTA_BIGCOPY_PAYLOAD > data.size) throw IllegalArgumentException("unexpected EOF")
                val src = checkFitsInt(getU64Long(data, pos), "BIGMOVE src"); pos += DELTA_U64_SIZE
                val dst = checkFitsInt(getU64Long(data, pos), "BIGMOVE dst"); pos += DELTA_U64_SIZE
                val len = checkFitsInt(getU64Long(data, pos), "BIGMOVE length"); pos += DELTA_U64_SIZE
                validatePlacedRange(dst, len, versionSize, "BIGMOVE")
                if (src.toLong() + len.toLong() > dst.toLong())
                    throw IllegalArgumentException("BIGMOVE src+length > dst: encoder ordering constraint violated")
                commands.add(PlacedCommand.Move(src, dst, len))
            }
            else -> throw IllegalArgumentException("unknown command type: $t")
        }
    }
    if (!sawEnd) throw IllegalArgumentException("missing END command")
    if (pos != data.size) throw IllegalArgumentException("trailing data after END")
    return DecodeResult(commands, inplace, versionSize, srcCrc, dstCrc)
}

// ── Helpers ───────────────────────────────────────────────────────────────

private fun matchesMagic(data: ByteArray, magic: ByteArray): Boolean {
    if (data.size < magic.size) return false
    for (i in magic.indices) if (data[i] != magic[i]) return false
    return true
}

private fun putU32BE(buf: ByteArray, off: Int, value: Int) {
    buf[off]     = (value ushr 24).toByte()
    buf[off + 1] = (value ushr 16).toByte()
    buf[off + 2] = (value ushr 8).toByte()
    buf[off + 3] = value.toByte()
}

private fun putU64BE(buf: ByteArray, off: Int, value: Long) {
    buf[off]     = (value ushr 56).toByte()
    buf[off + 1] = (value ushr 48).toByte()
    buf[off + 2] = (value ushr 40).toByte()
    buf[off + 3] = (value ushr 32).toByte()
    buf[off + 4] = (value ushr 24).toByte()
    buf[off + 5] = (value ushr 16).toByte()
    buf[off + 6] = (value ushr 8).toByte()
    buf[off + 7] = value.toByte()
}

private fun getU32BE(buf: ByteArray, off: Int): Int =
    ((buf[off].toInt()     and 0xFF) shl 24) or
    ((buf[off + 1].toInt() and 0xFF) shl 16) or
    ((buf[off + 2].toInt() and 0xFF) shl 8)  or
     (buf[off + 3].toInt() and 0xFF)

private fun getU64Long(buf: ByteArray, off: Int): Long =
    ((buf[off].toLong()     and 0xFF) shl 56) or
    ((buf[off + 1].toLong() and 0xFF) shl 48) or
    ((buf[off + 2].toLong() and 0xFF) shl 40) or
    ((buf[off + 3].toLong() and 0xFF) shl 32) or
    ((buf[off + 4].toLong() and 0xFF) shl 24) or
    ((buf[off + 5].toLong() and 0xFF) shl 16) or
    ((buf[off + 6].toLong() and 0xFF) shl 8)  or
     (buf[off + 7].toLong() and 0xFF)

/** Guard against u64 values that exceed Int.MAX_VALUE (JVM array limit). */
private fun checkFitsInt(value: Long, field: String): Int {
    if (value < 0 || value > Int.MAX_VALUE)
        throw IllegalArgumentException("$field value $value exceeds Int.MAX_VALUE")
    return value.toInt()
}

private fun validatePlacedRange(dst: Int, len: Int, versionSize: Int, kind: String) {
    if (dst < 0 || len < 0 || dst > versionSize || len > versionSize - dst)
        throw IllegalArgumentException("$kind extends past version size")
}
