package fuzz;

import delta.Encoding;

/**
 * Jazzer fuzz target: feed arbitrary bytes to Encoding.decodeDelta().
 *
 * Invariant: decodeDelta must never throw anything other than
 * IllegalArgumentException (or its subclasses) regardless of input.
 * Any other throwable — NullPointerException, ArrayIndexOutOfBoundsException,
 * NegativeArraySizeException, StackOverflowError, etc. — is a bug.
 *
 * Run:
 *   # build first:
 *   cd src/java
 *   make
 *   javac -cp out:fuzz/jazzer_standalone.jar fuzz/FuzzDecode.java -d out/
 *
 *   # fuzz:
 *   fuzz/jazzer --target_class=fuzz.FuzzDecode --cp=out/ --instrumentation_includes=delta.** \
 *       --reproducer_path=fuzz/findings/ -max_total_time=300
 */
public class FuzzDecode {
    public static void fuzzerTestOneInput(byte[] data) {
        try {
            Encoding.decodeDelta(data);
        } catch (IllegalArgumentException e) {
            // expected rejection path for malformed input
        }
    }
}
