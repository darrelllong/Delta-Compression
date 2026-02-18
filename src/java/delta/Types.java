package delta;

/** Shared types and constants (Ajtai et al. 2002). */
public final class Types {
    private Types() {}

    public static final int SEED_LEN = 16;
    public static final int TABLE_SIZE = 1048573; // largest prime < 2^20
    public static final long HASH_BASE = 263;
    public static final long HASH_MOD = (1L << 61) - 1; // Mersenne prime

    // Binary delta format constants
    public static final byte[] DELTA_MAGIC = {'D', 'L', 'T', 0x01};
    public static final byte DELTA_FLAG_INPLACE = 0x01;
    public static final int DELTA_CMD_END  = 0;
    public static final int DELTA_CMD_COPY = 1;
    public static final int DELTA_CMD_ADD  = 2;
    public static final int DELTA_HEADER_SIZE = 9;  // magic(4) + flags(1) + version_size(4)
    public static final int DELTA_U32_SIZE = 4;
    public static final int DELTA_COPY_PAYLOAD = 12; // src(4) + dst(4) + len(4)
    public static final int DELTA_ADD_HEADER = 8;    // dst(4) + len(4)
    public static final int DELTA_BUF_CAP = 256;

    public enum Algorithm { GREEDY, ONEPASS, CORRECTING }
    public enum CyclePolicy { LOCALMIN, CONSTANT }

    // ── Algorithm-level commands (offset into R, no destination yet) ──

    public interface Command {}

    public static final class CopyCmd implements Command {
        public final int offset;
        public final int length;
        public CopyCmd(int offset, int length) { this.offset = offset; this.length = length; }
    }

    public static final class AddCmd implements Command {
        public final byte[] data;
        public AddCmd(byte[] data) { this.data = data; }
    }

    // ── Placed commands (explicit src/dst for binary encoding) ──

    public interface PlacedCommand {}

    public static final class PlacedCopy implements PlacedCommand {
        public final int src, dst, length;
        public PlacedCopy(int src, int dst, int length) {
            this.src = src; this.dst = dst; this.length = length;
        }
    }

    public static final class PlacedAdd implements PlacedCommand {
        public final int dst;
        public final byte[] data;
        public PlacedAdd(int dst, byte[] data) { this.dst = dst; this.data = data; }
    }

    // ── Diff options ──

    public static final class DiffOptions {
        public int p = SEED_LEN;
        public int q = TABLE_SIZE;
        public int bufCap = DELTA_BUF_CAP;
        public boolean verbose = false;
        public boolean useSplay = false;
    }

    // ── Statistics ──

    public static final class PlacedSummary {
        public final int numCommands, numCopies, numAdds;
        public final long copyBytes, addBytes, totalOutputBytes;
        public PlacedSummary(int numCommands, int numCopies, int numAdds,
                             long copyBytes, long addBytes, long totalOutputBytes) {
            this.numCommands = numCommands;
            this.numCopies = numCopies;
            this.numAdds = numAdds;
            this.copyBytes = copyBytes;
            this.addBytes = addBytes;
            this.totalOutputBytes = totalOutputBytes;
        }
    }
}
