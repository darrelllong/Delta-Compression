package fuzz;

import delta.Apply;
import delta.Diff;
import delta.Encoding;
import delta.Hash;
import delta.Types.*;

import java.util.Arrays;
import java.util.List;

/**
 * Jazzer fuzz target: encode→decode→apply round-trip.
 *
 * Input layout: [split_byte | reference... | version...]
 *   split index = 1 + (split_byte * (len-1)) / 256
 *
 * Inputs are capped at 4 KiB (diffGreedy is O(|ref|×|ver|)).
 *
 * Invariants:
 *   1. decodeDelta must not throw on our own encoder output.
 *   2. Reconstructed output must equal the original version bytes.
 *
 * Run:
 *   fuzz/jazzer --target_class=fuzz.FuzzRoundtrip --cp=out/ --instrumentation_includes=delta.** \
 *       --reproducer_path=fuzz/findings/ -max_total_time=300
 */
public class FuzzRoundtrip {
    public static void fuzzerTestOneInput(byte[] data) {
        if (data.length < 2 || data.length > 4096) return;

        int split = 1 + ((data[0] & 0xFF) * (data.length - 1)) / 256;
        if (split > data.length) split = data.length;
        byte[] ref = Arrays.copyOfRange(data, 1, split);
        byte[] ver = Arrays.copyOfRange(data, split, data.length);

        // Encode
        List<Command> cmds = Diff.diff(Algorithm.GREEDY, ref, ver, new DiffOptions());
        List<PlacedCommand> placed = Apply.placeCommands(cmds);
        byte[] srcCrc = Hash.Crc64.hash8(ref);
        byte[] dstCrc = Hash.Crc64.hash8(ver);
        byte[] encoded = Encoding.encodeDeltaLarge(placed, false, ver.length, srcCrc, dstCrc, false);

        // Decode — must not fail on our own output
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

        // Apply
        byte[] out = new byte[(int) result.versionSize()];
        Apply.applyPlacedTo(ref, result.commands(), out);
        if (!Arrays.equals(out, ver))
            throw new AssertionError("reconstructed output differs from version");
    }
}
