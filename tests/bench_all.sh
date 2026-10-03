#!/usr/bin/env bash
# Measure the encode throughput of the Rust, C, C++, Java and Go
# implementations with pilot-bench and print a Markdown table.
#
# Usage: ./tests/bench_all.sh
#
# Columns: language, algorithm, mean MiB/s, CI width, runs.  The CI column is
# the full width of the 95% confidence interval, as pilot-bench prints it
# after "Reading CI"; the interval is the mean plus or minus half of it.
#
# Workload: Shakespeare's complete works (~5.4 MB) as the reference and a
# copy with 5% of its bytes overwritten as the version, encoded with onepass
# and with correcting.  tests/get_shakespeare.sh fetches both into WORKDIR
# (default /tmp/delta-kernel-test) if they are not already there.
#
# Requirements:
#   - pilot-bench, at $PILOT_BENCH_CLI or ~/pilot-bench/build/cli/bench
#   - the implementations already built; tests/per-language-benchmark.sh and
#     tests/correctness.sh both build them.  Java is skipped if there is no
#     java on PATH, Go if its binary is missing.
#
# Optional CPU pinning (Linux): PIN_SINGLE is a taskset CPU list for the
# single-threaded implementations (Rust, C, C++), PIN_MULTI one for those whose
# runtimes start threads of their own (Java, Go).
set -euo pipefail

BENCH="${PILOT_BENCH_CLI:-$HOME/pilot-bench/build/cli/bench}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORKLOAD="$REPO_ROOT/tests/pilot_lang.sh"
export WORKDIR="${WORKDIR:-/tmp/delta-kernel-test}"
mkdir -p "$WORKDIR"

export PILOT_REF="$WORKDIR/shakespeare.txt"
export PILOT_VER="$WORKDIR/shakespeare-5pct.txt"

if [[ ! -f "$PILOT_REF" || ! -f "$PILOT_VER" ]]; then
    echo "Fetching Shakespeare benchmark data..."
    bash "$REPO_ROOT/tests/get_shakespeare.sh"
fi

JAVA=$(command -v java 2>/dev/null || true)

measure() {
    local name=$1 lang=$2 algo=$3
    local out mean ci rounds status=0
    local pin=()
    case "$lang" in
        Rust|C|Cpp) [[ -n "${PIN_SINGLE:-}" ]] && pin=(taskset -c "$PIN_SINGLE") ;;
        Java|Go)    [[ -n "${PIN_MULTI:-}"  ]] && pin=(taskset -c "$PIN_MULTI") ;;
    esac
    out=$(${pin[@]+"${pin[@]}"} "$BENCH" run_program --preset quick \
          --pi "${name},MiB/s,0,1,1" \
          -- "$WORKLOAD" "$lang" "$algo" 2>&1) || status=$?
    if [[ "$status" -ne 0 ]]; then
        echo "pilot-bench failed for $name with status $status:" >&2
        echo "$out" >&2
        exit 1
    fi
    mean=$(echo   "$out" | awk '/Reading mean/{print $5}')
    ci=$(echo     "$out" | awk '/Reading CI/{print $5}')
    rounds=$(echo "$out" | awk '/^Rounds:/{print $2}')
    printf "| %-8s | %-10s | %10s | %10s | %5s |\n" \
           "$lang" "$algo" "$mean" "$ci" "$rounds"
}

sep() { echo "|----------|------------|------------|------------|-------|"; }

hdr() {
    echo ""
    echo "### $1"
    echo ""
    echo "| Language | Algorithm  |   MiB/s    | CI width (95%) | Runs  |"
    sep
}

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
fi

GO_BIN="$REPO_ROOT/src/go/delta/delta"
if [[ -x "$GO_BIN" ]]; then
    measure "Go-op"       Go      onepass
    measure "Go-co"       Go      correcting
else
    echo "# Go: skipped (binary not found: $GO_BIN)"
fi

echo ""
