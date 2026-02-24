package delta;

/** Shared types and constants (Ajtai et al. 2002). */
public final class Types {
    private Types() {}

    public static final int  SEED_LEN      = 16;
    public static final int  TABLE_SIZE    = 1048573;       // largest prime < 2^20
    public static final int  MAX_TABLE_SIZE = 1073741827;   // prime near 2^30; default ceiling for auto-sizing
    public static final long HASH_BASE     = 263;
    public static final long HASH_MOD      = (1L << 61) - 1; // Mersenne prime

    // Binary delta format constants
    public static final byte[] DELTA_MAGIC        = {'D', 'L', 'T', 0x03};
    public static final byte   DELTA_FLAG_INPLACE = 0x01;
    public static final int    DELTA_CMD_END      = 0;
    public static final int    DELTA_CMD_COPY     = 1;
    public static final int    DELTA_CMD_ADD      = 2;
    public static final int    DELTA_CRC_SIZE     = 8;     // CRC-64/XZ digest bytes
    public static final int    DELTA_HEADER_SIZE  = 25;    // magic(4)+flags(1)+version_size(4)+src_crc(8)+dst_crc(8)
    public static final int    DELTA_U32_SIZE     = 4;
    public static final int    DELTA_COPY_PAYLOAD = 12;    // src(4) + dst(4) + len(4)
    public static final int    DELTA_ADD_HEADER   = 8;     // dst(4) + len(4)
    public static final int    DELTA_BUF_CAP      = 256;

    public enum Algorithm   { GREEDY, ONEPASS, CORRECTING }
    public enum CyclePolicy { LOCALMIN, CONSTANT }

    // ── Algorithm-level commands (offset into R, no destination yet) ──

    public sealed interface Command permits CopyCmd, AddCmd {}

    public record CopyCmd(int offset, int length) implements Command {}
    public record AddCmd(byte[] data)             implements Command {}

    // ── Placed commands (explicit src/dst for binary encoding) ──

    public sealed interface PlacedCommand permits PlacedCopy, PlacedAdd {}

    public record PlacedCopy(int src, int dst, int length) implements PlacedCommand {}
    public record PlacedAdd(int dst, byte[] data)          implements PlacedCommand {}

    // ── Diff options (mutable — not a record) ──

    public static final class DiffOptions {
        public int     p        = SEED_LEN;
        public int     q        = TABLE_SIZE;
        public int     bufCap   = DELTA_BUF_CAP;
        public boolean verbose  = false;
        public boolean useSplay = false;
        public int     maxTable = MAX_TABLE_SIZE;
    }

    // ── Statistics ──

    public record PlacedSummary(
        int  numCommands,
        int  numCopies,
        int  numAdds,
        long copyBytes,
        long addBytes,
        long totalOutputBytes
    ) {}
}
