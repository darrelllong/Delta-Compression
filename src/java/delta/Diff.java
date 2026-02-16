package delta;

import java.util.List;

import static delta.Types.*;

/** Dispatch to the appropriate differencing algorithm. */
public final class Diff {
    private Diff() {}

    public static List<Command> diff(Algorithm algo, byte[] r, byte[] v,
                                     int p, int q, boolean verbose,
                                     boolean useSplay, int minCopy) {
        switch (algo) {
            case GREEDY:
                return Greedy.diff(r, v, p, q, verbose, useSplay, minCopy);
            case ONEPASS:
                return Onepass.diff(r, v, p, q, verbose, useSplay, minCopy);
            case CORRECTING:
                return Correcting.diff(r, v, p, q, 256, verbose, useSplay, minCopy);
            default:
                throw new IllegalArgumentException("unknown algorithm: " + algo);
        }
    }

    public static List<Command> diffDefault(Algorithm algo, byte[] r, byte[] v) {
        return diff(algo, r, v, SEED_LEN, TABLE_SIZE, false, false, 0);
    }
}
