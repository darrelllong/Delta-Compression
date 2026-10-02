// delta: compute, apply and inspect binary deltas.

#include <CLI/CLI.hpp>
#include <delta/delta.h>

#include <array>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <optional>
#include <string>
#include <vector>

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

using namespace delta;

namespace {

[[noreturn]] void die_errno(const char* what, const std::string& path) {
    std::fprintf(stderr, "Error %s %s: %s\n", what, path.c_str(), std::strerror(errno));
    std::exit(1);
}

/// A file mapped read-only for the life of the object.
class MappedFile {
public:
    explicit MappedFile(const std::string& path) {
        int fd = ::open(path.c_str(), O_RDONLY);
        if (fd < 0) { die_errno("opening", path); }
        struct stat st;
        if (::fstat(fd, &st) < 0) { die_errno("stat", path); }
        size_ = static_cast<size_t>(st.st_size);
        if (size_ > 0) { // mmap rejects a length of zero
            void* p = ::mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0);
            if (p == MAP_FAILED) { die_errno("mmap", path); }
            data_ = static_cast<const uint8_t*>(p);
        }
        ::close(fd); // the mapping outlives the descriptor
    }

    ~MappedFile() {
        if (data_) { ::munmap(const_cast<uint8_t*>(data_), size_); }
    }

    MappedFile(const MappedFile&) = delete;
    MappedFile& operator=(const MappedFile&) = delete;

    std::span<const uint8_t> span() const { return {data_, size_}; }

private:
    const uint8_t* data_ = nullptr;
    size_t size_ = 0;
};

std::vector<uint8_t> read_file(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) {
        std::fprintf(stderr, "Error reading %s\n", path.c_str());
        std::exit(1);
    }
    return {std::istreambuf_iterator<char>(f), {}};
}

void write_file(const std::string& path, std::span<const uint8_t> data) {
    std::ofstream f(path, std::ios::binary);
    f.write(reinterpret_cast<const char*>(data.data()),
            static_cast<std::streamsize>(data.size()));
    f.close();
    if (!f) {
        std::fprintf(stderr, "Error writing %s\n", path.c_str());
        std::exit(1);
    }
}

using Crc = std::array<uint8_t, DELTA_CRC_SIZE>;

Crc crc_of(std::span<const uint8_t> data) {
    return crc64_xz(data.data(), data.size());
}

std::string hex(const Crc& crc) {
    char buf[2 * DELTA_CRC_SIZE + 1];
    for (size_t i = 0; i < crc.size(); ++i) {
        std::snprintf(buf + 2 * i, 3, "%02x", crc[i]);
    }
    return buf;
}

/// Parses a count with an optional decimal suffix: k, M or B (billion).
size_t parse_size(const std::string& s) {
    if (s.empty()) { return 0; }
    size_t mult = 1;
    switch (s.back()) {
    case 'k': case 'K': mult = 1'000; break;
    case 'm': case 'M': mult = 1'000'000; break;
    case 'b': case 'B': mult = 1'000'000'000; break;
    }
    try {
        return std::stoull(mult == 1 ? s : s.substr(0, s.size() - 1)) * mult;
    } catch (const std::exception&) {
        std::fprintf(stderr, "error: invalid size: %s\n", s.c_str());
        std::exit(1);
    }
}

std::optional<Algorithm> parse_algorithm(const std::string& s) {
    if (s == "greedy") { return Algorithm::Greedy; }
    if (s == "onepass") { return Algorithm::Onepass; }
    if (s == "correcting") { return Algorithm::Correcting; }
    std::fprintf(stderr, "Unknown algorithm: %s\n", s.c_str());
    return std::nullopt;
}

std::optional<CyclePolicy> parse_policy(const std::string& s) {
    if (s == "localmin") { return CyclePolicy::Localmin; }
    if (s == "constant") { return CyclePolicy::Constant; }
    std::fprintf(stderr, "Unknown policy: %s\n", s.c_str());
    return std::nullopt;
}

