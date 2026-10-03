#!/bin/bash
# remote-bench.sh HOST: run both Pilot suites on HOST with the current tree
# and bring the results back to $S/results/HOST.
#
# The tree is synced to ~/bench-2026-10-02/Delta-Compression on the host and
# built there.  Hosts without javac get the classes compiled here; hosts
# without Go get a cross-compiled binary.  Linux hosts pin the single-threaded
# implementations to one CPU and the JVM and Go to a CPU set.
set -euo pipefail
host=$1
S=${SCRATCH:?scratch directory holding data/ and results/}
REPO=/Users/darrell/Delta-Compression
R='~/bench-2026-10-02'
ssh_() { if [ "$host" = dyson ]; then bash -c "$*"; else ssh -o BatchMode=yes "$host" "$*"; fi; }

case $host in
    dyson) dir=$S/dyson; pin_single=; pin_multi=; taskset=; need_java=; need_go=;;
    dmz)    dir="$R/Delta-Compression"; pin_single=2; pin_multi=0-7; taskset="taskset -c 2"; need_java=; need_go=;;
    baase)  dir="$R/Delta-Compression"; pin_single=9; pin_multi=5-9,15-19; taskset="taskset -c 9"; need_java=yes; need_go=linux/arm64;;
esac

echo "== $host: sync"
if [ "$host" = dyson ]; then
    rm -rf "$dir"; mkdir -p "$dir"; rsync -a --exclude .git --exclude target --exclude build --exclude '*.o' --exclude __pycache__ "$REPO/" "$dir/"
    rsync -a --delete "$S/data/" "$dir/../dyson-data/"
    data="$dir/../dyson-data"
else
    ssh -o BatchMode=yes "$host" "mkdir -p $R/data"
    rsync -a --delete --exclude .git --exclude target --exclude build --exclude '*.o' --exclude __pycache__ \
        --exclude src/c/delta --exclude src/go/delta/delta --exclude src/c/test_overflow --exclude src/java/out \
        "$REPO/" "$host:$R/Delta-Compression/"
    rsync -a "$S/data/" "$host:$R/data/"
    data="$R/data"
fi
if [ -n "$need_java" ]; then
    (cd "$REPO/src/java" && make -s) && rsync -a "$REPO/src/java/out/" "$host:$R/Delta-Compression/src/java/out/"
fi
if [ -n "$need_go" ]; then
    (cd "$REPO/src/go" && GOOS=${need_go%/*} GOARCH=${need_go#*/} go build -o "$S/go-$host" ./cmd/delta)
    rsync -a "$S/go-$host" "$host:$R/Delta-Compression/src/go/delta/delta"
fi

echo "== $host: build"
ssh_ "set -e; cd $dir
    (cd src/c && make -s 2>&1 | grep -v '^$' | head -5 || true)
    (cd src/cpp && cmake -B build -DCMAKE_BUILD_TYPE=Release > /dev/null && cmake --build build --parallel 2>&1 | grep -iE 'warning|error' || true)
    (cd src/rust/delta && cargo build --release 2>&1 | tail -1)
    [ -n '$need_java' ] || (cd src/java && make -s)
    [ -n '$need_go' ] || (cd src/go && go build -o delta/delta ./cmd/delta)
    ls src/c/delta src/cpp/build/delta src/rust/delta/target/release/delta src/rust/delta/target/release/pilot_delta src/go/delta/delta src/java/out/delta/Delta.class > /dev/null
    for l in Rust C Cpp Java Go; do for a in onepass correcting; do
        PILOT_REF=$data/shakespeare.txt PILOT_VER=$data/shakespeare-5pct.txt WORKDIR=$data bash tests/pilot_lang.sh \$l \$a > /dev/null
    done; done
    (cd $data && (sha256sum pilot-*.delta 2>/dev/null || shasum -a 256 pilot-*.delta) | cut -c1-12 | sort | uniq -c)"

echo "== $host: run"
ssh_ "set -u; cd $dir; mkdir -p results; cd results
    export PILOT_BENCH_CLI=\$HOME/pilot-bench/build/cli/bench WORKDIR=$data
    { date -Is; uptime; } > start.txt
    $taskset bash ../tests/bench_rust.sh > bench_rust.md 2> bench_rust.stderr
    PIN_SINGLE=$pin_single PIN_MULTI=$pin_multi bash ../tests/bench_all.sh > bench_all.md 2> bench_all.stderr
    { date -Is; uptime; } > end.txt
    { echo '== uname'; uname -a; echo '== cpu'; (lscpu 2>/dev/null | grep -E 'Model name|^CPU\(s\)|MHz' || sysctl -n machdep.cpu.brand_string hw.ncpu); echo '== memory'; (free -h 2>/dev/null | sed -n 2p || sysctl -n hw.memsize); echo '== os'; (grep PRETTY /etc/os-release 2>/dev/null || sw_vers); echo '== compilers'; cc --version | head -1; c++ --version | head -1; rustc --version; java -version 2>&1 | head -1; (javac -version 2>&1 || echo 'javac: classes compiled on dyson with javac 19.0.1 --release 17'); (go version || echo 'go: cross-compiled on dyson with go1.27.1'); python3 --version; cmake --version | head -1; echo '== pilot-bench'; git -C \$HOME/pilot-bench rev-parse --short HEAD; echo '== governor'; cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo n/a; } > host.txt 2>&1
    cat bench_rust.md bench_all.md; tail -n 3 bench_rust.stderr bench_all.stderr"
mkdir -p "$S/results/$host"
if [ "$host" = dyson ]; then cp "$dir"/results/*.{md,txt} "$S/results/$host/"; else rsync -a --include='*.md' --include='*.txt' --exclude='*' "$host:$R/Delta-Compression/results/" "$S/results/$host/"; fi
echo "== $host: done"
