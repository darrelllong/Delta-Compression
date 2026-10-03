# The binary is delta/delta because the package directory delta/ already
# has the name the binary would take here.
BIN := delta/delta

.PHONY: all build test clean

all: build

build:
	go build -o $(BIN) ./cmd/delta

test:
	go test ./... -v

clean:
	rm -f $(BIN)
