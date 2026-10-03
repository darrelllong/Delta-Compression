#!/bin/sh
#
# Tests of the C delta tool, and of its agreement with the other
# implementations: each of those that has been built is run against the same
# inputs.  Run from src/c after make.
#
# The exit status is 0 if every check passes.  A check that needs an
# implementation that is not built is skipped with a note.

set -e

DELTA=${DELTA:-./delta}
PASS=0
FAIL=0

RUST_DELTA=""
CPP_DELTA=""
PY_DELTA=""
JAVA_DELTA=""
GO_DELTA=""

if [ -x ../rust/delta/target/release/delta ]; then
    RUST_DELTA=../rust/delta/target/release/delta
fi
if [ -x ../cpp/build/delta ]; then
    CPP_DELTA=../cpp/build/delta
fi
if [ -f ../python/delta.py ]; then
    PY_DELTA="python3 ../python/delta.py"
fi
if [ -d ../java ]; then
    JAVA_BIN="${JAVA:-java}"
    if [ "$JAVA_BIN" = java ] && [ -x /opt/homebrew/opt/openjdk@17/bin/java ]; then
        JAVA_BIN=/opt/homebrew/opt/openjdk@17/bin/java
    fi
    # Rebuilt every time, with the javac that goes with JAVA_BIN, so that the
    # classes are neither stale nor too new for it.
    if make -s -C ../java JAVA="$JAVA_BIN" JAVAC="${JAVA_BIN%java}javac" 2>/dev/null \
       && [ -f ../java/out/delta/Delta.class ]; then
        JAVA_DELTA="$JAVA_BIN -cp ../java/out delta.Delta"
    fi
fi
if [ -x ../go/delta/delta ]; then
    GO_DELTA=../go/delta/delta
fi

# have L: is implementation L built?
have() {
    case "$1" in
        C)      true ;;
        Rust)   [ -n "$RUST_DELTA" ] ;;
        C++)    [ -n "$CPP_DELTA" ] ;;
        Python) [ -n "$PY_DELTA" ] ;;
        Java)   [ -n "$JAVA_DELTA" ] ;;
        Go)     [ -n "$GO_DELTA" ] ;;
        *)      echo "have: unknown implementation $1" >&2; exit 2 ;;
    esac
}

# have_all L...: are they all built?  If not, say which check is skipped.
have_all() {
    local l
    for l in "$@"; do
        if ! have "$l"; then
            echo "  (skipped: $l not found)"
            return 1
        fi
    done
}

# run L args...: run implementation L's delta command.
run() {
    local l="$1"; shift
    case "$l" in
        C)      $DELTA "$@" ;;
        Rust)   $RUST_DELTA "$@" ;;
        C++)    $CPP_DELTA "$@" ;;
        Python) $PY_DELTA "$@" ;;
        Java)   $JAVA_DELTA "$@" ;;
        Go)     $GO_DELTA "$@" ;;
    esac
}

# quiet cmd...: run it without output; its status is not checked.
quiet() {
    "$@" >/dev/null 2>&1 || true
}

pass() {
    PASS=$((PASS + 1))
    printf "  ok  %s\n" "$1"
}

fail() {
    FAIL=$((FAIL + 1))
    printf "FAIL  %s\n" "$1"
}

# check desc cmd...: the command must succeed.
check() {
    local desc="$1"; shift
    if "$@" >/dev/null 2>&1; then pass "$desc"; else fail "$desc"; fi
}

# check_fails desc cmd...: the command must fail.
check_fails() {
    local desc="$1"; shift
    if "$@" >/dev/null 2>&1; then fail "$desc"; else pass "$desc"; fi
}

# check_output desc expected outfile cmd...: the command must succeed and
# leave the expected text in outfile.
check_output() {
    local desc="$1" expected="$2" outfile="$3"; shift 3
    if "$@" >/dev/null 2>&1 && [ "$(cat "$outfile" 2>/dev/null)" = "$expected" ]; then
        pass "$desc"
    else
        fail "$desc"
    fi
}

section() {
    echo ""
    echo "=== $1 ==="
}

tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT
n=0

# tmp: set $t to a fresh file name under tmpdir.
tmp() {
    n=$((n + 1))
    t="$tmpdir/f$n"
}

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

algos="greedy onepass correcting"

# encode L algo ref ver delta [options]
encode() {
    local l="$1" algo="$2" r="$3" v="$4" d="$5"; shift 5
    run "$l" encode "$algo" "$r" "$v" "$d" "$@"
}

