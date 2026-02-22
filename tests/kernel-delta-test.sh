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
#   - curl, gunzip, bc
#   - Rust toolchain (builds the delta binary via cargo)
#   - ~8 GB disk in /tmp (eight ~1 GB tarballs)
#   - ~2.5 GB RAM (auto-sized hash tables for 871 MB kernel tarballs)
#
# The tarballs are cached in WORKDIR so re-runs skip the download.

set -euo pipefail

WORKDIR="${WORKDIR:-/tmp/delta-kernel-test}"
KERNEL_BASE="https://cdn.kernel.org/pub/linux/kernel/v5.x"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MAXVER=7   # download 5.1.0 through 5.1.MAXVER

# ── Build ─────────────────────────────────────────────────────────────────

echo "Building delta tool (release)..."
cd "$REPO_ROOT/src/rust/delta"
cargo build --release 2>&1 | tail -1
DELTA="$REPO_ROOT/src/rust/delta/target/release/delta"
echo ""

# ── Download ──────────────────────────────────────────────────────────────

mkdir -p "$WORKDIR"
cd "$WORKDIR"

echo "Downloading Linux 5.1.x kernel tarballs to $WORKDIR ..."
for i in $(seq 0 $MAXVER); do
    if [ "$i" -eq 0 ]; then
        TAR="linux-5.1.tar"
        GZ="linux-5.1.tar.gz"
        URL="$KERNEL_BASE/linux-5.1.tar.gz"
    else
        TAR="linux-5.1.$i.tar"
        GZ="linux-5.1.$i.tar.gz"
        URL="$KERNEL_BASE/$GZ"
    fi

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

# ── Helpers ───────────────────────────────────────────────────────────────

fmt_bytes() {
    echo "$1" | awk '{ printf "%\047d", $1 }'
}

header() {
    printf "%-20s  %14s  %14s  %8s  %8s\n" "$1" "$2" "$3" "$4" "$5"
}

row() {
    local label="$1" tar_sz="$2" delta_sz="$3" ratio="$4" elapsed="$5"
    printf "%-20s  %14s  %14s  %7s%%  %7ss\n" \
        "$label" "$(fmt_bytes "$tar_sz")" "$(fmt_bytes "$delta_sz")" \
        "$ratio" "$elapsed"
}

# encode_quiet <ref> <ver> <delta_file>
# Encodes and returns timing (seconds); no verbose output.
encode_timed() {
    local ref="$1" ver="$2" delta_file="$3"
    "$DELTA" encode "$ALGO" "$ref" "$ver" "$delta_file" > /dev/null 2>&1
    local t0 t1
    t0=$(date +%s)
    "$DELTA" encode "$ALGO" "$ref" "$ver" "$delta_file" > /dev/null 2>&1
    t1=$(date +%s)
    echo $((t1 - t0))
}

# tar_name <i>   → linux-5.1.tar (i=0) or linux-5.1.$i.tar (i>0)
tar_name() {
    if [ "$1" -eq 0 ]; then echo "linux-5.1.tar"
    else echo "linux-5.1.$1.tar"
    fi
}

REF="linux-5.1.tar"
REF_BYTES=$(wc -c < "$REF" | tr -d ' ')
REF_MB=$(echo "scale=1; $REF_BYTES / 1048576" | bc)

echo ""
echo "Reference: $REF  ($REF_MB MB)"
echo ""

# ── Section 1: From base (5.1.0 → 5.1.{1..MAXVER}) ───────────────────────

echo "=== From base: 5.1.0 → 5.1.{1..$MAXVER} ==="
echo ""

for ALGO in onepass correcting; do
    echo "--- $ALGO ---"
    echo ""
    header "Version" "Tar Size" "Delta Size" "Ratio" "Time"
    header "-------" "--------" "----------" "-----" "----"

    for i in $(seq 1 $MAXVER); do
        VER="linux-5.1.$i.tar"
        DELTA_FILE="delta-${ALGO}-base-to-5.1.$i.delta"
        VER_BYTES=$(wc -c < "$VER" | tr -d ' ')

        # First run: verbose diagnostics (stderr) + create delta file
        "$DELTA" encode "$ALGO" "$REF" "$VER" "$DELTA_FILE" --verbose > /dev/null

        DELTA_BYTES=$(wc -c < "$DELTA_FILE" | tr -d ' ')
        RATIO=$(echo "scale=2; $DELTA_BYTES * 100 / $VER_BYTES" | bc)
        ELAPSED=$(encode_timed "$REF" "$VER" "$DELTA_FILE")

        row "5.1.$i" "$VER_BYTES" "$DELTA_BYTES" "$RATIO" "$ELAPSED"
    done
    echo ""
