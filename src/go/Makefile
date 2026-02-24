BIN     := delta
PKG     := ./cmd/delta
TESTPKG := ./delta/...

.PHONY: all build test clean

all: build

build:
	go build -o $(BIN) $(PKG)

test:
	go test $(TESTPKG) -v

clean:
	rm -f $(BIN)
