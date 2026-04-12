package delta;

import java.util.ArrayList;
import java.util.List;

import static delta.Types.*;

/**
 * Binary delta format encode/decode (DLT\x03 small and DLT\x04 large).
 *
 * DLT\x03 (small) — 25-byte header, u32 fields, COPY/ADD only:
 *   magic(4)+flags(1)+version_size(u32 BE)+src_crc(8)+dst_crc(8)
 *
 * DLT\x04 (large) — 29-byte header, u64 fields, adds BIGCOPY/BIGADD/MOVE/BIGMOVE:
 *   magic(4)+flags(1)+version_size(u64 BE)+src_crc(8)+dst_crc(8)
 */
public final class Encoding {
    private Encoding() {}

    private static final long U32_MAX = 0xFFFFFFFFL;

    /**
     * Decoded delta file content.
     *
     * @param commands    The placed commands to execute during apply.
     * @param inplace     True if the delta uses the in-place format.
     * @param versionSize Byte length of the reconstructed version.
     * @param srcCrc      CRC-64/XZ of the reference (8 bytes big-endian).
     * @param dstCrc      CRC-64/XZ of the version (8 bytes big-endian).
     */
    public record DecodeResult(
        List<PlacedCommand> commands,
        boolean inplace,
        long versionSize,
        byte[] srcCrc,
        byte[] dstCrc
    ) {}

    /**
     * Encode placed commands to DLT\x03 format (u32 fields, max 4 GiB).
     * Throws if any field exceeds UINT32_MAX or if a PlacedMove is present.
     * Use {@link #encodeDeltaLarge} for DLT\x04 (u64 fields, PlacedMove support).
     */
    public static byte[] encodeDelta(List<PlacedCommand> commands,
                                     boolean inplace, long versionSize,
                                     byte[] srcCrc, byte[] dstCrc) {
        if (versionSize < 0 || versionSize > U32_MAX)
            throw new IllegalArgumentException("versionSize exceeds u32 range");
        int est = DELTA_HEADER_SIZE + 1;
        for (PlacedCommand cmd : commands) {
            if (cmd instanceof PlacedCopy) {
                est += 1 + DELTA_COPY_PAYLOAD;
            } else if (cmd instanceof PlacedAdd a) {
                est += 1 + DELTA_ADD_HEADER + a.data().length;
            } else if (cmd instanceof PlacedMove) {
                throw new IllegalArgumentException(
                    "PlacedMove requires DLT\\x04 format; use encodeDeltaLarge");
            }
        }
        byte[] out = new byte[est];
        int pos = 0;

        System.arraycopy(DELTA_MAGIC, 0, out, 0, DELTA_MAGIC.length);
        pos = DELTA_MAGIC.length;
        out[pos++] = inplace ? DELTA_FLAG_INPLACE : 0;
        putU32BE(out, pos, (int) versionSize); pos += DELTA_U32_SIZE;
        System.arraycopy(srcCrc, 0, out, pos, DELTA_CRC_SIZE); pos += DELTA_CRC_SIZE;
        System.arraycopy(dstCrc, 0, out, pos, DELTA_CRC_SIZE); pos += DELTA_CRC_SIZE;

        for (PlacedCommand cmd : commands) {
            if (cmd instanceof PlacedCopy c) {
                if (c.src() > U32_MAX || c.dst() > U32_MAX || c.length() > U32_MAX)
                    throw new IllegalArgumentException(
                        "COPY field exceeds u32 range; use encodeDeltaLarge");
                out[pos++] = DELTA_CMD_COPY;
                putU32BE(out, pos, (int) c.src());    pos += DELTA_U32_SIZE;
                putU32BE(out, pos, (int) c.dst());    pos += DELTA_U32_SIZE;
                putU32BE(out, pos, (int) c.length()); pos += DELTA_U32_SIZE;
            } else if (cmd instanceof PlacedAdd a) {
                if (a.dst() > U32_MAX || (long) a.data().length > U32_MAX)
                    throw new IllegalArgumentException(
                        "ADD field exceeds u32 range; use encodeDeltaLarge");
                out[pos++] = DELTA_CMD_ADD;
                putU32BE(out, pos, (int) a.dst());         pos += DELTA_U32_SIZE;
                putU32BE(out, pos, a.data().length); pos += DELTA_U32_SIZE;
                System.arraycopy(a.data(), 0, out, pos, a.data().length);
                pos += a.data().length;
            }
        }

        out[pos++] = DELTA_CMD_END;

        if (pos != out.length) {
            byte[] trimmed = new byte[pos];
            System.arraycopy(out, 0, trimmed, 0, pos);
            return trimmed;
        }
        return out;
    }

