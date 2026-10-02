#!/usr/bin/env bash
# Write seed inputs for fuzz_decode into a directory.
#
# Usage: bash fuzz/gen_corpus.sh fuzz/corpus
set -euo pipefail

OUT="${1:-corpus}"
mkdir -p "$OUT"

# zeros N: N zero bytes.
zeros() { head -c "$1" /dev/zero; }

# Valid deltas from an empty reference to an empty version: magic, flags,
# version size (u32 or u64), two CRCs of 8 bytes, END.  The CRC-64/XZ of the
# empty string is zero, so all that follows the magic is zero.
{ printf 'DLT\003'; zeros 22; } > "$OUT/seed_v3_empty.delta"
{ printf 'DLT\004'; zeros 26; } > "$OUT/seed_v4_empty.delta"

# The magic and nothing else.
printf 'DLT\003' > "$OUT/seed_just_magic_v3"
printf 'DLT\004' > "$OUT/seed_just_magic_v4"

printf '\336\255\276\357' > "$OUT/seed_bad_magic"
: > "$OUT/seed_empty"

echo "Wrote $(ls "$OUT" | wc -l | tr -d ' ') corpus seeds to $OUT/"
