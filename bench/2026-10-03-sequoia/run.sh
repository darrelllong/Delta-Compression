#!/bin/bash
# bench-sequoia.sh <tree> <label>: run both Pilot suites for /mnt/delta/<tree>,
# P-cores only (CPUs 0-7 are the four hyperthreaded P-cores), performance
# governor for the duration, restored on exit.
set -u
tree=/mnt/delta/$1; out=/mnt/delta/results/$2; mkdir -p "$out"; cd "$out"
export PILOT_BENCH_CLI=/mnt/delta/pilot-bench/build/cli/bench WORKDIR=/mnt/delta/data
gov() { for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do echo "$1" | sudo tee "$f" >/dev/null; done; }
old=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor)
trap 'gov "$old"' EXIT
gov performance
{ date -Is; uptime; } > start.txt
taskset -c 2 bash "$tree/tests/bench_rust.sh" > bench_rust.md 2> bench_rust.stderr
PIN_SINGLE=2 PIN_MULTI=0-7 bash "$tree/tests/bench_all.sh" > bench_all.md 2> bench_all.stderr
{ date -Is; uptime; } > end.txt
cat bench_rust.md bench_all.md; cat bench_rust.stderr bench_all.stderr | tail -5
