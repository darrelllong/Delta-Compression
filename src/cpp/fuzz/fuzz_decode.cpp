// libFuzzer target: decode_delta on arbitrary bytes.  Malformed input must
// be rejected with DeltaError; any other exception, a crash or a sanitizer
// report is a bug.
// See CMakeLists.txt in this directory for how to build and run.

#include <cstdint>
#include <span>

#include <delta/delta.h>

using namespace delta;

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
    try {
        (void)decode_delta(std::span<const uint8_t>(data, size));
    } catch (const DeltaError&) {
    }
    return 0;
}
