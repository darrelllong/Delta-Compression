CC      = cc
CFLAGS  = -std=c11 -O2 -Wall -Wextra -Wpedantic -D_POSIX_C_SOURCE=200809L
LDFLAGS =

LIB_OBJS = hash.o crc64.o splay.o encoding.o apply.o inplace.o \
           diff.o greedy.o onepass.o correcting.o
OBJS     = $(LIB_OBJS) main.o
HEADERS  = delta.h internal.h

TARGET   = delta
TEST_BIN = test_overflow

all: $(TARGET) $(TEST_BIN)

$(TARGET): $(OBJS)
	$(CC) $(LDFLAGS) -o $@ $(OBJS)

$(TEST_BIN): test_overflow.o $(LIB_OBJS)
	$(CC) $(LDFLAGS) -o $@ test_overflow.o $(LIB_OBJS)

%.o: %.c $(HEADERS)
	$(CC) $(CFLAGS) -c -o $@ $<

test: $(TARGET) $(TEST_BIN)
	./test_delta.sh

clean:
	rm -f $(OBJS) test_overflow.o $(TARGET) $(TEST_BIN) \
	    fuzz/fuzz_decode fuzz/fuzz_decode_lf

# Fuzzing delta_decode; fuzz/fuzz_decode.c says how to run each.  The library
# is compiled from source so that it is instrumented too.
LIB_SRCS = $(LIB_OBJS:.o=.c)

fuzz-afl:
	AFL_USE_ASAN=1 afl-clang-fast $(CFLAGS) -o fuzz/fuzz_decode \
	    fuzz/fuzz_decode.c $(LIB_SRCS)

fuzz-libfuzzer:
	clang $(CFLAGS) -fsanitize=fuzzer,address -DLIBFUZZER \
	    -o fuzz/fuzz_decode_lf fuzz/fuzz_decode.c $(LIB_SRCS)

.PHONY: all test clean fuzz-afl fuzz-libfuzzer