/// Seconds since the stopwatch was made.
class Stopwatch {
public:
    double seconds() const {
        return std::chrono::duration<double>(std::chrono::steady_clock::now() - start_).count();
    }

private:
    std::chrono::steady_clock::time_point start_ = std::chrono::steady_clock::now();
};

bool source_matches(std::span<const uint8_t> r, const Crc& expected) {
    const Crc actual = crc_of(r);
    if (actual == expected) { return true; }
    std::fprintf(stderr, "source file does not match delta: expected %s, got %s\n",
        hex(expected).c_str(), hex(actual).c_str());
    return false;
}

struct EncodeArgs {
    std::string algorithm, reference, version, delta;
    std::string policy = "localmin";
    std::string max_table = std::to_string(MAX_TABLE_SIZE);
    size_t seed_len = SEED_LEN;
    size_t table_size = TABLE_SIZE;
    bool inplace = false, large = false, verbose = false, splay = false;
};

int encode(const EncodeArgs& args) {
    const auto algo = parse_algorithm(args.algorithm);
    const auto policy = parse_policy(args.policy);
    if (!algo || !policy) { return 1; }
    if (args.seed_len == 0) {
        std::fprintf(stderr, "error: --seed-len must be >= 1\n");
        return 1;
    }

    const MappedFile r_file(args.reference);
    const MappedFile v_file(args.version);
    const auto r = r_file.span();
    const auto v = v_file.span();
    const Crc src_crc = crc_of(r);
    const Crc dst_crc = crc_of(v);

    DiffOptions opts;
    opts.p = args.seed_len;
    opts.q = args.table_size;
    opts.max_table = parse_size(args.max_table);
    opts.verbose = args.verbose;
    opts.use_splay = args.splay;

    const Stopwatch timer;
    const auto commands = diff(*algo, r, v, opts);
    const auto placed = args.inplace ? make_inplace(r, commands, *policy)
                                     : place_commands(commands);
    const double elapsed = timer.seconds();

    const auto delta_bytes =
        encode_delta_large(placed, args.inplace, v.size(), src_crc, dst_crc, args.large);
    write_file(args.delta, delta_bytes);

    const auto stats = placed_summary(placed);
    const double ratio = v.empty() ? 0.0
        : static_cast<double>(delta_bytes.size()) / static_cast<double>(v.size());
    const char* splay = args.splay ? " [splay]" : "";
    if (args.inplace) {
        std::printf("Algorithm:    %s%s + in-place (%s)\n",
            args.algorithm.c_str(), splay, args.policy.c_str());
    } else {
        std::printf("Algorithm:    %s%s\n", args.algorithm.c_str(), splay);
    }
    std::printf("Reference:    %s (%zu bytes)\n", args.reference.c_str(), r.size());
    std::printf("Version:      %s (%zu bytes)\n", args.version.c_str(), v.size());
    std::printf("Delta:        %s (%zu bytes)\n", args.delta.c_str(), delta_bytes.size());
    std::printf("Compression:  %.4f (delta/version)\n", ratio);
    std::printf("Commands:     %zu copies, %zu adds\n", stats.num_copies, stats.num_adds);
    std::printf("Copy bytes:   %zu\n", stats.copy_bytes);
    std::printf("Add bytes:    %zu\n", stats.add_bytes);
    std::printf("Src CRC:      %s\n", hex(src_crc).c_str());
    std::printf("Dst CRC:      %s\n", hex(dst_crc).c_str());
    std::printf("Time:         %.3fs\n", elapsed);
    return 0;
}

struct DecodeArgs {
    std::string reference, delta, output;
    bool ignore_hash = false;
};

