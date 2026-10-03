#include "delta/apply.h"
#include "overloaded.h"

#include <algorithm>
#include <cstring>
#include <numeric>
#include <string>

namespace delta {

using detail::overloaded;

namespace {

size_t destination(const PlacedCommand& cmd) {
    return std::visit([](const auto& c) { return c.dst; }, cmd);
}

/// Throws unless [start, start+length) lies within [0, limit).  The test is
/// written so that start + length cannot wrap around.
void check_range(size_t start, size_t length, size_t limit, const char* what) {
    if (start > limit || length > limit - start) {
        throw DeltaError(std::string(what) + " out of range");
    }
}

} // namespace

DeltaSummary delta_summary(const std::vector<Command>& commands) {
    DeltaSummary s{};
    s.num_commands = commands.size();
    for (const auto& cmd : commands) {
        if (const auto* c = std::get_if<CopyCmd>(&cmd)) {
            ++s.num_copies;
            s.copy_bytes += c->length;
        } else {
            ++s.num_adds;
            s.add_bytes += std::get<AddCmd>(cmd).data.size();
        }
    }
    s.total_output_bytes = s.copy_bytes + s.add_bytes;
    return s;
}

DeltaSummary placed_summary(const std::vector<PlacedCommand>& commands) {
    DeltaSummary s{};
    s.num_commands = commands.size();
    auto copy = [&](size_t length) {
        ++s.num_copies;
        s.copy_bytes += length;
    };
    for (const auto& cmd : commands) {
        std::visit(overloaded{
            [&](const PlacedCopy& c) { copy(c.length); },
            [&](const PlacedMove& m) { copy(m.length); },
            [&](const PlacedAdd& a) {
                ++s.num_adds;
                s.add_bytes += a.data.size();
            },
        }, cmd);
    }
    s.total_output_bytes = s.copy_bytes + s.add_bytes;
    return s;
}

size_t output_size(const std::vector<Command>& commands) {
    return delta_summary(commands).total_output_bytes;
}

std::vector<PlacedCommand> place_commands(const std::vector<Command>& commands) {
    std::vector<PlacedCommand> placed;
    placed.reserve(commands.size());
    size_t dst = 0;
    for (const auto& cmd : commands) {
        if (const auto* c = std::get_if<CopyCmd>(&cmd)) {
            placed.emplace_back(PlacedCopy{c->offset, dst, c->length});
            dst += c->length;
        } else {
            const auto& data = std::get<AddCmd>(cmd).data;
            placed.emplace_back(PlacedAdd{dst, data});
            dst += data.size();
        }
    }
    return placed;
}

std::vector<Command> unplace_commands(const std::vector<PlacedCommand>& placed) {
    std::vector<size_t> order(placed.size());
    std::iota(order.begin(), order.end(), 0);
    std::sort(order.begin(), order.end(), [&](size_t a, size_t b) {
        return destination(placed[a]) < destination(placed[b]);
    });

    std::vector<Command> commands;
    commands.reserve(placed.size());
    for (size_t i : order) {
        std::visit(overloaded{
            [&](const PlacedCopy& c) { commands.emplace_back(CopyCmd{c.src, c.length}); },
            [&](const PlacedAdd& a) { commands.emplace_back(AddCmd{a.data}); },
            [&](const PlacedMove&) {
                throw DeltaError("PlacedMove has no algorithm-level equivalent");
            },
        }, placed[i]);
    }
    return commands;
}

void validate_placed_commands(
    const std::vector<PlacedCommand>& commands,
    size_t reference_size,
    size_t version_size,
    bool inplace) {

    const size_t source_limit = inplace ? std::max(reference_size, version_size)
                                        : reference_size;
    for (const auto& cmd : commands) {
        std::visit(overloaded{
            [&](const PlacedCopy& c) {
                check_range(c.dst, c.length, version_size, "copy destination");
                check_range(c.src, c.length, source_limit, "copy source");
            },
            [&](const PlacedAdd& a) {
                check_range(a.dst, a.data.size(), version_size, "add destination");
            },
            [&](const PlacedMove& m) {
                check_range(m.dst, m.length, version_size, "move destination");
                if (m.src > m.dst || m.length > m.dst - m.src) {
                    throw DeltaError("move src+length > dst: encoder ordering constraint violated");
                }
            },
        }, cmd);
    }
}

size_t apply_placed_to(
    std::span<const uint8_t> r,
    const std::vector<PlacedCommand>& commands,
    std::span<uint8_t> out) {

    size_t written = 0;
    for (const auto& cmd : commands) {
        written = std::max(written, std::visit(overloaded{
            [&](const PlacedCopy& c) {
                std::memcpy(out.data() + c.dst, r.data() + c.src, c.length);
                return c.dst + c.length;
            },
            [&](const PlacedAdd& a) {
                std::memcpy(out.data() + a.dst, a.data.data(), a.data.size());
                return a.dst + a.data.size();
            },
            [&](const PlacedMove& m) {
                std::memcpy(out.data() + m.dst, out.data() + m.src, m.length);
                return m.dst + m.length;
            },
        }, cmd));
    }
    return written;
}

void apply_placed_inplace_to(
    const std::vector<PlacedCommand>& commands,
    std::span<uint8_t> buf) {

    for (const auto& cmd : commands) {
        std::visit(overloaded{
            // A copy may read bytes that it also overwrites.
            [&](const PlacedCopy& c) {
                std::memmove(buf.data() + c.dst, buf.data() + c.src, c.length);
            },
            [&](const PlacedAdd& a) {
                std::memcpy(buf.data() + a.dst, a.data.data(), a.data.size());
            },
            [&](const PlacedMove& m) {
                std::memcpy(buf.data() + m.dst, buf.data() + m.src, m.length);
            },
        }, cmd);
    }
}

std::vector<uint8_t> apply_delta(
    std::span<const uint8_t> r,
    const std::vector<Command>& commands) {

    std::vector<uint8_t> out;
    out.reserve(output_size(commands));
    for (const auto& cmd : commands) {
        if (const auto* c = std::get_if<CopyCmd>(&cmd)) {
            out.insert(out.end(), r.begin() + c->offset, r.begin() + c->offset + c->length);
        } else {
            const auto& data = std::get<AddCmd>(cmd).data;
            out.insert(out.end(), data.begin(), data.end());
        }
    }
    return out;
}

std::vector<uint8_t> apply_delta_inplace(
    std::span<const uint8_t> r,
    const std::vector<PlacedCommand>& commands,
    size_t version_size) {

    std::vector<uint8_t> buf(std::max(r.size(), version_size));
    std::copy(r.begin(), r.end(), buf.begin());
    apply_placed_inplace_to(commands, buf);
    buf.resize(version_size);
    return buf;
}

} // namespace delta
