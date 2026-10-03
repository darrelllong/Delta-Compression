#!/usr/bin/env bash
#
# transposition-benchmark.sh: measure delta compression on permuted blocks.
#
# The inputs come from tests/gen_transpositions.py: V holds the blocks of R
# in a different order.  Each dataset is generated at five permutation
# levels, 0, 25, 50, 75 and 100%, and encoded with the Rust implementation.
# The sections, in the order they are printed:
#
#   1. 16 MB (32,000 blocks, 512 B mean): greedy, onepass, correcting
#   2. the same data, standard against in-place encoding (onepass,
#      correcting): what in-place conversion costs as transpositions increase
#   3. decode time of the deltas of section 2
#   4. standard against in-place, correcting only, at 16, 32, 64, 128 and
#      256 MB (512 B mean)
#   5. 1 GB (8,000,000 blocks, 128 B mean): onepass, correcting
#
# All times are those the delta tool reports for itself.
#
# Usage:
#   ./tests/transposition-benchmark.sh
#
# Requirements:
#   - Rust toolchain (cargo)
#   - Python 3.6+
#   - ~15 GB disk for the generated files, plus the deltas
#
# The files are kept in $DELTA_BENCH_WORKDIR (default
# /tmp/delta-transposition-benchmark); later runs skip the generation.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKDIR="${DELTA_BENCH_WORKDIR:-/tmp/delta-transposition-benchmark}"
GEN="$SCRIPT_DIR/gen_transpositions.py"

echo "Building delta tool (release)..."
cd "$REPO_ROOT/src/rust/delta"
cargo build --release -q
DELTA="$REPO_ROOT/src/rust/delta/target/release/delta"
echo ""

mkdir -p "$WORKDIR"

