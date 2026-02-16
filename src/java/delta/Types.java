package delta;

/** Shared types and constants (Ajtai et al. 2002). */
public final class Types {
    private Types() {}

    public static final int SEED_LEN = 16;
    public static final int TABLE_SIZE = 1048573; // largest prime < 2^20
    public static final long HASH_BASE = 263;
    public static final long HASH_MOD = (1L << 61) - 1; // Mersenne prime

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