int decode(const DecodeArgs& args) {
    const MappedFile r_file(args.reference);
    const auto r = r_file.span();
    const auto delta_bytes = read_file(args.delta);
    const auto [placed, inplace, version_size, src_crc, dst_crc] = decode_delta(delta_bytes);

    if (args.ignore_hash) {
        if (crc_of(r) != src_crc) {
            std::fprintf(stderr, "warning: skipping source CRC check (--ignore-hash)\n");
        }
    } else if (!source_matches(r, src_crc)) {
        return 1;
    }
    validate_placed_commands(placed, r.size(), version_size, inplace);

    const Stopwatch timer;
    std::vector<uint8_t> out;
    if (inplace) {
        out = apply_delta_inplace(r, placed, version_size);
    } else {
        out.resize(version_size);
        apply_placed_to(r, placed, out);
    }
    const double elapsed = timer.seconds();

    if (crc_of(out) != dst_crc) {
        if (!args.ignore_hash) {
            std::fprintf(stderr, "output integrity check failed\n");
            return 1;
        }
        std::fprintf(stderr, "warning: skipping output CRC check (--ignore-hash)\n");
    }
    write_file(args.output, out);

    std::printf("Format:       %s\n", inplace ? "in-place" : "standard");
    std::printf("Reference:    %s (%zu bytes)\n", args.reference.c_str(), r.size());
    std::printf("Delta:        %s (%zu bytes)\n", args.delta.c_str(), delta_bytes.size());
    std::printf("Output:       %s (%zu bytes)\n", args.output.c_str(), version_size);
    if (!args.ignore_hash) {
        std::printf("Src CRC:      %s  OK\n", hex(src_crc).c_str());
        std::printf("Dst CRC:      %s  OK\n", hex(dst_crc).c_str());
    }
    std::printf("Time:         %.3fs\n", elapsed);
    return 0;
}

int info(const std::string& delta_path) {
    const auto delta_bytes = read_file(delta_path);
    const auto [placed, inplace, version_size, src_crc, dst_crc] = decode_delta(delta_bytes);
    const auto stats = placed_summary(placed);

    std::printf("Delta file:   %s (%zu bytes)\n", delta_path.c_str(), delta_bytes.size());
    std::printf("Format:       %s\n", inplace ? "in-place" : "standard");
    std::printf("Version size: %zu bytes\n", version_size);
    std::printf("Src CRC:      %s\n", hex(src_crc).c_str());
    std::printf("Dst CRC:      %s\n", hex(dst_crc).c_str());
    std::printf("Commands:     %zu\n", stats.num_commands);
    std::printf("  Copies:     %zu (%zu bytes)\n", stats.num_copies, stats.copy_bytes);
    std::printf("  Adds:       %zu (%zu bytes)\n", stats.num_adds, stats.add_bytes);
    std::printf("Output size:  %zu bytes\n", stats.total_output_bytes);
    return 0;
}

struct InplaceArgs {
    std::string reference, delta_in, delta_out;
    std::string policy = "localmin";
    bool large = false;
};

int convert_to_inplace(const InplaceArgs& args) {
    const auto policy = parse_policy(args.policy);
    if (!policy) { return 1; }

    const MappedFile r_file(args.reference);
    const auto r = r_file.span();
    const auto delta_bytes = read_file(args.delta_in);
    const auto [placed, inplace, version_size, src_crc, dst_crc] = decode_delta(delta_bytes);

    if (inplace) {
        write_file(args.delta_out, delta_bytes);
        std::printf("Delta is already in-place format; copied unchanged.\n");
        return 0;
    }
    // Converting a copy to an add reads R, so R must be the right file.
    if (!source_matches(r, src_crc)) { return 1; }
    validate_placed_commands(placed, r.size(), version_size, false);

    const Stopwatch timer;
    const auto ip_placed = make_inplace(r, unplace_commands(placed), *policy);
    const double elapsed = timer.seconds();

    const auto ip_delta =
        encode_delta_large(ip_placed, true, version_size, src_crc, dst_crc, args.large);
    write_file(args.delta_out, ip_delta);

    const auto stats = placed_summary(ip_placed);
    std::printf("Reference:    %s (%zu bytes)\n", args.reference.c_str(), r.size());
    std::printf("Input delta:  %s (%zu bytes)\n", args.delta_in.c_str(), delta_bytes.size());
    std::printf("Output delta: %s (%zu bytes)\n", args.delta_out.c_str(), ip_delta.size());
    std::printf("Format:       in-place (%s)\n", args.policy.c_str());
    std::printf("Commands:     %zu copies, %zu adds\n", stats.num_copies, stats.num_adds);
    std::printf("Copy bytes:   %zu\n", stats.copy_bytes);
    std::printf("Add bytes:    %zu\n", stats.add_bytes);
    std::printf("Time:         %.3fs\n", elapsed);
    return 0;
}

} // namespace

