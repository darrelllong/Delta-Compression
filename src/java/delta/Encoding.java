package delta;

import java.util.ArrayList;
import java.util.List;

import static delta.Types.*;

/**
 * Unified binary delta format (Section 2.1.1).
 *
 * Header: magic(4) + flags(1) + version_size(u32 BE) + src_hash(16) + dst_hash(16)
 * Commands:
 *   END:  type=0
 *   COPY: type=1, src:u32, dst:u32, len:u32
 *   ADD:  type=2, dst:u32, len:u32, data
 */
public final class Encoding {
    private Encoding() {}

    /** Encode placed commands to the unified binary delta format. */
    public static byte[] encodeDelta(List<PlacedCommand> commands,
                                     boolean inplace, int versionSize,
                                     byte[] srcHash, byte[] dstHash) {
        // Estimate size: header + commands + END(1)
        int est = DELTA_HEADER_SIZE + 1;
        for (PlacedCommand cmd : commands) {
            if (cmd instanceof PlacedCopy) {
                est += 1 + DELTA_COPY_PAYLOAD;
            } else if (cmd instanceof PlacedAdd) {
                est += 1 + DELTA_ADD_HEADER + ((PlacedAdd) cmd).data.length;
            }
        }
        byte[] out = new byte[est];
        int pos = 0;

        // Header
        System.arraycopy(DELTA_MAGIC, 0, out, 0, DELTA_MAGIC.length);
        pos = DELTA_MAGIC.length;
        out[pos++] = inplace ? DELTA_FLAG_INPLACE : 0;
        putU32BE(out, pos, versionSize); pos += DELTA_U32_SIZE;
        System.arraycopy(srcHash, 0, out, pos, DELTA_HASH_SIZE); pos += DELTA_HASH_SIZE;
        System.arraycopy(dstHash, 0, out, pos, DELTA_HASH_SIZE); pos += DELTA_HASH_SIZE;

        for (PlacedCommand cmd : commands) {
            if (cmd instanceof PlacedCopy) {
                PlacedCopy c = (PlacedCopy) cmd;
                out[pos++] = DELTA_CMD_COPY;
                putU32BE(out, pos, c.src); pos += DELTA_U32_SIZE;
                putU32BE(out, pos, c.dst); pos += DELTA_U32_SIZE;
                putU32BE(out, pos, c.length); pos += DELTA_U32_SIZE;
            } else if (cmd instanceof PlacedAdd) {
                PlacedAdd a = (PlacedAdd) cmd;
                out[pos++] = DELTA_CMD_ADD;
                putU32BE(out, pos, a.dst); pos += DELTA_U32_SIZE;
                putU32BE(out, pos, a.data.length); pos += DELTA_U32_SIZE;
                System.arraycopy(a.data, 0, out, pos, a.data.length);
                pos += a.data.length;
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

    /** Decode the unified binary delta format. */
    public static DecodeResult decodeDelta(byte[] data) {
        if (data.length < DELTA_HEADER_SIZE) {
            throw new IllegalArgumentException("not a delta file");
        }
        for (int i = 0; i < DELTA_MAGIC.length; i++) {
            if (data[i] != DELTA_MAGIC[i]) {
                throw new IllegalArgumentException("not a delta file");
            }
        }

        boolean inplace = (data[DELTA_MAGIC.length] & DELTA_FLAG_INPLACE) != 0;
        int versionSize = getU32BE(data, DELTA_MAGIC.length + 1);
        int hashOff = DELTA_MAGIC.length + 1 + DELTA_U32_SIZE;
        byte[] srcHash = new byte[DELTA_HASH_SIZE];
        byte[] dstHash = new byte[DELTA_HASH_SIZE];
        System.arraycopy(data, hashOff, srcHash, 0, DELTA_HASH_SIZE);
        System.arraycopy(data, hashOff + DELTA_HASH_SIZE, dstHash, 0, DELTA_HASH_SIZE);
        int pos = DELTA_HEADER_SIZE;
        List<PlacedCommand> commands = new ArrayList<>();

        while (pos < data.length) {
            int t = data[pos++] & 0xFF;
            if (t == DELTA_CMD_END) break;

            if (t == DELTA_CMD_COPY) {
                if (pos + DELTA_COPY_PAYLOAD > data.length) {
                    throw new IllegalArgumentException("unexpected EOF");
                }
                int src = getU32BE(data, pos); pos += DELTA_U32_SIZE;
                int dst = getU32BE(data, pos); pos += DELTA_U32_SIZE;
                int len = getU32BE(data, pos); pos += DELTA_U32_SIZE;
                commands.add(new PlacedCopy(src, dst, len));
            } else if (t == DELTA_CMD_ADD) {
                if (pos + DELTA_ADD_HEADER > data.length) {
                    throw new IllegalArgumentException("unexpected EOF");
                }
                int dst = getU32BE(data, pos); pos += DELTA_U32_SIZE;
                int len = getU32BE(data, pos); pos += DELTA_U32_SIZE;
                if (pos + len > data.length) {
                    throw new IllegalArgumentException("unexpected EOF");
                }
                byte[] payload = new byte[len];
                System.arraycopy(data, pos, payload, 0, len);
                pos += len;
                commands.add(new PlacedAdd(dst, payload));
            } else {
                throw new IllegalArgumentException("unknown command type: " + t);
            }
        }

        return new DecodeResult(commands, inplace, versionSize, srcHash, dstHash);
    }

    /** Check if binary data is an in-place delta. */
    public static boolean isInplaceDelta(byte[] data) {
        if (data.length < DELTA_MAGIC.length + 1) return false;
        for (int i = 0; i < DELTA_MAGIC.length; i++) {
            if (data[i] != DELTA_MAGIC[i]) return false;
        }
        return (data[DELTA_MAGIC.length] & DELTA_FLAG_INPLACE) != 0;
    }

    public static final class DecodeResult {
        public final List<PlacedCommand> commands;
        public final boolean inplace;
        public final int versionSize;
        public final byte[] srcHash;
        public final byte[] dstHash;
        DecodeResult(List<PlacedCommand> commands, boolean inplace, int versionSize,
                     byte[] srcHash, byte[] dstHash) {
            this.commands = commands;
            this.inplace = inplace;
            this.versionSize = versionSize;
            this.srcHash = srcHash;
            this.dstHash = dstHash;
        }
    }

    private static void putU32BE(byte[] buf, int off, int value) {
        buf[off]     = (byte) (value >>> 24);
        buf[off + 1] = (byte) (value >>> 16);
        buf[off + 2] = (byte) (value >>> 8);
        buf[off + 3] = (byte) value;
    }

    private static int getU32BE(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 24)
             | ((buf[off + 1] & 0xFF) << 16)
             | ((buf[off + 2] & 0xFF) << 8)
             | (buf[off + 3] & 0xFF);
    }
}
