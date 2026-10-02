#include "delta/algorithm.h"
#include "scan.h"

#include <algorithm>
#include <cstdio>

namespace delta {

std::vector<Command> diff(
    Algorithm algo,
    std::span<const uint8_t> r,
    std::span<const uint8_t> v,
    const DiffOptions& opts) {

    switch (algo) {
    case Algorithm::Greedy:
        return diff_greedy(r, v, opts);
    case Algorithm::Onepass:
        return diff_onepass(r, v, opts);
    case Algorithm::Correcting:
        return diff_correcting(r, v, opts);
    }
    throw DeltaError("unknown algorithm");
}

void print_command_stats(const std::vector<Command>& commands) {
    const DeltaSummary s = delta_summary(commands);
    std::fprintf(stderr,
        "  result: %zu copies (%zu bytes), %zu adds (%zu bytes)\n"
        "  result: copy coverage %.1f%%, output %zu bytes\n",
        s.num_copies, s.copy_bytes, s.num_adds, s.add_bytes,
        detail::percent(s.copy_bytes, s.total_output_bytes), s.total_output_bytes);
    if (s.num_copies == 0) { return; }

    std::vector<size_t> lengths;
    lengths.reserve(s.num_copies);
    for (const auto& cmd : commands) {
        if (const auto* c = std::get_if<CopyCmd>(&cmd)) { lengths.push_back(c->length); }
    }
    std::sort(lengths.begin(), lengths.end());
    std::fprintf(stderr,
        "  copies: %zu regions, min=%zu max=%zu mean=%.1f median=%zu bytes\n",
        lengths.size(), lengths.front(), lengths.back(),
        static_cast<double>(s.copy_bytes) / lengths.size(),
        lengths[lengths.size() / 2]);
}

} // namespace delta
