package fuzz;

import delta.Apply;
import delta.Diff;
import delta.Encoding;
import delta.Hash;
import delta.Types.*;

import java.util.Arrays;
import java.util.List;

/**
 * Jazzer target: a greedy diff, encoded, decoded and applied, must give back
 * the version.
 *
 * The first input byte b splits the rest into reference and version at
 * index 1 + b * (length - 1) / 256.  Inputs over 4 KiB are skipped, since
 * greedy is quadratic.
 *
 * <pre>
 *   fuzz/jazzer --target_class=fuzz.FuzzRoundtrip --cp=out/ --instrumentation_includes=delta.** \
 *       --reproducer_path=fuzz/findings/ -max_total_time=300
 * </pre>
 */
public class FuzzRoundtrip {
    public static void fuzzerTestOneInput(byte[] data) {
        if (data.length < 2 || data.length > 4096) return;

        int split = 1 + ((data[0] & 0xFF) * (data.length - 1)) / 256;
        byte[] ref = Arrays.copyOfRange(data, 1, split);
        byte[] ver = Arrays.copyOfRange(data, split, data.length);

        List<Command> cmds = Diff.diff(Algorithm.GREEDY, ref, ver, new DiffOptions());
        List<PlacedCommand> placed = Apply.placeCommands(cmds);
        byte[] srcCrc = Hash.Crc64.hash8(ref);
        byte[] dstCrc = Hash.Crc64.hash8(ver);
        byte[] encoded = Encoding.encodeDeltaLarge(placed, false, ver.length, srcCrc, dstCrc, false);

        Encoding.DecodeResult result;
        try {
            result = Encoding.decodeDelta(encoded);
        } catch (IllegalArgumentException e) {
            throw new AssertionError("decode failed on valid encoder output: " + e.getMessage(), e);
        }

        if (!Arrays.equals(result.srcCrc(), srcCrc))
            throw new AssertionError("src_crc did not round-trip");
        if (!Arrays.equals(result.dstCrc(), dstCrc))
            throw new AssertionError("dst_crc did not round-trip");
        if (result.versionSize() != ver.length)
            throw new AssertionError("version_size did not round-trip");

        byte[] out = new byte[(int) result.versionSize()];
        Apply.applyPlacedTo(ref, result.commands(), out);
        if (!Arrays.equals(out, ver))
            throw new AssertionError("reconstructed output differs from version");
    }
}
