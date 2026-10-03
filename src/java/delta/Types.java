package delta;

/**
 * Constants and value types shared by the library.
 *
 * Section numbers refer to Ajtai, Burns, Fagin, Long and Stockmeyer, JACM 2002,
 * unless Burns, Long and Stockmeyer, IEEE TKDE 2003, is named.
 */
public final class Types {
    private Types() {}

    /** Default seed length p, in bytes. */
    public static final int  SEED_LEN       = 16;
    /** Default hash table size: the largest prime below 2^20. */
    public static final int  TABLE_SIZE     = 1048573;
    /** Default ceiling for an auto-sized table: a prime just above 2^30. */
    public static final int  MAX_TABLE_SIZE = 1073741827;
    /** The base b of the fingerprint polynomial (Eq. 1). */
    public static final long HASH_BASE      = 263;
    /** The Mersenne prime 2^61 - 1. */
    public static final long HASH_MOD       = (1L << 61) - 1;

    // The wire format.  DLT\x03 has u32 fields and only COPY and ADD; DLT\x04
    // has a u64 version size and adds the commands numbered 3 to 6.
    public static final byte[] DELTA_MAGIC             = {'D', 'L', 'T', 0x03};
    public static final byte[] DELTA_MAGIC_LARGE       = {'D', 'L', 'T', 0x04};
    public static final byte   DELTA_FLAG_INPLACE      = 0x01;
    public static final int    DELTA_CMD_END           = 0;
    public static final int    DELTA_CMD_COPY          = 1;
    public static final int    DELTA_CMD_ADD           = 2;
    public static final int    DELTA_CMD_BIGCOPY       = 3;  // COPY with u64 fields
    public static final int    DELTA_CMD_BIGADD        = 4;  // ADD with u64 dst and length
    public static final int    DELTA_CMD_MOVE          = 5;  // copy within the output, u32 fields
    public static final int    DELTA_CMD_BIGMOVE       = 6;  // MOVE with u64 fields
    public static final int    DELTA_CRC_SIZE          = 8;  // CRC-64/XZ, big-endian
    public static final int    DELTA_HEADER_SIZE       = 25; // magic(4) flags(1) version_size(4) crcs(16)
    public static final int    DELTA_HEADER_SIZE_LARGE = 29; // magic(4) flags(1) version_size(8) crcs(16)
    public static final int    DELTA_U32_SIZE          = 4;
    public static final int    DELTA_U64_SIZE          = 8;
    public static final int    DELTA_COPY_PAYLOAD      = 12; // src(4) dst(4) len(4)
    public static final int    DELTA_ADD_HEADER        = 8;  // dst(4) len(4)
    public static final int    DELTA_BIGCOPY_PAYLOAD   = 24; // src(8) dst(8) len(8)
    public static final int    DELTA_BIGADD_HEADER     = 16; // dst(8) len(8)
    /** Default capacity of the correcting algorithm's lookback buffer, in commands. */
    public static final int    DELTA_BUF_CAP           = 256;

    /** A differencing algorithm. */
    public enum Algorithm {
        /** Optimal under the simple cost measure if p &lt;= 2; O(|V| |R|) time, O(|R|) space (Section 3). */
        GREEDY,
        /** Linear time; scans R and V together, each once, and misses transposed blocks (Section 4). */
        ONEPASS,
        /** Near-optimal 1.5-pass with checkpointed fingerprints (Sections 7 and 8). */
        CORRECTING
    }

    /** How in-place conversion chooses the copy to give up when copies form a cycle. */
    public enum CyclePolicy {
        /** The shortest copy on a cycle, so the fewest literal bytes are added. */
        LOCALMIN,
        /** The lowest-numbered remaining copy; no cycle search, but ignores lengths. */
        CONSTANT
    }

    /**
     * A command as the differencing algorithms produce it: the commands of a
     * delta, in order, write V from left to right, so no destination is stored.
     */
    public sealed interface Command permits CopyCmd, AddCmd {}

    /** Copy {@code length} bytes of R starting at {@code offset}. */
    public record CopyCmd(long offset, long length) implements Command {}

    /** Append the literal bytes {@code data}.  The array is not copied. */
    public record AddCmd(byte[] data) implements Command {}

    /**
     * A command with an explicit destination, which is what the wire format
     * carries and what lets an in-place delta run its commands out of order.
     */
    public sealed interface PlacedCommand permits PlacedCopy, PlacedAdd, PlacedMove {}

    /**
     * Copy {@code length} bytes from {@code src} to {@code dst}.  The source is
     * R in a standard delta and the buffer being rewritten in an in-place one.
     */
    public record PlacedCopy(long src, long dst, long length) implements PlacedCommand {}

    /** Write the literal bytes {@code data} at {@code dst}.  The array is not copied. */
    public record PlacedAdd(long dst, byte[] data) implements PlacedCommand {}

    /**
     * Copy {@code length} bytes of output already written at {@code src} to
     * {@code dst}.  Requires {@code src + length <= dst}, and the DLT\x04 format.
     */
    public record PlacedMove(long src, long dst, long length) implements PlacedCommand {}

    /** Tuning parameters for the differencing algorithms.  Set the fields directly. */
    public static final class DiffOptions {
        /** Seed length: the fingerprint window and the shortest match (Section 2.1.3). */
        public int     p        = SEED_LEN;
        /**
         * Smallest hash table, in slots.  Onepass and correcting use a larger
         * one when |R| asks for it; greedy does not use q.
         */
        public int     q        = TABLE_SIZE;
        /** Commands the correcting algorithm can still revise (Section 5.2). */
        public int     bufCap   = DELTA_BUF_CAP;
        /** Print statistics to stderr. */
        public boolean verbose  = false;
        /** Index fingerprints in a splay tree instead of a hash table. */
        public boolean useSplay = false;
        /** Largest table the correcting algorithm may grow to; 0 means MAX_TABLE_SIZE. */
        public int     maxTable = MAX_TABLE_SIZE;
    }

    /**
     * What in-place conversion did.  The input had numCopies +
     * copiesConverted copies; each cycle is broken by converting one copy,
     * so cyclesBroken and copiesConverted are equal.
     */
    public record InplaceStats(
        int  numCopies,       // copies in the result
        int  numAdds,         // adds in the result, including converted copies
        int  edges,           // edges of the CRWI digraph
        int  cyclesBroken,
        int  copiesConverted, // copies replaced by adds
        long bytesConverted   // their total length
    ) {}

    /** The commands of an in-place delta, and how the conversion went. */
    public record InplaceResult(java.util.List<PlacedCommand> commands, InplaceStats stats) {}

    /**
     * Counts over a list of placed commands.  A MOVE counts as a copy, and
     * totalOutputBytes is copyBytes + addBytes.
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
