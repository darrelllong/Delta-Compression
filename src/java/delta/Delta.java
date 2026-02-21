package delta;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static delta.Types.*;

/**
 * CLI for differential compression (Ajtai et al. 2002).
 *
 * Usage:
 *   java delta.Delta encode algorithm reference version delta  [options]
 *   java delta.Delta decode reference delta output
 *   java delta.Delta info delta
 *
 * Algorithms: greedy, onepass, correcting
 * Options: --seed-len N, --table-size N, --inplace, --policy P,
 *          --verbose, --splay
 */
public final class Delta {

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException {
        if (args.length < 1) usage();

        String cmd = args[0];
        if ("encode".equals(cmd)) {
            encode(args);
        } else if ("decode".equals(cmd)) {
            decode(args);
        } else if ("info".equals(cmd)) {
            info(args);
        } else if ("inplace".equals(cmd)) {
            inplace(args);
        } else {
            usage();
        }
    }

    /** Parse a size string with optional k/M/B suffix (decimal multipliers). */
    private static int parseSizeSuffix(String s) {
        if (s.isEmpty()) throw new IllegalArgumentException("empty size value");
        char last = s.charAt(s.length() - 1);
        long mult = 1;
        String num = s;
        if (last == 'k' || last == 'K') { mult = 1_000L;         num = s.substring(0, s.length() - 1); }
        else if (last == 'M' || last == 'm') { mult = 1_000_000L;     num = s.substring(0, s.length() - 1); }
        else if (last == 'B' || last == 'b') { mult = 1_000_000_000L; num = s.substring(0, s.length() - 1); }
        return (int) (Long.parseLong(num) * mult);
    }

    private static void usage() {
        throw new IllegalArgumentException(
            "Usage:\n" +
            "  java delta.Delta encode <algorithm> <ref> <ver> <delta> [options]\n" +
            "  java delta.Delta decode <ref> <delta> <output>\n" +
            "  java delta.Delta info <delta>\n" +
            "  java delta.Delta inplace <ref> <delta_in> <delta_out> [--policy P]\n\n" +
            "Algorithms: greedy, onepass, correcting\n" +
            "Options: --seed-len N, --table-size N, --max-table N (k/M/B ok),\n" +
            "         --inplace, --policy P, --verbose, --splay");
    }

    private static void encode(String[] args) throws IOException {
        if (args.length < 5) usage();

        Algorithm algo = parseAlgorithm(args[1]);
        String refPath = args[2];
        String verPath = args[3];
        String deltaPath = args[4];

        DiffOptions opts = new DiffOptions();
        boolean inplace = false;
        CyclePolicy policy = CyclePolicy.LOCALMIN;

        for (int i = 5; i < args.length; i++) {
            String opt = args[i];
            if ("--seed-len".equals(opt)) {
                opts.p = Integer.parseInt(args[++i]);
            } else if ("--table-size".equals(opt)) {
                opts.q = Integer.parseInt(args[++i]);
            } else if ("--max-table".equals(opt)) {
                opts.maxTable = parseSizeSuffix(args[++i]);
            } else if ("--inplace".equals(opt)) {
                inplace = true;
            } else if ("--policy".equals(opt)) {
                policy = parsePolicy(args[++i]);
            } else if ("--verbose".equals(opt)) {
                opts.verbose = true;
            } else if ("--splay".equals(opt)) {
                opts.useSplay = true;
            } else {
                throw new IllegalArgumentException("Unknown option: " + opt);
            }
        }

        if (opts.p < 1)
            throw new IllegalArgumentException("--seed-len must be >= 1");

        byte[] r = readFile(refPath);
        byte[] v = readFile(verPath);

        byte[] srcHash = Hash.Shake128.hash16(r);
        byte[] dstHash = Hash.Shake128.hash16(v);

        long t0 = System.nanoTime();
        List<Command> commands = Diff.diff(algo, r, v, opts);

        List<PlacedCommand> placed = inplace
            ? Apply.makeInplace(r, commands, policy)
            : Apply.placeCommands(commands);
        long elapsed = System.nanoTime() - t0;

        byte[] deltaBytes = Encoding.encodeDelta(placed, inplace, v.length, srcHash, dstHash);
        writeFile(deltaPath, deltaBytes);

        PlacedSummary stats = Apply.placedSummary(placed);
        double ratio = v.length > 0 ? (double) deltaBytes.length / v.length : 0;
        String algoName = algo.name().toLowerCase();
        String splayTag = opts.useSplay ? " [splay]" : "";
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
        System.out.printf("Commands:     %d copies, %d adds%n", stats.numCopies, stats.numAdds);
        System.out.printf("Copy bytes:   %d%n", stats.copyBytes);
        System.out.printf("Add bytes:    %d%n", stats.addBytes);
        System.out.printf("Src hash:     %s%n", toHex(srcHash));
        System.out.printf("Dst hash:     %s%n", toHex(dstHash));
        System.out.printf("Time:         %.3fs%n", elapsed / 1e9);
    }

