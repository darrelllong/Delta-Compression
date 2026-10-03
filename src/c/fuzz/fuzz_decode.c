// Fuzzing harness for delta_decode, for AFL++ or libFuzzer.
//
// delta_decode exits on malformed input.  AFL++ restarts the process.
// libFuzzer counts an exit as a crash, so it must run in fork mode and be
// told to carry on; what it then reports as "fuzz target exited" is a
// rejected input, and a finding is a report from AddressSanitizer.
//
//   AFL++:      make fuzz-afl
//               afl-fuzz -i fuzz/corpus -o fuzz/findings -- ./fuzz/fuzz_decode @@
//   libFuzzer:  make fuzz-libfuzzer
//               ./fuzz/fuzz_decode_lf -fork=1 -ignore_crashes=1 fuzz/corpus/
//
// fuzz/gen_corpus.sh writes a seed corpus.

#include "../delta.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef LIBFUZZER

int
LLVMFuzzerTestOneInput(const uint8_t *data, size_t size)
{
	// Without the magic delta_decode exits at once, at the cost of a
	// fork; such inputs teach the fuzzer nothing.
	if (size < 4 || (memcmp(data, "DLT\x03", 4) != 0 &&
	                 memcmp(data, "DLT\x04", 4) != 0)) {
		return 0;
	}
	delta_decode_result_t r = delta_decode(data, size);
	delta_decode_result_free(&r);
	return 0;
}

#else

// Built without AFL's compiler, main decodes one input and returns.
#ifndef __AFL_LOOP
static int looped;
#define __AFL_LOOP(n) (!looped++)
#endif
#ifndef __AFL_INIT
#define __AFL_INIT()
#endif

#define MAX_INPUT (1 << 16)

// The input is the file named by the argument, or standard input.
int
main(int argc, char **argv)
{
	__AFL_INIT();

	uint8_t *buf = malloc(MAX_INPUT);
	if (!buf) {
		perror("malloc");
		return 1;
	}

	while (__AFL_LOOP(10000)) {
		size_t n = 0;
		if (argc > 1) {
			FILE *f = fopen(argv[1], "rb");
			if (f) {
				n = fread(buf, 1, MAX_INPUT, f);
				fclose(f);
			}
		} else {
			n = fread(buf, 1, MAX_INPUT, stdin);
		}
		if (n == 0) {
			continue;
		}
		delta_decode_result_t r = delta_decode(buf, n);
		delta_decode_result_free(&r);
	}

	free(buf);
	return 0;
}

#endif
