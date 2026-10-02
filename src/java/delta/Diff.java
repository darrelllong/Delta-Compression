package delta;

import java.util.Arrays;
import java.util.List;

import static delta.Types.*;

/** Entry point to the differencing algorithms, and the pieces they share. */
public final class Diff {
    private Diff() {}

    /** Returns commands that build v from r, computed by the given algorithm. */
    public static List<Command> diff(Algorithm algo, byte[] r, byte[] v,
                                     DiffOptions opts) {
        return switch (algo) {
            case GREEDY     -> Greedy.diff(r, v, opts);
            case ONEPASS    -> Onepass.diff(r, v, opts);
            case CORRECTING -> Correcting.diff(r, v, opts);
        };
    }

    /** Calls {@link #diff} with the default options. */
    public static List<Command> diffDefault(Algorithm algo, byte[] r, byte[] v) {
        return diff(algo, r, v, new DiffOptions());
    }

    /** Returns the number of p-byte seeds in data. */
    static int seedCount(byte[] data, int p) {
        return data.length >= p ? data.length - p + 1 : 0;
    }

    /** Reports whether the p bytes at a[aOff] equal the p bytes at b[bOff]. */
    static boolean seedsEqual(byte[] a, int aOff, byte[] b, int bOff, int p) {
        return Arrays.equals(a, aOff, aOff + p, b, bOff, bOff + p);
    }

    /** Returns how many bytes starting at a[aOff] and b[bOff] agree. */
    static int matchLength(byte[] a, int aOff, byte[] b, int bOff) {
        int n = Math.min(a.length - aOff, b.length - bOff);
        int diff = Arrays.mismatch(a, aOff, aOff + n, b, bOff, bOff + n);
        return diff < 0 ? n : diff;
    }

    /** Returns an add of v[from..to). */
    static AddCmd literal(byte[] v, int from, int to) {
        return new AddCmd(Arrays.copyOfRange(v, from, to));
    }

    /** Prints the verbose summary of a finished diff to stderr. */
    static void printStats(List<Command> commands) {
        long[] copyLens = new long[commands.size()];
        int numCopies = 0, numAdds = 0;
        long totalCopy = 0, totalAdd = 0;
        for (Command cmd : commands) {
            if (cmd instanceof CopyCmd c) {
                totalCopy += c.length();
                copyLens[numCopies++] = c.length();
            } else if (cmd instanceof AddCmd a) {
                totalAdd += a.data().length;
                numAdds++;
            }
        }
        long totalOut = totalCopy + totalAdd;
        double copyPct = totalOut > 0 ? totalCopy * 100.0 / totalOut : 0;
        System.err.printf("  result: %d copies (%d bytes), %d adds (%d bytes)%n" +
            "  result: copy coverage %.1f%%, output %d bytes%n",
            numCopies, totalCopy, numAdds, totalAdd, copyPct, totalOut);
        if (numCopies > 0) {
            Arrays.sort(copyLens, 0, numCopies);
            double mean = totalCopy / (double) numCopies;
            System.err.printf("  copies: %d regions, min=%d max=%d mean=%.1f median=%d bytes%n",
                numCopies, copyLens[0], copyLens[numCopies - 1], mean, copyLens[numCopies / 2]);
        }
    }
}
