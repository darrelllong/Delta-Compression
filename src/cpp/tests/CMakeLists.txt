include(FetchContent)

FetchContent_Declare(
    Catch2
    GIT_REPOSITORY https://github.com/catchorg/Catch2.git
    GIT_TAG        v3.5.4
)
FetchContent_MakeAvailable(Catch2)

list(APPEND CMAKE_MODULE_PATH ${catch2_SOURCE_DIR}/extras)
include(Catch)

add_executable(test_hash test_hash.cpp)
target_link_libraries(test_hash PRIVATE delta_lib Catch2::Catch2WithMain)
catch_discover_tests(test_hash)

add_executable(test_integration test_integration.cpp)
target_link_libraries(test_integration PRIVATE delta_lib Catch2::Catch2WithMain)
catch_discover_tests(test_integration)
