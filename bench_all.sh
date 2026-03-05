#!/usr/bin/env bash
# Run all 8 language implementations through pilot-bench and emit a Markdown table.
# Columns: language, algorithm, MiB/s (throughput), ±CI (95%), runs-to-CI
#
# Workload: Shakespeare's complete works (~5.4 MB, ~5% byte mutations),
#           onepass and correcting.
# Build first: run tests/per-language-benchmark.sh (builds all implementations).
set -euo pipefail

BENCH=~/pilot-bench/build/cli/bench
REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"
WORKLOAD="$REPO_ROOT/tests/pilot_lang.sh"
export WORKDIR="${WORKDIR:-/tmp/delta-kernel-test}"
mkdir -p "$WORKDIR"

# ── Prepare Shakespeare benchmark data (once, cached) ────────────────────────

export PILOT_REF="$WORKDIR/shakespeare.txt"
export PILOT_VER="$WORKDIR/shakespeare-5pct.txt"

if [[ ! -f "$PILOT_REF" || ! -f "$PILOT_VER" ]]; then
    echo "Fetching Shakespeare benchmark data..."
    bash "$REPO_ROOT/tests/get_shakespeare.sh"
fi

# ── Locate Java ───────────────────────────────────────────────────────────────

JAVA=/opt/homebrew/opt/openjdk@17/bin/java
if [[ ! -x "$JAVA" ]]; then
    JAVA=$(command -v java 2>/dev/null || true)
fi

# ── Locate Scala library ──────────────────────────────────────────────────────

SCALA_LIB=""
if [[ -f "$REPO_ROOT/src/scala/Makefile" ]]; then
    SCALA_LIB=$(grep 'SCALA_LIB\s*=' "$REPO_ROOT/src/scala/Makefile" | head -1 | sed 's/.*= *//')
fi

# ── Measure helper ────────────────────────────────────────────────────────────

measure() {
    local name=$1 lang=$2 algo=$3
    local out mean ci rounds
    out=$("$BENCH" run_program --preset quick \
          --pi "${name},MiB/s,0,1,1" \
          -- "$WORKLOAD" "$lang" "$algo" 2>&1)
    mean=$(echo   "$out" | awk '/Reading mean/{print $5}')
    ci=$(echo     "$out" | awk '/Reading CI/{print $5}')
    rounds=$(echo "$out" | awk '/^Rounds:/{print $2}')
    printf "| %-8s | %-10s | %10s | %10s | %5s |\n" \
           "$lang" "$algo" "$mean" "±$ci" "$rounds"
}

sep() { echo "|----------|------------|------------|------------|-------|"; }

hdr() {
    echo ""
    echo "### $1"
    echo ""
    echo "| Language | Algorithm  |   MiB/s    | ±CI (95%)  | Runs  |"
    sep
}

# ── Benchmarks ────────────────────────────────────────────────────────────────

hdr "Encode: Shakespeare (~5.4 MB, 5% mutations)"

measure "Rust-op"     Rust    onepass
measure "Rust-co"     Rust    correcting
measure "C-op"        C       onepass
measure "C-co"        C       correcting
measure "Cpp-op"      Cpp     onepass
measure "Cpp-co"      Cpp     correcting

if [[ -n "$JAVA" && -x "$JAVA" ]]; then
    measure "Java-op"     Java    onepass
    measure "Java-co"     Java    correcting
    measure "Kotlin-op"   Kotlin  onepass
    measure "Kotlin-co"   Kotlin  correcting
    if [[ -n "$SCALA_LIB" && -f "$SCALA_LIB" ]]; then
        measure "Scala-op"    Scala   onepass
        measure "Scala-co"    Scala   correcting
    fi
fi

GO_BIN="$REPO_ROOT/src/go/delta/delta"
if [[ -x "$GO_BIN" ]]; then
    measure "Go-op"       Go      onepass
    measure "Go-co"       Go      correcting
else
    echo "# Go: skipped (binary not found: $GO_BIN)"
fi

measure "Python-op"   Python  onepass
measure "Python-co"   Python  correcting

echo ""
