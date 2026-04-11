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
JAVA_DELTA=""

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

if [ -d "../java/out" ] && [ -f "../java/out/delta/Delta.class" ]; then
    # Prefer the same Java used to compile (Makefile sets JAVA=/opt/homebrew/opt/openjdk@17/bin/java).
    JAVA_BIN="${JAVA:-java}"
    if [ "$JAVA_BIN" = "java" ] && [ -x "/opt/homebrew/opt/openjdk@17/bin/java" ]; then
        JAVA_BIN="/opt/homebrew/opt/openjdk@17/bin/java"
    fi
    JAVA_DELTA="$JAVA_BIN -cp ../java/out delta.Delta"
fi

GO_DELTA=""
if [ -x "../go/delta/delta" ]; then
    GO_DELTA="../go/delta/delta"
fi

KT_DELTA=""
if [ -f "../kotlin/delta.jar" ]; then
    KT_DELTA="java -cp ../kotlin/delta.jar delta.Delta"
fi

SCALA_DELTA=""
if [ -f "../scala/delta.jar" ]; then
    SCALA_JAVA="${JAVA:-/opt/homebrew/opt/openjdk@17/bin/java}"
    SCALA_LIB="/opt/homebrew/opt/scala/libexec/lib/scala.jar"
    if [ -x "$SCALA_JAVA" ] && [ -f "$SCALA_LIB" ]; then
        SCALA_DELTA="$SCALA_JAVA -cp ../scala/delta.jar:$SCALA_LIB delta.Delta"
    fi
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

