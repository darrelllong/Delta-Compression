// libFuzzer target: diff, encode, decode and apply must reproduce the
// version exactly, and a delta this library encoded must decode without
// error.
//
// The input is split into a reference and a version.  Its first byte says
// where: split = 1 + first * (size - 1) / 256, the reference is
// data[1, split) and the version is data[split, size).  Inputs are limited
// to 4 KiB because the greedy algorithm is quadratic.
// See CMakeLists.txt in this directory for how to build and run.

#include <algorithm>
#include <cstdint>
#include <span>
#include <vector>

#include <delta/delta.h>

using namespace delta;

// Unlike assert, this is not compiled out of an optimized build.
static void require(bool ok) {
    if (!ok) __builtin_trap();
}

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
    if (size < 2 || size > 4096) return 0;

    size_t split = 1 + (static_cast<size_t>(data[0]) * (size - 1)) / 256;
    split = std::min(split, size);

    std::span<const uint8_t> ref(data + 1, split - 1);
    std::span<const uint8_t> ver(data + split, size - split);

    try {
        auto cmds    = diff_greedy(ref, ver);
        auto placed  = place_commands(cmds);
        auto src_crc = crc64_xz(ref.data(), ref.size());
        auto dst_crc = crc64_xz(ver.data(), ver.size());
        auto encoded = encode_delta_large(placed, false, ver.size(), src_crc, dst_crc);

        auto [placed2, is_ip, vsize, sc2, dc2] = decode_delta(encoded);

        require(sc2 == src_crc);
        require(dc2 == dst_crc);

        std::vector<uint8_t> out(vsize, 0);
        apply_placed_to(ref, placed2, out);
        require(out.size() == ver.size());
        require(std::equal(out.begin(), out.end(), ver.begin(), ver.end()));
    } catch (const DeltaError&) {
        __builtin_trap();
    }
    return 0;
}
