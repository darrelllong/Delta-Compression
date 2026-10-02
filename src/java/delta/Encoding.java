package delta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static delta.Types.*;

/**
 * The binary delta format.  All integers are big-endian.
 *
 * <pre>
 *   DLT\x03: magic(4) flags(1) version_size(u32) src_crc(8) dst_crc(8) commands END
 *   DLT\x04: magic(4) flags(1) version_size(u64) src_crc(8) dst_crc(8) commands END
 *
 *   COPY  type(1) src dst len
 *   ADD   type(1) dst len data
 *   MOVE  type(1) src dst len      DLT\x04 only
 * </pre>
 *
 * Command fields are u32.  DLT\x04 also has BIGCOPY, BIGADD and BIGMOVE,
 * which are the same commands with u64 fields.
 */
public final class Encoding {
    private Encoding() {}

    private static final long U32_MAX = 0xFFFFFFFFL;

    /**
     * A decoded delta.
     *
     * @param commands    the commands, in file order
     * @param inplace     whether the delta is to be applied in place
     * @param versionSize length of the version, in bytes
     * @param srcCrc      CRC-64/XZ of the reference, 8 bytes big-endian
     * @param dstCrc      CRC-64/XZ of the version, 8 bytes big-endian
     */
    public record DecodeResult(
        List<PlacedCommand> commands,
        boolean inplace,
        long versionSize,
        byte[] srcCrc,
        byte[] dstCrc
    ) {}

    /**
     * Encodes commands as DLT\x03.
     *
     * @throws IllegalArgumentException if a field does not fit in a u32 or a
     *         command is a PlacedMove; {@link #encodeDeltaLarge} takes both
     */
    public static byte[] encodeDelta(List<PlacedCommand> commands,
                                     boolean inplace, long versionSize,
                                     byte[] srcCrc, byte[] dstCrc) {
        if (versionSize < 0 || versionSize > U32_MAX) {
            throw new IllegalArgumentException("versionSize exceeds u32 range");
        }
        return encode(commands, inplace, versionSize, srcCrc, dstCrc, false, false);
    }

    /**
     * Encodes commands as DLT\x04.  A command gets u64 fields only if one of
     * its fields needs them, unless forceLarge asks for u64 fields throughout.
     */
    public static byte[] encodeDeltaLarge(List<PlacedCommand> commands,
                                          boolean inplace, long versionSize,
                                          byte[] srcCrc, byte[] dstCrc,
                                          boolean forceLarge) {
        return encode(commands, inplace, versionSize, srcCrc, dstCrc, true, forceLarge);
    }

    private static byte[] encode(List<PlacedCommand> commands,
                                 boolean inplace, long versionSize,
                                 byte[] srcCrc, byte[] dstCrc,
                                 boolean large, boolean forceBig) {
        long size = (large ? DELTA_HEADER_SIZE_LARGE : DELTA_HEADER_SIZE) + 1;
        for (PlacedCommand cmd : commands) {
            if (cmd instanceof PlacedCopy c) {
                size += 1 + (isBig(large, forceBig, "COPY", c.src(), c.dst(), c.length())
                    ? DELTA_BIGCOPY_PAYLOAD : DELTA_COPY_PAYLOAD);
            } else if (cmd instanceof PlacedAdd a) {
                size += 1 + (isBig(large, forceBig, "ADD", a.dst(), a.data().length, 0)
                    ? DELTA_BIGADD_HEADER : DELTA_ADD_HEADER) + a.data().length;
            } else if (cmd instanceof PlacedMove m) {
                if (!large) {
                    throw new IllegalArgumentException(
                        "PlacedMove requires DLT\\x04 format; use encodeDeltaLarge");
                }
                size += 1 + (isBig(large, forceBig, "MOVE", m.src(), m.dst(), m.length())
                    ? DELTA_BIGCOPY_PAYLOAD : DELTA_COPY_PAYLOAD);
            }
        }
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("delta too large for JVM");
        }

        Writer out = new Writer((int) size);
        out.bytes(large ? DELTA_MAGIC_LARGE : DELTA_MAGIC, DELTA_MAGIC.length);
        out.u8(inplace ? DELTA_FLAG_INPLACE : 0);
        out.field(large, versionSize);
        out.bytes(srcCrc, DELTA_CRC_SIZE);
        out.bytes(dstCrc, DELTA_CRC_SIZE);

