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
    echo "  (test_overflow binary not found, skipping)"
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
    for algo in onepass correcting; do
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
    echo "  (Rust binary not found, skipping)"
fi

if [ -n "$GO_DELTA" ]; then
    for algo in onepass correcting; do
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
    echo "  (Go binary not found, skipping)"
fi

if [ -n "$KT_DELTA" ]; then
    for algo in onepass correcting; do
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
    echo "  (Kotlin JAR not found, skipping)"
fi

if [ -n "$SCALA_DELTA" ]; then
    for algo in onepass correcting; do
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
    echo "  (Scala JAR not found, skipping)"
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
    echo "  (Rust binary not found, skipping)"
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
    echo "  (C++ binary not found, skipping)"
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
    echo "  (Python not found, skipping)"
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
    echo "  (Java classes not found, skipping)"
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
    echo "  (Kotlin JAR not found, skipping)"
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
    echo "  (Scala JAR not found, skipping)"
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
    echo "  (Go binary not found, skipping)"
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

if [ -n "$JAVA_DELTA" ]; then
    for algo in onepass correcting; do
        c_d="$tmpdir/c-jdec-${algo}.delta"
        j_out="$tmpdir/java-from-c-${algo}.out"
        $DELTA encode $algo "$ref" "$ver" "$c_d"
        $JAVA_DELTA decode "$ref" "$c_d" "$j_out"
        check "C encode -> Java decode ($algo)" diff -q "$ver" "$j_out"
    done

    for algo in onepass correcting; do
        j_d="$tmpdir/java-cdec-${algo}.delta"
        c_out="$tmpdir/c-from-java-${algo}.out"
        $JAVA_DELTA encode $algo "$ref" "$ver" "$j_d"
        $DELTA decode "$ref" "$j_d" "$c_out"
        check "Java encode -> C decode ($algo)" diff -q "$ver" "$c_out"
    done
fi

if [ -n "$KT_DELTA" ]; then
    for algo in onepass correcting; do
        c_d="$tmpdir/c-ktdec-${algo}.delta"
        kt_out="$tmpdir/kotlin-from-c-${algo}.out"
        $DELTA encode $algo "$ref" "$ver" "$c_d"
        $KT_DELTA decode "$ref" "$c_d" "$kt_out"
        check "C encode -> Kotlin decode ($algo)" diff -q "$ver" "$kt_out"
    done

    for algo in onepass correcting; do
        kt_d="$tmpdir/kotlin-cdec-${algo}.delta"
        c_out="$tmpdir/c-from-kotlin-${algo}.out"
        $KT_DELTA encode $algo "$ref" "$ver" "$kt_d"
        $DELTA decode "$ref" "$kt_d" "$c_out"
        check "Kotlin encode -> C decode ($algo)" diff -q "$ver" "$c_out"
    done
fi

if [ -n "$SCALA_DELTA" ]; then
    for algo in onepass correcting; do
        c_d="$tmpdir/c-scdec-${algo}.delta"
        sc_out="$tmpdir/scala-from-c-${algo}.out"
        $DELTA encode $algo "$ref" "$ver" "$c_d"
        $SCALA_DELTA decode "$ref" "$c_d" "$sc_out"
        check "C encode -> Scala decode ($algo)" diff -q "$ver" "$sc_out"
    done

    for algo in onepass correcting; do
        sc_d="$tmpdir/scala-cdec-${algo}.delta"
        c_out="$tmpdir/c-from-scala-${algo}.out"
        $SCALA_DELTA encode $algo "$ref" "$ver" "$sc_d"
        $DELTA decode "$ref" "$sc_d" "$c_out"
        check "Scala encode -> C decode ($algo)" diff -q "$ver" "$c_out"
    done
fi

if [ -n "$GO_DELTA" ]; then
    for algo in onepass correcting; do
        c_d="$tmpdir/c-godec-${algo}.delta"
        go_out="$tmpdir/go-from-c-${algo}.out"
        $DELTA encode $algo "$ref" "$ver" "$c_d"
        $GO_DELTA decode "$ref" "$c_d" "$go_out"
        check "C encode -> Go decode ($algo)" diff -q "$ver" "$go_out"
    done

    for algo in onepass correcting; do
        go_d="$tmpdir/go-cdec-${algo}.delta"
        c_out="$tmpdir/c-from-go-${algo}.out"
        $GO_DELTA encode $algo "$ref" "$ver" "$go_d"
        $DELTA decode "$ref" "$go_d" "$c_out"
        check "Go encode -> C decode ($algo)" diff -q "$ver" "$c_out"
    done
fi

echo ""
echo "========================================"
printf "Results: %d passed, %d failed (of %d)\n" "$PASS" "$FAIL" "$TESTS"
echo "========================================"

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
