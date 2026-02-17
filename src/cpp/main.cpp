#include <CLI/CLI.hpp>
#include <delta/delta.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <vector>

// POSIX mmap
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

using namespace delta;

// ── RAII mmap wrapper ────────────────────────────────────────────────────

class MappedFile {
public:
    MappedFile() = default;

    static MappedFile open_read(const std::string& path) {
        MappedFile mf;
        mf.fd_ = ::open(path.c_str(), O_RDONLY);
        if (mf.fd_ < 0) {
            std::fprintf(stderr, "Error opening %s: %s\n",
                path.c_str(), std::strerror(errno));
            std::exit(1);
        }
        struct stat st;
        if (::fstat(mf.fd_, &st) < 0) {
            std::fprintf(stderr, "Error stat %s: %s\n",
                path.c_str(), std::strerror(errno));
            std::exit(1);
        }
        mf.size_ = static_cast<size_t>(st.st_size);
        if (mf.size_ > 0) {
            mf.data_ = static_cast<uint8_t*>(
                ::mmap(nullptr, mf.size_, PROT_READ, MAP_PRIVATE, mf.fd_, 0));
            if (mf.data_ == MAP_FAILED) {
                std::fprintf(stderr, "Error mmap %s: %s\n",
                    path.c_str(), std::strerror(errno));
                std::exit(1);
            }
        }
        return mf;
    }

    ~MappedFile() {
        if (data_ && data_ != MAP_FAILED) ::munmap(data_, size_);
        if (fd_ >= 0) ::close(fd_);
    }

    MappedFile(const MappedFile&) = delete;
    MappedFile& operator=(const MappedFile&) = delete;
    MappedFile(MappedFile&& o) noexcept
        : data_(o.data_), size_(o.size_), fd_(o.fd_) {
        o.data_ = nullptr; o.size_ = 0; o.fd_ = -1;
    }
    MappedFile& operator=(MappedFile&& o) noexcept {
        if (this != &o) {
            if (data_ && data_ != MAP_FAILED) ::munmap(data_, size_);
            if (fd_ >= 0) ::close(fd_);
            data_ = o.data_; size_ = o.size_; fd_ = o.fd_;
            o.data_ = nullptr; o.size_ = 0; o.fd_ = -1;
        }
        return *this;
    }

    std::span<const uint8_t> span() const {
        return {data_, size_};
    }
    size_t size() const { return size_; }

private:
    uint8_t* data_ = nullptr;
    size_t size_ = 0;
    int fd_ = -1;
};

// ── file I/O helpers ─────────────────────────────────────────────────────

static std::vector<uint8_t> read_file(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) {
        std::fprintf(stderr, "Error reading %s\n", path.c_str());
        std::exit(1);
    }
    return {std::istreambuf_iterator<char>(f), {}};
}

static void write_file(const std::string& path, std::span<const uint8_t> data) {
    std::ofstream f(path, std::ios::binary);
    if (!f) {
        std::fprintf(stderr, "Error writing %s\n", path.c_str());
        std::exit(1);
    }
    f.write(reinterpret_cast<const char*>(data.data()), data.size());
}

// ── main ─────────────────────────────────────────────────────────────────

