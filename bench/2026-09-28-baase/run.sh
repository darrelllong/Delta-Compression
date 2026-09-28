#!/bin/bash
# Rate sessions (MB/s, MiB/s; Pilot type 1) with Pilot 475063f.
set -u
export PILOT_BENCH_CLI=$HOME/pilot-bench/build/cli/bench
echo "pilot-bench $(cd ~/pilot-bench && git rev-parse --short HEAD)" > $HOME/rerun/rates-pilot.txt
# cryptography, symmetric and hash
S=$HOME/rerun/sweep-2026-09-28; mkdir -p $S/rates/work; cd $S/rates/work
export PILOT_CIPHER_BIN=$HOME/rerun/cryptography/target/release/pilot_cipher
export PILOT_HASH_BIN=$HOME/rerun/cryptography/target/release/pilot_hash
R=$HOME/rerun/cryptography/scripts
date -Is > $S/rates/start.txt; uptime >> $S/rates/start.txt
PILOT_PRESET=normal PILOT_CONFIDENCE_LEVEL=0.90 PILOT_SESSION_LIMIT=300 /usr/bin/time -f "%e s" -o $S/rates/symmetric.time taskset -c 9 bash $R/bench_all.sh > $S/rates/symmetric.md 2> $S/rates/symmetric.stderr
PILOT_PRESET=normal PILOT_CONFIDENCE_LEVEL=0.90 PILOT_SESSION_LIMIT=300 /usr/bin/time -f "%e s" -o $S/rates/hash.time taskset -c 9 bash $R/bench_all_hash.sh > $S/rates/hash.md 2> $S/rates/hash.stderr
date -Is > $S/rates/end.txt; uptime >> $S/rates/end.txt
# Delta-Compression
D=$HOME/rerun/delta-sweep-2026-09-28; cd $D/work
export WORKDIR=$HOME/rerun/delta-data
DR=$HOME/rerun/Delta-Compression
date -Is > $D/start.txt; uptime >> $D/start.txt
/usr/bin/time -f "%e s" -o $D/rust.time taskset -c 9 bash $DR/tests/bench_rust.sh > $D/bench_rust.md 2> $D/bench_rust.stderr
PIN_SINGLE=9 PIN_MULTI=5-9,15-19 /usr/bin/time -f "%e s" -o $D/all.time bash $DR/tests/bench_all.sh > $D/bench_all.md 2> $D/bench_all.stderr
date -Is > $D/end.txt; uptime >> $D/end.txt
touch $HOME/rerun/rates.done
