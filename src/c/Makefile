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
	rm -f $(OBJS) test_overflow.o $(TARGET) $(TEST_BIN)

.PHONY: all test clean
