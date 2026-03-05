GHC ?= ghc
GHCFLAGS ?= -O2 -Wall

.PHONY: all clean

all: delta-hs

delta-hs: Main.hs Delta/Types.hs Delta/Util.hs Delta/Hash.hs Delta/Diff.hs \
	Delta/Apply.hs Delta/Encoding.hs Delta/Inplace.hs \
	Delta/Algorithms/Greedy.hs Delta/Algorithms/Onepass.hs \
	Delta/Algorithms/Correcting.hs
	$(GHC) $(GHCFLAGS) -i. -o $@ Main.hs

clean:
	rm -f delta-hs Main.o Main.hi
	find Delta -name '*.o' -delete
	find Delta -name '*.hi' -delete