    private static void decode(String[] args) throws IOException {
        if (args.length < 4) usage();

        String refPath = args[1];
        String deltaPath = args[2];
        String outPath = args[3];

        byte[] r = readFile(refPath);
        byte[] deltaBytes = readFile(deltaPath);

        Encoding.DecodeResult result = Encoding.decodeDelta(deltaBytes);

        /* Pre-check: verify reference matches embedded src_hash. */
        byte[] rHash = Hash.Shake128.hash16(r);
        if (!Arrays.equals(rHash, result.srcHash)) {
            System.err.printf("source file does not match delta: expected %s, got %s%n",
                toHex(result.srcHash), toHex(rHash));
            System.exit(1);
        }

        long t0 = System.nanoTime();
        byte[] out;
        if (result.inplace) {
            out = Apply.applyDeltaInplace(r, result.commands, result.versionSize);
        } else {
            out = new byte[result.versionSize];
            Apply.applyPlacedTo(r, result.commands, out);
        }
        long elapsed = System.nanoTime() - t0;

        /* Write output and compute hash in a single pass. */
        byte[] outHash = writeFileHashed(outPath, out);

        /* Post-check: verify output matches embedded dst_hash. */
        if (!Arrays.equals(outHash, result.dstHash)) {
            System.err.println("output integrity check failed");
            System.exit(1);
        }

        String fmt = result.inplace ? "in-place" : "standard";
        System.out.printf("Format:       %s%n", fmt);
        System.out.printf("Reference:    %s (%d bytes)%n", refPath, r.length);
        System.out.printf("Delta:        %s (%d bytes)%n", deltaPath, deltaBytes.length);
        System.out.printf("Output:       %s (%d bytes)%n", outPath, out.length);
        System.out.printf("Src hash:     %s  OK%n", toHex(result.srcHash));
        System.out.printf("Dst hash:     %s  OK%n", toHex(result.dstHash));
        System.out.printf("Time:         %.3fs%n", elapsed / 1e9);
    }

    private static void info(String[] args) throws IOException {
        if (args.length < 2) usage();

        String deltaPath = args[1];
        byte[] deltaBytes = readFile(deltaPath);

        Encoding.DecodeResult result = Encoding.decodeDelta(deltaBytes);
        PlacedSummary stats = Apply.placedSummary(result.commands);

        String fmt = result.inplace ? "in-place" : "standard";
        System.out.printf("Delta file:   %s (%d bytes)%n", deltaPath, deltaBytes.length);
        System.out.printf("Format:       %s%n", fmt);
        System.out.printf("Version size: %d bytes%n", result.versionSize);
        System.out.printf("Src hash:     %s%n", toHex(result.srcHash));
        System.out.printf("Dst hash:     %s%n", toHex(result.dstHash));
        System.out.printf("Commands:     %d%n", stats.numCommands);
        System.out.printf("  Copies:     %d (%d bytes)%n", stats.numCopies, stats.copyBytes);
        System.out.printf("  Adds:       %d (%d bytes)%n", stats.numAdds, stats.addBytes);
        System.out.printf("Output size:  %d bytes%n", stats.totalOutputBytes);
    }

