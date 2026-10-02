package delta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import static delta.Types.*;

/** The command-line tool.  Run it without arguments for the usage. */
public final class Delta {
    private Delta() {}

    private static final HexFormat HEX = HexFormat.of();

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException | IOException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException {
        if (args.length < 1) usage();
        switch (args[0]) {
            case "encode"  -> encode(args);
            case "decode"  -> decode(args);
            case "info"    -> info(args);
            case "inplace" -> inplace(args);
            default        -> usage();
        }
    }

    private static void usage() {
        throw new IllegalArgumentException(
            "Usage:\n" +
            "  java delta.Delta encode <algorithm> <ref> <ver> <delta> [options]\n" +
            "  java delta.Delta decode <ref> <delta> <output> [--ignore-hash]\n" +
            "  java delta.Delta info <delta>\n" +
            "  java delta.Delta inplace <ref> <delta_in> <delta_out> [--policy P]\n\n" +
            "Algorithms: greedy, onepass, correcting\n" +
            "Options: --seed-len N, --table-size N, --max-table N (k/M/B ok),\n" +
            "         --inplace, --policy P, --verbose, --splay");
    }

    private static void encode(String[] args) throws IOException {
        if (args.length < 5) usage();

        Algorithm algo   = parseAlgorithm(args[1]);
        String refPath   = args[2];
        String verPath   = args[3];
        String deltaPath = args[4];

        DiffOptions opts = new DiffOptions();
        boolean inplace = false;
        boolean forceLarge = false;
        CyclePolicy policy = CyclePolicy.LOCALMIN;

        for (int i = 5; i < args.length; i++) {
            switch (args[i]) {
                case "--seed-len"   -> opts.p = Integer.parseInt(optionValue(args, ++i));
                case "--table-size" -> opts.q = Integer.parseInt(optionValue(args, ++i));
                case "--max-table"  -> opts.maxTable = parseSize(optionValue(args, ++i));
                case "--policy"     -> policy = parsePolicy(optionValue(args, ++i));
                case "--inplace"    -> inplace = true;
                case "--large"      -> forceLarge = true;
                case "--verbose"    -> opts.verbose = true;
                case "--splay"      -> opts.useSplay = true;
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        if (opts.p < 1) {
            throw new IllegalArgumentException("--seed-len must be >= 1");
        }

        byte[] r = readFile(refPath);
        byte[] v = readFile(verPath);
        byte[] srcCrc = Hash.Crc64.hash8(r);
        byte[] dstCrc = Hash.Crc64.hash8(v);

        long t0 = System.nanoTime();
        List<Command> commands = Diff.diff(algo, r, v, opts);
        List<PlacedCommand> placed = inplace
            ? Apply.makeInplace(r, commands, policy)
            : Apply.placeCommands(commands);
        long elapsed = System.nanoTime() - t0;

        byte[] delta = Encoding.encodeDeltaLarge(placed, inplace, v.length, srcCrc, dstCrc, forceLarge);
        writeFile(deltaPath, delta);

        PlacedSummary stats = Apply.placedSummary(placed);
        String name = lower(algo) + (opts.useSplay ? " [splay]" : "");
        if (inplace) name += " + in-place (" + lower(policy) + ")";
        System.out.printf("Algorithm:    %s%n", name);
        System.out.printf("Reference:    %s (%d bytes)%n", refPath, r.length);
        System.out.printf("Version:      %s (%d bytes)%n", verPath, v.length);
        System.out.printf("Delta:        %s (%d bytes)%n", deltaPath, delta.length);
        System.out.printf("Compression:  %.4f (delta/version)%n",
            v.length > 0 ? (double) delta.length / v.length : 0);
        System.out.printf("Commands:     %d copies, %d adds%n", stats.numCopies(), stats.numAdds());
        System.out.printf("Copy bytes:   %d%n", stats.copyBytes());
        System.out.printf("Add bytes:    %d%n", stats.addBytes());
        System.out.printf("Src CRC:      %s%n", HEX.formatHex(srcCrc));
        System.out.printf("Dst CRC:      %s%n", HEX.formatHex(dstCrc));
        System.out.printf("Time:         %.3fs%n", elapsed / 1e9);
    }

    private static void decode(String[] args) throws IOException {
        if (args.length < 4) usage();

        String refPath   = args[1];
        String deltaPath = args[2];
        String outPath   = args[3];

        boolean ignoreHash = false;
        for (int i = 4; i < args.length; i++) {
            if (!args[i].equals("--ignore-hash")) {
                throw new IllegalArgumentException("Unknown decode option: " + args[i]);
            }
            ignoreHash = true;
        }

        byte[] r     = readFile(refPath);
        byte[] delta = readFile(deltaPath);
        Encoding.DecodeResult result = Encoding.decodeDelta(delta);

        if (!referenceMatches(r, result)) {
            if (!ignoreHash) System.exit(1);
            System.err.println("warning: skipping source CRC check (--ignore-hash)");
        }
        Apply.validatePlacedCommands(result.commands(), r.length, result.versionSize(), result.inplace());

        long size = result.versionSize();
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("version size too large for JVM: " + size);
        }
        long t0 = System.nanoTime();
        byte[] out;
        if (result.inplace()) {
            out = Apply.applyDeltaInplace(r, result.commands(), size);
        } else {
            out = new byte[(int) size];
            Apply.applyPlacedTo(r, result.commands(), out);
        }
        long elapsed = System.nanoTime() - t0;

        // The output is written even if its CRC then turns out wrong.
        writeFile(outPath, out);
        if (!Arrays.equals(Hash.Crc64.hash8(out), result.dstCrc())) {
            if (!ignoreHash) {
                System.err.println("output integrity check failed");
                System.exit(1);
            }
            System.err.println("warning: skipping output CRC check (--ignore-hash)");
        }

        System.out.printf("Format:       %s%n", result.inplace() ? "in-place" : "standard");
        System.out.printf("Reference:    %s (%d bytes)%n", refPath, r.length);
        System.out.printf("Delta:        %s (%d bytes)%n", deltaPath, delta.length);
        System.out.printf("Output:       %s (%d bytes)%n", outPath, out.length);
        if (!ignoreHash) {
            System.out.printf("Src CRC:      %s  OK%n", HEX.formatHex(result.srcCrc()));
            System.out.printf("Dst CRC:      %s  OK%n", HEX.formatHex(result.dstCrc()));
        }
        System.out.printf("Time:         %.3fs%n", elapsed / 1e9);
    }

    private static void info(String[] args) throws IOException {
        if (args.length < 2) usage();

        String deltaPath = args[1];
        byte[] delta = readFile(deltaPath);
        Encoding.DecodeResult result = Encoding.decodeDelta(delta);
        PlacedSummary stats = Apply.placedSummary(result.commands());

        System.out.printf("Delta file:   %s (%d bytes)%n", deltaPath, delta.length);
        System.out.printf("Format:       %s%n", result.inplace() ? "in-place" : "standard");
        System.out.printf("Version size: %d bytes%n", result.versionSize());
        System.out.printf("Src CRC:      %s%n", HEX.formatHex(result.srcCrc()));
        System.out.printf("Dst CRC:      %s%n", HEX.formatHex(result.dstCrc()));
        System.out.printf("Commands:     %d%n", stats.numCommands());
        System.out.printf("  Copies:     %d (%d bytes)%n", stats.numCopies(), stats.copyBytes());
        System.out.printf("  Adds:       %d (%d bytes)%n", stats.numAdds(), stats.addBytes());
        System.out.printf("Output size:  %d bytes%n", stats.totalOutputBytes());
    }

    /** Converts a standard delta to an in-place one. */
    private static void inplace(String[] args) throws IOException {
        if (args.length < 4) usage();

        String refPath = args[1];
        String inPath  = args[2];
        String outPath = args[3];

        CyclePolicy policy = CyclePolicy.LOCALMIN;
        boolean forceLarge = false;
        for (int i = 4; i < args.length; i++) {
            switch (args[i]) {
                case "--policy" -> policy = parsePolicy(optionValue(args, ++i));
                case "--large"  -> forceLarge = true;
                default -> throw new IllegalArgumentException("Unknown inplace option: " + args[i]);
            }
        }

        byte[] r     = readFile(refPath);
        byte[] delta = readFile(inPath);
        Encoding.DecodeResult result = Encoding.decodeDelta(delta);

        if (result.inplace()) {
            writeFile(outPath, delta);
            System.out.println("Delta is already in-place format; copied unchanged.");
            return;
        }

        // The conversion reads the reference, so it must be the right one.
        if (!referenceMatches(r, result)) System.exit(1);
        Apply.validatePlacedCommands(result.commands(), r.length, result.versionSize(), false);

        long t0 = System.nanoTime();
        List<Command> commands = Apply.unplaceCommands(result.commands());
        List<PlacedCommand> placed = Apply.makeInplace(r, commands, policy);
        long elapsed = System.nanoTime() - t0;

        byte[] out = Encoding.encodeDeltaLarge(placed, true, result.versionSize(),
                                               result.srcCrc(), result.dstCrc(), forceLarge);
        writeFile(outPath, out);

        PlacedSummary stats = Apply.placedSummary(placed);
        System.out.printf("Reference:    %s (%d bytes)%n", refPath, r.length);
        System.out.printf("Input delta:  %s (%d bytes)%n", inPath, delta.length);
        System.out.printf("Output delta: %s (%d bytes)%n", outPath, out.length);
        System.out.printf("Format:       in-place (%s)%n", lower(policy));
        System.out.printf("Commands:     %d copies, %d adds%n", stats.numCopies(), stats.numAdds());
        System.out.printf("Copy bytes:   %d%n", stats.copyBytes());
        System.out.printf("Add bytes:    %d%n", stats.addBytes());
        System.out.printf("Time:         %.3fs%n", elapsed / 1e9);
    }

    /** Reports whether r has the source CRC that the delta records; if not, says so on stderr. */
    private static boolean referenceMatches(byte[] r, Encoding.DecodeResult result) {
        byte[] crc = Hash.Crc64.hash8(r);
        if (Arrays.equals(crc, result.srcCrc())) return true;
        System.err.printf("source file does not match delta: expected %s, got %s%n",
            HEX.formatHex(result.srcCrc()), HEX.formatHex(crc));
        return false;
    }

    /** Returns args[i], the value of the option args[i - 1]. */
    private static String optionValue(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException(args[i - 1] + ": missing value");
        }
        return args[i];
    }