check_fails() {
    TESTS=$((TESTS + 1))
    desc="$1"; shift
    if "$@" >/dev/null 2>&1; then
        FAIL=$((FAIL + 1))
        printf "FAIL  %s\n" "$desc"
    else
        PASS=$((PASS + 1))
        printf "  ok  %s\n" "$desc"
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
echo "=== Encode overflow rejection ==="

if [ -x "./test_overflow" ]; then
    for case in version_size copy_src copy_dst copy_len add_dst add_len; do
        check_fails "C encode rejects $case overflow" ./test_overflow "$case"
    done
else
    echo "  (test_overflow: not found)"
fi

echo ""
echo "=== Decode validation ==="

base_delta="$tmpdir/onepass.delta"
base_size=$(wc -c < "$base_delta" | tr -d ' ')

missing_end="$tmpdir/missing-end.delta"
dd if="$base_delta" of="$missing_end" bs=1 count=$((base_size - 1)) 2>/dev/null
check_fails "info rejects missing END" $DELTA info "$missing_end"

trailing="$tmpdir/trailing.delta"
cp "$base_delta" "$trailing"
printf '\177' >> "$trailing"
check_fails "info rejects trailing data" $DELTA info "$trailing"

bad_crc="$tmpdir/bad-crc.delta"
bad_out="$tmpdir/bad-crc.out"
cp "$base_delta" "$bad_crc"
printf '\377' | dd of="$bad_crc" bs=1 seek=17 conv=notrunc 2>/dev/null
TESTS=$((TESTS + 1))
if ! $DELTA decode "$ref" "$bad_crc" "$bad_out" >/dev/null 2>&1 \
    && [ ! -e "$bad_out" ]; then
    PASS=$((PASS + 1))
    printf "  ok  decode rejects bad CRC without writing output\n"
else
    FAIL=$((FAIL + 1))
    printf "FAIL  decode rejects bad CRC without writing output\n"
fi

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
echo "=== Inplace subcommand tests ==="

for algo in greedy onepass correcting; do
    # 1. encode standard → inplace subcommand → decode roundtrip
    std_d="$tmpdir/${algo}-sub-std.delta"
    ip_d="$tmpdir/${algo}-sub-ip.delta"
    out="$tmpdir/${algo}-sub.out"
    $DELTA encode $algo "$ref" "$ver" "$std_d"
    $DELTA inplace "$ref" "$std_d" "$ip_d"
    $DELTA decode "$ref" "$ip_d" "$out"
    check "$algo inplace subcommand roundtrip" diff -q "$ver" "$out"

    # 2. idempotency: already-inplace delta passed to subcommand → still correct
    ip_d2="$tmpdir/${algo}-sub-ip2.delta"
    out2="$tmpdir/${algo}-sub2.out"
    $DELTA inplace "$ref" "$ip_d" "$ip_d2"
    $DELTA decode "$ref" "$ip_d2" "$out2"
    check "$algo inplace subcommand idempotent" diff -q "$ver" "$out2"

    # 3. byte-identical: encode --inplace vs encode then inplace subcommand
    direct_d="$tmpdir/${algo}-direct-ip.delta"
    $DELTA encode $algo "$ref" "$ver" "$direct_d" --inplace
    check "$algo inplace subcommand byte-identical to --inplace" diff -q "$direct_d" "$ip_d"
done

echo ""
echo "=== Cross-language inplace subcommand ==="

if [ -n "$RUST_DELTA" ]; then
    for algo in greedy onepass correcting; do
        # C encode standard → Rust inplace subcommand → C decode
        c_std="$tmpdir/c-std-${algo}.delta"
        r_ip="$tmpdir/rust-ip-from-c-${algo}.delta"
        c_out="$tmpdir/c-out-from-rust-ip-${algo}.out"
        $DELTA encode $algo "$ref" "$ver" "$c_std"
        $RUST_DELTA inplace "$ref" "$c_std" "$r_ip"
        $DELTA decode "$ref" "$r_ip" "$c_out"
        check "C encode -> Rust inplace -> C decode ($algo)" diff -q "$ver" "$c_out"

        # Rust encode standard → C inplace subcommand → Rust decode
        r_std="$tmpdir/rust-std-${algo}.delta"
        c_ip="$tmpdir/c-ip-from-rust-${algo}.delta"
        r_out="$tmpdir/rust-out-from-c-ip-${algo}.out"
        $RUST_DELTA encode $algo "$ref" "$ver" "$r_std"
        $DELTA inplace "$ref" "$r_std" "$c_ip"
        $RUST_DELTA decode "$ref" "$c_ip" "$r_out"
        check "Rust encode -> C inplace -> Rust decode ($algo)" diff -q "$ver" "$r_out"
    done
else
    echo "  (Rust inplace: not found)"
fi

if [ -n "$GO_DELTA" ]; then
    for algo in greedy onepass correcting; do
        c_std="$tmpdir/c-std-go-${algo}.delta"
        go_ip="$tmpdir/go-ip-from-c-${algo}.delta"
        c_out="$tmpdir/c-out-from-go-ip-${algo}.out"
        $DELTA encode $algo "$ref" "$ver" "$c_std"
        $GO_DELTA inplace "$ref" "$c_std" "$go_ip"
        $DELTA decode "$ref" "$go_ip" "$c_out"
        check "C encode -> Go inplace -> C decode ($algo)" diff -q "$ver" "$c_out"

        go_std="$tmpdir/go-std-${algo}.delta"
        c_ip="$tmpdir/c-ip-from-go-${algo}.delta"
        go_out="$tmpdir/go-out-from-c-ip-${algo}.out"
        $GO_DELTA encode $algo "$ref" "$ver" "$go_std"
        $DELTA inplace "$ref" "$go_std" "$c_ip"
        $GO_DELTA decode "$ref" "$c_ip" "$go_out"
        check "Go encode -> C inplace -> Go decode ($algo)" diff -q "$ver" "$go_out"
    done
else
    echo "  (Go inplace: not found)"
fi

if [ -n "$KT_DELTA" ]; then
    for algo in greedy onepass correcting; do
        c_std="$tmpdir/c-std-kt-${algo}.delta"
        kt_ip="$tmpdir/kt-ip-from-c-${algo}.delta"
        c_out="$tmpdir/c-out-from-kt-ip-${algo}.out"
        $DELTA encode $algo "$ref" "$ver" "$c_std"
        $KT_DELTA inplace "$ref" "$c_std" "$kt_ip"
        $DELTA decode "$ref" "$kt_ip" "$c_out"
        check "C encode -> Kotlin inplace -> C decode ($algo)" diff -q "$ver" "$c_out"

        kt_std="$tmpdir/kt-std-${algo}.delta"
        c_ip="$tmpdir/c-ip-from-kt-${algo}.delta"
        kt_out="$tmpdir/kt-out-from-c-ip-${algo}.out"
        $KT_DELTA encode $algo "$ref" "$ver" "$kt_std"
        $DELTA inplace "$ref" "$kt_std" "$c_ip"
        $KT_DELTA decode "$ref" "$c_ip" "$kt_out"
        check "Kotlin encode -> C inplace -> Kotlin decode ($algo)" diff -q "$ver" "$kt_out"
    done
else
    echo "  (Kotlin inplace: not found)"
fi

if [ -n "$SCALA_DELTA" ]; then
    for algo in greedy onepass correcting; do
        c_std="$tmpdir/c-std-sc-${algo}.delta"
        sc_ip="$tmpdir/sc-ip-from-c-${algo}.delta"
        c_out="$tmpdir/c-out-from-sc-ip-${algo}.out"
        $DELTA encode $algo "$ref" "$ver" "$c_std"
        $SCALA_DELTA inplace "$ref" "$c_std" "$sc_ip"
        $DELTA decode "$ref" "$sc_ip" "$c_out"
        check "C encode -> Scala inplace -> C decode ($algo)" diff -q "$ver" "$c_out"

        sc_std="$tmpdir/sc-std-${algo}.delta"
        c_ip="$tmpdir/c-ip-from-sc-${algo}.delta"
        sc_out="$tmpdir/sc-out-from-c-ip-${algo}.out"
        $SCALA_DELTA encode $algo "$ref" "$ver" "$sc_std"
        $DELTA inplace "$ref" "$sc_std" "$c_ip"
        $SCALA_DELTA decode "$ref" "$c_ip" "$sc_out"
        check "Scala encode -> C inplace -> Scala decode ($algo)" diff -q "$ver" "$sc_out"
    done
else
    echo "  (Scala inplace: not found)"
fi

echo ""
echo "=== Byte-identical deltas (C vs other implementations) ==="

if [ -n "$RUST_DELTA" ]; then
    for algo in greedy onepass correcting; do
        c_d="$tmpdir/c-${algo}.delta"
        r_d="$tmpdir/rust-${algo}.delta"
        $DELTA encode $algo "$ref" "$ver" "$c_d"
        $RUST_DELTA encode $algo "$ref" "$ver" "$r_d"
        check "C vs Rust $algo byte-identical" diff -q "$c_d" "$r_d"
    done
else
    echo "  (C vs Rust byte-identical: not found)"
fi

if [ -n "$CPP_DELTA" ]; then
    for algo in greedy onepass correcting; do
        c_d="$tmpdir/c-${algo}2.delta"
        cpp_d="$tmpdir/cpp-${algo}.delta"
        $DELTA encode $algo "$ref" "$ver" "$c_d"
        $CPP_DELTA encode $algo "$ref" "$ver" "$cpp_d"
        check "C vs C++ $algo byte-identical" diff -q "$c_d" "$cpp_d"
    done
else
    echo "  (C vs C++ byte-identical: not found)"
fi

if [ -n "$PY_DELTA" ]; then
    for algo in greedy onepass correcting; do
        c_d="$tmpdir/c-${algo}3.delta"
        py_d="$tmpdir/py-${algo}.delta"
        $DELTA encode $algo "$ref" "$ver" "$c_d"
        $PY_DELTA encode $algo "$ref" "$ver" "$py_d"
        check "C vs Python $algo byte-identical" diff -q "$c_d" "$py_d"
    done
else
    echo "  (C vs Python byte-identical: not found)"
fi

if [ -n "$JAVA_DELTA" ]; then
    for algo in greedy onepass correcting; do
        c_d="$tmpdir/c-${algo}4.delta"
        j_d="$tmpdir/java-${algo}.delta"
        $DELTA encode $algo "$ref" "$ver" "$c_d"
        $JAVA_DELTA encode $algo "$ref" "$ver" "$j_d"
        check "C vs Java $algo byte-identical" diff -q "$c_d" "$j_d"
    done
else
    echo "  (C vs Java byte-identical: not found)"
fi

if [ -n "$KT_DELTA" ]; then
    for algo in greedy onepass correcting; do
        c_d="$tmpdir/c-${algo}5.delta"
        kt_d="$tmpdir/kotlin-${algo}.delta"
        $DELTA encode $algo "$ref" "$ver" "$c_d"
        $KT_DELTA encode $algo "$ref" "$ver" "$kt_d"
        check "C vs Kotlin $algo byte-identical" diff -q "$c_d" "$kt_d"
    done
else
    echo "  (C vs Kotlin byte-identical: not found)"
fi

if [ -n "$SCALA_DELTA" ]; then
    for algo in greedy onepass correcting; do
        c_d="$tmpdir/c-${algo}6.delta"
        sc_d="$tmpdir/scala-${algo}.delta"
        $DELTA encode $algo "$ref" "$ver" "$c_d"
        $SCALA_DELTA encode $algo "$ref" "$ver" "$sc_d"
        check "C vs Scala $algo byte-identical" diff -q "$c_d" "$sc_d"
    done
else
    echo "  (C vs Scala byte-identical: not found)"
fi

if [ -n "$GO_DELTA" ]; then
    for algo in greedy onepass correcting; do
        c_d="$tmpdir/c-${algo}7.delta"
        go_d="$tmpdir/go-${algo}.delta"
        $DELTA encode $algo "$ref" "$ver" "$c_d"
        $GO_DELTA encode $algo "$ref" "$ver" "$go_d"
        check "C vs Go $algo byte-identical" diff -q "$c_d" "$go_d"
    done
else
    echo "  (C vs Go byte-identical: not found)"
fi

echo ""
echo "=== Encoder DLT\x04 header ==="
# Verify each encoder writes a valid DLT\x04 header for both 'encode' and
# 'inplace' subcommands:
#   bytes 0-3  = DLT\x04 magic (444c5404)
#   bytes 5-12 = version_size as u64 big-endian
# The u64 check catches a buggy encoder that writes u32+4-zero-padding:
# for version_size=39, correct u64 = 0000000000000027 but u32+pad = 0000002700000000.

magic_ref="$tmpdir/magic-ref.bin"
magic_ver="$tmpdir/magic-ver.bin"
printf '%s' "reference data for magic test" > "$magic_ref"
printf '%s' "version data for magic test -- modified" > "$magic_ver"
magic_ver_size=$(wc -c < "$magic_ver" | tr -d ' ')
magic_ver_size_hex=$(printf '%016x' "$magic_ver_size")

# check_v4_header <label> <delta_file> <subcommand>
# Reads magic (bytes 0-3) and version_size u64 (bytes 5-12) via od.
check_v4_header() {
    local label="$1" delta_file="$2" subcmd="$3"
    local magic hex_vs result=false
    magic=$(od -An -tx1 -N4 "$delta_file" 2>/dev/null | tr -d ' \n')
    hex_vs=$(od -An -tx1 -j5 -N8 "$delta_file" 2>/dev/null | tr -d ' \n')
    if [ "$magic" = "444c5404" ] && [ "$hex_vs" = "$magic_ver_size_hex" ]; then
        result=true
    fi
    check "DLT\\x04 header: $label ($subcmd)" $result
}

# Each language encodes its own delta, then runs inplace on its own output —
# independent per-language tests with no shared C-produced input.

# C
d_enc="$tmpdir/magic-c-enc.delta"; d_ip="$tmpdir/magic-c-ip.delta"
$DELTA encode onepass "$magic_ref" "$magic_ver" "$d_enc" > /dev/null 2>&1
check_v4_header "C" "$d_enc" "encode"
$DELTA inplace "$magic_ref" "$d_enc" "$d_ip" > /dev/null 2>&1
check_v4_header "C" "$d_ip" "inplace"

if [ -n "$RUST_DELTA" ]; then
    d_enc="$tmpdir/magic-rust-enc.delta"; d_ip="$tmpdir/magic-rust-ip.delta"
    $RUST_DELTA encode onepass "$magic_ref" "$magic_ver" "$d_enc" > /dev/null 2>&1
    check_v4_header "Rust" "$d_enc" "encode"
    $RUST_DELTA inplace "$magic_ref" "$d_enc" "$d_ip" > /dev/null 2>&1
    check_v4_header "Rust" "$d_ip" "inplace"
else
    echo "  (DLT\\x04 header Rust: not found)"
fi

if [ -n "$GO_DELTA" ]; then
    d_enc="$tmpdir/magic-go-enc.delta"; d_ip="$tmpdir/magic-go-ip.delta"
    $GO_DELTA encode onepass "$magic_ref" "$magic_ver" "$d_enc" > /dev/null 2>&1
    check_v4_header "Go" "$d_enc" "encode"
    $GO_DELTA inplace "$magic_ref" "$d_enc" "$d_ip" > /dev/null 2>&1
    check_v4_header "Go" "$d_ip" "inplace"
else
    echo "  (DLT\\x04 header Go: not found)"
fi

if [ -n "$CPP_DELTA" ]; then
    d_enc="$tmpdir/magic-cpp-enc.delta"; d_ip="$tmpdir/magic-cpp-ip.delta"
    $CPP_DELTA encode onepass "$magic_ref" "$magic_ver" "$d_enc" > /dev/null 2>&1
    check_v4_header "C++" "$d_enc" "encode"
    $CPP_DELTA inplace "$magic_ref" "$d_enc" "$d_ip" > /dev/null 2>&1
    check_v4_header "C++" "$d_ip" "inplace"
else
    echo "  (DLT\\x04 header C++: not found)"
fi

if [ -n "$JAVA_DELTA" ]; then
    d_enc="$tmpdir/magic-java-enc.delta"; d_ip="$tmpdir/magic-java-ip.delta"
    $JAVA_DELTA encode onepass "$magic_ref" "$magic_ver" "$d_enc" > /dev/null 2>&1
    check_v4_header "Java" "$d_enc" "encode"
    $JAVA_DELTA inplace "$magic_ref" "$d_enc" "$d_ip" > /dev/null 2>&1
    check_v4_header "Java" "$d_ip" "inplace"
else
    echo "  (DLT\\x04 header Java: not found)"
fi

if [ -n "$KT_DELTA" ]; then
    d_enc="$tmpdir/magic-kotlin-enc.delta"; d_ip="$tmpdir/magic-kotlin-ip.delta"
    $KT_DELTA encode onepass "$magic_ref" "$magic_ver" "$d_enc" > /dev/null 2>&1
    check_v4_header "Kotlin" "$d_enc" "encode"
    $KT_DELTA inplace "$magic_ref" "$d_enc" "$d_ip" > /dev/null 2>&1
    check_v4_header "Kotlin" "$d_ip" "inplace"
else
    echo "  (DLT\\x04 header Kotlin: not found)"
fi

if [ -n "$SCALA_DELTA" ]; then
    d_enc="$tmpdir/magic-scala-enc.delta"; d_ip="$tmpdir/magic-scala-ip.delta"
    $SCALA_DELTA encode onepass "$magic_ref" "$magic_ver" "$d_enc" > /dev/null 2>&1
    check_v4_header "Scala" "$d_enc" "encode"
    $SCALA_DELTA inplace "$magic_ref" "$d_enc" "$d_ip" > /dev/null 2>&1
    check_v4_header "Scala" "$d_ip" "inplace"
else
    echo "  (DLT\\x04 header Scala: not found)"
fi

if [ -n "$PY_DELTA" ]; then
    d_enc="$tmpdir/magic-python-enc.delta"; d_ip="$tmpdir/magic-python-ip.delta"
    $PY_DELTA encode onepass "$magic_ref" "$magic_ver" "$d_enc" > /dev/null 2>&1
    check_v4_header "Python" "$d_enc" "encode"
    $PY_DELTA inplace "$magic_ref" "$d_enc" "$d_ip" > /dev/null 2>&1
    check_v4_header "Python" "$d_ip" "inplace"
else
    echo "  (DLT\\x04 header Python: not found)"
fi

echo ""
echo "=== Cross-language inplace chains ==="
# encode→inplace→decode across three different language implementations.
# Each chain is independent: a different language at each stage.
# Format: A encodes standard delta, B converts to inplace, C decodes.

xip_ref="$tmpdir/xip-ref.bin"
xip_ver="$tmpdir/xip-ver.bin"
printf '%s' "cross-language inplace reference data" > "$xip_ref"
printf '%s' "cross-language inplace version data -- modified here" > "$xip_ver"

# Rust encode → Go inplace → C decode
if [ -n "$RUST_DELTA" ] && [ -n "$GO_DELTA" ]; then
    xip_d="$tmpdir/xip-rust-std.delta"; xip_ip="$tmpdir/xip-rust-go-ip.delta"
    xip_out="$tmpdir/xip-rust-go-c.out"
    $RUST_DELTA encode onepass "$xip_ref" "$xip_ver" "$xip_d" > /dev/null 2>&1
    $GO_DELTA inplace "$xip_ref" "$xip_d" "$xip_ip" > /dev/null 2>&1
    $DELTA decode "$xip_ref" "$xip_ip" "$xip_out" > /dev/null 2>&1
    check "Rust encode -> Go inplace -> C decode" diff -q "$xip_ver" "$xip_out"
else
    echo "  (Rust->Go->C inplace chain: not found)"
fi

# Go encode → C++ inplace → Rust decode
if [ -n "$GO_DELTA" ] && [ -n "$CPP_DELTA" ] && [ -n "$RUST_DELTA" ]; then
    xip_d="$tmpdir/xip-go-std.delta"; xip_ip="$tmpdir/xip-go-cpp-ip.delta"
    xip_out="$tmpdir/xip-go-cpp-rust.out"
    $GO_DELTA encode onepass "$xip_ref" "$xip_ver" "$xip_d" > /dev/null 2>&1
    $CPP_DELTA inplace "$xip_ref" "$xip_d" "$xip_ip" > /dev/null 2>&1
    $RUST_DELTA decode "$xip_ref" "$xip_ip" "$xip_out" > /dev/null 2>&1
    check "Go encode -> C++ inplace -> Rust decode" diff -q "$xip_ver" "$xip_out"
else
    echo "  (Go->C++->Rust inplace chain: not found)"
fi

# Java encode → Kotlin inplace → Scala decode
if [ -n "$JAVA_DELTA" ] && [ -n "$KT_DELTA" ] && [ -n "$SCALA_DELTA" ]; then
    xip_d="$tmpdir/xip-java-std.delta"; xip_ip="$tmpdir/xip-java-kt-ip.delta"
    xip_out="$tmpdir/xip-java-kt-scala.out"
    $JAVA_DELTA encode onepass "$xip_ref" "$xip_ver" "$xip_d" > /dev/null 2>&1
    $KT_DELTA inplace "$xip_ref" "$xip_d" "$xip_ip" > /dev/null 2>&1
    $SCALA_DELTA decode "$xip_ref" "$xip_ip" "$xip_out" > /dev/null 2>&1
    check "Java encode -> Kotlin inplace -> Scala decode" diff -q "$xip_ver" "$xip_out"
else
    echo "  (Java->Kotlin->Scala inplace chain: not found)"
fi

# C++ encode → Scala inplace → Kotlin decode
if [ -n "$CPP_DELTA" ] && [ -n "$SCALA_DELTA" ] && [ -n "$KT_DELTA" ]; then
    xip_d="$tmpdir/xip-cpp-std.delta"; xip_ip="$tmpdir/xip-cpp-scala-ip.delta"
    xip_out="$tmpdir/xip-cpp-scala-kt.out"
    $CPP_DELTA encode onepass "$xip_ref" "$xip_ver" "$xip_d" > /dev/null 2>&1
    $SCALA_DELTA inplace "$xip_ref" "$xip_d" "$xip_ip" > /dev/null 2>&1
    $KT_DELTA decode "$xip_ref" "$xip_ip" "$xip_out" > /dev/null 2>&1
    check "C++ encode -> Scala inplace -> Kotlin decode" diff -q "$xip_ver" "$xip_out"
else
    echo "  (C++->Scala->Kotlin inplace chain: not found)"
fi

# C encode → Python inplace → Rust decode
if [ -n "$PY_DELTA" ] && [ -n "$RUST_DELTA" ]; then
    xip_d="$tmpdir/xip-c-std.delta"; xip_ip="$tmpdir/xip-c-py-ip.delta"
    xip_out="$tmpdir/xip-c-py-rust.out"
    $DELTA encode onepass "$xip_ref" "$xip_ver" "$xip_d" > /dev/null 2>&1
    $PY_DELTA inplace "$xip_ref" "$xip_d" "$xip_ip" > /dev/null 2>&1
    $RUST_DELTA decode "$xip_ref" "$xip_ip" "$xip_out" > /dev/null 2>&1
    check "C encode -> Python inplace -> Rust decode" diff -q "$xip_ver" "$xip_out"
else
    echo "  (C->Python->Rust inplace chain: not found)"
fi

# Python encode → Go inplace → Java decode
if [ -n "$PY_DELTA" ] && [ -n "$GO_DELTA" ] && [ -n "$JAVA_DELTA" ]; then
    xip_d="$tmpdir/xip-py-std.delta"; xip_ip="$tmpdir/xip-py-go-ip.delta"
    xip_out="$tmpdir/xip-py-go-java.out"
    $PY_DELTA encode onepass "$xip_ref" "$xip_ver" "$xip_d" > /dev/null 2>&1
    $GO_DELTA inplace "$xip_ref" "$xip_d" "$xip_ip" > /dev/null 2>&1
    $JAVA_DELTA decode "$xip_ref" "$xip_ip" "$xip_out" > /dev/null 2>&1
    check "Python encode -> Go inplace -> Java decode" diff -q "$xip_ver" "$xip_out"
else
    echo "  (Python->Go->Java inplace chain: not found)"
fi

echo ""
echo "=== Cross-language decode ==="

if [ -n "$RUST_DELTA" ]; then
    for algo in greedy onepass correcting; do
        c_d="$tmpdir/c-xdec-${algo}.delta"
        r_out="$tmpdir/rust-from-c-${algo}.out"
        $DELTA encode $algo "$ref" "$ver" "$c_d"
        $RUST_DELTA decode "$ref" "$c_d" "$r_out"
        check "C encode -> Rust decode ($algo)" diff -q "$ver" "$r_out"
    done
    for algo in greedy onepass correcting; do
        r_d="$tmpdir/rust-xdec-${algo}.delta"
        c_out="$tmpdir/c-from-rust-${algo}.out"
        $RUST_DELTA encode $algo "$ref" "$ver" "$r_d"
        $DELTA decode "$ref" "$r_d" "$c_out"
        check "Rust encode -> C decode ($algo)" diff -q "$ver" "$c_out"
    done
else
    echo "  (Rust cross-decode: not found)"
fi

if [ -n "$JAVA_DELTA" ]; then
    for algo in greedy onepass correcting; do
        c_d="$tmpdir/c-jdec-${algo}.delta"
        j_out="$tmpdir/java-from-c-${algo}.out"
        $DELTA encode $algo "$ref" "$ver" "$c_d"
        $JAVA_DELTA decode "$ref" "$c_d" "$j_out"
        check "C encode -> Java decode ($algo)" diff -q "$ver" "$j_out"
    done
    for algo in greedy onepass correcting; do
        j_d="$tmpdir/java-cdec-${algo}.delta"
        c_out="$tmpdir/c-from-java-${algo}.out"
        $JAVA_DELTA encode $algo "$ref" "$ver" "$j_d"
        $DELTA decode "$ref" "$j_d" "$c_out"
        check "Java encode -> C decode ($algo)" diff -q "$ver" "$c_out"
    done
else
    echo "  (Java cross-decode: not found)"
fi

if [ -n "$KT_DELTA" ]; then
    for algo in greedy onepass correcting; do
        c_d="$tmpdir/c-ktdec-${algo}.delta"
        kt_out="$tmpdir/kotlin-from-c-${algo}.out"
        $DELTA encode $algo "$ref" "$ver" "$c_d"
        $KT_DELTA decode "$ref" "$c_d" "$kt_out"
        check "C encode -> Kotlin decode ($algo)" diff -q "$ver" "$kt_out"
    done
    for algo in greedy onepass correcting; do
        kt_d="$tmpdir/kotlin-cdec-${algo}.delta"
        c_out="$tmpdir/c-from-kotlin-${algo}.out"
        $KT_DELTA encode $algo "$ref" "$ver" "$kt_d"
        $DELTA decode "$ref" "$kt_d" "$c_out"
        check "Kotlin encode -> C decode ($algo)" diff -q "$ver" "$c_out"
    done
else
    echo "  (Kotlin cross-decode: not found)"
fi

if [ -n "$SCALA_DELTA" ]; then
    for algo in greedy onepass correcting; do
        c_d="$tmpdir/c-scdec-${algo}.delta"
        sc_out="$tmpdir/scala-from-c-${algo}.out"
        $DELTA encode $algo "$ref" "$ver" "$c_d"
        $SCALA_DELTA decode "$ref" "$c_d" "$sc_out"
        check "C encode -> Scala decode ($algo)" diff -q "$ver" "$sc_out"
    done
    for algo in greedy onepass correcting; do
        sc_d="$tmpdir/scala-cdec-${algo}.delta"
        c_out="$tmpdir/c-from-scala-${algo}.out"
        $SCALA_DELTA encode $algo "$ref" "$ver" "$sc_d"
        $DELTA decode "$ref" "$sc_d" "$c_out"
        check "Scala encode -> C decode ($algo)" diff -q "$ver" "$c_out"
    done
else
    echo "  (Scala cross-decode: not found)"
fi

if [ -n "$GO_DELTA" ]; then
    for algo in greedy onepass correcting; do
        c_d="$tmpdir/c-godec-${algo}.delta"
        go_out="$tmpdir/go-from-c-${algo}.out"
        $DELTA encode $algo "$ref" "$ver" "$c_d"
        $GO_DELTA decode "$ref" "$c_d" "$go_out"
        check "C encode -> Go decode ($algo)" diff -q "$ver" "$go_out"
    done
    for algo in greedy onepass correcting; do
        go_d="$tmpdir/go-cdec-${algo}.delta"
        c_out="$tmpdir/c-from-go-${algo}.out"
        $GO_DELTA encode $algo "$ref" "$ver" "$go_d"
        $DELTA decode "$ref" "$go_d" "$c_out"
        check "Go encode -> C decode ($algo)" diff -q "$ver" "$c_out"
    done
else
    echo "  (Go cross-decode: not found)"
fi

# ── Real-data cross-language validation ──────────────────────────────────────
#
# Generate ~64 KB of pseudo-Shakespeare text (deterministic seed) and a
# permuted version — shuffled paragraphs + word substitutions.  Then encode
# with one language and decode with every other, so bugs that only surface on
# realistic data (hash collisions, long copy chains, off-by-one in large
# windows) are caught by more than a 3-line test corpus.
echo ""
echo "=== Real-data cross-language validation (pseudo-Shakespeare) ==="

sh_ref="$tmpdir/sh-ref.txt"
sh_ver="$tmpdir/sh-ver.txt"
have_sh=false

if command -v python3 >/dev/null 2>&1; then
    python3 - "$sh_ref" "$sh_ver" <<'PYEOF'
import random, sys

random.seed(0xDEADBEEF)

WORDS = [
    "the", "and", "of", "to", "in", "that", "is", "was", "he", "she",
    "his", "her", "it", "not", "have", "you", "with", "do", "from",
    "hath", "thou", "thee", "thy", "mine", "lord", "king", "queen",
    "shall", "art", "be", "love", "death", "fate", "glory", "honor",
    "night", "day", "sword", "heart", "eyes", "world", "time", "hand",
    "man", "men", "great", "good", "well", "know", "come", "go",
    "speak", "hear", "see", "make", "give", "think", "tell", "hold",
]

def para():
    return '\n'.join(
        ' '.join(random.choices(WORDS, k=random.randint(6, 14))).capitalize() + '.'
        for _ in range(random.randint(4, 8))
    ) + '\n'

paras = [para() for _ in range(200)]

# permuted version: swap 1/3 of paragraphs, substitute words in 1/10
ver_paras = list(paras)
idxs = list(range(len(ver_paras))); random.shuffle(idxs)
n = len(ver_paras) // 3
for i in range(n):
    a, b = idxs[i], idxs[n + i]
    ver_paras[a], ver_paras[b] = ver_paras[b], ver_paras[a]
SUBS = {"lord": "duke", "king": "prince", "love": "hate", "glory": "shame"}
for i in random.sample(range(len(ver_paras)), len(ver_paras) // 10):
    p = ver_paras[i]
    for old, new in SUBS.items():
        p = p.replace(old, new)
    ver_paras[i] = p

with open(sys.argv[1], 'w') as f: f.write('\n'.join(paras))
with open(sys.argv[2], 'w') as f: f.write('\n'.join(ver_paras))
PYEOF
    have_sh=true
fi

if $have_sh; then
    # Outer-if per language so skips are properly counted.
    # C reference: each other language decodes a C-encoded delta.
    if [ -n "$RUST_DELTA" ]; then
        for algo in greedy onepass correcting; do
            sh_d="$tmpdir/sh-c-${algo}.delta"
            $DELTA encode $algo "$sh_ref" "$sh_ver" "$sh_d"
            out="$tmpdir/sh-c-rust-${algo}.out"
            $RUST_DELTA decode "$sh_ref" "$sh_d" "$out"
            check "Shakespeare C encode -> Rust decode ($algo)" diff -q "$sh_ver" "$out"
        done
    else
        echo "  (Shakespeare C->Rust: not found)"
    fi
    if [ -n "$GO_DELTA" ]; then
        for algo in greedy onepass correcting; do
            sh_d="$tmpdir/sh-c-${algo}.delta"
            $DELTA encode $algo "$sh_ref" "$sh_ver" "$sh_d"
            out="$tmpdir/sh-c-go-${algo}.out"
            $GO_DELTA decode "$sh_ref" "$sh_d" "$out"
            check "Shakespeare C encode -> Go decode ($algo)" diff -q "$sh_ver" "$out"
        done
    else
        echo "  (Shakespeare C->Go: not found)"
    fi
    if [ -n "$CPP_DELTA" ]; then
        for algo in greedy onepass correcting; do
            sh_d="$tmpdir/sh-c-${algo}.delta"
            $DELTA encode $algo "$sh_ref" "$sh_ver" "$sh_d"
            out="$tmpdir/sh-c-cpp-${algo}.out"
            $CPP_DELTA decode "$sh_ref" "$sh_d" "$out"
            check "Shakespeare C encode -> C++ decode ($algo)" diff -q "$sh_ver" "$out"
        done
    else
        echo "  (Shakespeare C->C++: not found)"
    fi
    if [ -n "$JAVA_DELTA" ]; then
        for algo in greedy onepass correcting; do
            sh_d="$tmpdir/sh-c-${algo}.delta"
            $DELTA encode $algo "$sh_ref" "$sh_ver" "$sh_d"
            out="$tmpdir/sh-c-java-${algo}.out"
            $JAVA_DELTA decode "$sh_ref" "$sh_d" "$out"
            check "Shakespeare C encode -> Java decode ($algo)" diff -q "$sh_ver" "$out"
        done
    else
        echo "  (Shakespeare C->Java: not found)"
    fi
    if [ -n "$KT_DELTA" ]; then
        for algo in greedy onepass correcting; do
            sh_d="$tmpdir/sh-c-${algo}.delta"
            $DELTA encode $algo "$sh_ref" "$sh_ver" "$sh_d"
            out="$tmpdir/sh-c-kt-${algo}.out"
            $KT_DELTA decode "$sh_ref" "$sh_d" "$out"
            check "Shakespeare C encode -> Kotlin decode ($algo)" diff -q "$sh_ver" "$out"
        done
    else
        echo "  (Shakespeare C->Kotlin: not found)"
    fi
    if [ -n "$SCALA_DELTA" ]; then
        for algo in greedy onepass correcting; do
            sh_d="$tmpdir/sh-c-${algo}.delta"
            $DELTA encode $algo "$sh_ref" "$sh_ver" "$sh_d"
            out="$tmpdir/sh-c-sc-${algo}.out"
            $SCALA_DELTA decode "$sh_ref" "$sh_d" "$out"
            check "Shakespeare C encode -> Scala decode ($algo)" diff -q "$sh_ver" "$out"
        done
    else
        echo "  (Shakespeare C->Scala: not found)"
    fi

    # Each non-C language encodes; C decodes.
    if [ -n "$RUST_DELTA" ]; then
        for algo in greedy onepass correcting; do
            sh_d="$tmpdir/sh-rust-${algo}.delta"
            out="$tmpdir/sh-rust-c-${algo}.out"
            $RUST_DELTA encode $algo "$sh_ref" "$sh_ver" "$sh_d"
            $DELTA decode "$sh_ref" "$sh_d" "$out"
            check "Shakespeare Rust encode -> C decode ($algo)" diff -q "$sh_ver" "$out"
        done
    else
        echo "  (Shakespeare Rust->C: not found)"
    fi
    if [ -n "$GO_DELTA" ]; then
        for algo in greedy onepass correcting; do
            sh_d="$tmpdir/sh-go-${algo}.delta"
            out="$tmpdir/sh-go-c-${algo}.out"
            $GO_DELTA encode $algo "$sh_ref" "$sh_ver" "$sh_d"
            $DELTA decode "$sh_ref" "$sh_d" "$out"
            check "Shakespeare Go encode -> C decode ($algo)" diff -q "$sh_ver" "$out"
        done
    else
        echo "  (Shakespeare Go->C: not found)"
    fi
    if [ -n "$CPP_DELTA" ]; then
        for algo in greedy onepass correcting; do
            sh_d="$tmpdir/sh-cpp-${algo}.delta"
            out="$tmpdir/sh-cpp-c-${algo}.out"
            $CPP_DELTA encode $algo "$sh_ref" "$sh_ver" "$sh_d"
            $DELTA decode "$sh_ref" "$sh_d" "$out"
            check "Shakespeare C++ encode -> C decode ($algo)" diff -q "$sh_ver" "$out"
        done
    else
        echo "  (Shakespeare C++->C: not found)"
    fi
    if [ -n "$JAVA_DELTA" ]; then
        for algo in greedy onepass correcting; do
            sh_d="$tmpdir/sh-java-${algo}.delta"
            out="$tmpdir/sh-java-c-${algo}.out"
            $JAVA_DELTA encode $algo "$sh_ref" "$sh_ver" "$sh_d"
            $DELTA decode "$sh_ref" "$sh_d" "$out"
            check "Shakespeare Java encode -> C decode ($algo)" diff -q "$sh_ver" "$out"
        done
    else
        echo "  (Shakespeare Java->C: not found)"
    fi
    if [ -n "$KT_DELTA" ]; then
        for algo in greedy onepass correcting; do
            sh_d="$tmpdir/sh-kt-${algo}.delta"
            out="$tmpdir/sh-kt-c-${algo}.out"
            $KT_DELTA encode $algo "$sh_ref" "$sh_ver" "$sh_d"
            $DELTA decode "$sh_ref" "$sh_d" "$out"
            check "Shakespeare Kotlin encode -> C decode ($algo)" diff -q "$sh_ver" "$out"
        done
    else
        echo "  (Shakespeare Kotlin->C: not found)"
    fi
    if [ -n "$SCALA_DELTA" ]; then
        for algo in greedy onepass correcting; do
            sh_d="$tmpdir/sh-sc-${algo}.delta"
            out="$tmpdir/sh-sc-c-${algo}.out"
            $SCALA_DELTA encode $algo "$sh_ref" "$sh_ver" "$sh_d"
            $DELTA decode "$sh_ref" "$sh_d" "$out"
            check "Shakespeare Scala encode -> C decode ($algo)" diff -q "$sh_ver" "$out"
        done
    else
        echo "  (Shakespeare Scala->C: not found)"
    fi

    # Non-C cross-language pairs (ring topology, catches shared encoder/decoder bugs).
    if [ -n "$GO_DELTA" ] && [ -n "$RUST_DELTA" ]; then
        for algo in greedy onepass correcting; do
            sh_d="$tmpdir/sh-go-rust-${algo}.delta"
            out="$tmpdir/sh-go-rust-${algo}.out"
            $GO_DELTA encode $algo "$sh_ref" "$sh_ver" "$sh_d"
            $RUST_DELTA decode "$sh_ref" "$sh_d" "$out"
            check "Shakespeare Go encode -> Rust decode ($algo)" diff -q "$sh_ver" "$out"
        done
    fi
    if [ -n "$RUST_DELTA" ] && [ -n "$KT_DELTA" ]; then
        for algo in greedy onepass correcting; do
            sh_d="$tmpdir/sh-rust-kt-${algo}.delta"
            out="$tmpdir/sh-rust-kt-${algo}.out"
            $RUST_DELTA encode $algo "$sh_ref" "$sh_ver" "$sh_d"
            $KT_DELTA decode "$sh_ref" "$sh_d" "$out"
            check "Shakespeare Rust encode -> Kotlin decode ($algo)" diff -q "$sh_ver" "$out"
        done
    fi
    if [ -n "$JAVA_DELTA" ] && [ -n "$GO_DELTA" ]; then
        for algo in greedy onepass correcting; do
            sh_d="$tmpdir/sh-java-go-${algo}.delta"
            out="$tmpdir/sh-java-go-${algo}.out"
            $JAVA_DELTA encode $algo "$sh_ref" "$sh_ver" "$sh_d"
            $GO_DELTA decode "$sh_ref" "$sh_d" "$out"
            check "Shakespeare Java encode -> Go decode ($algo)" diff -q "$sh_ver" "$out"
        done
    fi
    if [ -n "$KT_DELTA" ] && [ -n "$SCALA_DELTA" ]; then
        for algo in greedy onepass correcting; do
            sh_d="$tmpdir/sh-kt-sc-${algo}.delta"
            out="$tmpdir/sh-kt-sc-${algo}.out"
            $KT_DELTA encode $algo "$sh_ref" "$sh_ver" "$sh_d"
            $SCALA_DELTA decode "$sh_ref" "$sh_d" "$out"
            check "Shakespeare Kotlin encode -> Scala decode ($algo)" diff -q "$sh_ver" "$out"
        done
    fi
    if [ -n "$SCALA_DELTA" ] && [ -n "$CPP_DELTA" ]; then
        for algo in greedy onepass correcting; do
            sh_d="$tmpdir/sh-sc-cpp-${algo}.delta"
            out="$tmpdir/sh-sc-cpp-${algo}.out"
            $SCALA_DELTA encode $algo "$sh_ref" "$sh_ver" "$sh_d"
            $CPP_DELTA decode "$sh_ref" "$sh_d" "$out"
            check "Shakespeare Scala encode -> C++ decode ($algo)" diff -q "$sh_ver" "$out"
        done
    fi
    if [ -n "$CPP_DELTA" ] && [ -n "$JAVA_DELTA" ]; then
        for algo in greedy onepass correcting; do
            sh_d="$tmpdir/sh-cpp-java-${algo}.delta"
            out="$tmpdir/sh-cpp-java-${algo}.out"
            $CPP_DELTA encode $algo "$sh_ref" "$sh_ver" "$sh_d"
            $JAVA_DELTA decode "$sh_ref" "$sh_d" "$out"
            check "Shakespeare C++ encode -> Java decode ($algo)" diff -q "$sh_ver" "$out"
        done
    fi
else
    # Count skipped tests: 12 star pairs + 6 ring pairs = 18 language pairs × 3 algos
    echo "  (Shakespeare tests: not found)"
fi

# ── DLT\x03 legacy decode regression ─────────────────────────────────────────
# All encoders now emit DLT\x04 unconditionally, but decoders must still handle
# DLT\x03 artifacts from before the migration.  These deltas are crafted by hand
# (Python inline) so the test is independent of any encoder binary.
#
#   v3_copy_delta   — COPY 6 bytes from ref + ADD "world"  → "hello world" (flags=0x00)
#   v3_add_delta    — ADD "hello world" from empty ref     → "hello world" (flags=0x00)
#   v3_inplace_delta— ADD "abc" + COPY src=0→dst=3 len=6 inplace (flags=0x01, src≠dst)
echo ""
echo "=== DLT\\x03 legacy decode regression ==="

v3_copy_ref="$tmpdir/v3-copy-ref.bin"
v3_copy_delta="$tmpdir/v3-copy.delta"
v3_add_delta="$tmpdir/v3-add.delta"
v3_inplace_ref="$tmpdir/v3-inplace-ref.bin"
v3_inplace_delta="$tmpdir/v3-inplace.delta"
have_v3=false

if command -v python3 >/dev/null 2>&1; then
    python3 - "$tmpdir" <<'PYEOF'
import struct, sys, os

def crc64_xz(data):
    poly = 0xC96C5795D7870F42
    table = []
    for i in range(256):
        c = i
        for _ in range(8):
            if c & 1: c = (c >> 1) ^ poly
            else:     c >>= 1
        table.append(c)
    crc = 0xFFFFFFFFFFFFFFFF
    for b in data:
        crc = table[(crc ^ b) & 0xFF] ^ (crc >> 8)
    return (crc ^ 0xFFFFFFFFFFFFFFFF) & 0xFFFFFFFFFFFFFFFF

def crc8(data):
    return crc64_xz(data).to_bytes(8, 'big')

def v3hdr(ref, ver, inplace=False):
    # DLT\x03 header: magic(4) + flags(1) + version_size u32(4) + src_crc(8) + dst_crc(8)
    flags = b'\x01' if inplace else b'\x00'
    return b'DLT\x03' + flags + struct.pack('>I', len(ver)) + crc8(ref) + crc8(ver)

d = sys.argv[1]

# v3_copy: standard — COPY 6 bytes from ref, ADD "world"
ref, ver = b'hello ', b'hello world'
with open(os.path.join(d, 'v3-copy-ref.bin'), 'wb') as f:
    f.write(ref)
with open(os.path.join(d, 'v3-copy.delta'), 'wb') as f:
    f.write(v3hdr(ref, ver))
    f.write(b'\x01' + struct.pack('>III', 0, 0, len(ref)))        # COPY src=0 dst=0 len=6
    f.write(b'\x02' + struct.pack('>II', len(ref), 5) + b'world') # ADD  dst=6 len=5
    f.write(b'\x00')  # END

# v3_add: standard — ADD "hello world" from empty ref
ref, ver = b'', b'hello world'
with open(os.path.join(d, 'v3-add.delta'), 'wb') as f:
    f.write(v3hdr(ref, ver))
    f.write(b'\x02' + struct.pack('>II', 0, len(ver)) + ver)      # ADD dst=0 len=11
    f.write(b'\x00')  # END

# v3_inplace: flags=0x01, non-trivial COPY with src≠dst to exercise inplace apply path.
# ref="abcabc" (6 bytes); ver="abcabcabc" (9 bytes).
# COPY src=0→dst=3 len=6: copies ref[0:6]="abcabc" to out[3:9].
# ADD  dst=0 len=3 data="abc": fills out[0:3].
# Result: "abc" + "abcabc" = "abcabcabc" ✓
# src≠dst (0≠3) distinguishes inplace apply from standard apply.
ref, ver = b'abcabc', b'abcabcabc'
with open(os.path.join(d, 'v3-inplace-ref.bin'), 'wb') as f:
    f.write(ref)
with open(os.path.join(d, 'v3-inplace.delta'), 'wb') as f:
    f.write(v3hdr(ref, ver, inplace=True))
    f.write(b'\x02' + struct.pack('>II', 0, 3) + b'abc')          # ADD  dst=0 len=3
    f.write(b'\x01' + struct.pack('>III', 0, 3, len(ref)))         # COPY src=0 dst=3 len=6
    f.write(b'\x00')  # END
PYEOF
    have_v3=true
fi

# decode_v3_check <desc> <expected_content> <outfile> <cmd...>
decode_v3_check() {
    desc="$1"; expected="$2"; outfile="$3"; shift 3
    if "$@" 2>/dev/null && [ "$(cat "$outfile" 2>/dev/null)" = "$expected" ]; then
        check "$desc" true
    else
        check "$desc" false
    fi
}

# run_v3_tests <label> <decode_binary...>
run_v3_tests() {
    label="$1"; shift
    decode_v3_check "DLT\\x03 COPY decode ($label)" "hello world" \
        "$tmpdir/v3-${label}-copy.out" \
        "$@" "$v3_copy_ref" "$v3_copy_delta" "$tmpdir/v3-${label}-copy.out"
    decode_v3_check "DLT\\x03 ADD decode ($label)" "hello world" \
        "$tmpdir/v3-${label}-add.out" \
        "$@" /dev/null "$v3_add_delta" "$tmpdir/v3-${label}-add.out"
    decode_v3_check "DLT\\x03 inplace decode ($label)" "abcabcabc" \
        "$tmpdir/v3-${label}-inplace.out" \
        "$@" "$v3_inplace_ref" "$v3_inplace_delta" "$tmpdir/v3-${label}-inplace.out"
}

if $have_v3; then
    run_v3_tests C       $DELTA decode

    if [ -n "$RUST_DELTA" ]; then
        run_v3_tests Rust    $RUST_DELTA decode
    else
        echo "  (DLT\\x03 Rust: not found)"
    fi

    if [ -n "$GO_DELTA" ]; then
        run_v3_tests Go      $GO_DELTA decode
    else
        echo "  (DLT\\x03 Go: not found)"
    fi

    if [ -n "$CPP_DELTA" ]; then
        run_v3_tests "C++"   $CPP_DELTA decode
    else
        echo "  (DLT\\x03 C++: not found)"
    fi

    if [ -n "$JAVA_DELTA" ]; then
        run_v3_tests Java    $JAVA_DELTA decode
    else
        echo "  (DLT\\x03 Java: not found)"
    fi

    if [ -n "$KT_DELTA" ]; then
        run_v3_tests Kotlin  $KT_DELTA decode
    else
        echo "  (DLT\\x03 Kotlin: not found)"
    fi

    if [ -n "$SCALA_DELTA" ]; then
        run_v3_tests Scala   $SCALA_DELTA decode
    else
        echo "  (DLT\\x03 Scala: not found)"
    fi

    if [ -n "$PY_DELTA" ]; then
        run_v3_tests Python  $PY_DELTA decode
    else
        echo "  (DLT\\x03 Python: not found)"
    fi
else
    echo "  (DLT\\x03 legacy tests: not found)"
fi

# ── DLT\x04 cross-language decode ────────────────────────────────────────────
echo ""
echo "=== DLT\\x04 cross-language decode ==="

# Python generates six DLT\x04 test deltas with correct CRC-64/XZ checksums
# so --ignore-hash is not needed and the full decode path is exercised.
#
#   v4_add_delta     — ADD "hello world" (cmd 2, u32 fields; ref=empty)
#   v4_move_delta    — ADD "hello" + MOVE src=0 dst=5 len=5 (cmd 5, u32)
#   v4_bigadd_delta  — BIGADD "hello world" (cmd 4, u64 fields; ref=empty)
#   v4_bigmove_delta — ADD "hello" + BIGMOVE src=0 dst=5 len=5 (cmd 6, u64)
#   v4_bigcopy_delta — BIGCOPY 6 bytes from ref + ADD " world" (cmd 3, u64)
#   v4_bad_delta     — DLT\x05 magic (all decoders must reject)

v4_add_delta="$tmpdir/v4-add.delta"
v4_move_delta="$tmpdir/v4-move.delta"
v4_bigadd_delta="$tmpdir/v4-bigadd.delta"
v4_bigmove_delta="$tmpdir/v4-bigmove.delta"
v4_bigcopy_delta="$tmpdir/v4-bigcopy.delta"
v4_bigcopy_ref="$tmpdir/v4-bigcopy-ref.bin"
v4_bad_delta="$tmpdir/v4-bad.delta"
have_v4=false

if command -v python3 >/dev/null 2>&1; then
    python3 - "$tmpdir" <<'PYEOF'
import struct, sys, os

def crc64_xz(data):
    poly = 0xC96C5795D7870F42
    table = []
    for i in range(256):
        c = i
        for _ in range(8):
            if c & 1: c = (c >> 1) ^ poly
            else:     c >>= 1
        table.append(c)
    crc = 0xFFFFFFFFFFFFFFFF
    for b in data:
        crc = table[(crc ^ b) & 0xFF] ^ (crc >> 8)
    return (crc ^ 0xFFFFFFFFFFFFFFFF) & 0xFFFFFFFFFFFFFFFF

def crc8(data):
    return crc64_xz(data).to_bytes(8, 'big')

def v4hdr(ref, ver):
    return b'DLT\x04' + b'\x00' + struct.pack('>Q', len(ver)) + crc8(ref) + crc8(ver)

d = sys.argv[1]

# v4_add: ADD "hello world" from empty ref
ref, ver = b'', b'hello world'
with open(os.path.join(d, 'v4-add.delta'), 'wb') as f:
    f.write(v4hdr(ref, ver))
    f.write(b'\x02' + struct.pack('>II', 0, len(ver)) + ver)
    f.write(b'\x00')

# v4_move: ADD "hello" then MOVE src=0 dst=5 len=5 → "hellohello"
ref, ver = b'', b'hellohello'
with open(os.path.join(d, 'v4-move.delta'), 'wb') as f:
    f.write(v4hdr(ref, ver))
    f.write(b'\x02' + struct.pack('>II', 0, 5) + b'hello')
    f.write(b'\x05' + struct.pack('>III', 0, 5, 5))
    f.write(b'\x00')

# v4_bigadd: BIGADD (cmd 4, u64 dst+len) "hello world"
ref, ver = b'', b'hello world'
with open(os.path.join(d, 'v4-bigadd.delta'), 'wb') as f:
    f.write(v4hdr(ref, ver))
    f.write(b'\x04' + struct.pack('>QQ', 0, len(ver)) + ver)
    f.write(b'\x00')

# v4_bigmove: ADD "hello" then BIGMOVE (cmd 6, u64 src+dst+len) src=0 dst=5 len=5
ref, ver = b'', b'hellohello'
with open(os.path.join(d, 'v4-bigmove.delta'), 'wb') as f:
    f.write(v4hdr(ref, ver))
    f.write(b'\x02' + struct.pack('>II', 0, 5) + b'hello')
    f.write(b'\x06' + struct.pack('>QQQ', 0, 5, 5))
    f.write(b'\x00')

# v4_bigcopy_ref + v4_bigcopy: BIGCOPY (cmd 3, u64) 6 bytes from ref, ADD " world"
ref, ver = b'hello ', b'hello world'
with open(os.path.join(d, 'v4-bigcopy-ref.bin'), 'wb') as f:
    f.write(ref)
with open(os.path.join(d, 'v4-bigcopy.delta'), 'wb') as f:
    f.write(v4hdr(ref, ver))
    f.write(b'\x03' + struct.pack('>QQQ', 0, 0, len(ref)))
    f.write(b'\x02' + struct.pack('>II', len(ref), 5) + b'world')
    f.write(b'\x00')

# v4_bad: unknown magic DLT\x05
with open(os.path.join(d, 'v4-bad.delta'), 'wb') as f:
    f.write(b'DLT\x05' + b'\x00' * 30)
PYEOF
    have_v4=true
fi

# decode_v4_check <desc> <expected_content> <outfile> <cmd...>
# Runs cmd and verifies outfile content equals expected.
# The if-guard prevents set -e from aborting on decoder failure.
decode_v4_check() {
    desc="$1"; expected="$2"; outfile="$3"; shift 3
    if "$@" 2>/dev/null && [ "$(cat "$outfile" 2>/dev/null)" = "$expected" ]; then
        check "$desc" true
    else
        check "$desc" false
    fi
}

# run_v4_tests <label> <decode_binary...>
# Tests ADD, MOVE, BIGADD, BIGMOVE, BIGCOPY, bad-magic for one decoder.
run_v4_tests() {
    label="$1"; shift
    decode_cmd="$*"
    decode_v4_check "DLT\\x04 ADD decode ($label)" "hello world" \
        "$tmpdir/v4-${label}-add.out" \
        $decode_cmd /dev/null "$v4_add_delta" "$tmpdir/v4-${label}-add.out"
    decode_v4_check "DLT\\x04 MOVE decode ($label)" "hellohello" \
        "$tmpdir/v4-${label}-move.out" \
        $decode_cmd /dev/null "$v4_move_delta" "$tmpdir/v4-${label}-move.out"
    decode_v4_check "DLT\\x04 BIGADD decode ($label)" "hello world" \
        "$tmpdir/v4-${label}-bigadd.out" \
        $decode_cmd /dev/null "$v4_bigadd_delta" "$tmpdir/v4-${label}-bigadd.out"
    decode_v4_check "DLT\\x04 BIGMOVE decode ($label)" "hellohello" \
        "$tmpdir/v4-${label}-bigmove.out" \
        $decode_cmd /dev/null "$v4_bigmove_delta" "$tmpdir/v4-${label}-bigmove.out"
    decode_v4_check "DLT\\x04 BIGCOPY decode ($label)" "hello world" \
        "$tmpdir/v4-${label}-bigcopy.out" \
        $decode_cmd "$v4_bigcopy_ref" "$v4_bigcopy_delta" "$tmpdir/v4-${label}-bigcopy.out"
    check_fails "DLT\\x05 magic rejected ($label)" \
        $decode_cmd /dev/null "$v4_bad_delta" /dev/null
}

if $have_v4; then
    run_v4_tests C       $DELTA decode

    if [ -n "$RUST_DELTA" ]; then
        run_v4_tests Rust    $RUST_DELTA decode
    else
        echo "  (DLT\\x04 Rust: not found)"
    fi

    if [ -n "$GO_DELTA" ]; then
        run_v4_tests Go      $GO_DELTA decode
    else
        echo "  (DLT\\x04 Go: not found)"
    fi

    if [ -n "$CPP_DELTA" ]; then
        run_v4_tests "C++"   $CPP_DELTA decode
    else
        echo "  (DLT\\x04 C++: not found)"
    fi

    if [ -n "$JAVA_DELTA" ]; then
        run_v4_tests Java    $JAVA_DELTA decode
    else
        echo "  (DLT\\x04 Java: not found)"
    fi

    if [ -n "$KT_DELTA" ]; then
        run_v4_tests Kotlin  $KT_DELTA decode
    else
        echo "  (DLT\\x04 Kotlin: not found)"
    fi

    if [ -n "$SCALA_DELTA" ]; then
        run_v4_tests Scala   $SCALA_DELTA decode
    else
        echo "  (DLT\\x04 Scala: not found)"
    fi
else
    echo "  (DLT\\x04 tests: not found)"
fi

echo ""
echo "=== CRC mismatch rejection ==="
# Two-sided CRC validation test:
#   Negative: corrupt delta rejected WITHOUT --ignore-hash (exit nonzero)
#   Positive: same corrupt delta accepted WITH --ignore-hash, output matches ver
# Without both legs you can't distinguish "CRC validation fired" from
# "decoder failed for some other reason (e.g. /dev/null output)".
#
# bad-src delta: correct commands, src_crc byte 13 flipped → pre-decode rejection
# bad-dst delta: correct commands, dst_crc byte 21 flipped → post-decode rejection

crc_ref="$tmpdir/crc-ref.bin"
crc_ver="$tmpdir/crc-ver.bin"
crc_bad_src="$tmpdir/crc-bad-src.delta"
crc_bad_dst="$tmpdir/crc-bad-dst.delta"
have_crc_neg=false

if command -v python3 >/dev/null 2>&1; then
    python3 - "$tmpdir" <<'PYEOF'
import struct, sys, os

def crc64_xz(data):
    poly = 0xC96C5795D7870F42
    table = []
    for i in range(256):
        c = i
        for _ in range(8):
            if c & 1: c = (c >> 1) ^ poly
            else:     c >>= 1
        table.append(c)
    crc = 0xFFFFFFFFFFFFFFFF
    for b in data:
        crc = table[(crc ^ b) & 0xFF] ^ (crc >> 8)
    return (crc ^ 0xFFFFFFFFFFFFFFFF) & 0xFFFFFFFFFFFFFFFF

def crc8(data): return crc64_xz(data).to_bytes(8, 'big')

d = sys.argv[1]
ref = b'crc validation test reference data'
ver = b'crc validation test version data'
with open(os.path.join(d, 'crc-ref.bin'), 'wb') as f:
    f.write(ref)
with open(os.path.join(d, 'crc-ver.bin'), 'wb') as f:
    f.write(ver)

# Valid DLT\x04: ADD all of ver (no COPY so decoder still must check src_crc)
hdr  = b'DLT\x04' + b'\x00' + struct.pack('>Q', len(ver)) + crc8(ref) + crc8(ver)
body = b'\x02' + struct.pack('>II', 0, len(ver)) + ver + b'\x00'
good = hdr + body

# bad-src: corrupt byte 13 (first byte of src_crc, offset = 4+1+8 = 13)
bad_src = bytearray(good); bad_src[13] ^= 0xFF
with open(os.path.join(d, 'crc-bad-src.delta'), 'wb') as f:
    f.write(bytes(bad_src))

# bad-dst: corrupt byte 21 (first byte of dst_crc, offset = 4+1+8+8 = 21)
bad_dst = bytearray(good); bad_dst[21] ^= 0xFF
with open(os.path.join(d, 'crc-bad-dst.delta'), 'wb') as f:
    f.write(bytes(bad_dst))
PYEOF
    have_crc_neg=true
fi

if $have_crc_neg; then
    # C
    out_src="$tmpdir/crc-c-badsrc.out"; out_dst2="$tmpdir/crc-c-baddst2.out"
    check_fails "src_crc mismatch rejected (C)"      $DELTA decode "$crc_ref" "$crc_bad_src" /dev/null
    check_fails "dst_crc mismatch rejected (C)"      $DELTA decode "$crc_ref" "$crc_bad_dst" "$tmpdir/crc-c-baddst.out"
    $DELTA decode "$crc_ref" "$crc_bad_src" "$out_src" --ignore-hash 2>/dev/null
    check "--ignore-hash bypasses src_crc (C)"   diff -q "$crc_ver" "$out_src"
    $DELTA decode "$crc_ref" "$crc_bad_dst" "$out_dst2" --ignore-hash 2>/dev/null
    check "--ignore-hash bypasses dst_crc (C)"   diff -q "$crc_ver" "$out_dst2"

    if [ -n "$RUST_DELTA" ]; then
        out_src="$tmpdir/crc-rust-badsrc.out"; out_dst2="$tmpdir/crc-rust-baddst2.out"
        check_fails "src_crc mismatch rejected (Rust)"   $RUST_DELTA decode "$crc_ref" "$crc_bad_src" /dev/null
        check_fails "dst_crc mismatch rejected (Rust)"   $RUST_DELTA decode "$crc_ref" "$crc_bad_dst" "$tmpdir/crc-rust-baddst.out"
        $RUST_DELTA decode "$crc_ref" "$crc_bad_src" "$out_src" --ignore-hash 2>/dev/null
        check "--ignore-hash bypasses src_crc (Rust)"   diff -q "$crc_ver" "$out_src"
        $RUST_DELTA decode "$crc_ref" "$crc_bad_dst" "$out_dst2" --ignore-hash 2>/dev/null
        check "--ignore-hash bypasses dst_crc (Rust)"   diff -q "$crc_ver" "$out_dst2"
    else
        echo "  (CRC mismatch Rust: not found)"
    fi

    if [ -n "$GO_DELTA" ]; then
        out_src="$tmpdir/crc-go-badsrc.out"; out_dst2="$tmpdir/crc-go-baddst2.out"
        check_fails "src_crc mismatch rejected (Go)"     $GO_DELTA decode "$crc_ref" "$crc_bad_src" /dev/null
        check_fails "dst_crc mismatch rejected (Go)"     $GO_DELTA decode "$crc_ref" "$crc_bad_dst" "$tmpdir/crc-go-baddst.out"
        $GO_DELTA decode "$crc_ref" "$crc_bad_src" "$out_src" --ignore-hash 2>/dev/null
        check "--ignore-hash bypasses src_crc (Go)"     diff -q "$crc_ver" "$out_src"
        $GO_DELTA decode "$crc_ref" "$crc_bad_dst" "$out_dst2" --ignore-hash 2>/dev/null
        check "--ignore-hash bypasses dst_crc (Go)"     diff -q "$crc_ver" "$out_dst2"
    else
        echo "  (CRC mismatch Go: not found)"
    fi

    if [ -n "$CPP_DELTA" ]; then
        out_src="$tmpdir/crc-cpp-badsrc.out"; out_dst2="$tmpdir/crc-cpp-baddst2.out"
        check_fails "src_crc mismatch rejected (C++)"    $CPP_DELTA decode "$crc_ref" "$crc_bad_src" /dev/null
        check_fails "dst_crc mismatch rejected (C++)"    $CPP_DELTA decode "$crc_ref" "$crc_bad_dst" "$tmpdir/crc-cpp-baddst.out"
        $CPP_DELTA decode "$crc_ref" "$crc_bad_src" "$out_src" --ignore-hash 2>/dev/null
        check "--ignore-hash bypasses src_crc (C++)"    diff -q "$crc_ver" "$out_src"
        $CPP_DELTA decode "$crc_ref" "$crc_bad_dst" "$out_dst2" --ignore-hash 2>/dev/null
        check "--ignore-hash bypasses dst_crc (C++)"    diff -q "$crc_ver" "$out_dst2"
    else
        echo "  (CRC mismatch C++: not found)"
    fi

    if [ -n "$JAVA_DELTA" ]; then
        out_src="$tmpdir/crc-java-badsrc.out"; out_dst2="$tmpdir/crc-java-baddst2.out"
        check_fails "src_crc mismatch rejected (Java)"   $JAVA_DELTA decode "$crc_ref" "$crc_bad_src" /dev/null
        check_fails "dst_crc mismatch rejected (Java)"   $JAVA_DELTA decode "$crc_ref" "$crc_bad_dst" "$tmpdir/crc-java-baddst.out"
        $JAVA_DELTA decode "$crc_ref" "$crc_bad_src" "$out_src" --ignore-hash 2>/dev/null
        check "--ignore-hash bypasses src_crc (Java)"   diff -q "$crc_ver" "$out_src"
        $JAVA_DELTA decode "$crc_ref" "$crc_bad_dst" "$out_dst2" --ignore-hash 2>/dev/null
        check "--ignore-hash bypasses dst_crc (Java)"   diff -q "$crc_ver" "$out_dst2"
    else
        echo "  (CRC mismatch Java: not found)"
    fi

    if [ -n "$KT_DELTA" ]; then
        out_src="$tmpdir/crc-kt-badsrc.out"; out_dst2="$tmpdir/crc-kt-baddst2.out"
        check_fails "src_crc mismatch rejected (Kotlin)" $KT_DELTA decode "$crc_ref" "$crc_bad_src" /dev/null
        check_fails "dst_crc mismatch rejected (Kotlin)" $KT_DELTA decode "$crc_ref" "$crc_bad_dst" "$tmpdir/crc-kt-baddst.out"
        $KT_DELTA decode "$crc_ref" "$crc_bad_src" "$out_src" --ignore-hash 2>/dev/null
        check "--ignore-hash bypasses src_crc (Kotlin)" diff -q "$crc_ver" "$out_src"
        $KT_DELTA decode "$crc_ref" "$crc_bad_dst" "$out_dst2" --ignore-hash 2>/dev/null
        check "--ignore-hash bypasses dst_crc (Kotlin)" diff -q "$crc_ver" "$out_dst2"
    else
        echo "  (CRC mismatch Kotlin: not found)"
    fi

    if [ -n "$SCALA_DELTA" ]; then
        out_src="$tmpdir/crc-scala-badsrc.out"; out_dst2="$tmpdir/crc-scala-baddst2.out"
        check_fails "src_crc mismatch rejected (Scala)"  $SCALA_DELTA decode "$crc_ref" "$crc_bad_src" /dev/null
        check_fails "dst_crc mismatch rejected (Scala)"  $SCALA_DELTA decode "$crc_ref" "$crc_bad_dst" "$tmpdir/crc-scala-baddst.out"
        $SCALA_DELTA decode "$crc_ref" "$crc_bad_src" "$out_src" --ignore-hash 2>/dev/null
        check "--ignore-hash bypasses src_crc (Scala)"  diff -q "$crc_ver" "$out_src"
        $SCALA_DELTA decode "$crc_ref" "$crc_bad_dst" "$out_dst2" --ignore-hash 2>/dev/null
        check "--ignore-hash bypasses dst_crc (Scala)"  diff -q "$crc_ver" "$out_dst2"
    else
        echo "  (CRC mismatch Scala: not found)"
    fi

    if [ -n "$PY_DELTA" ]; then
        out_src="$tmpdir/crc-py-badsrc.out"; out_dst2="$tmpdir/crc-py-baddst2.out"
        check_fails "src_crc mismatch rejected (Python)" $PY_DELTA decode "$crc_ref" "$crc_bad_src" /dev/null
        check_fails "dst_crc mismatch rejected (Python)" $PY_DELTA decode "$crc_ref" "$crc_bad_dst" "$tmpdir/crc-py-baddst.out"
        $PY_DELTA decode "$crc_ref" "$crc_bad_src" "$out_src" --ignore-hash 2>/dev/null
        check "--ignore-hash bypasses src_crc (Python)" diff -q "$crc_ver" "$out_src"
        $PY_DELTA decode "$crc_ref" "$crc_bad_dst" "$out_dst2" --ignore-hash 2>/dev/null
        check "--ignore-hash bypasses dst_crc (Python)" diff -q "$crc_ver" "$out_dst2"
    else
        echo "  (CRC mismatch Python: not found)"
    fi
else
    echo "  (CRC mismatch tests: not found)"
fi

echo ""
echo "=== --large flag: force 64-bit BIGCOPY/BIGADD commands ==="

# check_big_cmd verifies that the first command byte in a DLT\x04 delta
# is a 64-bit variant (BIGCOPY=03, BIGADD=04, BIGMOVE=06).
# The DLT\x04 header is 29 bytes so the first command byte is at offset 29.
check_big_cmd() {
    local label="$1" delta_file="$2"
    TESTS=$((TESTS + 1))
    local cmd_byte
    cmd_byte=$(od -An -tx1 -j29 -N1 "$delta_file" 2>/dev/null | tr -d ' \n')
    case "$cmd_byte" in
        "03"|"04"|"06")
            PASS=$((PASS + 1))
            printf "  ok  %s\n" "$label"
            ;;
        *)
            FAIL=$((FAIL + 1))
            printf "FAIL  %s (got cmd byte: 0x%s, want 03/04/06)\n" "$label" "$cmd_byte"
            ;;
    esac
}