# encode_and_measure <algo> <ref> <ver> <delta> [extra-flags...]: encode and
# print "ratio copies adds time cycles_broken", taken from the tool's
# summary.  cycles_broken is 0 unless --inplace is given.
encode_and_measure() {
    local algo="$1" ref="$2" ver="$3" delta="$4"
    shift 4
    local out
    out=$("$DELTA" encode "$algo" "$ref" "$ver" "$delta" "$@" 2>/dev/null)
    local ratio copies adds elapsed cycles
    ratio=$(echo "$out"   | awk '/^Compression/   { print $2 }')
    copies=$(echo "$out"  | awk '/^Commands/      { print $2 }')
    adds=$(echo "$out"    | awk '/^Commands/      { print $4 }')
    elapsed=$(echo "$out" | awk '/^Time/          { print $2 }')
    cycles=$(echo "$out"  | awk '/^Cycles broken/ { print $3 }')
    echo "$ratio $copies $adds $elapsed ${cycles:-0}"
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

print_inplace_header() {
    printf "  %-12s  %7s  %9s  %9s  %9s  %9s  %8s  %8s  %8s\n" \
        "Algorithm" "Perm%" "Ratio-N" "Ratio-IP" "Adds-N" "Adds-IP" "Time-N" "Time-IP" "Cycles"
    printf "  %-12s  %7s  %9s  %9s  %9s  %9s  %8s  %8s  %8s\n" \
        "---------" "-----" "-------" "--------" "------" "-------" "------" "-------" "------"
}

print_inplace_row() {
    printf "  %-12s  %7s  %9s  %9s  %9s  %9s  %8s  %8s  %8s\n" \
        "$1" "${2}%" "$3" "$4" "$5" "$6" "$7" "$8" "$9"
}

PERMS="0 25 50 75 100"

echo "=== 16 MB (greedy, onepass, correcting) ==="
echo "    32,000 blocks × 512 B mean"
echo ""

for pct in $PERMS; do
    ref="$WORKDIR/16mb-ref-${pct}.bin"
    ver="$WORKDIR/16mb-ver-${pct}.bin"
    if [[ ! -f "$ref" ]]; then
        python3 "$GEN" 32000 512 "$pct" "$ref" "$ver" > /dev/null
    fi
done

print_header
last_algo=""
for algo in greedy onepass correcting; do
    [[ -n "$last_algo" ]] && echo ""
    for pct in $PERMS; do
        ref="$WORKDIR/16mb-ref-${pct}.bin"
        ver="$WORKDIR/16mb-ver-${pct}.bin"
        delta="$WORKDIR/16mb-${algo}-${pct}.delta"
        read -r ratio copies adds elapsed _ \
            < <(encode_and_measure "$algo" "$ref" "$ver" "$delta")
        print_row "$algo" "$pct" "$ratio" "$copies" "$adds" "$elapsed"
    done
    last_algo="$algo"
done

echo ""
echo ""

echo "=== Inplace vs normal (16 MB, onepass and correcting) ==="
echo "    32,000 blocks × 512 B mean"
echo ""

print_inplace_header
last_algo=""
for algo in onepass correcting; do
    [[ -n "$last_algo" ]] && echo ""
    for pct in $PERMS; do
        ref="$WORKDIR/16mb-ref-${pct}.bin"
        ver="$WORKDIR/16mb-ver-${pct}.bin"
        delta_n="$WORKDIR/16mb-${algo}-${pct}.delta"
        delta_ip="$WORKDIR/16mb-${algo}-ip-${pct}.delta"
        read -r rn cn an tn _cn \
            < <(encode_and_measure "$algo" "$ref" "$ver" "$delta_n")
        read -r ri ci ai ti cycles \
            < <(encode_and_measure "$algo" "$ref" "$ver" "$delta_ip" --inplace)
        print_inplace_row "$algo" "$pct" "$rn" "$ri" "$an" "$ai" "$tn" "$ti" "$cycles"
    done
    last_algo="$algo"
done

echo ""
echo ""

# Decode the standard and in-place deltas written by the section above.
echo "=== Apply-phase performance (16 MB, onepass and correcting) ==="
echo "    32,000 blocks × 512 B mean"
echo ""

printf "  %-12s  %7s  %9s  %9s\n" \
    "Algorithm" "Perm%" "Apply-N" "Apply-IP"
printf "  %-12s  %7s  %9s  %9s\n" \
    "---------" "-----" "-------" "--------"

apply_scratch="$WORKDIR/apply-scratch.bin"
last_algo=""
for algo in onepass correcting; do
    [[ -n "$last_algo" ]] && echo ""
    for pct in $PERMS; do
        ref="$WORKDIR/16mb-ref-${pct}.bin"
        delta_n="$WORKDIR/16mb-${algo}-${pct}.delta"
        delta_ip="$WORKDIR/16mb-${algo}-ip-${pct}.delta"

        out_n=$("$DELTA" decode "$ref" "$delta_n" "$apply_scratch" 2>/dev/null)
        ta=$(echo "$out_n" | awk '/^Time/ { print $2 }')

        out_ip=$("$DELTA" decode "$ref" "$delta_ip" "$apply_scratch" 2>/dev/null)
        tb=$(echo "$out_ip" | awk '/^Time/ { print $2 }')

        printf "  %-12s  %7s  %9s  %9s\n" \
            "$algo" "${pct}%" "$ta" "$tb"
    done
    last_algo="$algo"
done
rm -f "$apply_scratch"

echo ""
echo ""

echo "=== Inplace scaling (correcting, 16 → 256 MB) ==="
echo "    ~512 B mean blocks"
echo ""

printf "  %-8s  %7s  %9s  %9s  %9s  %8s  %8s\n" \
    "Size" "Perm%" "Ratio-N" "Ratio-IP" "Adds-IP" "Time-N" "Time-IP"
printf "  %-8s  %7s  %9s  %9s  %9s  %8s  %8s\n" \
    "----" "-----" "-------" "--------" "-------" "------" "-------"

for size_mb in 16 32 64 128 256; do
    nblocks=$((size_mb * 2000)); mean=512; tag="${size_mb}mb"

    for pct in $PERMS; do
        ref="$WORKDIR/${tag}-ref-${pct}.bin"
        ver="$WORKDIR/${tag}-ver-${pct}.bin"
        if [[ ! -f "$ref" ]]; then
            python3 "$GEN" "$nblocks" "$mean" "$pct" "$ref" "$ver" > /dev/null
        fi
    done

    first=1
    for pct in $PERMS; do
        ref="$WORKDIR/${tag}-ref-${pct}.bin"
        ver="$WORKDIR/${tag}-ver-${pct}.bin"
        delta_n="$WORKDIR/${tag}-correcting-${pct}.delta"
        delta_ip="$WORKDIR/${tag}-correcting-ip-${pct}.delta"
        read -r rn cn an tn _ \
            < <(encode_and_measure correcting "$ref" "$ver" "$delta_n")
        read -r ri ci ai ti cycles \
            < <(encode_and_measure correcting "$ref" "$ver" "$delta_ip" --inplace)
        if [[ "$first" -eq 1 ]]; then
            size_label="${size_mb} MB"
            first=0
        else
            size_label=""
        fi
        printf "  %-8s  %7s  %9s  %9s  %9s  %8s  %8s\n" \
            "$size_label" "${pct}%" "$rn" "$ri" "$ai" "$tn" "$ti"
    done
    echo ""
done

echo "=== 1 GB (onepass, correcting) ==="
echo "    8,000,000 blocks × 128 B mean"
echo ""

for pct in $PERMS; do
    ref="$WORKDIR/1gb-ref-${pct}.bin"
    ver="$WORKDIR/1gb-ver-${pct}.bin"
    if [[ ! -f "$ref" ]]; then
        echo "  Generating ${pct}% permutation..."
        python3 "$GEN" 8000000 128 "$pct" "$ref" "$ver" > /dev/null
    fi
done
echo ""

print_header
last_algo=""
for algo in onepass correcting; do
    [[ -n "$last_algo" ]] && echo ""
    for pct in $PERMS; do
        ref="$WORKDIR/1gb-ref-${pct}.bin"
        ver="$WORKDIR/1gb-ver-${pct}.bin"
        delta="$WORKDIR/1gb-${algo}-${pct}.delta"
        read -r ratio copies adds elapsed _ \
            < <(encode_and_measure "$algo" "$ref" "$ver" "$delta")
        print_row "$algo" "$pct" "$ratio" "$copies" "$adds" "$elapsed"
    done
    last_algo="$algo"
done

echo ""
echo "Working directory preserved at $WORKDIR"
echo "To clean up:  rm -rf $WORKDIR"