        for (PlacedCommand cmd : commands) {
            if (cmd instanceof PlacedCopy c) {
                boolean big = isBig(large, forceBig, "COPY", c.src(), c.dst(), c.length());
                out.u8(big ? DELTA_CMD_BIGCOPY : DELTA_CMD_COPY);
                out.field(big, c.src());
                out.field(big, c.dst());
                out.field(big, c.length());
            } else if (cmd instanceof PlacedAdd a) {
                boolean big = isBig(large, forceBig, "ADD", a.dst(), a.data().length, 0);
                out.u8(big ? DELTA_CMD_BIGADD : DELTA_CMD_ADD);
                out.field(big, a.dst());
                out.field(big, a.data().length);
                out.bytes(a.data(), a.data().length);
            } else if (cmd instanceof PlacedMove m) {
                boolean big = isBig(large, forceBig, "MOVE", m.src(), m.dst(), m.length());
                out.u8(big ? DELTA_CMD_BIGMOVE : DELTA_CMD_MOVE);
                out.field(big, m.src());
                out.field(big, m.dst());
                out.field(big, m.length());
            }
        }
        out.u8(DELTA_CMD_END);
        return out.buf;
    }

    /**
     * Reports whether a command with fields a, b and c is written with u64
     * fields.  Throws if it would have to be and the format has none.
     */
    private static boolean isBig(boolean large, boolean forceBig, String kind,
                                 long a, long b, long c) {
        if (a <= U32_MAX && b <= U32_MAX && c <= U32_MAX) return large && forceBig;
        if (!large) {
            throw new IllegalArgumentException(
                kind + " field exceeds u32 range; use encodeDeltaLarge");
        }
        return true;
    }

    /**
     * Decodes a delta in either format.  Checks that the file is well formed
     * and that every command writes within the version; it does not check
     * the sources of copies or the CRCs, which need the reference.
     *
     * @throws IllegalArgumentException if data is not a valid delta
     */
    public static DecodeResult decodeDelta(byte[] data) {
        boolean large;
        if (hasMagic(data, DELTA_MAGIC)) {
            large = false;
        } else if (hasMagic(data, DELTA_MAGIC_LARGE)) {
            large = true;
        } else {
            throw new IllegalArgumentException("not a delta file");
        }
        if (data.length < (large ? DELTA_HEADER_SIZE_LARGE : DELTA_HEADER_SIZE)) {
            throw new IllegalArgumentException("not a delta file");
        }

        Reader in = new Reader(data, DELTA_MAGIC.length);
        boolean inplace = (in.u8() & DELTA_FLAG_INPLACE) != 0;
        long versionSize = large ? in.u64() : in.u32();
        if (versionSize < 0) {
            throw new IllegalArgumentException("version_size overflows long");
        }
        byte[] srcCrc = in.bytes(DELTA_CRC_SIZE);
        byte[] dstCrc = in.bytes(DELTA_CRC_SIZE);

        List<PlacedCommand> commands = new ArrayList<>();
        for (;;) {
            if (in.remaining() == 0) {
                throw new IllegalArgumentException("missing END command");
            }
            int type = in.u8();
            if (type == DELTA_CMD_END) break;
            if (!large && type >= DELTA_CMD_BIGCOPY && type <= DELTA_CMD_BIGMOVE) {
                throw new IllegalArgumentException(
                    "command type " + type + " requires DLT\\x04 format");
            }
            commands.add(switch (type) {
                case DELTA_CMD_COPY    -> readCopy(in, "COPY", false, false, versionSize);
                case DELTA_CMD_BIGCOPY -> readCopy(in, "BIGCOPY", true, false, versionSize);
                case DELTA_CMD_MOVE    -> readCopy(in, "MOVE", false, true, versionSize);
                case DELTA_CMD_BIGMOVE -> readCopy(in, "BIGMOVE", true, true, versionSize);
                case DELTA_CMD_ADD     -> readAdd(in, "ADD", false, versionSize);
                case DELTA_CMD_BIGADD  -> readAdd(in, "BIGADD", true, versionSize);
                default -> throw new IllegalArgumentException("unknown command type: " + type);
            });
        }
        if (in.remaining() != 0) {
            throw new IllegalArgumentException("trailing data after END");
        }
        return new DecodeResult(commands, inplace, versionSize, srcCrc, dstCrc);
    }

    /** Reports whether data is a delta, of either format, with the in-place flag set. */
    public static boolean isInplaceDelta(byte[] data) {
        return data.length > DELTA_MAGIC.length
            && (hasMagic(data, DELTA_MAGIC) || hasMagic(data, DELTA_MAGIC_LARGE))
            && (data[DELTA_MAGIC.length] & DELTA_FLAG_INPLACE) != 0;
    }

    private static boolean hasMagic(byte[] data, byte[] magic) {
        return data.length >= magic.length
            && Arrays.equals(data, 0, magic.length, magic, 0, magic.length);
    }

    /** Reads the fields of a copy or, if move is set, of a move. */
    private static PlacedCommand readCopy(Reader in, String kind, boolean big,
                                          boolean move, long versionSize) {
        in.need(big ? DELTA_BIGCOPY_PAYLOAD : DELTA_COPY_PAYLOAD);
        long src = in.field(big, kind, " src");
        long dst = in.field(big, kind, " dst");
        long len = in.field(big, kind, " length");
        checkDestination(dst, len, versionSize, kind);
        if (!move) return new PlacedCopy(src, dst, len);
        // A move reads only output already written: src + len <= dst.
        // The sum can overflow; the difference cannot.
        if (src > dst - len) {
            throw new IllegalArgumentException(
                kind + " src+length > dst: encoder ordering constraint violated");
        }
        return new PlacedMove(src, dst, len);
    }

    private static PlacedCommand readAdd(Reader in, String kind, boolean big,
                                         long versionSize) {
        in.need(big ? DELTA_BIGADD_HEADER : DELTA_ADD_HEADER);
        long dst = in.field(big, kind, " dst");
        long len = in.field(big, kind, " length");
        if (big && len > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("BIGADD length too large for JVM");
        }
        if (len > in.remaining()) {
            throw new IllegalArgumentException("unexpected EOF");
        }
        checkDestination(dst, len, versionSize, kind);
        return new PlacedAdd(dst, in.bytes((int) len));
    }

    private static void checkDestination(long dst, long len, long versionSize, String kind) {
        if (dst > versionSize || len > versionSize - dst) {
            throw new IllegalArgumentException(kind + " extends past version size");
        }
    }

    /** Writes big-endian fields into an array of exactly the right size. */
    private static final class Writer {
        final byte[] buf;
        private int pos;

        Writer(int size) { buf = new byte[size]; }

        void u8(int value) { buf[pos++] = (byte) value; }

        /** Writes value as a u64 if big is set and as a u32 otherwise. */
        void field(boolean big, long value) {
            for (int shift = (big ? 64 : 32) - 8; shift >= 0; shift -= 8) {
                buf[pos++] = (byte) (value >>> shift);
            }
        }

        void bytes(byte[] src, int n) {
            System.arraycopy(src, 0, buf, pos, n);
            pos += n;
        }
    }

    /** Reads big-endian fields; the caller checks need or remaining first. */
    private static final class Reader {
        private final byte[] data;
        private int pos;

        Reader(byte[] data, int pos) {
            this.data = data;
            this.pos = pos;
        }

        int remaining() { return data.length - pos; }

        void need(int n) {
            if (remaining() < n) throw new IllegalArgumentException("unexpected EOF");
        }

        int u8() { return data[pos++] & 0xFF; }

        long u32() {
            long x = 0;
            for (int i = 0; i < DELTA_U32_SIZE; i++) x = x << 8 | (data[pos++] & 0xFF);
            return x;
        }

        /** Returns the next u64 as a long, which is negative if it exceeds Long.MAX_VALUE. */
        long u64() {
            long x = 0;
            for (int i = 0; i < DELTA_U64_SIZE; i++) x = x << 8 | (data[pos++] & 0xFF);
            return x;
        }

        /** Reads a command field, a u64 if big is set and a u32 otherwise. */
        long field(boolean big, String kind, String name) {
            if (!big) return u32();
            long x = u64();
            if (x < 0) {
                throw new IllegalArgumentException(kind + name + " value overflows long");
            }
            return x;
        }

        byte[] bytes(int n) {
            byte[] out = Arrays.copyOfRange(data, pos, pos + n);
            pos += n;
            return out;
        }
    }
}
