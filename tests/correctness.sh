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
#   Python  — 180 unit tests  (python3 -m unittest)
#   Rust    — 67 tests        (cargo test)
#   C++     — 55 tests        (ctest)
#   C       — 45 tests        (test_delta.sh)
#   Java    — 43 unit tests   (make test)
#   Cross   — cross-language byte-identical encode/decode (src/c/test_delta.sh)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

PASS_SUITES=0
FAIL_SUITES=0

# ── helpers ───────────────────────────────────────────────────────────────────

banner() { echo ""; echo "════════════════════════════════════════"; echo "  $*"; echo "════════════════════════════════════════"; }

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

banner "Python (180 tests)"
run_suite "Python unit tests" \
    bash -c "cd '$REPO_ROOT/src/python' && python3 -m unittest test_delta -v"

# ── Rust ──────────────────────────────────────────────────────────────────────

banner "Rust (67 tests)"
run_suite "Rust tests" \
    bash -c "cd '$REPO_ROOT/src/rust/delta' && cargo test"

# ── C++ ───────────────────────────────────────────────────────────────────────

banner "C++ (55 tests)"
run_suite "C++ build + ctest" \
    bash -c "cd '$REPO_ROOT/src/cpp' && cmake -B build -DCMAKE_BUILD_TYPE=Release -DCMAKE_BUILD_PARALLEL_LEVEL=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4) 2>&1 | tail -3 && cmake --build build --parallel && ctest --test-dir build --output-on-failure"

# ── C ─────────────────────────────────────────────────────────────────────────

banner "C (45 tests)"
run_suite "C build + integration tests" \
    bash -c "cd '$REPO_ROOT/src/c' && make && bash test_delta.sh"

# ── Java ──────────────────────────────────────────────────────────────────────

banner "Java (43 tests)"
run_suite "Java build + unit tests" \
    bash -c "cd '$REPO_ROOT/src/java' && make test"

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