    /**
     * Encode placed commands to DLT\x04 format (u64 fields, PlacedMove support).
     * Commands whose fields exceed UINT32_MAX are encoded as BIGCOPY/BIGADD/BIGMOVE.
     * When forceLarge is true all commands use 64-bit fields regardless of value.
     */
    public static byte[] encodeDeltaLarge(List<PlacedCommand> commands,
                                          boolean inplace, long versionSize,
                                          byte[] srcCrc, byte[] dstCrc,
                                          boolean forceLarge) {
        int est = DELTA_HEADER_SIZE_LARGE + 1;
        for (PlacedCommand cmd : commands) {
            if (cmd instanceof PlacedCopy c) {
                est += useBig(forceLarge, c.src(), c.dst(), c.length())
                    ? 1 + DELTA_BIGCOPY_PAYLOAD : 1 + DELTA_COPY_PAYLOAD;
            } else if (cmd instanceof PlacedAdd a) {
                est += (useBig(forceLarge, a.dst(), a.data().length, 0)
                    ? 1 + DELTA_BIGADD_HEADER : 1 + DELTA_ADD_HEADER) + a.data().length;
            } else if (cmd instanceof PlacedMove m) {
                est += useBig(forceLarge, m.src(), m.dst(), m.length())
                    ? 1 + DELTA_BIGCOPY_PAYLOAD : 1 + DELTA_COPY_PAYLOAD;
            }
        }
        byte[] out = new byte[est];
        int pos = 0;

        System.arraycopy(DELTA_MAGIC_LARGE, 0, out, 0, DELTA_MAGIC_LARGE.length);
        pos = DELTA_MAGIC_LARGE.length;
        out[pos++] = inplace ? DELTA_FLAG_INPLACE : 0;
        putU64BE(out, pos, versionSize); pos += DELTA_U64_SIZE;
        System.arraycopy(srcCrc, 0, out, pos, DELTA_CRC_SIZE); pos += DELTA_CRC_SIZE;
        System.arraycopy(dstCrc, 0, out, pos, DELTA_CRC_SIZE); pos += DELTA_CRC_SIZE;

        for (PlacedCommand cmd : commands) {
            if (cmd instanceof PlacedCopy c) {
                if (useBig(forceLarge, c.src(), c.dst(), c.length())) {
                    out[pos++] = DELTA_CMD_BIGCOPY;
                    putU64BE(out, pos, c.src());    pos += DELTA_U64_SIZE;
                    putU64BE(out, pos, c.dst());    pos += DELTA_U64_SIZE;
                    putU64BE(out, pos, c.length()); pos += DELTA_U64_SIZE;
                } else {
                    out[pos++] = DELTA_CMD_COPY;
                    putU32BE(out, pos, (int) c.src());    pos += DELTA_U32_SIZE;
                    putU32BE(out, pos, (int) c.dst());    pos += DELTA_U32_SIZE;
                    putU32BE(out, pos, (int) c.length()); pos += DELTA_U32_SIZE;
                }
            } else if (cmd instanceof PlacedAdd a) {
                if (useBig(forceLarge, a.dst(), a.data().length, 0)) {
                    out[pos++] = DELTA_CMD_BIGADD;
                    putU64BE(out, pos, a.dst());          pos += DELTA_U64_SIZE;
                    putU64BE(out, pos, a.data().length);  pos += DELTA_U64_SIZE;
                } else {
                    out[pos++] = DELTA_CMD_ADD;
                    putU32BE(out, pos, (int) a.dst());         pos += DELTA_U32_SIZE;
                    putU32BE(out, pos, a.data().length); pos += DELTA_U32_SIZE;
                }
                System.arraycopy(a.data(), 0, out, pos, a.data().length);
                pos += a.data().length;
            } else if (cmd instanceof PlacedMove m) {
                if (useBig(forceLarge, m.src(), m.dst(), m.length())) {
                    out[pos++] = DELTA_CMD_BIGMOVE;
                    putU64BE(out, pos, m.src());    pos += DELTA_U64_SIZE;
                    putU64BE(out, pos, m.dst());    pos += DELTA_U64_SIZE;
                    putU64BE(out, pos, m.length()); pos += DELTA_U64_SIZE;
                } else {
                    out[pos++] = DELTA_CMD_MOVE;
                    putU32BE(out, pos, (int) m.src());    pos += DELTA_U32_SIZE;
                    putU32BE(out, pos, (int) m.dst());    pos += DELTA_U32_SIZE;
                    putU32BE(out, pos, (int) m.length()); pos += DELTA_U32_SIZE;
                }
            }
        }

        out[pos++] = DELTA_CMD_END;

        if (pos != out.length) {
            byte[] trimmed = new byte[pos];
            System.arraycopy(out, 0, trimmed, 0, pos);
            return trimmed;
        }
        return out;
    }

