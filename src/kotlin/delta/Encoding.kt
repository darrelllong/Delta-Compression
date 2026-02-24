package delta

/**
 * Unified binary delta format (Section 2.1.1).
 *
 * Header: magic(4) + flags(1) + version_size(u32 BE) + src_crc(8) + dst_crc(8)
 * Commands:
 *   END:  type=0
 *   COPY: type=1, src:u32, dst:u32, len:u32
 *   ADD:  type=2, dst:u32, len:u32, data
 */

class DecodeResult(
    val commands: List<PlacedCommand>,
    val inplace: Boolean,
    val versionSize: Int,
    val srcCrc: ByteArray,
    val dstCrc: ByteArray,
)

/** Encode placed commands to the unified binary delta format. */
fun encodeDelta(commands: List<PlacedCommand>, inplace: Boolean,
                versionSize: Int, srcCrc: ByteArray, dstCrc: ByteArray): ByteArray {
    // Estimate size: header + commands + END(1)
    var est = DELTA_HEADER_SIZE + 1
    for (cmd in commands) {
        est += when (cmd) {
            is PlacedCommand.Copy -> 1 + DELTA_COPY_PAYLOAD
            is PlacedCommand.Add  -> 1 + DELTA_ADD_HEADER + cmd.data.size
        }
    }
    val out = ByteArray(est)
    var pos = 0

    // Header
    DELTA_MAGIC.copyInto(out, pos); pos += DELTA_MAGIC.size
    out[pos++] = if (inplace) DELTA_FLAG_INPLACE else 0
    putU32BE(out, pos, versionSize); pos += DELTA_U32_SIZE
    srcCrc.copyInto(out, pos);      pos += DELTA_CRC_SIZE
    dstCrc.copyInto(out, pos);      pos += DELTA_CRC_SIZE

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
                putU32BE(out, pos, cmd.dst);        pos += DELTA_U32_SIZE
                putU32BE(out, pos, cmd.data.size);  pos += DELTA_U32_SIZE
                cmd.data.copyInto(out, pos);        pos += cmd.data.size
            }
        }
    }

    out[pos++] = DELTA_CMD_END.toByte()

    return if (pos != out.size) out.copyOf(pos) else out
}

/** Decode the unified binary delta format. */
fun decodeDelta(data: ByteArray): DecodeResult {
    if (data.size < DELTA_HEADER_SIZE) throw IllegalArgumentException("not a delta file")
    for (i in DELTA_MAGIC.indices) {
        if (data[i] != DELTA_MAGIC[i]) throw IllegalArgumentException("not a delta file")
    }

    val inplace     = (data[DELTA_MAGIC.size].toInt() and DELTA_FLAG_INPLACE.toInt()) != 0
    val versionSize = getU32BE(data, DELTA_MAGIC.size + 1)
    val crcOff      = DELTA_MAGIC.size + 1 + DELTA_U32_SIZE
    val srcCrc      = data.copyOfRange(crcOff, crcOff + DELTA_CRC_SIZE)
    val dstCrc      = data.copyOfRange(crcOff + DELTA_CRC_SIZE, crcOff + 2 * DELTA_CRC_SIZE)
    var pos         = DELTA_HEADER_SIZE
    val commands    = mutableListOf<PlacedCommand>()

    while (pos < data.size) {
        val t = data[pos++].toInt() and 0xFF
        if (t == DELTA_CMD_END) break

        when (t) {
            DELTA_CMD_COPY -> {
                if (pos + DELTA_COPY_PAYLOAD > data.size) throw IllegalArgumentException("unexpected EOF")
                val src = getU32BE(data, pos); pos += DELTA_U32_SIZE
                val dst = getU32BE(data, pos); pos += DELTA_U32_SIZE
                val len = getU32BE(data, pos); pos += DELTA_U32_SIZE
                commands.add(PlacedCommand.Copy(src, dst, len))
            }
            DELTA_CMD_ADD -> {
                if (pos + DELTA_ADD_HEADER > data.size) throw IllegalArgumentException("unexpected EOF")
                val dst = getU32BE(data, pos); pos += DELTA_U32_SIZE
                val len = getU32BE(data, pos); pos += DELTA_U32_SIZE
                if (pos + len > data.size) throw IllegalArgumentException("unexpected EOF")
                val payload = data.copyOfRange(pos, pos + len); pos += len
                commands.add(PlacedCommand.Add(dst, payload))
            }
            else -> throw IllegalArgumentException("unknown command type: $t")
        }
    }

    return DecodeResult(commands, inplace, versionSize, srcCrc, dstCrc)
}

/** Check if binary data is an in-place delta. */
fun isInplaceDelta(data: ByteArray): Boolean {
    if (data.size < DELTA_MAGIC.size + 1) return false
    for (i in DELTA_MAGIC.indices) { if (data[i] != DELTA_MAGIC[i]) return false }
    return (data[DELTA_MAGIC.size].toInt() and DELTA_FLAG_INPLACE.toInt()) != 0
}

private fun putU32BE(buf: ByteArray, off: Int, value: Int) {
    buf[off]     = (value ushr 24).toByte()
    buf[off + 1] = (value ushr 16).toByte()
    buf[off + 2] = (value ushr 8).toByte()
    buf[off + 3] = value.toByte()
}

private fun getU32BE(buf: ByteArray, off: Int): Int =
    ((buf[off].toInt()     and 0xFF) shl 24) or
    ((buf[off + 1].toInt() and 0xFF) shl 16) or
    ((buf[off + 2].toInt() and 0xFF) shl 8)  or
     (buf[off + 3].toInt() and 0xFF)
