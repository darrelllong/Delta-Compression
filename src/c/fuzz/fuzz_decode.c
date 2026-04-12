/*
 * fuzz_decode.c — AFL++ / libFuzzer harness for delta_decode().
 *
 * The C decoder calls exit(1) on malformed input, which is incompatible
 * with in-process libFuzzer (it would terminate the fuzzer process).
 * Recommended runners:
 *
 *   AFL++ (preferred):
 *     AFL_USE_ASAN=1 afl-clang-fast -o fuzz_decode \
 *         fuzz/fuzz_decode.c hash.o encoding.o apply.o inplace.o \
 *         greedy.o onepass.o correcting.o splay.o -lm
 *     afl-fuzz -i fuzz/corpus -o fuzz/findings -- ./fuzz_decode @@
 *
 *   libFuzzer fork mode (one process per input, handles exit()):
 *     clang -fsanitize=fuzzer,address -DLIBFUZZER -o fuzz_decode_lf \
 *         fuzz/fuzz_decode.c hash.o encoding.o apply.o inplace.o \
 *         greedy.o onepass.o correcting.o splay.o
 *     ./fuzz_decode_lf -fork=1 fuzz/corpus/
 *
 * Seed corpus:
 *     bash fuzz/gen_corpus.sh fuzz/corpus
 */

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../delta.h"

/* ── Maximum input size ──────────────────────────────────────────────────── */
#define MAX_INPUT (1 << 16)  /* 64 KiB */

/* ── libFuzzer entry point ───────────────────────────────────────────────── */
#ifdef LIBFUZZER

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size)
{
    /*
     * delta_decode calls exit(1) on bad magic.  With -fork=1, libFuzzer
     * spawns a fresh child per input, so the exit terminates only the child.
     * Without -fork=1 the fuzzer process itself would die; we guard against
     * that by rejecting obviously bad magic early (still exercises the header
     * parser for well-formed-looking but internally corrupt inputs).
     */
    if (size < 4) return 0;
    if (memcmp(data, "DLT\x03", 4) != 0 && memcmp(data, "DLT\x04", 4) != 0) return 0;

    delta_decode_result_t r = delta_decode(data, size);
    delta_decode_result_free(&r);
    return 0;
}

/* ── AFL++ entry point ───────────────────────────────────────────────────── */
#else

#ifndef __AFL_LOOP
#  define __AFL_LOOP(n) 1   /* run once when built without AFL instrumentation */
#endif
#ifndef __AFL_INIT
#  define __AFL_INIT()       /* no-op outside AFL */
#endif

int main(int argc, char **argv)
{
    __AFL_INIT();

    uint8_t *buf = malloc(MAX_INPUT);
    if (!buf) { perror("malloc"); return 1; }

    while (__AFL_LOOP(10000)) {
        size_t n = 0;

        if (argc > 1) {
            /* File-based mode: afl-fuzz ... -- ./fuzz_decode @@ */
            FILE *f = fopen(argv[1], "rb");
            if (f) { n = fread(buf, 1, MAX_INPUT, f); fclose(f); }
        } else {
            /* Stdin mode */
            n = fread(buf, 1, MAX_INPUT, stdin);
        }

        if (n == 0) continue;

        /*
         * delta_decode calls exit(1) on bad magic; this terminates the
         * __AFL_LOOP iteration and AFL++ restarts the process automatically.
         * AddressSanitizer will abort() on any memory error first.
         */
        delta_decode_result_t r = delta_decode(buf, n);
        delta_decode_result_free(&r);
    }

    free(buf);
    return 0;
}

#endif /* LIBFUZZER */
