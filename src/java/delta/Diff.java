package delta;

import java.util.ArrayList;
import java.util.List;

import static delta.Types.*;

/** Dispatch to the appropriate differencing algorithm. */
public final class Diff {
    private Diff() {}

    // ── Shared utilities used by all three algorithm implementations ──

    /** Compare byte subarrays for equality. */
    static boolean arrayEquals(byte[] a, int aOff, byte[] b, int bOff, int len) {
        for (int i = 0; i < len; i++) {
            if (a[aOff + i] != b[bOff + i]) return false;
        }
        return true;
    }

    /** Copy a range of bytes into a new array. */
    static byte[] copyRange(byte[] data, int from, int to) {
        byte[] result = new byte[to - from];
        System.arraycopy(data, from, result, 0, to - from);
        return result;
    }

    /** Print delta compression statistics to stderr. */
    static void printStats(List<Command> commands) {
        List<Integer> copyLens = new ArrayList<>();
        long totalCopy = 0, totalAdd = 0;
        int numCopies = 0, numAdds = 0;
        for (Command cmd : commands) {
            if (cmd instanceof CopyCmd) {
                CopyCmd c = (CopyCmd) cmd;
                totalCopy += c.length;
                numCopies++;
                copyLens.add(c.length);
            } else if (cmd instanceof AddCmd) {
                AddCmd a = (AddCmd) cmd;
                totalAdd += a.data.length;
                numAdds++;
            }
        }
        long totalOut = totalCopy + totalAdd;
        double copyPct = totalOut > 0 ? totalCopy * 100.0 / totalOut : 0;
        System.err.printf("  result: %d copies (%d bytes), %d adds (%d bytes)%n" +
            "  result: copy coverage %.1f%%, output %d bytes%n",
            numCopies, totalCopy, numAdds, totalAdd, copyPct, totalOut);
        if (!copyLens.isEmpty()) {
            copyLens.sort(null);
            double mean = totalCopy / (double) copyLens.size();
            int median = copyLens.get(copyLens.size() / 2);
            System.err.printf("  copies: %d regions, min=%d max=%d mean=%.1f median=%d bytes%n",
                copyLens.size(), copyLens.get(0), copyLens.get(copyLens.size() - 1), mean, median);
        }
    }

    public static List<Command> diff(Algorithm algo, byte[] r, byte[] v,
                                     DiffOptions opts) {
        switch (algo) {
            case GREEDY:
                return Greedy.diff(r, v, opts);
            case ONEPASS:
                return Onepass.diff(r, v, opts);
            case CORRECTING:
                return Correcting.diff(r, v, opts);
            default:
                throw new IllegalArgumentException("unknown algorithm: " + algo);
        }
    }

    public static List<Command> diffDefault(Algorithm algo, byte[] r, byte[] v) {
        return diff(algo, r, v, new DiffOptions());
    }
}