# C
large_c="$tmpdir/large-c.delta"; large_c_out="$tmpdir/large-c.out"
$DELTA encode greedy "$ref" "$ver" "$large_c" --large
check_big_cmd "C --large: first cmd is BIGCOPY/BIGADD" "$large_c"
$DELTA decode "$ref" "$large_c" "$large_c_out"
check "C --large roundtrip" diff -q "$ver" "$large_c_out"

large_ip_c="$tmpdir/large-ip-c.delta"; large_ip_c_out="$tmpdir/large-ip-c.out"
$DELTA encode greedy "$ref" "$ver" "$large_ip_c" --inplace --large
check_big_cmd "C --inplace --large: first cmd is BIGCOPY/BIGADD" "$large_ip_c"
$DELTA decode "$ref" "$large_ip_c" "$large_ip_c_out"
check "C --inplace --large roundtrip" diff -q "$ver" "$large_ip_c_out"

# Go
if [ -n "$GO_DELTA" ]; then
    large_go="$tmpdir/large-go.delta"; large_go_out="$tmpdir/large-go.out"
    $GO_DELTA encode greedy "$ref" "$ver" "$large_go" --large
    check_big_cmd "Go --large: first cmd is BIGCOPY/BIGADD" "$large_go"
    $GO_DELTA decode "$ref" "$large_go" "$large_go_out"
    check "Go --large roundtrip" diff -q "$ver" "$large_go_out"

    large_ip_go="$tmpdir/large-ip-go.delta"; large_ip_go_out="$tmpdir/large-ip-go.out"
    $GO_DELTA encode greedy "$ref" "$ver" "$large_ip_go" --inplace --large
    check_big_cmd "Go --inplace --large: first cmd is BIGCOPY/BIGADD" "$large_ip_go"
    $GO_DELTA decode "$ref" "$large_ip_go" "$large_ip_go_out"
    check "Go --inplace --large roundtrip" diff -q "$ver" "$large_ip_go_out"
