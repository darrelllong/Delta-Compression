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
#   Python  — 208 unit tests  (python3 -m unittest)
#   Rust    — 56 tests        (cargo test)
#   C++     — 64 tests        (ctest)
#   C       — 45 tests        (test_delta.sh)
#   Java    — 52 unit tests   (make test)
#   Go      — 52 tests        (go test)
#   Kotlin  — 52 unit tests   (make test)
#   Scala   — 52 unit tests   (make test)
#   Haskell — build + roundtrip smoke test (make)
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

banner "Python (208 tests)"
run_suite "Python unit tests" \
    bash -c "cd '$REPO_ROOT/src/python' && python3 -m unittest test_delta -v"

# ── Rust ──────────────────────────────────────────────────────────────────────

banner "Rust (56 tests)"
run_suite "Rust tests" \
    bash -c "cd '$REPO_ROOT/src/rust/delta' && cargo test"

# ── C++ ───────────────────────────────────────────────────────────────────────

banner "C++ (64 tests)"
run_suite "C++ build + ctest" \
    bash -c "cd '$REPO_ROOT/src/cpp' && cmake -B build -DCMAKE_BUILD_TYPE=Release -DCMAKE_BUILD_PARALLEL_LEVEL=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4) 2>&1 | tail -3 && cmake --build build --parallel && ctest --test-dir build --output-on-failure"

# ── C ─────────────────────────────────────────────────────────────────────────

banner "C (45 tests)"
run_suite "C build + integration tests" \
    bash -c "cd '$REPO_ROOT/src/c' && make && bash test_delta.sh"

# ── Java ──────────────────────────────────────────────────────────────────────

banner "Java (52 tests)"
run_suite "Java build + unit tests" \
    bash -c "cd '$REPO_ROOT/src/java' && make test"

# ── Go ────────────────────────────────────────────────────────────────────────

banner "Go (52 tests)"
run_suite "Go tests" \
    bash -c "cd '$REPO_ROOT/src/go' && go test ./delta/..."

# ── Kotlin ────────────────────────────────────────────────────────────────────

banner "Kotlin (52 tests)"
run_suite "Kotlin build + unit tests" \
    bash -c "cd '$REPO_ROOT/src/kotlin' && make test"

# ── Scala ─────────────────────────────────────────────────────────────────────

banner "Scala (52 tests)"
run_suite "Scala build + unit tests" \
    bash -c "cd '$REPO_ROOT/src/scala' && make test"

# ── Haskell ──────────────────────────────────────────────────────────────────

banner "Haskell (build + smoke)"
run_suite "Haskell build + roundtrip smoke" \
    bash -c "cd '$REPO_ROOT/src/haskell' && make clean && make && tmp=\$(mktemp -d) && \
             printf 'AAAA BBBB CCCC\n' > \"\$tmp/ref.txt\" && \
             printf 'AAAA XXXX CCCC DDDD\n' > \"\$tmp/ver.txt\" && \
             ./delta-hs encode onepass \"\$tmp/ref.txt\" \"\$tmp/ver.txt\" \"\$tmp/std.delta\" >/dev/null && \
             ./delta-hs decode \"\$tmp/ref.txt\" \"\$tmp/std.delta\" \"\$tmp/std.out\" >/dev/null && \
             diff -q \"\$tmp/ver.txt\" \"\$tmp/std.out\" >/dev/null && \
             ./delta-hs encode correcting \"\$tmp/ref.txt\" \"\$tmp/ver.txt\" \"\$tmp/ip.delta\" --inplace >/dev/null && \
             ./delta-hs decode \"\$tmp/ref.txt\" \"\$tmp/ip.delta\" \"\$tmp/ip.out\" >/dev/null && \
             diff -q \"\$tmp/ver.txt\" \"\$tmp/ip.out\" >/dev/null && \
             rm -rf \"\$tmp\""

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