    /** Parses a size with an optional decimal suffix: k, M, or B for 10^9. */
    private static int parseSize(String s) {
        if (s.isEmpty()) throw new IllegalArgumentException("empty size value");
        long mult = switch (s.charAt(s.length() - 1)) {
            case 'k', 'K' -> 1_000L;
            case 'm', 'M' -> 1_000_000L;
            case 'b', 'B' -> 1_000_000_000L;
            default -> 1;
        };
        String digits = mult == 1 ? s : s.substring(0, s.length() - 1);
        long size = Long.parseLong(digits) * mult;
        if (size < 0 || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("size too large: " + s);
        }
        return (int) size;
    }

    private static Algorithm parseAlgorithm(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "greedy"     -> Algorithm.GREEDY;
            case "onepass"    -> Algorithm.ONEPASS;
            case "correcting" -> Algorithm.CORRECTING;
            default -> throw new IllegalArgumentException("Unknown algorithm: " + s);
        };
    }

    private static CyclePolicy parsePolicy(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "localmin" -> CyclePolicy.LOCALMIN;
            case "constant" -> CyclePolicy.CONSTANT;
            default -> throw new IllegalArgumentException("Unknown policy: " + s);
        };
    }

    private static String lower(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    private static byte[] readFile(String path) throws IOException {
        return Files.readAllBytes(Path.of(path));
    }

    private static void writeFile(String path, byte[] data) throws IOException {
        Files.write(Path.of(path), data);
    }
}
