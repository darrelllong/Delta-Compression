package fuzz;

import delta.Encoding;

/**
 * Jazzer target: decodeDelta on arbitrary bytes.  It may reject them with an
 * IllegalArgumentException; any other throwable is a bug.
 *
 * <pre>
 *   cd src/java &amp;&amp; make
 *   javac -cp out:fuzz/jazzer_standalone.jar -d out fuzz/FuzzDecode.java
 *   fuzz/jazzer --target_class=fuzz.FuzzDecode --cp=out/ --instrumentation_includes=delta.** \
 *       --reproducer_path=fuzz/findings/ -max_total_time=300
 * </pre>
 */
public class FuzzDecode {
    public static void fuzzerTestOneInput(byte[] data) {
        try {
            Encoding.decodeDelta(data);
        } catch (IllegalArgumentException e) {
            // Malformed input, rejected as it should be.
        }
    }
}