# roundtrip desc L1 L2 ref ver algo [options]: L1 encodes, L2 decodes, and the
# result must be ver.
roundtrip() {
    local desc="$1" enc="$2" dec="$3" r="$4" v="$5" algo="$6"; shift 6
    local d out
    tmp; d=$t; tmp; out=$t
    encode "$enc" "$algo" "$r" "$v" "$d" "$@"
    run "$dec" decode "$r" "$d" "$out"
    check "$desc" cmp -s "$v" "$out"
}

section "Roundtrip tests"

for algo in $algos; do
    roundtrip "$algo roundtrip" C C "$ref" "$ver" $algo
done
base_delta="$tmpdir/onepass.delta"
$DELTA encode onepass "$ref" "$ver" "$base_delta"

section "Encode overflow rejection"

if [ -x ./test_overflow ]; then
    for field in version_size copy_src copy_dst copy_len add_dst add_len; do
        check_fails "C encode rejects $field overflow" ./test_overflow "$field"
    done
else
    echo "  (skipped: test_overflow not built)"
fi

section "Decode validation"

base_size=$(wc -c < "$base_delta" | tr -d ' ')

tmp; missing_end=$t
dd if="$base_delta" of="$missing_end" bs=1 count=$((base_size - 1)) 2>/dev/null
check_fails "info rejects missing END" $DELTA info "$missing_end"

tmp; trailing=$t
cp "$base_delta" "$trailing"
printf '\177' >> "$trailing"
check_fails "info rejects trailing data" $DELTA info "$trailing"

# Byte 17 is in the CRC of the reference.
tmp; bad_crc=$t
tmp; bad_out=$t
cp "$base_delta" "$bad_crc"
printf '\377' | dd of="$bad_crc" bs=1 seek=17 conv=notrunc 2>/dev/null
if ! $DELTA decode "$ref" "$bad_crc" "$bad_out" >/dev/null 2>&1 \
    && [ ! -e "$bad_out" ]; then
    pass "decode rejects bad CRC without writing output"
else
    fail "decode rejects bad CRC without writing output"
fi

section "In-place tests"

for algo in $algos; do
    for pol in localmin constant; do
        roundtrip "$algo inplace ($pol) roundtrip" C C "$ref" "$ver" $algo \
            --inplace --policy $pol
    done
done

section "Splay tree tests"

for algo in $algos; do
    roundtrip "$algo splay roundtrip" C C "$ref" "$ver" $algo --splay
done

section "Info command"

check "info command" $DELTA info "$base_delta"

section "Empty file tests"

empty="$tmpdir/empty"
: > "$empty"
roundtrip "empty ref roundtrip" C C "$empty" "$ver" onepass
roundtrip "empty ver roundtrip" C C "$ref" "$empty" onepass

section "Identical files"

roundtrip "identical files roundtrip" C C "$ref" "$ref" onepass

section "Inplace subcommand tests"

for algo in $algos; do
    tmp; std_d=$t
    tmp; ip_d=$t
    tmp; out=$t
    $DELTA encode $algo "$ref" "$ver" "$std_d"
    $DELTA inplace "$ref" "$std_d" "$ip_d"
    $DELTA decode "$ref" "$ip_d" "$out"
    check "$algo inplace subcommand roundtrip" cmp -s "$ver" "$out"

    # A delta that is already in-place passes through.
    tmp; ip_d2=$t
    tmp; out=$t
    $DELTA inplace "$ref" "$ip_d" "$ip_d2"
    $DELTA decode "$ref" "$ip_d2" "$out"
    check "$algo inplace subcommand idempotent" cmp -s "$ver" "$out"

    tmp; direct_d=$t
    $DELTA encode $algo "$ref" "$ver" "$direct_d" --inplace
    check "$algo inplace subcommand byte-identical to --inplace" \
        cmp -s "$direct_d" "$ip_d"
done

# The conversion reads the reference, so the wrong one must be refused
# before anything is written.
tmp; std_d=$t
tmp; wrong_out=$t
$DELTA encode onepass "$ref" "$ver" "$std_d"
if ! $DELTA inplace "$ver" "$std_d" "$wrong_out" >/dev/null 2>&1 \
    && [ ! -e "$wrong_out" ]; then
    pass "inplace rejects wrong reference without writing output"
