# Fuzz targets — libFuzzer, requires Clang.
#
# Enable with:
#   cmake -DENABLE_FUZZING=ON -DCMAKE_CXX_COMPILER=clang++ \
#         -DCMAKE_BUILD_TYPE=RelWithDebInfo -B build-fuzz ..
#   cmake --build build-fuzz
#
# Run decode fuzzer:
#   mkdir -p fuzz/corpus fuzz/crashes
#   # Seed corpus (one valid V3 and one valid V4 delta for empty→empty):
#   bash fuzz/gen_corpus.sh fuzz/corpus
#   ./build-fuzz/fuzz/fuzz_decode fuzz/corpus/ -max_total_time=300 \
#       -artifact_prefix=fuzz/crashes/ -jobs=$(nproc)
#
# Run roundtrip fuzzer:
#   ./build-fuzz/fuzz/fuzz_roundtrip fuzz/corpus/ -max_total_time=300 \
#       -artifact_prefix=fuzz/crashes/ -jobs=$(nproc)

if(NOT CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    message(WARNING
        "Fuzzing requires Clang with libFuzzer support; "
        "ENABLE_FUZZING has no effect with ${CMAKE_CXX_COMPILER_ID}")
    return()
endif()

set(FUZZ_FLAGS -fsanitize=fuzzer,address,undefined)

foreach(target fuzz_decode fuzz_roundtrip)
    add_executable(${target} ${target}.cpp)
    target_link_libraries(${target} PRIVATE delta_lib)
    target_compile_options(${target} PRIVATE ${FUZZ_FLAGS})
    target_link_options(${target} PRIVATE ${FUZZ_FLAGS})
endforeach()