else
    echo "  (Go --large tests: not found)"
fi

# Rust
if [ -n "$RUST_DELTA" ]; then
    large_rs="$tmpdir/large-rs.delta"; large_rs_out="$tmpdir/large-rs.out"
    $RUST_DELTA encode greedy "$ref" "$ver" "$large_rs" --large
    check_big_cmd "Rust --large: first cmd is BIGCOPY/BIGADD" "$large_rs"
    $RUST_DELTA decode "$ref" "$large_rs" "$large_rs_out"
    check "Rust --large roundtrip" diff -q "$ver" "$large_rs_out"

    large_ip_rs="$tmpdir/large-ip-rs.delta"; large_ip_rs_out="$tmpdir/large-ip-rs.out"
    $RUST_DELTA encode greedy "$ref" "$ver" "$large_ip_rs" --inplace --large
    check_big_cmd "Rust --inplace --large: first cmd is BIGCOPY/BIGADD" "$large_ip_rs"
    $RUST_DELTA decode "$ref" "$large_ip_rs" "$large_ip_rs_out"
    check "Rust --inplace --large roundtrip" diff -q "$ver" "$large_ip_rs_out"
else
    echo "  (Rust --large tests: not found)"
fi

# C++
if [ -n "$CPP_DELTA" ]; then
    large_cpp="$tmpdir/large-cpp.delta"; large_cpp_out="$tmpdir/large-cpp.out"
    $CPP_DELTA encode greedy "$ref" "$ver" "$large_cpp" --large
    check_big_cmd "C++ --large: first cmd is BIGCOPY/BIGADD" "$large_cpp"
    $CPP_DELTA decode "$ref" "$large_cpp" "$large_cpp_out"
    check "C++ --large roundtrip" diff -q "$ver" "$large_cpp_out"

    large_ip_cpp="$tmpdir/large-ip-cpp.delta"; large_ip_cpp_out="$tmpdir/large-ip-cpp.out"
    $CPP_DELTA encode greedy "$ref" "$ver" "$large_ip_cpp" --inplace --large
    check_big_cmd "C++ --inplace --large: first cmd is BIGCOPY/BIGADD" "$large_ip_cpp"
    $CPP_DELTA decode "$ref" "$large_ip_cpp" "$large_ip_cpp_out"
    check "C++ --inplace --large roundtrip" diff -q "$ver" "$large_ip_cpp_out"