    private static void inplace(String[] args) throws IOException {
        if (args.length < 4) usage();

        String refPath = args[1];
        String deltaInPath = args[2];
        String deltaOutPath = args[3];

        CyclePolicy policy = CyclePolicy.LOCALMIN;
        String policyStr = "localmin";

        for (int i = 4; i < args.length; i++) {
            if ("--policy".equals(args[i]) && i + 1 < args.length) {
                policy = parsePolicy(args[++i]);
                policyStr = policy.name().toLowerCase();
            }
        }

        byte[] r = readFile(refPath);
        byte[] deltaBytes = readFile(deltaInPath);

        Encoding.DecodeResult result = Encoding.decodeDelta(deltaBytes);

        if (result.inplace) {
            writeFile(deltaOutPath, deltaBytes);
            System.out.println("Delta is already in-place format; copied unchanged.");
            return;
        }

        long t0 = System.nanoTime();
        List<Command> commands = Apply.unplaceCommands(result.commands);
        List<PlacedCommand> ipPlaced = Apply.makeInplace(r, commands, policy);
        long elapsed = System.nanoTime() - t0;

        byte[] ipDelta = Encoding.encodeDelta(ipPlaced, true, result.versionSize,
                                               result.srcHash, result.dstHash);
        writeFile(deltaOutPath, ipDelta);

        PlacedSummary stats = Apply.placedSummary(ipPlaced);
        System.out.printf("Reference:    %s (%d bytes)%n", refPath, r.length);
        System.out.printf("Input delta:  %s (%d bytes)%n", deltaInPath, deltaBytes.length);
        System.out.printf("Output delta: %s (%d bytes)%n", deltaOutPath, ipDelta.length);
        System.out.printf("Format:       in-place (%s)%n", policyStr);
        System.out.printf("Commands:     %d copies, %d adds%n", stats.numCopies, stats.numAdds);
        System.out.printf("Copy bytes:   %d%n", stats.copyBytes);
        System.out.printf("Add bytes:    %d%n", stats.addBytes);
        System.out.printf("Time:         %.3fs%n", elapsed / 1e9);
    }

    private static Algorithm parseAlgorithm(String s) {
        if ("greedy".equalsIgnoreCase(s)) return Algorithm.GREEDY;
        if ("onepass".equalsIgnoreCase(s)) return Algorithm.ONEPASS;
        if ("correcting".equalsIgnoreCase(s)) return Algorithm.CORRECTING;
        throw new IllegalArgumentException("Unknown algorithm: " + s);
    }

    private static CyclePolicy parsePolicy(String s) {
        if ("localmin".equalsIgnoreCase(s)) return CyclePolicy.LOCALMIN;
        if ("constant".equalsIgnoreCase(s)) return CyclePolicy.CONSTANT;
        throw new IllegalArgumentException("Unknown policy: " + s);
    }

    private static byte[] readFile(String path) throws IOException {
        return Files.readAllBytes(Path.of(path));
    }

    private static void writeFile(String path, byte[] data) throws IOException {
        Files.write(Path.of(path), data);
    }

    /** Write data to a file, returning its SHAKE128-16 hash computed during the write. */
    private static byte[] writeFileHashed(String path, byte[] data) throws IOException {
        Hash.Shake128 ctx = new Hash.Shake128();
        int chunk = 65536;
        int pos = 0;
        try (OutputStream out = Files.newOutputStream(Path.of(path))) {
            while (pos < data.length) {
                int n = Math.min(chunk, data.length - pos);
                out.write(data, pos, n);
                ctx.update(data, pos, n);
                pos += n;
            }
        }
        return ctx.finish();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes)
            sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }
}
