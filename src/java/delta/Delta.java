package delta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static delta.Types.*;

/**
 * CLI for differential compression (Ajtai et al. 2002).
 *
 * Usage:
 *   java delta.Delta encode <algorithm> <reference> <version> <delta>  [options]
 *   java delta.Delta decode <reference> <delta> <output>
 *   java delta.Delta info <delta>
 *
 * Options:
 *   --seed-len N      Seed length (default 16)
 *   --table-size N    Hash table size floor (default 1048573)
 *   --inplace         Produce in-place reconstructible delta
 *   --policy P        Cycle-breaking policy: localmin|constant (default localmin)
 *   --verbose         Print diagnostic messages to stderr
 *   --splay           Use splay tree instead of hash table
 *   --min-copy N      Minimum copy length (default 0 = use seed length)
 */
public final class Delta {

    public static void main(String[] args) {
        if (args.length < 1) { usage(); return; }

        switch (args[0]) {
            case "encode" -> encode(args);
            case "decode" -> decode(args);
            case "info"   -> info(args);
            default       -> usage();
        }
    }

    private static void usage() {
        System.err.println("Usage:");
        System.err.println("  java delta.Delta encode <algorithm> <ref> <ver> <delta> [options]");
        System.err.println("  java delta.Delta decode <ref> <delta> <output>");
        System.err.println("  java delta.Delta info <delta>");
        System.err.println();
        System.err.println("Algorithms: greedy, onepass, correcting");
        System.err.println("Options: --seed-len N, --table-size N, --inplace, --policy P,");
        System.err.println("         --verbose, --splay, --min-copy N");
        System.exit(1);
    }

    private static void encode(String[] args) {
        if (args.length < 5) { usage(); return; }

        Algorithm algo = parseAlgorithm(args[1]);
        String refPath = args[2];
        String verPath = args[3];
        String deltaPath = args[4];

        // Defaults
        int seedLen = SEED_LEN;
        int tableSize = TABLE_SIZE;
        boolean inplace = false;
        CyclePolicy policy = CyclePolicy.LOCALMIN;
        boolean verbose = false;
        boolean splay = false;
        int minCopy = 0;

        // Parse options
        for (int i = 5; i < args.length; i++) {
            switch (args[i]) {
                case "--seed-len"   -> seedLen = Integer.parseInt(args[++i]);
                case "--table-size" -> tableSize = Integer.parseInt(args[++i]);
                case "--inplace"    -> inplace = true;
                case "--policy"     -> policy = parsePolicy(args[++i]);
                case "--verbose"    -> verbose = true;
                case "--splay"      -> splay = true;
                case "--min-copy"   -> minCopy = Integer.parseInt(args[++i]);
                default -> { System.err.println("Unknown option: " + args[i]); System.exit(1); }
            }
        }

        byte[] r = readFile(refPath);
        byte[] v = readFile(verPath);

        long t0 = System.nanoTime();
        List<Command> commands = Diff.diff(algo, r, v, seedLen, tableSize,
                                           verbose, splay, minCopy);

        List<PlacedCommand> placed = inplace
            ? Apply.makeInplace(r, commands, policy)
            : Apply.placeCommands(commands);
        long elapsed = System.nanoTime() - t0;

        byte[] deltaBytes = Encoding.encodeDelta(placed, inplace, v.length);
        writeFile(deltaPath, deltaBytes);

        PlacedSummary stats = Apply.placedSummary(placed);
        double ratio = v.length > 0 ? (double) deltaBytes.length / v.length : 0;
        String algoName = algo.name().toLowerCase();
        String splayTag = splay ? " [splay]" : "";
        if (inplace) {
            System.out.printf("Algorithm:    %s%s + in-place (%s)%n",
                algoName, splayTag, policy.name().toLowerCase());
        } else {
            System.out.printf("Algorithm:    %s%s%n", algoName, splayTag);
        }
        System.out.printf("Reference:    %s (%d bytes)%n", refPath, r.length);
        System.out.printf("Version:      %s (%d bytes)%n", verPath, v.length);
        System.out.printf("Delta:        %s (%d bytes)%n", deltaPath, deltaBytes.length);
        System.out.printf("Compression:  %.4f (delta/version)%n", ratio);
        System.out.printf("Commands:     %d copies, %d adds%n", stats.numCopies(), stats.numAdds());
        System.out.printf("Copy bytes:   %d%n", stats.copyBytes());
        System.out.printf("Add bytes:    %d%n", stats.addBytes());
        System.out.printf("Time:         %.3fs%n", elapsed / 1e9);
    }