else
    echo "  (C++ --large tests: not found)"
fi

# Python
if [ -n "$PY_DELTA" ]; then
    large_py="$tmpdir/large-py.delta"; large_py_out="$tmpdir/large-py.out"
    $PY_DELTA encode greedy "$ref" "$ver" "$large_py" --large
    check_big_cmd "Python --large: first cmd is BIGCOPY/BIGADD" "$large_py"
    $PY_DELTA decode "$ref" "$large_py" "$large_py_out"
    check "Python --large roundtrip" diff -q "$ver" "$large_py_out"

    large_ip_py="$tmpdir/large-ip-py.delta"; large_ip_py_out="$tmpdir/large-ip-py.out"
    $PY_DELTA encode greedy "$ref" "$ver" "$large_ip_py" --inplace --large
    check_big_cmd "Python --inplace --large: first cmd is BIGCOPY/BIGADD" "$large_ip_py"
    $PY_DELTA decode "$ref" "$large_ip_py" "$large_ip_py_out"
    check "Python --inplace --large roundtrip" diff -q "$ver" "$large_ip_py_out"
else
    echo "  (Python --large tests: not found)"
fi

# Java
if [ -n "$JAVA_DELTA" ]; then
    large_java="$tmpdir/large-java.delta"; large_java_out="$tmpdir/large-java.out"
    $JAVA_DELTA encode greedy "$ref" "$ver" "$large_java" --large
    check_big_cmd "Java --large: first cmd is BIGCOPY/BIGADD" "$large_java"
    $JAVA_DELTA decode "$ref" "$large_java" "$large_java_out"
    check "Java --large roundtrip" diff -q "$ver" "$large_java_out"

    large_ip_java="$tmpdir/large-ip-java.delta"; large_ip_java_out="$tmpdir/large-ip-java.out"
    $JAVA_DELTA encode greedy "$ref" "$ver" "$large_ip_java" --inplace --large
    check_big_cmd "Java --inplace --large: first cmd is BIGCOPY/BIGADD" "$large_ip_java"
    $JAVA_DELTA decode "$ref" "$large_ip_java" "$large_ip_java_out"
    check "Java --inplace --large roundtrip" diff -q "$ver" "$large_ip_java_out"
