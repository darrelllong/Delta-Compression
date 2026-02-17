#include "delta/apply.h"

#include <algorithm>
#include <cstring>
#include <numeric>

namespace delta {

DeltaSummary delta_summary(const std::vector<Command>& commands) {
    size_t num_copies = 0, num_adds = 0, copy_bytes = 0, add_bytes = 0;
    for (const auto& cmd : commands) {
        if (auto* c = std::get_if<CopyCmd>(&cmd)) {
            ++num_copies;
            copy_bytes += c->length;
        } else if (auto* a = std::get_if<AddCmd>(&cmd)) {
            ++num_adds;
            add_bytes += a->data.size();
        }
    }
    return {commands.size(), num_copies, num_adds, copy_bytes, add_bytes,
            copy_bytes + add_bytes};
}

DeltaSummary placed_summary(const std::vector<PlacedCommand>& commands) {
    size_t num_copies = 0, num_adds = 0, copy_bytes = 0, add_bytes = 0;
    for (const auto& cmd : commands) {
        if (auto* c = std::get_if<PlacedCopy>(&cmd)) {
            ++num_copies;
            copy_bytes += c->length;
        } else if (auto* a = std::get_if<PlacedAdd>(&cmd)) {
            ++num_adds;
            add_bytes += a->data.size();
        }
    }
    return {commands.size(), num_copies, num_adds, copy_bytes, add_bytes,
            copy_bytes + add_bytes};
}

size_t output_size(const std::vector<Command>& commands) {
    size_t total = 0;
    for (const auto& cmd : commands) {
        if (auto* c = std::get_if<CopyCmd>(&cmd)) {
            total += c->length;
        } else if (auto* a = std::get_if<AddCmd>(&cmd)) {
            total += a->data.size();
        }
    }
    return total;
}

std::vector<PlacedCommand> place_commands(const std::vector<Command>& commands) {
    std::vector<PlacedCommand> placed;
    placed.reserve(commands.size());
    size_t dst = 0;
    for (const auto& cmd : commands) {
        if (auto* c = std::get_if<CopyCmd>(&cmd)) {
            placed.emplace_back(PlacedCopy{c->offset, dst, c->length});
            dst += c->length;
        } else if (auto* a = std::get_if<AddCmd>(&cmd)) {
            placed.emplace_back(PlacedAdd{dst, a->data});
            dst += a->data.size();
        }
    }
    return placed;
}

std::vector<Command> unplace_commands(const std::vector<PlacedCommand>& placed) {
    // Sort indices by destination offset to recover original sequential order.
    std::vector<size_t> indices(placed.size());
    std::iota(indices.begin(), indices.end(), 0);
    std::sort(indices.begin(), indices.end(), [&](size_t a, size_t b) {
        auto dst_of = [&](size_t i) -> size_t {
            if (auto* c = std::get_if<PlacedCopy>(&placed[i])) return c->dst;
            return std::get<PlacedAdd>(placed[i]).dst;
        };
        return dst_of(a) < dst_of(b);
    });

    std::vector<Command> commands;
    commands.reserve(placed.size());
    for (size_t i : indices) {
        if (auto* c = std::get_if<PlacedCopy>(&placed[i])) {
            commands.emplace_back(CopyCmd{c->src, c->length});
        } else if (auto* a = std::get_if<PlacedAdd>(&placed[i])) {
            commands.emplace_back(AddCmd{a->data});
        }
    }
    return commands;
}

size_t apply_placed_to(
    std::span<const uint8_t> r,
    const std::vector<PlacedCommand>& commands,
    std::span<uint8_t> out) {

    size_t max_written = 0;
    for (const auto& cmd : commands) {
        if (auto* c = std::get_if<PlacedCopy>(&cmd)) {
            std::memcpy(&out[c->dst], &r[c->src], c->length);
            size_t end = c->dst + c->length;
            if (end > max_written) max_written = end;
        } else if (auto* a = std::get_if<PlacedAdd>(&cmd)) {
            std::memcpy(&out[a->dst], a->data.data(), a->data.size());
            size_t end = a->dst + a->data.size();
            if (end > max_written) max_written = end;
        }
    }
    return max_written;
}

void apply_placed_inplace_to(
    const std::vector<PlacedCommand>& commands,
    std::span<uint8_t> buf) {

    for (const auto& cmd : commands) {
        if (auto* c = std::get_if<PlacedCopy>(&cmd)) {
            // memmove-safe for overlapping regions
            std::memmove(&buf[c->dst], &buf[c->src], c->length);
        } else if (auto* a = std::get_if<PlacedAdd>(&cmd)) {
            std::memcpy(&buf[a->dst], a->data.data(), a->data.size());
        }
    }
}

std::vector<uint8_t> apply_delta(
    std::span<const uint8_t> r,
    const std::vector<Command>& commands) {

    std::vector<uint8_t> out(output_size(commands), 0);
    size_t pos = 0;
    for (const auto& cmd : commands) {
        if (auto* c = std::get_if<CopyCmd>(&cmd)) {
            std::memcpy(&out[pos], &r[c->offset], c->length);
            pos += c->length;
        } else if (auto* a = std::get_if<AddCmd>(&cmd)) {
            std::memcpy(&out[pos], a->data.data(), a->data.size());
            pos += a->data.size();
        }
    }
    return out;
}

std::vector<uint8_t> apply_delta_inplace(
    std::span<const uint8_t> r,
    const std::vector<PlacedCommand>& commands,
    size_t version_size) {

    size_t buf_size = std::max(r.size(), version_size);
    std::vector<uint8_t> buf(buf_size, 0);
    std::memcpy(buf.data(), r.data(), r.size());
    apply_placed_inplace_to(commands, buf);
    buf.resize(version_size);
    return buf;
}

} // namespace delta
