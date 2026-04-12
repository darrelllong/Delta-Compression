// fuzz_roundtrip.cpp — libFuzzer round-trip target.
//
// Splits the fuzz input into a (reference, version) pair, runs the full
// encode → decode → apply pipeline, and asserts the reconstructed output
// exactly matches the version.  Any encode/decode mismatch is a bug; any
// unexpected DeltaError on the encode output is also a bug.
//
// Input layout: [split_byte | reference... | version...]
//   split_byte: 0-255; split = 1 + (split_byte * (size-1)) / 256
//
// Greedy is used because it is O(|ref|×|ver|); inputs are capped at 4 KiB
// to keep each iteration fast enough for libFuzzer's throughput target.
//
// Build/run: same flags as fuzz_decode.

#include <cassert>
#include <cstdint>
#include <cstdlib>
#include <span>
#include <vector>

#include <delta/delta.h>

using namespace delta;

extern "C" int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    // Keep greedy tractable.
    if (size < 2 || size > 4096) return 0;

    // Partition: first byte drives the split ratio.
    size_t split = 1 + (static_cast<size_t>(data[0]) * (size - 1)) / 256;
    split = std::min(split, size);

    std::span<const uint8_t> ref(data + 1, split - 1);
    std::span<const uint8_t> ver(data + split, size - split);

    try {
        // Encode
        auto cmds    = diff_greedy(ref, ver);
        auto placed  = place_commands(cmds);
        auto src_crc = crc64_xz(ref.data(), ref.size());
        auto dst_crc = crc64_xz(ver.data(), ver.size());
        auto encoded = encode_delta_large(placed, false, ver.size(), src_crc, dst_crc);

        // Decode
        auto [placed2, is_ip, vsize, sc2, dc2] = decode_delta(encoded);

        // CRC metadata must round-trip exactly.
        assert(sc2 == src_crc);
        assert(dc2 == dst_crc);

        // Apply and compare byte-for-byte.
        std::vector<uint8_t> out(vsize, 0);
        apply_placed_to(ref, placed2, out);
        assert(out.size() == ver.size());
        assert(std::equal(out.begin(), out.end(), ver.begin(), ver.end()));
    } catch (const DeltaError &) {
        // Thrown on a self-encoded delta — a definite bug.
        __builtin_trap();
    }
    return 0;
}
