// fuzz_decode.cpp — libFuzzer target: feed arbitrary bytes to decode_delta().
//
// Invariant: decode_delta() must never crash regardless of input.  It may
// throw DeltaError for malformed data; that is the expected rejection path.
// Any other exception or abort is a bug.
//
// Build:
//   cmake -DENABLE_FUZZING=ON -DCMAKE_CXX_COMPILER=clang++ -B build-fuzz ..
//   cmake --build build-fuzz
//
// Run (60 s, 4 parallel jobs):
//   mkdir -p corpus crashes
//   ./build-fuzz/fuzz/fuzz_decode corpus/ -max_total_time=60 -jobs=4 \
//       -artifact_prefix=crashes/

#include <cstdint>
#include <cstdlib>
#include <span>

#include <delta/delta.h>

using namespace delta;

extern "C" int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    try {
        auto [placed, is_ip, version_size, src_crc, dst_crc] =
            decode_delta(std::span<const uint8_t>(data, size));
        (void)placed;
        (void)is_ip;
        (void)version_size;
        (void)src_crc;
        (void)dst_crc;
    } catch (const DeltaError &) {
        // Expected: malformed input should throw, not crash.
    }
    return 0;
}