else
    fail "inplace rejects wrong reference without writing output"
fi

tmp; ip_d=$t
tmp; verbose_err=$t
check "inplace --verbose succeeds" \
    sh -c '"$@" 2>"$0"' "$verbose_err" \
    $DELTA inplace "$ref" "$std_d" "$ip_d" --verbose
check "inplace --verbose prints the inplace: line" \
    grep -Eq '^inplace: [0-9]+ copies, [0-9]+ CRWI edges, [0-9]+ cycles broken$' \
    "$verbose_err"

section "Inplace subcommand rejects the wrong reference"

for l in C Rust Go C++ Java Python; do
    have_all $l || continue
    tmp; d=$t
    tmp; wrong_out=$t
    quiet run $l encode onepass "$ref" "$ver" "$d"
    check_fails "inplace rejects wrong reference ($l)" \
        run $l inplace "$ver" "$d" "$wrong_out"
done

# chain desc L1 L2 L3 ref ver algo: L1 encodes, L2 converts to in-place, L3
# decodes.
chain() {
    local desc="$1" enc="$2" conv="$3" dec="$4" r="$5" v="$6" algo="$7"
    local d ip out
    tmp; d=$t; tmp; ip=$t; tmp; out=$t
    quiet run "$enc" encode "$algo" "$r" "$v" "$d"
    quiet run "$conv" inplace "$r" "$d" "$ip"
    quiet run "$dec" decode "$r" "$ip" "$out"
    check "$desc" cmp -s "$v" "$out"
}

section "Cross-language inplace subcommand"

for l in Rust Go; do
    have_all $l || continue
    for algo in $algos; do
        chain "C encode -> $l inplace -> C decode ($algo)" \
            C $l C "$ref" "$ver" $algo
        chain "$l encode -> C inplace -> $l decode ($algo)" \
            $l C $l "$ref" "$ver" $algo
    done
done

# identical desc L ref ver algo [options]: L and C must encode to the same
# bytes.
identical() {
    local desc="$1" l="$2" r="$3" v="$4" algo="$5"; shift 5
    local c_d l_d
    tmp; c_d=$t; tmp; l_d=$t
    encode C "$algo" "$r" "$v" "$c_d" "$@"
    encode "$l" "$algo" "$r" "$v" "$l_d" "$@"
    check "$desc" cmp -s "$c_d" "$l_d"
}

section "Byte-identical deltas (C vs other implementations)"

for l in Rust C++ Python Java Go; do
    have_all $l || continue
    for algo in $algos; do
        identical "C vs $l $algo byte-identical" $l "$ref" "$ver" $algo
    done
done

section "Byte-identical in-place deltas with several cycles"

# V swaps each adjacent pair of R's blocks, so every pair is a cycle of its
# own and all of them are stalled at once.  The order in which the cycles
# are broken is the order of the adds in the delta, and the implementations
# must agree on it under both policies.
tmp; swap_ref=$t; tmp; swap_ver=$t
python3 - "$swap_ref" "$swap_ver" <<'PYEOF'
import random, sys
rnd = random.Random(7)
sizes = [900, 1100, 700, 1300, 1000, 600, 1500, 800, 950, 1250]
blocks = [bytes(rnd.getrandbits(8) for _ in range(n)) for n in sizes]
with open(sys.argv[1], 'wb') as f:
    f.write(b''.join(blocks))
with open(sys.argv[2], 'wb') as f:
    f.write(b''.join(blocks[i ^ 1] for i in range(len(blocks))))
PYEOF

for policy in localmin constant; do
    roundtrip "swapped pairs correcting in-place roundtrip ($policy)" \
        C C "$swap_ref" "$swap_ver" correcting --inplace --policy $policy
    for l in Rust C++ Python Java Go; do
        have_all $l || continue
        identical "swapped pairs C vs $l in-place byte-identical ($policy)" \
            $l "$swap_ref" "$swap_ver" correcting --inplace --policy $policy
    done
done

section "Encoder DLT\\x04 header"

# Bytes 0-3 are the magic and bytes 5-12 the version size as a big-endian
# u64.  A u32 followed by four zero bytes, the DLT\x03 layout padded, is a
# different sequence of bytes.
magic_ref="$tmpdir/magic-ref.bin"
magic_ver="$tmpdir/magic-ver.bin"
printf '%s' "reference data for magic test" > "$magic_ref"
printf '%s' "version data for magic test -- modified" > "$magic_ver"
magic_ver_size_hex=$(printf '%016x' "$(wc -c < "$magic_ver" | tr -d ' ')")

