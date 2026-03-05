#!/usr/bin/env bash
# Download Shakespeare's complete works (PG #100) and create mutated versions
# for use as benchmark data.  Files are cached in WORKDIR.
#
# Output files (all in $WORKDIR):
#   shakespeare.txt           — original, ~5.5 MB
#   shakespeare-1pct.txt      — 1% single-byte substitutions
#   shakespeare-2pct.txt      — 2%
#   shakespeare-5pct.txt      — 5%
#   shakespeare-10pct.txt     — 10%
#   shakespeare-20pct.txt     — 20%
#
# Mutations are applied at the byte level using a deterministic seed so
# results are reproducible.  The mutated files are binary but differ from
# the original only in ~N% of bytes, giving realistic delta structure.
set -euo pipefail

WORKDIR="${WORKDIR:-/tmp/delta-kernel-test}"
mkdir -p "$WORKDIR"

SRC="$WORKDIR/shakespeare.txt"
URL="https://www.gutenberg.org/cache/epub/100/pg100.txt"

# ── Download ──────────────────────────────────────────────────────────────────

if [[ -f "$SRC" ]]; then
    echo "shakespeare.txt (cached, $(wc -c < "$SRC" | tr -d ' ') bytes)"
else
    echo "Downloading Shakespeare's complete works from Project Gutenberg..."
    curl -sfL -o "$SRC" "$URL"
    echo "Downloaded: $(wc -c < "$SRC" | tr -d ' ') bytes"
fi

# ── Generate mutated versions ─────────────────────────────────────────────────

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
