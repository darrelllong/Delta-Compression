#!/usr/bin/env bash
# Download Shakespeare's complete works (Project Gutenberg #100) and derive
# mutated versions of it, for use as benchmark data.
#
# Usage: ./tests/get_shakespeare.sh
# Requirements: curl, python3.
#
# Output files, in WORKDIR (default /tmp/delta-kernel-test):
#   shakespeare.txt           the original, ~5.4 MB
#   shakespeare-1pct.txt      1% of the bytes overwritten
#   shakespeare-2pct.txt      2%
#   shakespeare-5pct.txt      5%
#   shakespeare-10pct.txt     10%
#   shakespeare-20pct.txt     20%
#
# A file that already exists is kept.  For N%, that fraction of the byte
# positions is chosen without replacement and each is overwritten with a
# random byte, so the result is no longer text and about 1 in 256 of the
# chosen bytes is unchanged.  The generator is seeded from N, so the files
# are the same on every run.
set -euo pipefail

WORKDIR="${WORKDIR:-/tmp/delta-kernel-test}"
mkdir -p "$WORKDIR"

SRC="$WORKDIR/shakespeare.txt"
URL="https://www.gutenberg.org/cache/epub/100/pg100.txt"

if [[ -f "$SRC" ]]; then
    echo "shakespeare.txt (cached, $(wc -c < "$SRC" | tr -d ' ') bytes)"
else
    echo "Downloading Shakespeare's complete works from Project Gutenberg..."
    curl -sfL -o "$SRC" "$URL"
    echo "Downloaded: $(wc -c < "$SRC" | tr -d ' ') bytes"
fi

python3 - "$SRC" "$WORKDIR" <<'PYEOF'
import sys, random, os

src_path = sys.argv[1]
outdir   = sys.argv[2]

with open(src_path, 'rb') as f:
    data = bytearray(f.read())

size = len(data)

for pct in [1, 2, 5, 10, 20]:
    dst = os.path.join(outdir, f"shakespeare-{pct}pct.txt")
    if os.path.exists(dst):
        print(f"  shakespeare-{pct}pct.txt (cached)")
        continue
    rng = random.Random(0xDEAD_BEEF_0000 | pct)
    n = size * pct // 100
    mut = bytearray(data)
    for pos in sorted(rng.sample(range(size), n)):
        mut[pos] = rng.getrandbits(8)
    with open(dst, 'wb') as f:
        f.write(bytes(mut))
    print(f"  shakespeare-{pct}pct.txt  ({n} edits, {pct}%)")
PYEOF

echo ""
echo "Benchmark data ready in $WORKDIR"
