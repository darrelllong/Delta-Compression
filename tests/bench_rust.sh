#!/usr/bin/env bash
# Run Rust delta-compression micro-benchmarks through pilot-bench and emit a Markdown table.
# Columns: operation, ms/op mean, ±CI (95%), runs-to-CI
#
# Workload: 1 MiB synthetic data, ~5% single-byte mutations, default parameters.
# Build first:  cd src/rust/delta && cargo build --release --bin pilot_delta
set -euo pipefail

BENCH=~/pilot-bench/build/cli/bench
REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"
PILOT="$REPO_ROOT/src/rust/delta/target/release/pilot_delta"

measure() {
    local name=$1
    local out mean ci rounds
    out=$("$BENCH" run_program --preset quick \
          --pi "${name},MiB/s,0,1,1" \
          -- "$PILOT" "$name" 2>&1)
    mean=$(echo   "$out" | awk '/Reading mean/{print $5}')
    ci=$(echo     "$out" | awk '/Reading CI/{print $5}')
    rounds=$(echo "$out" | awk '/^Rounds:/{print $2}')
    printf "| %-28s | %10s | %10s | %5s |\n" \
           "$name" "$mean" "±$ci" "$rounds"
}

sep() { echo "|------------------------------|------------|------------|-------|"; }

hdr() {
    echo ""
    echo "### $1"
    echo ""
    echo "| Operation                    |   MiB/s    | ±CI (95%)  | Runs  |"
    sep
}

hdr "Encode (1 MiB, ~5% mutations)"
measure encode_greedy_1m
measure encode_onepass_1m
measure encode_correcting_1m

hdr "Decode / In-place (1 MiB)"
measure decode_1m
measure inplace_1m

echo ""
