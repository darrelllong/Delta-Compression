package delta;

import java.util.ArrayList;
import java.util.List;

import static delta.Types.*;

/**
 * Unified binary delta format (Section 2.1.1).
 *
 * Header: magic (4 bytes) + flags (1 byte) + version_size (u32 BE)
 * Commands:
 *   END:  type=0
 *   COPY: type=1, src:u32, dst:u32, len:u32
 *   ADD:  type=2, dst:u32, len:u32, data
 */
public final class Encoding {
    private Encoding() {}

    private static final byte[] MAGIC = {'D', 'L', 'T', 0x01};
    private static final byte FLAG_INPLACE = 0x01;

    /** Encode placed commands to the unified binary delta format. */
    public static byte[] encodeDelta(List<PlacedCommand> commands,
                                     boolean inplace, int versionSize) {
        // Estimate size: header(9) + commands + END(1)
        int est = 10;
        for (PlacedCommand cmd : commands) {
            if (cmd instanceof PlacedCopy) est += 13;
            else if (cmd instanceof PlacedAdd a) est += 9 + a.data().length;
        }
        byte[] out = new byte[est];
        int pos = 0;

        // Header
        System.arraycopy(MAGIC, 0, out, 0, 4); pos = 4;
        out[pos++] = inplace ? FLAG_INPLACE : 0;
        putU32BE(out, pos, versionSize); pos += 4;

        for (PlacedCommand cmd : commands) {
            if (cmd instanceof PlacedCopy c) {
                out[pos++] = 1;
                putU32BE(out, pos, c.src()); pos += 4;
                putU32BE(out, pos, c.dst()); pos += 4;
                putU32BE(out, pos, c.length()); pos += 4;
            } else if (cmd instanceof PlacedAdd a) {
                out[pos++] = 2;
                putU32BE(out, pos, a.dst()); pos += 4;
                putU32BE(out, pos, a.data().length); pos += 4;
                System.arraycopy(a.data(), 0, out, pos, a.data().length);
                pos += a.data().length;
            }
        }

        out[pos++] = 0; // END

        if (pos != out.length) {
            byte[] trimmed = new byte[pos];
            System.arraycopy(out, 0, trimmed, 0, pos);
            return trimmed;
        }
        return out;
    }

    /** Decode the unified binary delta format. Returns {commands, inplace, versionSize}. */
    public static DecodeResult decodeDelta(byte[] data) {
        if (data.length < 9) throw new IllegalArgumentException("not a delta file");
        for (int i = 0; i < 4; i++) {
            if (data[i] != MAGIC[i]) throw new IllegalArgumentException("not a delta file");
        }

        boolean inplace = (data[4] & FLAG_INPLACE) != 0;
        int versionSize = getU32BE(data, 5);
        int pos = 9;
        List<PlacedCommand> commands = new ArrayList<>();

        while (pos < data.length) {
            int t = data[pos++] & 0xFF;
            if (t == 0) break; // END

            if (t == 1) {
                // COPY
                if (pos + 12 > data.length) throw new IllegalArgumentException("unexpected EOF");
                int src = getU32BE(data, pos); pos += 4;
                int dst = getU32BE(data, pos); pos += 4;
                int len = getU32BE(data, pos); pos += 4;
                commands.add(new PlacedCopy(src, dst, len));
            } else if (t == 2) {
                // ADD
                if (pos + 8 > data.length) throw new IllegalArgumentException("unexpected EOF");
                int dst = getU32BE(data, pos); pos += 4;
                int len = getU32BE(data, pos); pos += 4;
                if (pos + len > data.length) throw new IllegalArgumentException("unexpected EOF");
                byte[] payload = new byte[len];
                System.arraycopy(data, pos, payload, 0, len);
                pos += len;
                commands.add(new PlacedAdd(dst, payload));
            } else {
                throw new IllegalArgumentException("unknown command type: " + t);
            }
        }

        return new DecodeResult(commands, inplace, versionSize);
    }

    /** Check if binary data is an in-place delta. */
    public static boolean isInplaceDelta(byte[] data) {
        return data.length >= 5
            && data[0] == MAGIC[0] && data[1] == MAGIC[1]
            && data[2] == MAGIC[2] && data[3] == MAGIC[3]
            && (data[4] & FLAG_INPLACE) != 0;
    }

    public record DecodeResult(List<PlacedCommand> commands, boolean inplace, int versionSize) {}

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
