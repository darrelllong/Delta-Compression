#!/usr/bin/env bash
#
# correctness.sh — Run all unit and cross-language compatibility tests
#
# Builds every implementation, runs its unit/integration test suite, then
# runs the cross-language compatibility tests in src/c/test_delta.sh.
#
# Usage:
#   ./tests/correctness.sh           # from repo root or tests/
#
# Exit status: 0 if all suites pass, 1 if any fail.
#
# Suites run:
#   Python  — 236 unit tests  (python3 -m unittest)
#   Rust    — 89 tests        (cargo test)
#   C++     — 93 checks       (ctest)
#   C       — 230 checks      (test_delta.sh)
#   Java    — 73 unit tests   (make test)
#   Go      — 81 tests        (go test)
#   Cross   — cross-language byte-identical encode/decode (src/c/test_delta.sh)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

PASS_SUITES=0
FAIL_SUITES=0

# ── Locate Java 17 toolchain ───────────────────────────────────────────────
# Prefer the Homebrew openjdk@17 install; fall back to PATH.  Derive JAVAC
# from the same prefix as JAVA so both point at the same JDK.

_JAVA_BIN=/opt/homebrew/opt/openjdk@17/bin/java
if [[ ! -x "$_JAVA_BIN" ]]; then
    _JAVA_BIN=$(command -v java 2>/dev/null || true)
fi
_JAVAC_BIN="${_JAVA_BIN%java}javac"
if [[ -z "$_JAVA_BIN" || ! -x "$_JAVA_BIN" || ! -x "$_JAVAC_BIN" ]]; then
    _JAVA_BIN=""
    _JAVAC_BIN=""
fi

# ── helpers ───────────────────────────────────────────────────────────────────

banner()    { echo ""; echo "════════════════════════════════════════"; echo "  $*"; echo "════════════════════════════════════════"; }

run_suite() {
    local name="$1"; shift
    if "$@"; then
        echo "  PASSED: $name"
        PASS_SUITES=$((PASS_SUITES + 1))
    else
        echo "  FAILED: $name"
        FAIL_SUITES=$((FAIL_SUITES + 1))
    fi
}

# ── Python ────────────────────────────────────────────────────────────────────

banner "Python (236 tests)"
run_suite "Python unit tests" \
    bash -c "cd '$REPO_ROOT/src/python' && python3 -m unittest test_delta -v"

# ── Rust ──────────────────────────────────────────────────────────────────────

banner "Rust (89 tests)"
run_suite "Rust tests" \
    bash -c "cd '$REPO_ROOT/src/rust/delta' && cargo test"

# ── C++ ───────────────────────────────────────────────────────────────────────

banner "C++ (93 checks)"
run_suite "C++ build + ctest" \
    bash -c "cd '$REPO_ROOT/src/cpp' && cmake -B build -DCMAKE_BUILD_TYPE=Release -DCMAKE_BUILD_PARALLEL_LEVEL=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4) 2>&1 | tail -3 && cmake --build build --parallel && ctest --test-dir build --output-on-failure"

# ── C ─────────────────────────────────────────────────────────────────────────

banner "C (230 checks)"
run_suite "C build + integration tests" \
    bash -c "cd '$REPO_ROOT/src/c' && make && bash test_delta.sh"

# ── Java ──────────────────────────────────────────────────────────────────────

banner "Java (73 tests)"
if [[ -n "$_JAVA_BIN" ]]; then
    run_suite "Java build + unit tests" \
        bash -c "cd '$REPO_ROOT/src/java' && make test JAVA='$_JAVA_BIN' JAVAC='$_JAVAC_BIN'"
else
    echo "  SKIPPED: Java (no compatible JDK 17 javac found)"
    FAIL_SUITES=$((FAIL_SUITES + 1))
fi

# ── Go ────────────────────────────────────────────────────────────────────────

banner "Go (81 tests)"
run_suite "Go tests" \
    bash -c "cd '$REPO_ROOT/src/go' && go test ./delta/..."

# ── Cross-language compatibility ───────────────────────────────────────────────

banner "Cross-language compatibility"
echo "  (encode with each implementation, decode with every other)"
run_suite "Cross-language (via src/c/test_delta.sh)" \
    bash -c "cd '$REPO_ROOT/src/c' && bash test_delta.sh"

# ── Summary ───────────────────────────────────────────────────────────────────

echo ""
echo "════════════════════════════════════════"
printf "  Suites passed: %d / %d\n" "$PASS_SUITES" "$((PASS_SUITES + FAIL_SUITES))"
echo "════════════════════════════════════════"
echo ""

if [ "$FAIL_SUITES" -gt 0 ]; then
    exit 1
fi
