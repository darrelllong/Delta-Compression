#!/usr/bin/env bash
#
# kernel-delta-test.sh — Measure delta compression on Linux kernel tarballs
#
# Downloads Linux 5.1.0 through 5.1.3 from kernel.org, decompresses the
# gzip layer (leaving .tar files), then encodes deltas from 5.1.0 to each
# successive release using the Rust delta tool.  Reports compression ratio
# for onepass and correcting algorithms (both auto-size their hash tables).
#
# Usage:
#   ./tests/kernel-delta-test.sh
#
# Requirements:
#   - curl, gunzip, bc
#   - Rust toolchain (builds the delta binary via cargo)
#   - ~4 GB disk in /tmp (four ~1 GB tarballs)
#   - ~2.5 GB RAM (auto-sized hash tables for 871 MB kernel tarballs)
#
# The tarballs are cached in WORKDIR so re-runs skip the download.

set -euo pipefail

WORKDIR="/tmp/delta-kernel-test"
KERNEL_BASE="https://cdn.kernel.org/pub/linux/kernel/v5.x"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

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
for i in $(seq 0 3); do
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

# ── Reference info ────────────────────────────────────────────────────────

REF="linux-5.1.tar"
REF_BYTES=$(wc -c < "$REF" | tr -d ' ')
REF_MB=$(echo "scale=1; $REF_BYTES / 1048576" | bc)

echo ""
echo "Reference: $REF  ($REF_MB MB)"
echo ""

# ── Encode deltas ─────────────────────────────────────────────────────────

fmt_bytes() {
    # Print byte count with commas (portable awk)
    echo "$1" | awk '{ printf "%\047d", $1 }'
}

header() {
    printf "%-16s  %14s  %14s  %8s  %8s\n" "$1" "$2" "$3" "$4" "$5"
}

row() {
    local label="$1" tar_sz="$2" delta_sz="$3" ratio="$4" elapsed="$5"
    printf "%-16s  %14s  %14s  %7s%%  %7ss\n" \
        "$label" "$(fmt_bytes "$tar_sz")" "$(fmt_bytes "$delta_sz")" \
        "$ratio" "$elapsed"
}

for ALGO in onepass correcting; do
    echo "=== $ALGO ==="
    echo ""
    header "Version" "Tar Size" "Delta Size" "Ratio" "Time"
    header "-------" "--------" "----------" "-----" "----"

    for i in $(seq 1 3); do
        VER="linux-5.1.$i.tar"
        DELTA_FILE="delta-${ALGO}-5.1.0-to-5.1.$i.delta"
        VER_BYTES=$(wc -c < "$VER" | tr -d ' ')

        # Encode delta; --verbose prints diagnostics to stderr on first run
        "$DELTA" encode "$ALGO" "$REF" "$VER" "$DELTA_FILE" --verbose > /dev/null

        DELTA_BYTES=$(wc -c < "$DELTA_FILE" | tr -d ' ')
        RATIO=$(echo "scale=2; $DELTA_BYTES * 100 / $VER_BYTES" | bc)

        # Time a fresh encode for the report (quiet)
        T0=$(date +%s)
        "$DELTA" encode "$ALGO" "$REF" "$VER" "$DELTA_FILE" > /dev/null 2>&1
        T1=$(date +%s)
        ELAPSED=$((T1 - T0))

        row "5.1.$i" "$VER_BYTES" "$DELTA_BYTES" "$RATIO" "$ELAPSED"
    done
    echo ""
done

# ── Verify round-trip ─────────────────────────────────────────────────────

echo "Verifying round-trip (correcting, 5.1.1)..."
"$DELTA" decode "$REF" "delta-correcting-5.1.0-to-5.1.1.delta" \
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