else
    echo "  (Java --large tests: not found)"
fi

# Kotlin
if [ -n "$KT_DELTA" ]; then
    large_kt="$tmpdir/large-kt.delta"; large_kt_out="$tmpdir/large-kt.out"
    $KT_DELTA encode greedy "$ref" "$ver" "$large_kt" --large
    check_big_cmd "Kotlin --large: first cmd is BIGCOPY/BIGADD" "$large_kt"
    $KT_DELTA decode "$ref" "$large_kt" "$large_kt_out"
    check "Kotlin --large roundtrip" diff -q "$ver" "$large_kt_out"

    large_ip_kt="$tmpdir/large-ip-kt.delta"; large_ip_kt_out="$tmpdir/large-ip-kt.out"
    $KT_DELTA encode greedy "$ref" "$ver" "$large_ip_kt" --inplace --large
    check_big_cmd "Kotlin --inplace --large: first cmd is BIGCOPY/BIGADD" "$large_ip_kt"
    $KT_DELTA decode "$ref" "$large_ip_kt" "$large_ip_kt_out"
    check "Kotlin --inplace --large roundtrip" diff -q "$ver" "$large_ip_kt_out"
else
    echo "  (Kotlin --large tests: not found)"
fi

# Scala
if [ -n "$SCALA_DELTA" ]; then
    large_sc="$tmpdir/large-sc.delta"; large_sc_out="$tmpdir/large-sc.out"
    $SCALA_DELTA encode greedy "$ref" "$ver" "$large_sc" --large
    check_big_cmd "Scala --large: first cmd is BIGCOPY/BIGADD" "$large_sc"
    $SCALA_DELTA decode "$ref" "$large_sc" "$large_sc_out"
    check "Scala --large roundtrip" diff -q "$ver" "$large_sc_out"

    large_ip_sc="$tmpdir/large-ip-sc.delta"; large_ip_sc_out="$tmpdir/large-ip-sc.out"
    $SCALA_DELTA encode greedy "$ref" "$ver" "$large_ip_sc" --inplace --large
    check_big_cmd "Scala --inplace --large: first cmd is BIGCOPY/BIGADD" "$large_ip_sc"
    $SCALA_DELTA decode "$ref" "$large_ip_sc" "$large_ip_sc_out"
    check "Scala --inplace --large roundtrip" diff -q "$ver" "$large_ip_sc_out"
