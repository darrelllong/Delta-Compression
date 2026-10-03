// Tests of the delta executable, whose path is DELTA_CLI.

#include "test_harness.h"
#include <delta/delta.h>

#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <numeric>
#include <string>
#include <vector>

#include <sys/wait.h>
#include <unistd.h>

using namespace delta;
namespace fs = std::filesystem;

namespace {

/// A directory under the system's temporary directory, removed on exit.
struct TempDir {
    fs::path path;

    TempDir() : path(fs::temp_directory_path() /
                     ("delta_test_cli_" + std::to_string(::getpid()))) {
        fs::create_directories(path);
    }
    ~TempDir() { fs::remove_all(path); }

    std::string file(const char* name) const { return (path / name).string(); }
};

void write(const std::string& path, std::span<const uint8_t> data) {
    std::ofstream f(path, std::ios::binary);
    f.write(reinterpret_cast<const char*>(data.data()),
            static_cast<std::streamsize>(data.size()));
}

std::string read_text(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(f), {}};
}

std::string quoted(const std::string& s) { return "'" + s + "'"; }

struct Run {
    int status;
    std::string err;
};

/// Runs `delta inplace` with the arguments given.  Returns the exit status
/// and what was written to stderr.
Run run_inplace(const TempDir& dir, const std::string& args) {
    const std::string err = dir.file("stderr");
    const std::string cmd = quoted(DELTA_CLI) + " inplace " + args +
        " > /dev/null 2> " + quoted(err);
    const int rc = std::system(cmd.c_str());
    return {WIFEXITED(rc) ? WEXITSTATUS(rc) : -1, read_text(err)};
}

std::vector<uint8_t> standard_delta(std::span<const uint8_t> r,
    std::span<const uint8_t> v, const std::vector<PlacedCommand>& placed, bool large) {
    return encode_delta_large(placed, false, v.size(),
        crc64_xz(r.data(), r.size()), crc64_xz(v.data(), v.size()), large);
}

} // namespace

// R = A B and V = B A: each copy reads what the other writes.
TEST_CASE("inplace --verbose reports the conversion", "[cli]") {
    const TempDir dir;
    std::vector<uint8_t> r(100);
    std::iota(r.begin(), r.end(), 0);
    std::vector<uint8_t> v(r.begin() + 40, r.end());
    v.insert(v.end(), r.begin(), r.begin() + 40);
    const std::vector<Command> cmds = {CopyCmd{40, 60}, CopyCmd{0, 40}};
    REQUIRE(apply_delta(r, cmds) == v);

    write(dir.file("r"), r);
    write(dir.file("std"), standard_delta(r, v, place_commands(cmds), false));
    const std::string files =
        quoted(dir.file("r")) + " " + quoted(dir.file("std")) + " " + quoted(dir.file("ip"));

    auto [status, err] = run_inplace(dir, files + " --verbose");
    CHECK(status == 0);
    CHECK(err == "inplace: 2 copies, 2 CRWI edges, 1 cycles broken\n"
                 "  converted 1 copies -> adds (40 bytes materialized)\n");

    std::ifstream f(dir.file("ip"), std::ios::binary);
    const std::vector<uint8_t> ip_bytes{std::istreambuf_iterator<char>(f), {}};
    auto [placed, inplace, version_size, sc, dc] = decode_delta(ip_bytes);
    CHECK(inplace);
    CHECK(apply_delta_inplace(r, placed, version_size) == v);

    // Without --verbose nothing goes to stderr.
    auto quiet = run_inplace(dir, files);
    CHECK(quiet.status == 0);
    CHECK(quiet.err.empty());

    // With no cycle the second line is absent.
    const std::vector<Command> whole = {CopyCmd{0, 100}};
    write(dir.file("std"), standard_delta(r, r, place_commands(whole), false));
    auto same = run_inplace(dir, files + " --verbose");
    CHECK(same.status == 0);
    CHECK(same.err == "inplace: 1 copies, 0 CRWI edges, 0 cycles broken\n");
}

TEST_CASE("inplace rejects a delta that has a MOVE", "[cli]") {
    const TempDir dir;
    const std::vector<uint8_t> r = {'r','e','f'};
    const std::vector<uint8_t> v = {'h','e','l','l','o','h','e','l','l','o'};
    const std::vector<PlacedCommand> placed = {
        PlacedAdd{0, {'h','e','l','l','o'}},
        PlacedMove{0, 5, 5},
    };
    write(dir.file("r"), r);
    write(dir.file("std"), standard_delta(r, v, placed, true));

    auto [status, err] = run_inplace(dir,
        quoted(dir.file("r")) + " " + quoted(dir.file("std")) + " " + quoted(dir.file("ip")));
    CHECK(status == 1);
    CHECK(err.find("MOVE") != std::string::npos);
    CHECK_FALSE(fs::exists(dir.file("ip")));
}

// Converting a copy to an add reads R, so the wrong R would put the wrong
// bytes in the delta.
TEST_CASE("inplace rejects the wrong reference", "[cli]") {
    const TempDir dir;
    std::vector<uint8_t> r(100);
    std::iota(r.begin(), r.end(), 0);
    std::vector<uint8_t> wrong = r;
    wrong[50] ^= 1;
    const std::vector<Command> cmds = {CopyCmd{40, 60}, CopyCmd{0, 40}};
    const auto v = apply_delta(r, cmds);

    write(dir.file("wrong"), wrong);
    write(dir.file("std"), standard_delta(r, v, place_commands(cmds), false));

    auto [status, err] = run_inplace(dir,
        quoted(dir.file("wrong")) + " " + quoted(dir.file("std")) + " " + quoted(dir.file("ip")));
    CHECK(status == 1);
    CHECK(err.find("source file does not match delta") != std::string::npos);
    CHECK_FALSE(fs::exists(dir.file("ip")));
}