done

# ── Section 2: Successive / chain (5.1.n → 5.1.n+1) ─────────────────────

echo "=== Successive: 5.1.n → 5.1.n+1 ==="
echo ""

for ALGO in onepass correcting; do
    echo "--- $ALGO ---"
    echo ""
    header "Transition" "Tar Size" "Delta Size" "Ratio" "Time"
    header "----------" "--------" "----------" "-----" "----"

    for i in $(seq 1 $MAXVER); do
        PREV=$((i - 1))
        REF_S=$(tar_name "$PREV")
        VER_S="linux-5.1.$i.tar"
        DELTA_FILE="delta-${ALGO}-chain-5.1.${PREV}-to-5.1.${i}.delta"
        VER_BYTES=$(wc -c < "$VER_S" | tr -d ' ')

        "$DELTA" encode "$ALGO" "$REF_S" "$VER_S" "$DELTA_FILE" > /dev/null 2>&1

        DELTA_BYTES=$(wc -c < "$DELTA_FILE" | tr -d ' ')
        RATIO=$(echo "scale=2; $DELTA_BYTES * 100 / $VER_BYTES" | bc)
        ELAPSED=$(encode_timed "$REF_S" "$VER_S" "$DELTA_FILE")

        if [ "$PREV" -eq 0 ]; then
            LABEL="5.1.0→5.1.${i}"
        else
            LABEL="5.1.${PREV}→5.1.${i}"
        fi
        row "$LABEL" "$VER_BYTES" "$DELTA_BYTES" "$RATIO" "$ELAPSED"
    done
    echo ""
done

# ── Section 3: From 5.1.1 (5.1.1 → 5.1.{2..MAXVER}) ─────────────────────

echo "=== From 5.1.1: divergence 5.1.1 → 5.1.{2..$MAXVER} ==="
echo ""

REF1="linux-5.1.1.tar"

for ALGO in onepass correcting; do
    echo "--- $ALGO ---"
    echo ""
    header "Version" "Tar Size" "Delta Size" "Ratio" "Time"
    header "-------" "--------" "----------" "-----" "----"

    for i in $(seq 2 $MAXVER); do
        VER_S="linux-5.1.$i.tar"
        DELTA_FILE="delta-${ALGO}-from-5.1.1-to-5.1.$i.delta"
        VER_BYTES=$(wc -c < "$VER_S" | tr -d ' ')

        "$DELTA" encode "$ALGO" "$REF1" "$VER_S" "$DELTA_FILE" > /dev/null 2>&1

        DELTA_BYTES=$(wc -c < "$DELTA_FILE" | tr -d ' ')
        RATIO=$(echo "scale=2; $DELTA_BYTES * 100 / $VER_BYTES" | bc)
        ELAPSED=$(encode_timed "$REF1" "$VER_S" "$DELTA_FILE")

        row "5.1.$i" "$VER_BYTES" "$DELTA_BYTES" "$RATIO" "$ELAPSED"
    done
    echo ""
done

# ── Verify round-trip ─────────────────────────────────────────────────────

echo "Verifying round-trip (correcting, 5.1.0→5.1.1)..."
"$DELTA" decode "$REF" "delta-correcting-base-to-5.1.1.delta" \
    "recovered-5.1.1.tar" > /dev/null 2>&1

if diff "linux-5.1.1.tar" "recovered-5.1.1.tar" > /dev/null 2>&1; then
    echo "  OK: recovered file matches original."
else
    echo "  FAILED: recovered file differs!"
    exit 1
fi

echo ""
echo "Working directory preserved at $WORKDIR"
echo "To clean up:  rm -rf $WORKDIR"
