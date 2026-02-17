CC      = cc
CFLAGS  = -std=c11 -O2 -Wall -Wextra -Wpedantic -D_POSIX_C_SOURCE=200809L
LDFLAGS =

OBJS = hash.o splay.o encoding.o apply.o inplace.o \
       greedy.o onepass.o correcting.o main.o

TARGET = delta

all: $(TARGET)

$(TARGET): $(OBJS)
	$(CC) $(LDFLAGS) -o $@ $(OBJS)

%.o: %.c delta.h
	$(CC) $(CFLAGS) -c -o $@ $<

test: $(TARGET)
	./test_delta.sh

clean:
	rm -f $(OBJS) $(TARGET)

.PHONY: all test clean