else
    echo "  (Scala --large tests: not found)"
fi

echo ""
echo "=== --large cross-language interop ==="

# C --large → Go decode
if [ -n "$GO_DELTA" ]; then
    xl_go_out="$tmpdir/xl-large-c-go.out"
    $GO_DELTA decode "$ref" "$large_c" "$xl_go_out"
    check "--large: C encode → Go decode" diff -q "$ver" "$xl_go_out"

    xl_ip_go_out="$tmpdir/xl-large-ip-c-go.out"
    $GO_DELTA decode "$ref" "$large_ip_c" "$xl_ip_go_out"
    check "--large inplace: C encode → Go decode" diff -q "$ver" "$xl_ip_go_out"
else
    echo "  (--large C→Go: not found)"
fi

# Go --large → Rust decode
if [ -n "$GO_DELTA" ] && [ -n "$RUST_DELTA" ]; then
    xl_rs_out="$tmpdir/xl-large-go-rs.out"
    $RUST_DELTA decode "$ref" "$large_go" "$xl_rs_out"
    check "--large: Go encode → Rust decode" diff -q "$ver" "$xl_rs_out"
else
    echo "  (--large Go→Rust: not found)"
fi

# Rust --large → C++ decode
if [ -n "$RUST_DELTA" ] && [ -n "$CPP_DELTA" ]; then
    xl_cpp_out="$tmpdir/xl-large-rs-cpp.out"
    $CPP_DELTA decode "$ref" "$large_rs" "$xl_cpp_out"
    check "--large: Rust encode → C++ decode" diff -q "$ver" "$xl_cpp_out"
