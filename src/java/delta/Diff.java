package delta;

import java.util.List;

import static delta.Types.*;

/** Dispatch to the appropriate differencing algorithm. */
public final class Diff {
    private Diff() {}

    public static List<Command> diff(Algorithm algo, byte[] r, byte[] v,
                                     int p, int q, boolean verbose,
                                     boolean useSplay, int minCopy) {
        return switch (algo) {
            case GREEDY -> Greedy.diff(r, v, p, q, verbose, useSplay, minCopy);
            case ONEPASS -> Onepass.diff(r, v, p, q, verbose, useSplay, minCopy);
            case CORRECTING -> Correcting.diff(r, v, p, q, 256, verbose, useSplay, minCopy);
        };
    }

    public static List<Command> diffDefault(Algorithm algo, byte[] r, byte[] v) {
        return diff(algo, r, v, SEED_LEN, TABLE_SIZE, false, false, 0);
    }
}