    /**
     * Decode DLT\x03 or DLT\x04 format. Dispatches on magic bytes.
     * CRC validation is the caller's responsibility.
     */
    public static DecodeResult decodeDelta(byte[] data) {
        if (data.length < DELTA_MAGIC.length)
            throw new IllegalArgumentException("not a delta file");
        if (matchesMagic(data, DELTA_MAGIC))
            return decodeSmall(data);
        if (matchesMagic(data, DELTA_MAGIC_LARGE))
            return decodeLarge(data);
        throw new IllegalArgumentException("not a delta file");
    }

    /** Check if binary data is an in-place delta (DLT\x03 or DLT\x04). */
    public static boolean isInplaceDelta(byte[] data) {
        if (data.length < DELTA_MAGIC.length + 1) return false;
        return (matchesMagic(data, DELTA_MAGIC) || matchesMagic(data, DELTA_MAGIC_LARGE))
            && (data[DELTA_MAGIC.length] & DELTA_FLAG_INPLACE) != 0;
    }

    // ── Private decoders ──────────────────────────────────────────────────

    private static DecodeResult decodeSmall(byte[] data) {
        if (data.length < DELTA_HEADER_SIZE)
            throw new IllegalArgumentException("not a delta file");

        boolean inplace  = (data[DELTA_MAGIC.length] & DELTA_FLAG_INPLACE) != 0;
        long versionSize = Integer.toUnsignedLong(getU32BE(data, DELTA_MAGIC.length + 1));
        int crcOff       = DELTA_MAGIC.length + 1 + DELTA_U32_SIZE;
        byte[] srcCrc    = new byte[DELTA_CRC_SIZE];
        byte[] dstCrc    = new byte[DELTA_CRC_SIZE];
        System.arraycopy(data, crcOff,                  srcCrc, 0, DELTA_CRC_SIZE);
        System.arraycopy(data, crcOff + DELTA_CRC_SIZE, dstCrc, 0, DELTA_CRC_SIZE);
        int pos = DELTA_HEADER_SIZE;
        List<PlacedCommand> commands = new ArrayList<>();
        boolean sawEnd = false;

        while (pos < data.length) {
            int t = data[pos++] & 0xFF;
            if (t == DELTA_CMD_END) { sawEnd = true; break; }

            if (t == DELTA_CMD_COPY) {
                if (pos + DELTA_COPY_PAYLOAD > data.length)
                    throw new IllegalArgumentException("unexpected EOF");
                long src = Integer.toUnsignedLong(getU32BE(data, pos)); pos += DELTA_U32_SIZE;
                long dst = Integer.toUnsignedLong(getU32BE(data, pos)); pos += DELTA_U32_SIZE;
                long len = Integer.toUnsignedLong(getU32BE(data, pos)); pos += DELTA_U32_SIZE;
                validatePlacedRange(dst, len, versionSize, "COPY");
                commands.add(new PlacedCopy(src, dst, len));
            } else if (t == DELTA_CMD_ADD) {
                if (pos + DELTA_ADD_HEADER > data.length)
                    throw new IllegalArgumentException("unexpected EOF");
                long dst = Integer.toUnsignedLong(getU32BE(data, pos)); pos += DELTA_U32_SIZE;
                int  len = getU32BE(data, pos);                         pos += DELTA_U32_SIZE;
                if (pos + len > data.length)
                    throw new IllegalArgumentException("unexpected EOF");
                validatePlacedRange(dst, Integer.toUnsignedLong(len), versionSize, "ADD");
                byte[] payload = new byte[len];
                System.arraycopy(data, pos, payload, 0, len);
                pos += len;
                commands.add(new PlacedAdd(dst, payload));
            } else if (t == DELTA_CMD_BIGCOPY || t == DELTA_CMD_BIGADD
                    || t == DELTA_CMD_MOVE    || t == DELTA_CMD_BIGMOVE) {
                throw new IllegalArgumentException(
                    "command type " + t + " requires DLT\\x04 format");
            } else {
                throw new IllegalArgumentException("unknown command type: " + t);
            }
        }

        if (!sawEnd) throw new IllegalArgumentException("missing END command");
        if (pos != data.length) throw new IllegalArgumentException("trailing data after END");
        return new DecodeResult(commands, inplace, versionSize, srcCrc, dstCrc);
    }

