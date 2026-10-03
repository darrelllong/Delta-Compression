#!/usr/bin/env bash
#
# correctness.sh: build every implementation and run every test suite.
#
# Each language's unit tests run first.  The C suite (src/c/test_delta.sh)
# comes last because it also checks that the implementations produce
# byte-identical deltas and decode one another's output; it uses whichever of
# the other binaries exist, so they are all built before it runs.
#
# Usage: ./tests/correctness.sh
# Exit status: 0 if every suite passes, 1 otherwise.  A suite whose toolchain
# is missing counts as a failure.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$REPO_ROOT/src"
JOBS=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)

passed=0
failed=0

# suite <name> <dir> <command>: run the command in the directory.
suite() {
    local name=$1 dir=$2 cmd=$3
    echo
    echo "== $name"
    if (cd "$dir" && bash -c "$cmd"); then
        echo "PASSED: $name"
        passed=$((passed + 1))
    else
        echo "FAILED: $name"
        failed=$((failed + 1))
    fi
}

# The Java classes are compiled with --release 17, so any JDK from 17 on will
# do.  Prefer Homebrew's openjdk@17 when it is installed; java and javac must
# come from the same JDK.
JAVA=/opt/homebrew/opt/openjdk@17/bin/java
[[ -x "$JAVA" ]] || JAVA=$(command -v java || true)
JAVAC="${JAVA%java}javac"
export JAVA

suite "Python" "$SRC/python" "python3 -m unittest test_delta"

suite "Rust" "$SRC/rust/delta" "cargo test && cargo build --release"

suite "C++" "$SRC/cpp" \
    "cmake -B build -DCMAKE_BUILD_TYPE=Release >/dev/null &&
     cmake --build build --parallel $JOBS &&
     ctest --test-dir build --output-on-failure"

if [[ -n "$JAVA" && -x "$JAVAC" ]]; then
    suite "Java" "$SRC/java" "make test JAVA='$JAVA' JAVAC='$JAVAC'"
else
    echo
    echo "FAILED: Java (no JDK found)"
    failed=$((failed + 1))
fi

suite "Go" "$SRC/go" "go test ./delta/... && go build -o delta/delta ./cmd/delta"

suite "C and cross-language" "$SRC/c" "make && sh test_delta.sh"

echo
echo "Suites passed: $passed / $((passed + failed))"
[[ "$failed" -eq 0 ]]
