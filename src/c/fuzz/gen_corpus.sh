#!/usr/bin/env bash
# gen_corpus.sh — Write minimal seed files into a corpus directory.
#
# Usage: bash fuzz/gen_corpus.sh fuzz/corpus
#
# Produces:
#   seed_v3_empty.delta  — valid DLT\x03 delta for empty→empty
#   seed_v4_empty.delta  — valid DLT\x04 delta for empty→empty
#   seed_just_magic_v3   — 4-byte magic only (truncated, triggers short-read)
#   seed_bad_magic       — 4 bytes of garbage magic
set -euo pipefail

OUT="${1:-corpus}"
mkdir -p "$OUT"

# DLT\x03 empty→empty (25 bytes header + 1 byte END):
#   magic(4) + flags(1=0x00) + version_size_u32_BE(4=0) + src_crc(8=0) + dst_crc(8=0) + END(1=0)
printf '\x44\x4c\x54\x03\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00' \
    > "$OUT/seed_v3_empty.delta"

# DLT\x04 empty→empty (29 bytes header + 1 byte END):
#   magic(4) + flags(1=0x00) + version_size_u64_BE(8=0) + src_crc(8=0) + dst_crc(8=0) + END(1=0)
printf '\x44\x4c\x54\x04\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00' \
    > "$OUT/seed_v4_empty.delta"

# Truncated: just the 4-byte magic (tests short-read path)
printf '\x44\x4c\x54\x03'                > "$OUT/seed_just_magic_v3"
printf '\x44\x4c\x54\x04'                > "$OUT/seed_just_magic_v4"

# Bad magic (tests rejection path fast)
printf '\xDE\xAD\xBE\xEF'               > "$OUT/seed_bad_magic"

# Empty input
printf ''                                > "$OUT/seed_empty"

echo "Wrote $(ls "$OUT" | wc -l | tr -d ' ') corpus seeds to $OUT/"
