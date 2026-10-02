#!/usr/bin/env bash
# Writes seed inputs for the fuzz targets into a corpus directory.
#
# Usage: bash fuzz/gen_corpus.sh fuzz/corpus
set -euo pipefail

OUT="${1:-corpus}"
mkdir -p "$OUT"

# A valid DLT\x03 delta from empty to empty: the 25-byte header, then END.
printf '\x44\x4c\x54\x03\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00' \
    > "$OUT/seed_v3_empty.delta"

# The same in DLT\x04, whose header is 29 bytes.
printf '\x44\x4c\x54\x04\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00' \
    > "$OUT/seed_v4_empty.delta"

# Truncated after the magic.
printf '\x44\x4c\x54\x03'                > "$OUT/seed_just_magic_v3"
printf '\x44\x4c\x54\x04'                > "$OUT/seed_just_magic_v4"

# Not a delta.
printf '\xDE\xAD\xBE\xEF'               > "$OUT/seed_bad_magic"
printf ''                                > "$OUT/seed_empty"

echo "Wrote $(ls "$OUT" | wc -l | tr -d ' ') corpus seeds to $OUT/"
