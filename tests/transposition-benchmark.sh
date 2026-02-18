#!/usr/bin/env bash
#
# transposition-benchmark.sh — Measure delta compression on permuted block data
#
# Generates two synthetic datasets:
#   1 MB  (~2,000 blocks × 512 B mean) — benchmarks greedy, onepass, correcting
#   128 MB (~1,000,000 blocks × 128 B mean) — benchmarks onepass, correcting
#
# Each dataset is generated at five permutation levels (0–100%) using
# tests/gen_transpositions.py.  V contains the same blocks as R, with the
# specified fraction displaced from their original positions.
#
# Usage:
#   ./tests/transposition-benchmark.sh
#
# Requirements:
#   - Rust toolchain (cargo)
#   - Python 3.6+
#
# Generated files are cached in WORKDIR; re-runs skip generation.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKDIR="/tmp/delta-transposition-benchmark"
GEN="$SCRIPT_DIR/gen_transpositions.py"

# ── Build ─────────────────────────────────────────────────────────────────

echo "Building delta tool (release)..."
cd "$REPO_ROOT/src/rust/delta"
cargo build --release -q
DELTA="$REPO_ROOT/src/rust/delta/target/release/delta"
echo ""

mkdir -p "$WORKDIR"

# ── Helpers ───────────────────────────────────────────────────────────────

# encode_and_measure <algo> <ref> <ver> <delta>
# Prints: ratio copies adds time
encode_and_measure() {
    local algo="$1" ref="$2" ver="$3" delta="$4"
    local out
    out=$("$DELTA" encode "$algo" "$ref" "$ver" "$delta" 2>/dev/null)
    local ratio copies adds elapsed
    ratio=$(echo "$out"   | awk '/^Compression/ { print $2 }')
    copies=$(echo "$out"  | awk '/^Commands/    { print $2 }')
    adds=$(echo "$out"    | awk '/^Commands/    { print $4 }')
    elapsed=$(echo "$out" | awk '/^Time/        { print $2 }')
    echo "$ratio $copies $adds $elapsed"
}

print_header() {
    printf "  %-12s  %7s  %9s  %9s  %7s  %7s\n" \
        "Algorithm" "Perm%" "Ratio" "Copies" "Adds" "Time"
    printf "  %-12s  %7s  %9s  %9s  %7s  %7s\n" \
        "---------" "-----" "-----" "------" "----" "----"
}

print_row() {
    local algo="$1" pct="$2" ratio="$3" copies="$4" adds="$5" elapsed="$6"
    printf "  %-12s  %7s  %9s  %9s  %7s  %7s\n" \
        "$algo" "${pct}%" "$ratio" "$copies" "$adds" "$elapsed"
}

PERMS="0 25 50 75 100"

# ── 1 MB dataset — all three algorithms ───────────────────────────────────

echo "=== 1 MB (greedy, onepass, correcting) ==="
echo "    2,000 blocks × 512 B mean"
echo ""

for pct in $PERMS; do
    ref="$WORKDIR/1mb-ref-${pct}.bin"
    ver="$WORKDIR/1mb-ver-${pct}.bin"
    if [[ ! -f "$ref" ]]; then
        python3 "$GEN" 2000 512 "$pct" "$ref" "$ver" > /dev/null
    fi
done

print_header
last_algo=""
for algo in greedy onepass correcting; do
    [[ -n "$last_algo" ]] && echo ""
    for pct in $PERMS; do
        ref="$WORKDIR/1mb-ref-${pct}.bin"
        ver="$WORKDIR/1mb-ver-${pct}.bin"
        delta="$WORKDIR/1mb-${algo}-${pct}.delta"
        read -r ratio copies adds elapsed \
            < <(encode_and_measure "$algo" "$ref" "$ver" "$delta")
        print_row "$algo" "$pct" "$ratio" "$copies" "$adds" "$elapsed"
    done
    last_algo="$algo"
done

echo ""
echo ""

# ── 128 MB dataset — onepass and correcting ───────────────────────────────

echo "=== 128 MB (onepass, correcting) ==="
echo "    1,000,000 blocks × 128 B mean"
echo ""

for pct in $PERMS; do
    ref="$WORKDIR/128mb-ref-${pct}.bin"
    ver="$WORKDIR/128mb-ver-${pct}.bin"
    if [[ ! -f "$ref" ]]; then
        echo "  Generating ${pct}% permutation..."
        python3 "$GEN" 1000000 128 "$pct" "$ref" "$ver" > /dev/null
    fi
done
echo ""

print_header
last_algo=""
for algo in onepass correcting; do
    [[ -n "$last_algo" ]] && echo ""
    for pct in $PERMS; do
        ref="$WORKDIR/128mb-ref-${pct}.bin"
        ver="$WORKDIR/128mb-ver-${pct}.bin"
        delta="$WORKDIR/128mb-${algo}-${pct}.delta"
        read -r ratio copies adds elapsed \
            < <(encode_and_measure "$algo" "$ref" "$ver" "$delta")
        print_row "$algo" "$pct" "$ratio" "$copies" "$adds" "$elapsed"
    done
    last_algo="$algo"
done

echo ""
echo "Working directory preserved at $WORKDIR"
echo "To clean up:  rm -rf $WORKDIR"
