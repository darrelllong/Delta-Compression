CC      = cc
CFLAGS  = -std=c11 -O2 -Wall -Wextra -Wpedantic -D_POSIX_C_SOURCE=200809L
LDFLAGS =

LIB_OBJS = hash.o splay.o encoding.o apply.o inplace.o \
            greedy.o onepass.o correcting.o
OBJS      = $(LIB_OBJS) main.o

TARGET   = delta
TEST_BIN = test_overflow

all: $(TARGET) $(TEST_BIN)

$(TARGET): $(OBJS)
	$(CC) $(LDFLAGS) -o $@ $(OBJS)

$(TEST_BIN): test_overflow.o $(LIB_OBJS)
	$(CC) $(LDFLAGS) -o $@ test_overflow.o $(LIB_OBJS)

%.o: %.c delta.h
	$(CC) $(CFLAGS) -c -o $@ $<

test: $(TARGET) $(TEST_BIN)
	./test_delta.sh

clean:
	rm -f $(OBJS) test_overflow.o $(TARGET) $(TEST_BIN) fuzz/fuzz_decode

# ── Fuzz target (AFL++ recommended; see fuzz/fuzz_decode.c for instructions) ──
#
# AFL++:
#   make fuzz-afl
#   bash fuzz/gen_corpus.sh fuzz/corpus
#   afl-fuzz -i fuzz/corpus -o fuzz/findings -- ./fuzz/fuzz_decode @@
#
# libFuzzer fork mode:
#   make fuzz-libfuzzer
#   bash fuzz/gen_corpus.sh fuzz/corpus
#   ./fuzz/fuzz_decode_lf -fork=1 fuzz/corpus/

LIB_OBJS_NO_MAIN = $(LIB_OBJS)

fuzz-afl: $(LIB_OBJS_NO_MAIN)
	AFL_USE_ASAN=1 afl-clang-fast $(CFLAGS) -o fuzz/fuzz_decode \
	    fuzz/fuzz_decode.c $(LIB_OBJS_NO_MAIN)

fuzz-libfuzzer: $(LIB_OBJS_NO_MAIN)
	clang $(CFLAGS) -fsanitize=fuzzer,address -DLIBFUZZER \
	    -o fuzz/fuzz_decode_lf fuzz/fuzz_decode.c $(LIB_OBJS_NO_MAIN)

.PHONY: all test clean fuzz-afl fuzz-libfuzzer
