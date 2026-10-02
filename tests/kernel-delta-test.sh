#!/usr/bin/env bash
#
# kernel-delta-test.sh — Measure delta compression on Linux kernel tarballs
#
# Downloads Linux 5.1.0 through 5.1.7 from kernel.org, decompresses the
# gzip layer (leaving .tar files), then encodes deltas in three modes:
#
#   1. From base: 5.1.0 → 5.1.{1..7}  (fixed reference)
#   2. Successive: 5.1.{n} → 5.1.{n+1}  (chain/successive deltas)
#   3. From 5.1.1: 5.1.1 → 5.1.{2..7}  (divergence from a non-zero base)
#
# Reports compression ratio for onepass and correcting algorithms.
#
# Usage:
#   ./tests/kernel-delta-test.sh
#
# Requirements:
#   - curl, gunzip, bc, python3 (for sub-second timing)
#   - Rust toolchain (builds the delta binary via cargo)
#   - ~8 GB disk in WORKDIR (eight ~1 GB tarballs)
#   - ~2.5 GB RAM (auto-sized hash tables for 871 MB kernel tarballs)
#
# The tarballs are cached in WORKDIR so re-runs skip the download.

set -euo pipefail

WORKDIR="${WORKDIR:-/tmp/delta-kernel-test}"
KERNEL_BASE="https://cdn.kernel.org/pub/linux/kernel/v5.x"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MAXVER=7   # download 5.1.0 through 5.1.MAXVER

echo "Building delta tool (release)..."
cd "$REPO_ROOT/src/rust/delta"
cargo build --release 2>&1 | tail -1
DELTA="$REPO_ROOT/src/rust/delta/target/release/delta"
echo ""

# tar_name <i>: the tarball for 5.1.<i>; 5.1.0 was released as plain "5.1".
tar_name() {
    if [ "$1" -eq 0 ]; then echo "linux-5.1.tar"
    else echo "linux-5.1.$1.tar"
    fi
}

mkdir -p "$WORKDIR"
cd "$WORKDIR"

echo "Downloading Linux 5.1.x kernel tarballs to $WORKDIR ..."
for i in $(seq 0 $MAXVER); do
    TAR=$(tar_name "$i")
    GZ="$TAR.gz"
    URL="$KERNEL_BASE/$GZ"

    if [ -f "$TAR" ]; then
        echo "  $TAR (cached)"
    elif [ -f "$GZ" ]; then
        echo "  $GZ (cached, decompressing)"
        gunzip "$GZ"
    else
        echo "  $URL"
        curl -sfLO "$URL"
        gunzip "$GZ"
    fi
done

fmt_bytes() {
    echo "$1" | awk '{ printf "%\047d", $1 }'
}

header() {
    printf "%-20s  %14s  %14s  %8s  %8s\n" "$1" "$2" "$3" "$4" "$5"
}

# encode_timed <algo> <ref> <ver> <delta_file>: print the wall-clock seconds
# of one encode, to one decimal place.
encode_timed() {
    python3 - "$DELTA" encode "$@" <<'EOF'
import sys, subprocess, time
t0 = time.perf_counter()
# A failed encode would otherwise be timed as a fast one.
subprocess.run(sys.argv[1:], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
print(f"{time.perf_counter()-t0:.1f}")
EOF
}

# row <label> <algo> <ref> <ver> <delta_file> [--verbose]: encode twice and
# print one table row.  The first encode writes the delta file and warms the
# page cache (with --verbose its diagnostics go to stderr); the second is the
# one timed.
row() {
    local label=$1 algo=$2 ref=$3 ver=$4 delta_file=$5 verbose=${6:-}
    local ver_bytes delta_bytes ratio elapsed

    if [ -n "$verbose" ]; then
        "$DELTA" encode "$algo" "$ref" "$ver" "$delta_file" --verbose > /dev/null
    else
        "$DELTA" encode "$algo" "$ref" "$ver" "$delta_file" > /dev/null 2>&1
    fi

    ver_bytes=$(wc -c < "$ver" | tr -d ' ')
    delta_bytes=$(wc -c < "$delta_file" | tr -d ' ')
    ratio=$(echo "scale=2; $delta_bytes * 100 / $ver_bytes" | bc)
    elapsed=$(encode_timed "$algo" "$ref" "$ver" "$delta_file")

    printf "%-20s  %14s  %14s  %7s%%  %7ss\n" \
        "$label" "$(fmt_bytes "$ver_bytes")" "$(fmt_bytes "$delta_bytes")" \
        "$ratio" "$elapsed"
}

# table <first-column> <dashes>: print the heading for one algorithm's table.
table() {
    echo "--- $ALGO ---"
    echo ""
    header "$1" "Tar Size" "Delta Size" "Ratio" "Time"
    header "$2" "--------" "----------" "-----" "----"
}

REF="linux-5.1.tar"
REF_BYTES=$(wc -c < "$REF" | tr -d ' ')
REF_MB=$(echo "scale=1; $REF_BYTES / 1048576" | bc)

echo ""
echo "Reference: $REF  ($REF_MB MB)"
echo ""

echo "=== From base: 5.1.0 → 5.1.{1..$MAXVER} ==="
echo ""

for ALGO in onepass correcting; do
    table "Version" "-------"
    for i in $(seq 1 $MAXVER); do
        row "5.1.$i" "$ALGO" "$REF" "linux-5.1.$i.tar" \
            "delta-${ALGO}-base-to-5.1.$i.delta" --verbose
    done
    echo ""
done

echo "=== Successive: 5.1.n → 5.1.n+1 ==="
echo ""

for ALGO in onepass correcting; do
    table "Transition" "----------"
    for i in $(seq 1 $MAXVER); do
        PREV=$((i - 1))
        row "5.1.${PREV}→5.1.${i}" "$ALGO" "$(tar_name "$PREV")" "linux-5.1.$i.tar" \
            "delta-${ALGO}-chain-5.1.${PREV}-to-5.1.${i}.delta"
    done
    echo ""
done

echo "=== From 5.1.1: divergence 5.1.1 → 5.1.{2..$MAXVER} ==="
echo ""

for ALGO in onepass correcting; do
    table "Version" "-------"
    for i in $(seq 2 $MAXVER); do
        row "5.1.$i" "$ALGO" "linux-5.1.1.tar" "linux-5.1.$i.tar" \
            "delta-${ALGO}-from-5.1.1-to-5.1.$i.delta"
    done
    echo ""
done

echo "Verifying round-trip (correcting, 5.1.0→5.1.1)..."
"$DELTA" decode "$REF" "delta-correcting-base-to-5.1.1.delta" \
    "recovered-5.1.1.tar" > /dev/null 2>&1

if cmp -s "linux-5.1.1.tar" "recovered-5.1.1.tar"; then
    echo "  OK: recovered file matches original."
else
    echo "  FAILED: recovered file differs!"
    exit 1
fi

echo ""
echo "Working directory preserved at $WORKDIR"
echo "To clean up:  rm -rf $WORKDIR"
