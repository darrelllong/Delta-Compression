# libFuzzer targets.  They need Clang.
#
#   cmake -B build-fuzz -DENABLE_FUZZING=ON -DCMAKE_CXX_COMPILER=clang++ \
#         -DCMAKE_BUILD_TYPE=RelWithDebInfo
#   cmake --build build-fuzz
#   bash fuzz/gen_corpus.sh fuzz/corpus
#   mkdir -p fuzz/crashes
#   ./build-fuzz/fuzz/fuzz_decode fuzz/corpus/ -max_total_time=300 \
#       -artifact_prefix=fuzz/crashes/ -jobs=$(nproc)
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