check_v4_header() {
    local desc="$1" delta_file="$2" magic size
    magic=$(od -An -tx1 -N4 "$delta_file" 2>/dev/null | tr -d ' \n')
    size=$(od -An -tx1 -j5 -N8 "$delta_file" 2>/dev/null | tr -d ' \n')
    check "$desc" [ "$magic" = 444c5404 -a "$size" = "$magic_ver_size_hex" ]
}

for l in C Rust Go C++ Java Python; do
    have_all $l || continue
    tmp; d_enc=$t
    tmp; d_ip=$t
    quiet run $l encode onepass "$magic_ref" "$magic_ver" "$d_enc"
    check_v4_header "DLT\\x04 header: $l (encode)" "$d_enc"
    quiet run $l inplace "$magic_ref" "$d_enc" "$d_ip"
    check_v4_header "DLT\\x04 header: $l (inplace)" "$d_ip"
done

section "Cross-language inplace chains"

xip_ref="$tmpdir/xip-ref.bin"
xip_ver="$tmpdir/xip-ver.bin"
printf '%s' "cross-language inplace reference data" > "$xip_ref"
printf '%s' "cross-language inplace version data -- modified here" > "$xip_ver"

for langs in "Rust Go C" "Go C++ Rust" "C Python Rust" "Python Go Java"; do
    set -- $langs
    have_all $1 $2 $3 || continue
    chain "$1 encode -> $2 inplace -> $3 decode" \
        $1 $2 $3 "$xip_ref" "$xip_ver" onepass
done

section "Cross-language decode"

for l in Rust Java Go; do
    have_all $l || continue
    for algo in $algos; do
        roundtrip "C encode -> $l decode ($algo)" C $l "$ref" "$ver" $algo
    done
    for algo in $algos; do
        roundtrip "$l encode -> C decode ($algo)" $l C "$ref" "$ver" $algo
    done
done

section "Real-data cross-language validation (pseudo-Shakespeare)"

# About 55 KB of generated text and a version of it with two thirds of the
# paragraphs exchanged in pairs and some words replaced: long matches, moved
# blocks, and enough copies of differing length that the order of an in-place
# delta is determined by the ordering rule and not by accident.
sh_ref="$tmpdir/sh-ref.txt"
sh_ver="$tmpdir/sh-ver.txt"

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

    for l in Rust Go C++ Java; do
        have_all $l || continue
        for algo in $algos; do
            roundtrip "Shakespeare C encode -> $l decode ($algo)" \
                C $l "$sh_ref" "$sh_ver" $algo
        done
    done
    for l in Rust Go C++ Java; do
        have_all $l || continue
        for algo in $algos; do
            roundtrip "Shakespeare $l encode -> C decode ($algo)" \
                $l C "$sh_ref" "$sh_ver" $algo
        done
    done
    # Pairs without C, so that an error C shares with one of them shows.
    for langs in "Go Rust" "Java Go" "C++ Java"; do
        set -- $langs
        have_all $1 $2 || continue
        for algo in $algos; do
            roundtrip "Shakespeare $1 encode -> $2 decode ($algo)" \
                $1 $2 "$sh_ref" "$sh_ver" $algo
        done
    done
    for l in Rust C++ Python Java Go; do
        have_all $l || continue
        for algo in $algos; do
            identical "Shakespeare C vs $l $algo in-place byte-identical" \
                $l "$sh_ref" "$sh_ver" $algo --inplace
        done
    done
else
    echo "  (skipped: python3 not found)"
fi

# The next three sections decode deltas written by hand, so that they test
# the decoders and not the encoders.  All carry correct CRCs unless noted.
have_fixtures=false
if command -v python3 >/dev/null 2>&1; then
    python3 - "$tmpdir" <<'PYEOF'
import os, struct, sys

def crc64_xz(data):
    poly = 0xC96C5795D7870F42
    table = []
    for i in range(256):
        c = i
        for _ in range(8):
            c = (c >> 1) ^ poly if c & 1 else c >> 1
        table.append(c)
    crc = 0xFFFFFFFFFFFFFFFF
    for b in data:
        crc = table[(crc ^ b) & 0xFF] ^ (crc >> 8)
    return (crc ^ 0xFFFFFFFFFFFFFFFF).to_bytes(8, 'big')

