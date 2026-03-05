#!/usr/bin/env bash
# Pilot-bench workload: one file-encode operation for one language implementation.
#
# Usage: pilot_lang.sh <lang> <algo>
#   lang: Python | Rust | Cpp | C | Java | Go | Kotlin | Scala | Haskell
#   algo: onepass | correcting
#
# Prints MiB/s (ref file size ÷ elapsed encode time) to stdout.
# Pilot-bench calls this repeatedly until statistical confidence is reached.
#
# REF/VER are resolved in order:
#   1. $PILOT_REF / $PILOT_VER  (set by bench_all.sh — 10 MiB synthetic pair)
#   2. $WORKDIR/linux-5.1.tar and linux-5.1.1.tar  (kernel tarball fallback)
set -euo pipefail

LANG_ARG="${1:-}"
ALGO="${2:-}"

if [[ -z "$LANG_ARG" || -z "$ALGO" ]]; then
    echo "usage: pilot_lang.sh <lang> <algo>" >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKDIR="${WORKDIR:-/tmp/delta-kernel-test}"
DELTA_TMP="$WORKDIR/pilot-${LANG_ARG}-${ALGO}.delta"

# Resolve input files: synthetic pair (from bench_all.sh) or kernel tarballs.
REF="${PILOT_REF:-$WORKDIR/linux-5.1.tar}"
VER="${PILOT_VER:-$WORKDIR/linux-5.1.1.tar}"

if [[ ! -f "$REF" || ! -f "$VER" ]]; then
    echo "Input files missing: $REF / $VER" >&2
    echo "Run bench_all.sh (generates synthetic pair) or" >&2
    echo "tests/per-language-benchmark.sh (downloads kernel tarballs)." >&2
    exit 1
fi

# ── Locate Java ───────────────────────────────────────────────────────────────

JAVA=/opt/homebrew/opt/openjdk@17/bin/java
if [[ ! -x "$JAVA" ]]; then
    JAVA=$(command -v java 2>/dev/null || true)
fi

# ── Locate Scala library ──────────────────────────────────────────────────────

SCALA_LIB=""
if [[ -f "$REPO_ROOT/src/scala/Makefile" ]]; then
    SCALA_LIB=$(grep 'SCALA_LIB\s*=' "$REPO_ROOT/src/scala/Makefile" | head -1 | sed 's/.*= *//')
fi

# ── Resolve command ───────────────────────────────────────────────────────────

case "$LANG_ARG" in
    Python)
        CMD=(python3 "$REPO_ROOT/src/python/delta.py" encode)
        ;;
    Rust)
        CMD=("$REPO_ROOT/src/rust/delta/target/release/delta" encode)
        ;;
    Cpp)
        CMD=("$REPO_ROOT/src/cpp/build/delta" encode)
        ;;
    C)
        CMD=("$REPO_ROOT/src/c/delta" encode)
        ;;
    Java)
        if [[ -z "$JAVA" || ! -x "$JAVA" ]]; then
            echo "Java not found" >&2; exit 1
        fi
        CMD=("$JAVA" -cp "$REPO_ROOT/src/java/out" delta.Delta encode)
        ;;
    Go)
        CMD=("$REPO_ROOT/src/go/delta/delta" encode)
        ;;
    Kotlin)
        if [[ -z "$JAVA" || ! -x "$JAVA" ]]; then
            echo "Java not found (needed for Kotlin)" >&2; exit 1
        fi
        CMD=("$JAVA" -cp "$REPO_ROOT/src/kotlin/delta.jar" delta.Delta encode)
        ;;
    Scala)
        if [[ -z "$JAVA" || ! -x "$JAVA" ]]; then
            echo "Java not found (needed for Scala)" >&2; exit 1
        fi
        if [[ -z "$SCALA_LIB" || ! -f "$SCALA_LIB" ]]; then
            echo "Scala library not found" >&2; exit 1
        fi
        CMD=("$JAVA" -cp "$REPO_ROOT/src/scala/delta.jar:$SCALA_LIB" delta.Delta encode)
        ;;
    Haskell)
        CMD=("$REPO_ROOT/src/haskell/delta-hs" encode)
        ;;
    *)
        echo "unknown language: $LANG_ARG" >&2
        exit 1
        ;;
esac

# ── Time one encode and print MiB/s ──────────────────────────────────────────
# Args layout passed to the inner script:
#   sys.argv[1:] = full command, ending with: <algo> <ref> <ver> <delta>
# So sys.argv[-3] = REF, sys.argv[-2] = VER, sys.argv[-1] = DELTA.

python3 - "${CMD[@]}" "$ALGO" "$REF" "$VER" "$DELTA_TMP" <<'PYEOF'
import sys, subprocess, time, os
t0 = time.perf_counter()
subprocess.run(sys.argv[1:], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
elapsed = time.perf_counter() - t0
ref_mib = os.path.getsize(sys.argv[-3]) / (1024.0 * 1024.0)
print(f"{ref_mib / elapsed:.6f}")
PYEOF