    private static void decode(String[] args) {
        if (args.length < 4) { usage(); return; }

        String refPath = args[1];
        String deltaPath = args[2];
        String outPath = args[3];

        byte[] r = readFile(refPath);
        byte[] deltaBytes = readFile(deltaPath);

        long t0 = System.nanoTime();
        Encoding.DecodeResult result = Encoding.decodeDelta(deltaBytes);

        byte[] out;
        if (result.inplace()) {
            out = Apply.applyDeltaInplace(r, result.commands(), result.versionSize());
        } else {
            out = new byte[result.versionSize()];
            Apply.applyPlacedTo(r, result.commands(), out);
        }
        long elapsed = System.nanoTime() - t0;

        writeFile(outPath, out);

        String fmt = result.inplace() ? "in-place" : "standard";
        System.out.printf("Format:       %s%n", fmt);
        System.out.printf("Reference:    %s (%d bytes)%n", refPath, r.length);
        System.out.printf("Delta:        %s (%d bytes)%n", deltaPath, deltaBytes.length);
        System.out.printf("Output:       %s (%d bytes)%n", outPath, out.length);
        System.out.printf("Time:         %.3fs%n", elapsed / 1e9);
    }

    private static void info(String[] args) {
        if (args.length < 2) { usage(); return; }

        String deltaPath = args[1];
        byte[] deltaBytes = readFile(deltaPath);

        Encoding.DecodeResult result = Encoding.decodeDelta(deltaBytes);
        PlacedSummary stats = Apply.placedSummary(result.commands());

        String fmt = result.inplace() ? "in-place" : "standard";
        System.out.printf("Delta file:   %s (%d bytes)%n", deltaPath, deltaBytes.length);
        System.out.printf("Format:       %s%n", fmt);
        System.out.printf("Version size: %d bytes%n", result.versionSize());
        System.out.printf("Commands:     %d%n", stats.numCommands());
        System.out.printf("  Copies:     %d (%d bytes)%n", stats.numCopies(), stats.copyBytes());
        System.out.printf("  Adds:       %d (%d bytes)%n", stats.numAdds(), stats.addBytes());
        System.out.printf("Output size:  %d bytes%n", stats.totalOutputBytes());
    }

    private static Algorithm parseAlgorithm(String s) {
        return switch (s.toLowerCase()) {
            case "greedy" -> Algorithm.GREEDY;
            case "onepass" -> Algorithm.ONEPASS;
            case "correcting" -> Algorithm.CORRECTING;
            default -> {
                System.err.println("Unknown algorithm: " + s);
                System.exit(1);
                yield null;
            }
        };
    }

    private static CyclePolicy parsePolicy(String s) {
        return switch (s.toLowerCase()) {
            case "localmin" -> CyclePolicy.LOCALMIN;
            case "constant" -> CyclePolicy.CONSTANT;
            default -> {
                System.err.println("Unknown policy: " + s);
                System.exit(1);
                yield null;
            }
        };
    }

    private static byte[] readFile(String path) {
        try {
            return Files.readAllBytes(Path.of(path));
        } catch (IOException e) {
            System.err.printf("Error reading %s: %s%n", path, e.getMessage());
            System.exit(1);
            return null;
        }
    }

    private static void writeFile(String path, byte[] data) {
        try {
            Files.write(Path.of(path), data);
        } catch (IOException e) {
            System.err.printf("Error writing %s: %s%n", path, e.getMessage());
            System.exit(1);
        }
    }
}
