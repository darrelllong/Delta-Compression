#!/usr/bin/env bash
# Pilot-bench workload: one encode, file to file, by one implementation.
#
# Usage: pilot_lang.sh <lang> <algo>
#   lang: Python | Rust | Cpp | C | Java | Go
#   algo: greedy | onepass | correcting
#
# Prints one number: the size of the reference in MiB divided by the
# wall-clock seconds of the whole encode command, which include process
# startup and file I/O.  Pilot-bench runs this script repeatedly until the
# confidence interval is narrow enough.
#
# The reference and version are $PILOT_REF and $PILOT_VER, which bench_all.sh
# sets to Shakespeare and its 5% mutation; otherwise the kernel tarballs
# linux-5.1.tar and linux-5.1.1.tar in WORKDIR (default
# /tmp/delta-kernel-test).  The implementation must already be built.
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

REF="${PILOT_REF:-$WORKDIR/linux-5.1.tar}"
VER="${PILOT_VER:-$WORKDIR/linux-5.1.1.tar}"

if [[ ! -f "$REF" || ! -f "$VER" ]]; then
    echo "Input files missing: $REF / $VER" >&2
    echo "Run bench_all.sh (generates synthetic pair) or" >&2
    echo "tests/per-language-benchmark.sh (downloads kernel tarballs)." >&2
    exit 1
fi

JAVA=$(command -v java 2>/dev/null || true)

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
    *)
        echo "unknown language: $LANG_ARG" >&2
        exit 1
        ;;
esac

# sys.argv[1:] is the encode command; it ends <algo> <ref> <ver> <delta>, so
# sys.argv[-3] is the reference.
python3 - "${CMD[@]}" "$ALGO" "$REF" "$VER" "$DELTA_TMP" <<'PYEOF'
import sys, subprocess, time, os
t0 = time.perf_counter()
# A failed encode would otherwise be timed as a fast one.
subprocess.run(sys.argv[1:], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
elapsed = time.perf_counter() - t0
ref_mib = os.path.getsize(sys.argv[-3]) / (1024.0 * 1024.0)
print(f"{ref_mib / elapsed:.6f}")
PYEOF
