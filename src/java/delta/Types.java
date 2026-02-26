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

    /** Differencing algorithm selection. */
    public enum Algorithm {
        /** Optimal under simple cost; O(|V|·|R|) time, O(|R|) space (Section 3). */
        GREEDY,
        /** Linear time and near-constant space; concurrent scan of R and V (Section 4). */
        ONEPASS,
        /** Near-optimal, 1.5-pass; hash table with fingerprint checkpointing (Sections 7–8). */
        CORRECTING
    }

    /** Cycle-breaking policy for in-place reordering (Section 4.3 of Burns et al. 2003). */
    public enum CyclePolicy {
        /** Break each cycle at the copy with the shortest length, minimising literal bytes added. */
        LOCALMIN,
        /** Break each cycle at the first remaining vertex; simpler but ignores copy lengths. */
        CONSTANT
    }

    // ── Algorithm-level commands (offset into R, no destination yet) ──

    /**
     * Algorithm-level command, as produced by the diff algorithms.
     *
     * Offsets are positions in R or V at the time of the diff scan; destinations
     * are not yet assigned.  Call placeCommands (or makeInplace) to get
     * PlacedCommands ready for encoding and application.
     */
    public sealed interface Command permits CopyCmd, AddCmd {}

    /** Copy {@code length} bytes starting at {@code offset} in the reference R. */
    public record CopyCmd(int offset, int length) implements Command {}

    /** Append literal bytes from V that could not be matched in R. */
    public record AddCmd(byte[] data)             implements Command {}

    // ── Placed commands (explicit src/dst for binary encoding) ──

    /**
     * A command with explicit source and destination byte offsets (Section 2.1.1).
     *
     * Produced by placeCommands or makeInplace; required for delta encoding and
     * for in-place or standard application.
     */
    public sealed interface PlacedCommand permits PlacedCopy, PlacedAdd {}

    /** Copy {@code length} bytes from {@code src} in R (or working buffer) to {@code dst} in output. */
    public record PlacedCopy(int src, int dst, int length) implements PlacedCommand {}

    /** Write literal bytes to {@code dst} in the output. */
    public record PlacedAdd(int dst, byte[] data)          implements PlacedCommand {}

    // ── Diff options (mutable — not a record) ──

    /**
     * Tuning parameters for differencing algorithms.
     *
     * All fields are public for direct mutation; no defensive copy is made.
     */
    public static final class DiffOptions {
        /** Seed length: minimum match length and fingerprint window (Section 2.1.3). */
        public int     p        = SEED_LEN;
        /** Hash table capacity floor; algorithms auto-size upward from input length. */
        public int     q        = TABLE_SIZE;
        /** Lookback buffer depth for the correcting algorithm (Section 5.2). */
        public int     bufCap   = DELTA_BUF_CAP;
        /** Print per-run statistics to stderr when true. */
        public boolean verbose  = false;
        /** Use a Sleator-Tarjan splay tree instead of a hash table for R lookups. */
        public boolean useSplay = false;
        /** Auto-sizing ceiling; prevents unbounded memory use on very large inputs. */
        public int     maxTable = MAX_TABLE_SIZE;
    }

    // ── Statistics ──

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
    public record PlacedSummary(
        int  numCommands,
        int  numCopies,
        int  numAdds,
        long copyBytes,
        long addBytes,
        long totalOutputBytes
    ) {}
}