else
    echo "  (--large Rust→C++: not found)"
fi

# Java --large → Kotlin decode
if [ -n "$JAVA_DELTA" ] && [ -n "$KT_DELTA" ]; then
    xl_kt_out="$tmpdir/xl-large-java-kt.out"
    $KT_DELTA decode "$ref" "$large_java" "$xl_kt_out"
    check "--large: Java encode → Kotlin decode" diff -q "$ver" "$xl_kt_out"
else
    echo "  (--large Java→Kotlin: not found)"
fi

# Scala --large → Java decode
if [ -n "$SCALA_DELTA" ] && [ -n "$JAVA_DELTA" ]; then
    xl_java_out="$tmpdir/xl-large-sc-java.out"
    $JAVA_DELTA decode "$ref" "$large_sc" "$xl_java_out"
    check "--large: Scala encode → Java decode" diff -q "$ver" "$xl_java_out"
else
    echo "  (--large Scala→Java: not found)"
fi

# Python --large → C decode
if [ -n "$PY_DELTA" ]; then
    xl_c_out="$tmpdir/xl-large-py-c.out"
    $DELTA decode "$ref" "$large_py" "$xl_c_out"
    check "--large: Python encode → C decode" diff -q "$ver" "$xl_c_out"
else
    echo "  (--large Python→C: not found)"
fi

echo ""
echo "========================================"
printf "Results: %d passed, %d failed\n" "$PASS" "$FAIL"
echo "========================================"

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