int main(int argc, char** argv) {
    CLI::App app{"Differential compression (Ajtai et al. 2002)"};
    app.require_subcommand(1);

    // ── encode subcommand ────────────────────────────────────────────
    auto* enc = app.add_subcommand("encode", "Compute delta encoding");
    std::string enc_algo_str;
    enc->add_option("algorithm", enc_algo_str, "Algorithm (greedy/onepass/correcting)")
        ->required();
    std::string enc_ref, enc_ver, enc_delta;
    enc->add_option("reference", enc_ref, "Reference file")->required();
    enc->add_option("version", enc_ver, "Version file")->required();
    enc->add_option("delta_file", enc_delta, "Output delta file")->required();
    size_t enc_seed_len = SEED_LEN;
    enc->add_option("--seed-len", enc_seed_len, "Seed length");
    size_t enc_table_size = TABLE_SIZE;
    enc->add_option("--table-size", enc_table_size, "Hash table size");
    bool enc_inplace = false;
    enc->add_flag("--inplace", enc_inplace, "Produce in-place delta");
    std::string enc_policy_str = "localmin";
    enc->add_option("--policy", enc_policy_str, "Cycle policy (localmin/constant)");
    bool enc_verbose = false;
    enc->add_flag("--verbose", enc_verbose, "Print diagnostics");
    bool enc_splay = false;
    enc->add_flag("--splay", enc_splay, "Use splay tree instead of hash table");
    size_t enc_min_copy = 0;
    enc->add_option("--min-copy", enc_min_copy, "Minimum copy length (0 = use seed length)");

    // ── decode subcommand ────────────────────────────────────────────
    auto* dec = app.add_subcommand("decode", "Reconstruct version from delta");
    std::string dec_ref, dec_delta, dec_output;
    dec->add_option("reference", dec_ref, "Reference file")->required();
    dec->add_option("delta_file", dec_delta, "Delta file")->required();
    dec->add_option("output", dec_output, "Output file")->required();

    // ── info subcommand ──────────────────────────────────────────────
    auto* inf = app.add_subcommand("info", "Show delta file statistics");
    std::string info_delta;
    inf->add_option("delta_file", info_delta, "Delta file")->required();

    CLI11_PARSE(app, argc, argv);

    if (enc->parsed()) {
        Algorithm algo;
        if (enc_algo_str == "greedy") algo = Algorithm::Greedy;
        else if (enc_algo_str == "onepass") algo = Algorithm::Onepass;
        else if (enc_algo_str == "correcting") algo = Algorithm::Correcting;
        else {
            std::fprintf(stderr, "Unknown algorithm: %s\n", enc_algo_str.c_str());
            return 1;
        }

        CyclePolicy pol = CyclePolicy::Localmin;
        if (enc_policy_str == "constant") pol = CyclePolicy::Constant;

        auto r_file = MappedFile::open_read(enc_ref);
        auto v_file = MappedFile::open_read(enc_ver);
        auto r = r_file.span();
        auto v = v_file.span();

        auto t0 = std::chrono::steady_clock::now();
        DiffOptions opts;
        opts.p = enc_seed_len;
        opts.q = enc_table_size;
        opts.verbose = enc_verbose;
        opts.use_splay = enc_splay;
        opts.min_copy = enc_min_copy;
        auto commands = diff(algo, r, v, opts);

        std::vector<PlacedCommand> placed;
        if (enc_inplace) {
            placed = make_inplace(r, commands, pol);
        } else {
            placed = place_commands(commands);
        }
        auto t1 = std::chrono::steady_clock::now();
        double elapsed = std::chrono::duration<double>(t1 - t0).count();

        auto delta_bytes = encode_delta(placed, enc_inplace, v.size());
        write_file(enc_delta, delta_bytes);

        auto stats = placed_summary(placed);
        double ratio = v.empty() ? 0.0
            : static_cast<double>(delta_bytes.size()) / v.size();

        if (enc_inplace) {
            std::printf("Algorithm:    %s%s + in-place (%s)\n",
                enc_algo_str.c_str(), enc_splay ? " [splay]" : "",
                enc_policy_str.c_str());
        } else {
            std::printf("Algorithm:    %s%s\n",
                enc_algo_str.c_str(), enc_splay ? " [splay]" : "");
        }
        std::printf("Reference:    %s (%zu bytes)\n", enc_ref.c_str(), r.size());
        std::printf("Version:      %s (%zu bytes)\n", enc_ver.c_str(), v.size());
        std::printf("Delta:        %s (%zu bytes)\n", enc_delta.c_str(), delta_bytes.size());
        std::printf("Compression:  %.4f (delta/version)\n", ratio);
        std::printf("Commands:     %zu copies, %zu adds\n", stats.num_copies, stats.num_adds);
        std::printf("Copy bytes:   %zu\n", stats.copy_bytes);
        std::printf("Add bytes:    %zu\n", stats.add_bytes);
        std::printf("Time:         %.3fs\n", elapsed);

    } else if (dec->parsed()) {
        auto r_file = MappedFile::open_read(dec_ref);
        auto r = r_file.span();
        auto delta_bytes = read_file(dec_delta);

        auto t0 = std::chrono::steady_clock::now();
        auto [placed, is_ip, version_size] = decode_delta(delta_bytes);

        if (is_ip) {
            auto result = apply_delta_inplace(r, placed, version_size);
            write_file(dec_output, result);
        } else {
            std::vector<uint8_t> out(version_size, 0);
            apply_placed_to(r, placed, out);
            write_file(dec_output, out);
        }
        auto t1 = std::chrono::steady_clock::now();
        double elapsed = std::chrono::duration<double>(t1 - t0).count();

        const char* fmt = is_ip ? "in-place" : "standard";
        std::printf("Format:       %s\n", fmt);
        std::printf("Reference:    %s (%zu bytes)\n", dec_ref.c_str(), r.size());
        std::printf("Delta:        %s (%zu bytes)\n", dec_delta.c_str(), delta_bytes.size());
        std::printf("Output:       %s (%zu bytes)\n", dec_output.c_str(), version_size);
        std::printf("Time:         %.3fs\n", elapsed);

    } else if (inf->parsed()) {
        auto delta_bytes = read_file(info_delta);
        auto [placed, is_ip, version_size] = decode_delta(delta_bytes);
        auto stats = placed_summary(placed);

        const char* fmt = is_ip ? "in-place" : "standard";
        std::printf("Delta file:   %s (%zu bytes)\n", info_delta.c_str(), delta_bytes.size());
        std::printf("Format:       %s\n", fmt);
        std::printf("Version size: %zu bytes\n", version_size);
        std::printf("Commands:     %zu\n", stats.num_commands);
        std::printf("  Copies:     %zu (%zu bytes)\n", stats.num_copies, stats.copy_bytes);
        std::printf("  Adds:       %zu (%zu bytes)\n", stats.num_adds, stats.add_bytes);
        std::printf("Output size:  %zu bytes\n", stats.total_output_bytes);
    }

    return 0;
}