def header(magic, size_format, ref, ver, inplace=False):
    return (magic + (b'\x01' if inplace else b'\x00') +
            struct.pack(size_format, len(ver)) + crc64_xz(ref) + crc64_xz(ver))

def v3(ref, ver, inplace=False):
    return header(b'DLT\x03', '>I', ref, ver, inplace)

def v4(ref, ver):
    return header(b'DLT\x04', '>Q', ref, ver)

END = b'\x00'
def copy(src, dst, n):    return b'\x01' + struct.pack('>III', src, dst, n)
def add(dst, data):       return b'\x02' + struct.pack('>II', dst, len(data)) + data
def bigcopy(src, dst, n): return b'\x03' + struct.pack('>QQQ', src, dst, n)
def bigadd(dst, data):    return b'\x04' + struct.pack('>QQ', dst, len(data)) + data
def move(src, dst, n):    return b'\x05' + struct.pack('>III', src, dst, n)
def bigmove(src, dst, n): return b'\x06' + struct.pack('>QQQ', src, dst, n)

def write(name, data):
    with open(os.path.join(sys.argv[1], name), 'wb') as f:
        f.write(data)

# DLT\x03, which the decoders accept and no encoder writes.
write('hello-ref.bin', b'hello ')
write('v3-copy.delta', v3(b'hello ', b'hello world') +
      copy(0, 0, 6) + add(6, b'world') + END)
write('v3-add.delta', v3(b'', b'hello world') + add(0, b'hello world') + END)
# In place: the working buffer grows from 6 bytes to 9, and the copy's source
# and destination overlap in it.
write('v3-inplace-ref.bin', b'abcabc')
write('v3-inplace.delta', v3(b'abcabc', b'abcabcabc', inplace=True) +
      add(0, b'abc') + copy(0, 3, 6) + END)

# DLT\x04: ADD, the commands that DLT\x03 lacks, and a magic that is neither.
write('v4-add.delta', v4(b'', b'hello world') + add(0, b'hello world') + END)
write('v4-move.delta', v4(b'', b'hellohello') +
      add(0, b'hello') + move(0, 5, 5) + END)
write('v4-bigadd.delta', v4(b'', b'hello world') +
      bigadd(0, b'hello world') + END)
write('v4-bigmove.delta', v4(b'', b'hellohello') +
      add(0, b'hello') + bigmove(0, 5, 5) + END)
write('v4-bigcopy.delta', v4(b'hello ', b'hello world') +
      bigcopy(0, 0, 6) + add(6, b'world') + END)
write('v4-bad.delta', b'DLT\x05' + bytes(30))

# One good delta with each CRC damaged in turn.  The CRC of the reference is
# at byte 13 and that of the version at byte 21.
ref = b'crc validation test reference data'
ver = b'crc validation test version data'
write('crc-ref.bin', ref)
write('crc-ver.bin', ver)
good = v4(ref, ver) + add(0, ver) + END
for name, at in (('crc-bad-src.delta', 13), ('crc-bad-dst.delta', 21)):
    bad = bytearray(good)
    bad[at] ^= 0xFF
    write(name, bytes(bad))
PYEOF
    have_fixtures=true
fi

section "DLT\\x03 legacy decode regression"

if $have_fixtures; then
    for l in C Rust Go C++ Java Python; do
        have_all $l || continue
        tmp; check_output "DLT\\x03 COPY decode ($l)" "hello world" $t \
            run $l decode "$tmpdir/hello-ref.bin" "$tmpdir/v3-copy.delta" $t
        tmp; check_output "DLT\\x03 ADD decode ($l)" "hello world" $t \
            run $l decode /dev/null "$tmpdir/v3-add.delta" $t
        tmp; check_output "DLT\\x03 inplace decode ($l)" "abcabcabc" $t \
            run $l decode "$tmpdir/v3-inplace-ref.bin" "$tmpdir/v3-inplace.delta" $t
    done
else
    echo "  (skipped: python3 not found)"
fi

section "DLT\\x04 cross-language decode"

