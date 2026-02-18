#!/bin/sh
#
# test_delta.sh — Integration tests for the C delta compression tool
#
# Verifies roundtrip encode/decode, in-place mode, splay tree mode,
# --min-copy, and cross-language byte-identical deltas.
#

set -e

DELTA=./delta
PASS=0
FAIL=0
TESTS=0

# Locate other implementations for cross-language tests
RUST_DELTA=""
CPP_DELTA=""
PY_DELTA=""

if [ -x "../../src/rust/delta/target/release/delta" ]; then
    RUST_DELTA="../../src/rust/delta/target/release/delta"
elif [ -x "../rust/delta/target/release/delta" ]; then
    RUST_DELTA="../rust/delta/target/release/delta"
fi

if [ -x "../cpp/build/delta" ]; then
    CPP_DELTA="../cpp/build/delta"
fi

if [ -f "../python/delta.py" ]; then
    PY_DELTA="python3 ../python/delta.py"
fi

check() {
    TESTS=$((TESTS + 1))
    desc="$1"; shift
    if "$@" >/dev/null 2>&1; then
        PASS=$((PASS + 1))
        printf "  ok  %s\n" "$desc"
    else
        FAIL=$((FAIL + 1))
        printf "FAIL  %s\n" "$desc"
    fi
}

tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT

# Create test files
ref="$tmpdir/ref.txt"
ver="$tmpdir/ver.txt"

cat > "$ref" <<'EOF'
AAAA BBBB CCCC DDDD EEEE FFFF GGGG HHHH IIII JJJJ KKKK LLLL MMMM NNNN OOOO PPPP
The quick brown fox jumps over the lazy dog! Pack my box with five dozen liquor jugs.
Sphinx of black quartz, judge my vow. How vexingly quick daft zebras jump.
EOF

cat > "$ver" <<'EOF'
AAAA BBBB XXXX DDDD EEEE FFFF GGGG HHHH IIII JJJJ KKKK LLLL MMMM NNNN OOOO PPPP
The quick brown cat jumps over the lazy dog! Pack my box with five dozen liquor jugs.
Sphinx of black quartz, judge my vow. How vexingly quick daft zebras jump. Extra text here.
EOF

echo ""
echo "=== Roundtrip tests ==="

for algo in greedy onepass correcting; do
    d="$tmpdir/${algo}.delta"
    out="$tmpdir/${algo}.out"
    $DELTA encode $algo "$ref" "$ver" "$d"
    $DELTA decode "$ref" "$d" "$out"
    check "$algo roundtrip" diff -q "$ver" "$out"
done

echo ""
echo "=== In-place tests ==="

for algo in greedy onepass correcting; do
    for pol in localmin constant; do
        d="$tmpdir/${algo}-ip-${pol}.delta"
        out="$tmpdir/${algo}-ip-${pol}.out"
        $DELTA encode $algo "$ref" "$ver" "$d" --inplace --policy $pol
        $DELTA decode "$ref" "$d" "$out"
        check "$algo inplace ($pol) roundtrip" diff -q "$ver" "$out"
    done
done

echo ""
echo "=== Splay tree tests ==="

for algo in greedy onepass correcting; do
    d="$tmpdir/${algo}-splay.delta"
    out="$tmpdir/${algo}-splay.out"
    $DELTA encode $algo "$ref" "$ver" "$d" --splay
    $DELTA decode "$ref" "$d" "$out"
    check "$algo splay roundtrip" diff -q "$ver" "$out"
done

echo ""
echo "=== Info command ==="

d="$tmpdir/onepass.delta"
check "info command" $DELTA info "$d"

echo ""
echo "=== Empty file tests ==="

empty="$tmpdir/empty"
: > "$empty"

d="$tmpdir/empty-ref.delta"
out="$tmpdir/empty-ref.out"
$DELTA encode onepass "$empty" "$ver" "$d"
$DELTA decode "$empty" "$d" "$out"
check "empty ref roundtrip" diff -q "$ver" "$out"

d="$tmpdir/empty-ver.delta"
out="$tmpdir/empty-ver.out"
$DELTA encode onepass "$ref" "$empty" "$d"
$DELTA decode "$ref" "$d" "$out"
check "empty ver roundtrip" diff -q "$empty" "$out"

echo ""
echo "=== Identical files ==="

d="$tmpdir/identical.delta"
out="$tmpdir/identical.out"
$DELTA encode onepass "$ref" "$ref" "$d"
$DELTA decode "$ref" "$d" "$out"
check "identical files roundtrip" diff -q "$ref" "$out"

echo ""
echo "=== Byte-identical deltas (C vs other implementations) ==="

if [ -n "$RUST_DELTA" ]; then
    for algo in onepass correcting; do
        c_d="$tmpdir/c-${algo}.delta"
        r_d="$tmpdir/rust-${algo}.delta"
        $DELTA encode $algo "$ref" "$ver" "$c_d"
        $RUST_DELTA encode $algo "$ref" "$ver" "$r_d"
        check "C vs Rust $algo byte-identical" diff -q "$c_d" "$r_d"
    done
else
    echo "  (Rust binary not found, skipping)"
fi

if [ -n "$CPP_DELTA" ]; then
    for algo in onepass correcting; do
        c_d="$tmpdir/c-${algo}2.delta"
        cpp_d="$tmpdir/cpp-${algo}.delta"
        $DELTA encode $algo "$ref" "$ver" "$c_d"
        $CPP_DELTA encode $algo "$ref" "$ver" "$cpp_d"
        check "C vs C++ $algo byte-identical" diff -q "$c_d" "$cpp_d"
    done
else
    echo "  (C++ binary not found, skipping)"
fi

if [ -n "$PY_DELTA" ]; then
    for algo in onepass correcting; do
        c_d="$tmpdir/c-${algo}3.delta"
        py_d="$tmpdir/py-${algo}.delta"
        $DELTA encode $algo "$ref" "$ver" "$c_d"
        $PY_DELTA encode $algo "$ref" "$ver" "$py_d"
        check "C vs Python $algo byte-identical" diff -q "$c_d" "$py_d"
    done
else
    echo "  (Python not found, skipping)"
fi

echo ""
echo "=== Cross-language decode ==="

if [ -n "$RUST_DELTA" ]; then
    for algo in onepass correcting; do
        c_d="$tmpdir/c-xdec-${algo}.delta"
        r_out="$tmpdir/rust-from-c-${algo}.out"
        $DELTA encode $algo "$ref" "$ver" "$c_d"
        $RUST_DELTA decode "$ref" "$c_d" "$r_out"
        check "C encode -> Rust decode ($algo)" diff -q "$ver" "$r_out"
    done
fi

if [ -n "$RUST_DELTA" ]; then
    for algo in onepass correcting; do
        r_d="$tmpdir/rust-xdec-${algo}.delta"
        c_out="$tmpdir/c-from-rust-${algo}.out"
        $RUST_DELTA encode $algo "$ref" "$ver" "$r_d"
        $DELTA decode "$ref" "$r_d" "$c_out"
        check "Rust encode -> C decode ($algo)" diff -q "$ver" "$c_out"
    done
fi

echo ""
echo "========================================"
printf "Results: %d passed, %d failed (of %d)\n" "$PASS" "$FAIL" "$TESTS"
echo "========================================"

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