int main(int argc, char** argv) {
    CLI::App app{"Differential compression (Ajtai et al. 2002)"};
    app.require_subcommand(1);

    EncodeArgs enc;
    auto* enc_cmd = app.add_subcommand("encode", "Compute delta encoding");
    enc_cmd->add_option("algorithm", enc.algorithm, "Algorithm (greedy/onepass/correcting)")
        ->required();
    enc_cmd->add_option("reference", enc.reference, "Reference file")->required();
    enc_cmd->add_option("version", enc.version, "Version file")->required();
    enc_cmd->add_option("delta_file", enc.delta, "Output delta file")->required();
    enc_cmd->add_option("--seed-len", enc.seed_len, "Seed length");
    enc_cmd->add_option("--table-size", enc.table_size, "Hash table floor size");
    enc_cmd->add_option("--max-table", enc.max_table,
                        "Max hash table size (k/M/B suffix: e.g. 512M, 2B)");
    enc_cmd->add_flag("--inplace", enc.inplace, "Produce in-place delta");
    enc_cmd->add_flag("--large", enc.large, "Force 64-bit (BIGCOPY/BIGADD) commands");
    enc_cmd->add_option("--policy", enc.policy, "Cycle policy (localmin/constant)");
    enc_cmd->add_flag("--verbose", enc.verbose, "Print diagnostics");
    enc_cmd->add_flag("--splay", enc.splay, "Use splay tree instead of hash table");

    DecodeArgs dec;
    auto* dec_cmd = app.add_subcommand("decode", "Reconstruct version from delta");
    dec_cmd->add_option("reference", dec.reference, "Reference file")->required();
    dec_cmd->add_option("delta_file", dec.delta, "Delta file")->required();
    dec_cmd->add_option("output", dec.output, "Output file")->required();
    dec_cmd->add_flag("--ignore-hash", dec.ignore_hash,
                      "Skip hash verification (for partial recovery)");

    std::string info_delta;
    auto* info_cmd = app.add_subcommand("info", "Show delta file statistics");
    info_cmd->add_option("delta_file", info_delta, "Delta file")->required();

    InplaceArgs inp;
    auto* inp_cmd = app.add_subcommand("inplace", "Convert standard delta to in-place delta");
    inp_cmd->add_option("reference", inp.reference, "Reference file")->required();
    inp_cmd->add_option("delta_in", inp.delta_in, "Input (standard) delta file")->required();
    inp_cmd->add_option("delta_out", inp.delta_out, "Output (in-place) delta file")->required();
    inp_cmd->add_option("--policy", inp.policy, "Cycle policy (localmin/constant)");
    inp_cmd->add_flag("--large", inp.large, "Force 64-bit (BIGCOPY/BIGADD) commands");

    CLI11_PARSE(app, argc, argv);

    try {
        if (enc_cmd->parsed()) { return encode(enc); }
        if (dec_cmd->parsed()) { return decode(dec); }
        if (info_cmd->parsed()) { return info(info_delta); }
        if (inp_cmd->parsed()) { return convert_to_inplace(inp); }
    } catch (const DeltaError& e) {
        std::fprintf(stderr, "error: %s\n", e.what());
        return 1;
    }
    return 0;
}