if $have_fixtures; then
    for l in C Rust Go C++ Java; do
        have_all $l || continue
        tmp; check_output "DLT\\x04 ADD decode ($l)" "hello world" $t \
            run $l decode /dev/null "$tmpdir/v4-add.delta" $t
        tmp; check_output "DLT\\x04 MOVE decode ($l)" "hellohello" $t \
            run $l decode /dev/null "$tmpdir/v4-move.delta" $t
        tmp; check_output "DLT\\x04 BIGADD decode ($l)" "hello world" $t \
            run $l decode /dev/null "$tmpdir/v4-bigadd.delta" $t
        tmp; check_output "DLT\\x04 BIGMOVE decode ($l)" "hellohello" $t \
            run $l decode /dev/null "$tmpdir/v4-bigmove.delta" $t
        tmp; check_output "DLT\\x04 BIGCOPY decode ($l)" "hello world" $t \
            run $l decode "$tmpdir/hello-ref.bin" "$tmpdir/v4-bigcopy.delta" $t
        check_fails "DLT\\x05 magic rejected ($l)" \
            run $l decode /dev/null "$tmpdir/v4-bad.delta" /dev/null
    done
else
    echo "  (skipped: python3 not found)"
fi

section "CRC mismatch rejection"

# Rejection alone could have another cause, so each damaged delta must also
# decode correctly once --ignore-hash is given.
crc_ref="$tmpdir/crc-ref.bin"
crc_ver="$tmpdir/crc-ver.bin"

if $have_fixtures; then
    for l in C Rust Go C++ Java Python; do
        have_all $l || continue
        tmp
        check_fails "src_crc mismatch rejected ($l)" \
            run $l decode "$crc_ref" "$tmpdir/crc-bad-src.delta" /dev/null
        check_fails "dst_crc mismatch rejected ($l)" \
            run $l decode "$crc_ref" "$tmpdir/crc-bad-dst.delta" $t
        tmp
        quiet run $l decode "$crc_ref" "$tmpdir/crc-bad-src.delta" $t --ignore-hash
        check "--ignore-hash bypasses src_crc ($l)" cmp -s "$crc_ver" $t
        tmp
        quiet run $l decode "$crc_ref" "$tmpdir/crc-bad-dst.delta" $t --ignore-hash
        check "--ignore-hash bypasses dst_crc ($l)" cmp -s "$crc_ver" $t
    done
else
    echo "  (skipped: python3 not found)"
fi

section "--large flag: force 64-bit BIGCOPY/BIGADD commands"

# The DLT\x04 header is 29 bytes, so byte 29 is the type of the first
# command: with --large it must be BIGCOPY (03), BIGADD (04) or BIGMOVE (06).
check_big_cmd() {
    local desc="$1" delta_file="$2" cmd_byte
    cmd_byte=$(od -An -tx1 -j29 -N1 "$delta_file" 2>/dev/null | tr -d ' \n')
    case "$cmd_byte" in
        03|04|06) pass "$desc" ;;
        *)        fail "$desc (first command byte is 0x$cmd_byte)" ;;
    esac
}

# large_delta L and large_ip_delta L name the deltas, kept for the next
# section.
large_delta()    { echo "$tmpdir/large-$1.delta"; }
large_ip_delta() { echo "$tmpdir/large-ip-$1.delta"; }

for l in C Go Rust C++ Python Java; do
    have_all $l || continue
    d=$(large_delta $l)
    run $l encode greedy "$ref" "$ver" "$d" --large
    check_big_cmd "$l --large: first cmd is BIGCOPY/BIGADD" "$d"
    tmp
    run $l decode "$ref" "$d" $t
    check "$l --large roundtrip" cmp -s "$ver" $t

    d=$(large_ip_delta $l)
    run $l encode greedy "$ref" "$ver" "$d" --inplace --large
    check_big_cmd "$l --inplace --large: first cmd is BIGCOPY/BIGADD" "$d"
    tmp
    run $l decode "$ref" "$d" $t
    check "$l --inplace --large roundtrip" cmp -s "$ver" $t
done

section "--large cross-language interop"

for langs in "C Go" "Go Rust" "Rust C++" "Java C" "Python C"; do
    set -- $langs
    have_all $1 $2 || continue
    tmp
    run $2 decode "$ref" "$(large_delta $1)" $t
    check "--large: $1 encode -> $2 decode" cmp -s "$ver" $t
done
if have_all Go; then
    tmp
    run Go decode "$ref" "$(large_ip_delta C)" $t
    check "--large inplace: C encode -> Go decode" cmp -s "$ver" $t
fi

echo ""
echo "========================================"
printf "Results: %d passed, %d failed\n" "$PASS" "$FAIL"
echo "========================================"

[ "$FAIL" -eq 0 ]
