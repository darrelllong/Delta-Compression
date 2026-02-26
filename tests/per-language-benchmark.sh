#!/usr/bin/env bash
#
# per-language-benchmark.sh — Compare delta encoding speed across all 8 implementations
#
# Encodes the Linux 5.1.0 → 5.1.1 kernel tarballs (~871 MB each) with onepass
# and correcting using each implementation in sequence to avoid SSD contention.
# Tarballs are shared with kernel-delta-test.sh (same default WORKDIR).
#
# Usage:
#   ./tests/per-language-benchmark.sh
#
# Requirements:
#   - All 8 language toolchains installed
#   - curl, gunzip (to download tarballs if not already cached)
#   - ~2 GB disk in WORKDIR (two ~1 GB tarballs)
#   - ~2.5 GB RAM (auto-sized hash tables for 871 MB kernel tarballs)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKDIR="${WORKDIR:-/tmp/delta-kernel-test}"
KERNEL_BASE="https://cdn.kernel.org/pub/linux/kernel/v5.x"

# ── Locate Java ───────────────────────────────────────────────────────────────

JAVA=/opt/homebrew/opt/openjdk@17/bin/java
if [[ ! -x "$JAVA" ]]; then
    JAVA=$(command -v java 2>/dev/null || true)
fi
if [[ -z "$JAVA" || ! -x "$JAVA" ]]; then
    echo "WARNING: Java not found — Java, Kotlin, and Scala will be skipped" >&2
    JAVA=""
fi

# ── Locate Scala library ──────────────────────────────────────────────────────

SCALA_LIB=$(grep 'SCALA_LIB\s*=' "$REPO_ROOT/src/scala/Makefile" | head -1 | sed 's/.*= *//')
if [[ ! -f "$SCALA_LIB" ]]; then
    echo "WARNING: Scala library not found at $SCALA_LIB — Scala will be skipped" >&2
    SCALA_LIB=""
fi

# ── Build all implementations ─────────────────────────────────────────────────

echo "Building all implementations..."
echo ""

echo "  Python  — nothing to build"

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
make -s

echo "  Go      — make"
cd "$REPO_ROOT/src/go"
make -s

echo "  Kotlin  — make"
cd "$REPO_ROOT/src/kotlin"
make -s 2>/dev/null

echo "  Scala   — make"
cd "$REPO_ROOT/src/scala"
make -s

echo ""

# ── Download / cache tarballs ─────────────────────────────────────────────────

mkdir -p "$WORKDIR"
cd "$WORKDIR"

echo "Fetching Linux 5.1.0 and 5.1.1 kernel tarballs..."
for i in 0 1; do
    if [[ "$i" -eq 0 ]]; then
        TAR="linux-5.1.tar"; GZ="linux-5.1.tar.gz"
        URL="$KERNEL_BASE/linux-5.1.tar.gz"
    else
        TAR="linux-5.1.$i.tar"; GZ="linux-5.1.$i.tar.gz"
        URL="$KERNEL_BASE/$GZ"
    fi
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

# ── Timing helper ─────────────────────────────────────────────────────────────

# time_encode <cmd...>
# Runs the given command and prints elapsed seconds to one decimal place.
time_encode() {
    python3 - "$@" <<'EOF'
import sys, subprocess, time
t0 = time.perf_counter()
subprocess.run(sys.argv[1:], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
print(f"{time.perf_counter()-t0:.1f}")
EOF
}

# ── Per-language timing ───────────────────────────────────────────────────────

echo "=== Per-language: linux-5.1.0 → 5.1.1 (~871 MB) ==="
echo ""

printf "  %-12s  %10s  %12s\n" "Language" "onepass" "correcting"
printf "  %-12s  %10s  %12s\n" "--------" "-------" "----------"

# run_lang <display-name> <encode-cmd...>
# The encode-cmd should be everything up to and including "encode"; this
# function appends: <algo> <ref> <ver> <delta-file>
run_lang() {
    local name="$1"; shift

    local d_op="$WORKDIR/per-lang-${name}-onepass.delta"
    local d_co="$WORKDIR/per-lang-${name}-correcting.delta"

    local t_op t_co
    t_op=$(time_encode "$@" onepass    "$REF" "$VER" "$d_op")
    t_co=$(time_encode "$@" correcting "$REF" "$VER" "$d_co")

    printf "  %-12s  %9ss  %11ss\n" "$name" "$t_op" "$t_co"
}

run_lang "Python"  python3 "$REPO_ROOT/src/python/delta.py" encode
run_lang "Rust"    "$REPO_ROOT/src/rust/delta/target/release/delta" encode
run_lang "C++"     "$REPO_ROOT/src/cpp/build/delta" encode
run_lang "C"       "$REPO_ROOT/src/c/delta" encode

if [[ -n "$JAVA" ]]; then
    run_lang "Java"   "$JAVA" -cp "$REPO_ROOT/src/java/out" delta.Delta encode
fi

run_lang "Go"      "$REPO_ROOT/src/go/delta/delta" encode

if [[ -n "$JAVA" ]]; then
    run_lang "Kotlin" "$JAVA" -cp "$REPO_ROOT/src/kotlin/delta.jar" delta.Delta encode
    if [[ -n "$SCALA_LIB" ]]; then
        run_lang "Scala" "$JAVA" \
            -cp "$REPO_ROOT/src/scala/delta.jar:$SCALA_LIB" delta.Delta encode
    fi
fi

echo ""
echo "Working directory preserved at $WORKDIR"
echo "To clean up:  rm -rf $WORKDIR"