    private static DecodeResult decodeLarge(byte[] data) {
        if (data.length < DELTA_HEADER_SIZE_LARGE)
            throw new IllegalArgumentException("not a delta file");

        boolean inplace = (data[DELTA_MAGIC.length] & DELTA_FLAG_INPLACE) != 0;
        long versionSize = getU64Long(data, DELTA_MAGIC.length + 1);
        if (versionSize < 0)
            throw new IllegalArgumentException("version_size overflows long");
        int crcOff      = DELTA_MAGIC.length + 1 + DELTA_U64_SIZE;
        byte[] srcCrc   = new byte[DELTA_CRC_SIZE];
        byte[] dstCrc   = new byte[DELTA_CRC_SIZE];
        System.arraycopy(data, crcOff,                  srcCrc, 0, DELTA_CRC_SIZE);
        System.arraycopy(data, crcOff + DELTA_CRC_SIZE, dstCrc, 0, DELTA_CRC_SIZE);
        int pos = DELTA_HEADER_SIZE_LARGE;
        List<PlacedCommand> commands = new ArrayList<>();
        boolean sawEnd = false;

        while (pos < data.length) {
            int t = data[pos++] & 0xFF;
            if (t == DELTA_CMD_END) { sawEnd = true; break; }

            if (t == DELTA_CMD_COPY) {
                if (pos + DELTA_COPY_PAYLOAD > data.length)
                    throw new IllegalArgumentException("unexpected EOF");
                long src = Integer.toUnsignedLong(getU32BE(data, pos)); pos += DELTA_U32_SIZE;
                long dst = Integer.toUnsignedLong(getU32BE(data, pos)); pos += DELTA_U32_SIZE;
                long len = Integer.toUnsignedLong(getU32BE(data, pos)); pos += DELTA_U32_SIZE;
                validatePlacedRange(dst, len, versionSize, "COPY");
                commands.add(new PlacedCopy(src, dst, len));
            } else if (t == DELTA_CMD_ADD) {
                if (pos + DELTA_ADD_HEADER > data.length)
                    throw new IllegalArgumentException("unexpected EOF");
                long dst = Integer.toUnsignedLong(getU32BE(data, pos)); pos += DELTA_U32_SIZE;
                int  len = getU32BE(data, pos);                         pos += DELTA_U32_SIZE;
                if (pos + len > data.length)
                    throw new IllegalArgumentException("unexpected EOF");
                validatePlacedRange(dst, Integer.toUnsignedLong(len), versionSize, "ADD");
                byte[] payload = new byte[len];
                System.arraycopy(data, pos, payload, 0, len);
                pos += len;
                commands.add(new PlacedAdd(dst, payload));
            } else if (t == DELTA_CMD_BIGCOPY) {
                if (pos + DELTA_BIGCOPY_PAYLOAD > data.length)
                    throw new IllegalArgumentException("unexpected EOF");
                long src = checkNonNegative(getU64Long(data, pos), "BIGCOPY src"); pos += DELTA_U64_SIZE;
                long dst = checkNonNegative(getU64Long(data, pos), "BIGCOPY dst"); pos += DELTA_U64_SIZE;
                long len = checkNonNegative(getU64Long(data, pos), "BIGCOPY length"); pos += DELTA_U64_SIZE;
                validatePlacedRange(dst, len, versionSize, "BIGCOPY");
                commands.add(new PlacedCopy(src, dst, len));
            } else if (t == DELTA_CMD_BIGADD) {
                if (pos + DELTA_BIGADD_HEADER > data.length)
                    throw new IllegalArgumentException("unexpected EOF");
                long dst = checkNonNegative(getU64Long(data, pos), "BIGADD dst"); pos += DELTA_U64_SIZE;
                long lenL = checkNonNegative(getU64Long(data, pos), "BIGADD length"); pos += DELTA_U64_SIZE;
                if (lenL > Integer.MAX_VALUE)
                    throw new IllegalArgumentException("BIGADD length too large for JVM");
                int len = (int) lenL;
                if (pos + len > data.length)
                    throw new IllegalArgumentException("unexpected EOF");
                validatePlacedRange(dst, lenL, versionSize, "BIGADD");
                byte[] payload = new byte[len];
                System.arraycopy(data, pos, payload, 0, len);
                pos += len;
                commands.add(new PlacedAdd(dst, payload));
            } else if (t == DELTA_CMD_MOVE) {
                if (pos + DELTA_COPY_PAYLOAD > data.length)
                    throw new IllegalArgumentException("unexpected EOF");
                long src = Integer.toUnsignedLong(getU32BE(data, pos)); pos += DELTA_U32_SIZE;
                long dst = Integer.toUnsignedLong(getU32BE(data, pos)); pos += DELTA_U32_SIZE;
                long len = Integer.toUnsignedLong(getU32BE(data, pos)); pos += DELTA_U32_SIZE;
                validatePlacedRange(dst, len, versionSize, "MOVE");
                if (src + len > dst)
                    throw new IllegalArgumentException(
                        "MOVE src+length > dst: encoder ordering constraint violated");
                commands.add(new PlacedMove(src, dst, len));
            } else if (t == DELTA_CMD_BIGMOVE) {
                if (pos + DELTA_BIGCOPY_PAYLOAD > data.length)
                    throw new IllegalArgumentException("unexpected EOF");
                long src = checkNonNegative(getU64Long(data, pos), "BIGMOVE src"); pos += DELTA_U64_SIZE;
                long dst = checkNonNegative(getU64Long(data, pos), "BIGMOVE dst"); pos += DELTA_U64_SIZE;
                long len = checkNonNegative(getU64Long(data, pos), "BIGMOVE length"); pos += DELTA_U64_SIZE;
                validatePlacedRange(dst, len, versionSize, "BIGMOVE");
                if (src + len > dst)
                    throw new IllegalArgumentException(
                        "BIGMOVE src+length > dst: encoder ordering constraint violated");
                commands.add(new PlacedMove(src, dst, len));
            } else {
                throw new IllegalArgumentException("unknown command type: " + t);
            }
        }

        if (!sawEnd) throw new IllegalArgumentException("missing END command");
        if (pos != data.length) throw new IllegalArgumentException("trailing data after END");
        return new DecodeResult(commands, inplace, versionSize, srcCrc, dstCrc);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static boolean useBig(boolean forceLarge, long a, long b, long c) {
        return forceLarge || a > U32_MAX || b > U32_MAX || c > U32_MAX;
    }

    private static boolean matchesMagic(byte[] data, byte[] magic) {
        if (data.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++)
            if (data[i] != magic[i]) return false;
        return true;
    }

    private static void putU32BE(byte[] buf, int off, int value) {
        buf[off]     = (byte) (value >>> 24);
        buf[off + 1] = (byte) (value >>> 16);
        buf[off + 2] = (byte) (value >>> 8);
        buf[off + 3] = (byte) value;
    }

    private static void putU64BE(byte[] buf, int off, long value) {
        buf[off]     = (byte) (value >>> 56);
        buf[off + 1] = (byte) (value >>> 48);
        buf[off + 2] = (byte) (value >>> 40);
        buf[off + 3] = (byte) (value >>> 32);
        buf[off + 4] = (byte) (value >>> 24);
        buf[off + 5] = (byte) (value >>> 16);
        buf[off + 6] = (byte) (value >>> 8);
        buf[off + 7] = (byte) value;
    }

    private static int getU32BE(byte[] buf, int off) {
        return ((buf[off]     & 0xFF) << 24)
             | ((buf[off + 1] & 0xFF) << 16)
             | ((buf[off + 2] & 0xFF) << 8)
             |  (buf[off + 3] & 0xFF);
    }

    private static long getU64Long(byte[] buf, int off) {
        return ((long)(buf[off]     & 0xFF) << 56)
             | ((long)(buf[off + 1] & 0xFF) << 48)
             | ((long)(buf[off + 2] & 0xFF) << 40)
             | ((long)(buf[off + 3] & 0xFF) << 32)
             | ((long)(buf[off + 4] & 0xFF) << 24)
             | ((long)(buf[off + 5] & 0xFF) << 16)
             | ((long)(buf[off + 6] & 0xFF) << 8)
             |  (long)(buf[off + 7] & 0xFF);
    }

    private static long checkNonNegative(long value, String field) {
        if (value < 0)
            throw new IllegalArgumentException(field + " value overflows long");
        return value;
    }

    private static void validatePlacedRange(long dst, long len, long versionSize, String kind) {
        if (dst < 0 || len < 0 || dst > versionSize || len > versionSize - dst) {
            throw new IllegalArgumentException(kind + " extends past version size");
        }
    }
}
