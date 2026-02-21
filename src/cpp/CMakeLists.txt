cmake_minimum_required(VERSION 3.20)
project(delta_cpp LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Default to Release build if not specified.
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

# ── Library ───────────────────────────────────────────────────────────────

add_library(delta_lib STATIC
    src/crc64.cpp
    src/hash.cpp
    src/encoding.cpp
    src/apply.cpp
    src/greedy.cpp
    src/onepass.cpp
    src/correcting.cpp
    src/inplace.cpp
)
target_include_directories(delta_lib PUBLIC include)

# ── CLI binary ────────────────────────────────────────────────────────────

include(FetchContent)

FetchContent_Declare(
    cli11
    GIT_REPOSITORY https://github.com/CLIUtils/CLI11.git
    GIT_TAG        v2.4.2
)
FetchContent_MakeAvailable(cli11)

add_executable(delta main.cpp)
target_link_libraries(delta PRIVATE delta_lib CLI11::CLI11)

# ── Tests ─────────────────────────────────────────────────────────────────

enable_testing()
add_subdirectory(tests)
