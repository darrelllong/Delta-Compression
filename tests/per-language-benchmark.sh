#!/usr/bin/env bash
#
# per-language-benchmark.sh: compare the encode time of the compiled
# implementations.
#
# Builds the Rust, C++, C, Java and Go implementations, then encodes the
# Linux 5.1.0 -> 5.1.1 kernel tarballs (~871 MB each) with onepass and with
# correcting.  Each encode is run and timed once, one after another so that
# they do not compete for the disk.  The tarballs are those of
# kernel-delta-test.sh (same WORKDIR, default /tmp/delta-kernel-test).
#
# Python is left out: 871 MB is too much for the interpreted implementation.
#
# Usage:
#   ./tests/per-language-benchmark.sh
#
# Requirements:
#   - Rust, C, C++ (with cmake), Go and make; Java is skipped if no JDK is
#     found
#   - python3 (for sub-second timing)
#   - curl, gunzip (to download the tarballs if they are not cached)
#   - ~2 GB disk in WORKDIR for the two tarballs, plus the deltas
#   - ~2.5 GB RAM (the hash tables are sized from the 871 MB reference)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKDIR="${WORKDIR:-/tmp/delta-kernel-test}"
KERNEL_BASE="https://cdn.kernel.org/pub/linux/kernel/v5.x"

# Prefer Homebrew's openjdk@17 when it is installed; java and javac must
# come from the same JDK.
JAVA=/opt/homebrew/opt/openjdk@17/bin/java
if [[ ! -x "$JAVA" ]]; then
    JAVA=$(command -v java 2>/dev/null || true)
fi
JAVAC="${JAVA%java}javac"
if [[ -z "$JAVA" || ! -x "$JAVA" || ! -x "$JAVAC" ]]; then
    echo "WARNING: Java not found — Java will be skipped" >&2
    JAVA=""
    JAVAC=""
fi

echo "Building all implementations..."
echo ""

echo "  Rust    — cargo build --release"
cd "$REPO_ROOT/src/rust/delta"
cargo build --release -q

echo "  C++     — cmake"
cd "$REPO_ROOT/src/cpp"
cmake -B build > /dev/null 2>&1
cmake --build build > /dev/null 2>&1

echo "  C       — make"
cd "$REPO_ROOT/src/c"
make -s

echo "  Java    — make"
cd "$REPO_ROOT/src/java"
if [[ -n "$JAVA" ]]; then make -s JAVA="$JAVA" JAVAC="$JAVAC"; fi

echo "  Go      — make"
cd "$REPO_ROOT/src/go"
make -s

echo ""

mkdir -p "$WORKDIR"
cd "$WORKDIR"

echo "Fetching Linux 5.1.0 and 5.1.1 kernel tarballs..."
for TAR in linux-5.1.tar linux-5.1.1.tar; do
    GZ="$TAR.gz"
    URL="$KERNEL_BASE/$GZ"
    if [[ -f "$TAR" ]]; then
        echo "  $TAR (cached)"
    elif [[ -f "$GZ" ]]; then
        echo "  $GZ (cached, decompressing)"; gunzip "$GZ"
    else
        echo "  Downloading $URL"; curl -sfLO "$URL"; gunzip "$GZ"
    fi
done
echo ""

REF="$WORKDIR/linux-5.1.tar"
VER="$WORKDIR/linux-5.1.1.tar"

# time_encode <cmd...>: run the command and print its wall-clock seconds to
# one decimal place.
time_encode() {
    python3 - "$@" <<'EOF'
import sys, subprocess, time
t0 = time.perf_counter()
# A failed encode would otherwise be timed as a fast one.
subprocess.run(sys.argv[1:], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
print(f"{time.perf_counter()-t0:.1f}")
EOF
}

echo "=== Per-language: linux-5.1.0 → 5.1.1 (~871 MB) ==="
echo ""

printf "  %-12s  %10s  %12s\n" "Language" "onepass" "correcting"
printf "  %-12s  %10s  %12s\n" "--------" "-------" "----------"

# run_lang <display-name> <encode-cmd...>: print one table row.  The command
# is everything up to and including "encode"; <algo> <ref> <ver> <delta-file>
# are appended to it.
run_lang() {
    local name="$1"; shift

    local d_op="$WORKDIR/per-lang-${name}-onepass.delta"
    local d_co="$WORKDIR/per-lang-${name}-correcting.delta"

    local t_op t_co
    t_op=$(time_encode "$@" onepass    "$REF" "$VER" "$d_op")
    t_co=$(time_encode "$@" correcting "$REF" "$VER" "$d_co")

    printf "  %-12s  %9ss  %11ss\n" "$name" "$t_op" "$t_co"
}

run_lang "Rust"    "$REPO_ROOT/src/rust/delta/target/release/delta" encode
run_lang "C++"     "$REPO_ROOT/src/cpp/build/delta" encode
run_lang "C"       "$REPO_ROOT/src/c/delta" encode

if [[ -n "$JAVA" ]]; then
    run_lang "Java"   "$JAVA" -cp "$REPO_ROOT/src/java/out" delta.Delta encode
fi

run_lang "Go"      "$REPO_ROOT/src/go/delta/delta" encode

echo ""
echo "Working directory preserved at $WORKDIR"
echo "To clean up:  rm -rf $WORKDIR"
