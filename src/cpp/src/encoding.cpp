#include "delta/encoding.h"

#include <bit>
#include <cstring>

namespace delta {

// Big-endian u32 helpers
static inline void write_u32_be(std::vector<uint8_t>& out, uint32_t val) {
    if constexpr (std::endian::native == std::endian::little) {
        val = __builtin_bswap32(val);
    }
    const uint8_t* p = reinterpret_cast<const uint8_t*>(&val);
    out.insert(out.end(), p, p + 4);
}

static inline uint32_t read_u32_be(const uint8_t* p) {
    uint32_t val;
    std::memcpy(&val, p, 4);
    if constexpr (std::endian::native == std::endian::little) {
        val = __builtin_bswap32(val);
    }
    return val;
}

std::vector<uint8_t> encode_delta(
    const std::vector<PlacedCommand>& commands,
    bool inplace,
    size_t version_size) {

    std::vector<uint8_t> out;
    out.insert(out.end(), DELTA_MAGIC, DELTA_MAGIC + 4);
    out.push_back(inplace ? DELTA_FLAG_INPLACE : 0);
    write_u32_be(out, static_cast<uint32_t>(version_size));

    for (const auto& cmd : commands) {
        if (auto* c = std::get_if<PlacedCopy>(&cmd)) {
            out.push_back(1);
            write_u32_be(out, static_cast<uint32_t>(c->src));
            write_u32_be(out, static_cast<uint32_t>(c->dst));
            write_u32_be(out, static_cast<uint32_t>(c->length));
        } else if (auto* a = std::get_if<PlacedAdd>(&cmd)) {
            out.push_back(2);
            write_u32_be(out, static_cast<uint32_t>(a->dst));
            write_u32_be(out, static_cast<uint32_t>(a->data.size()));
            out.insert(out.end(), a->data.begin(), a->data.end());
        }
    }

    out.push_back(0); // END
    return out;
}

std::tuple<std::vector<PlacedCommand>, bool, size_t> decode_delta(
    std::span<const uint8_t> data) {

    if (data.size() < 9 || std::memcmp(data.data(), DELTA_MAGIC, 4) != 0) {
        throw DeltaError("not a delta file");
    }

    bool inplace = (data[4] & DELTA_FLAG_INPLACE) != 0;
    size_t version_size = read_u32_be(&data[5]);
    size_t pos = 9;
    std::vector<PlacedCommand> commands;

    while (pos < data.size()) {
        uint8_t t = data[pos];
        ++pos;

        switch (t) {
        case 0: // END
            return {std::move(commands), inplace, version_size};

        case 1: { // COPY
            if (pos + 12 > data.size()) {
                throw DeltaError("unexpected end of delta data");
            }
            size_t src = read_u32_be(&data[pos]); pos += 4;
            size_t dst = read_u32_be(&data[pos]); pos += 4;
            size_t length = read_u32_be(&data[pos]); pos += 4;
            commands.emplace_back(PlacedCopy{src, dst, length});
            break;
        }

        case 2: { // ADD
            if (pos + 8 > data.size()) {
                throw DeltaError("unexpected end of delta data");
            }
            size_t dst = read_u32_be(&data[pos]); pos += 4;
            size_t length = read_u32_be(&data[pos]); pos += 4;
            if (pos + length > data.size()) {
                throw DeltaError("unexpected end of delta data");
            }
            std::vector<uint8_t> add_data(data.begin() + pos,
                                          data.begin() + pos + length);
            pos += length;
            commands.emplace_back(PlacedAdd{dst, std::move(add_data)});
            break;
        }

        default:
            throw DeltaError("unknown command type: " + std::to_string(t));
        }
    }

    return {std::move(commands), inplace, version_size};
}

bool is_inplace_delta(std::span<const uint8_t> data) {
    return data.size() >= 5
        && std::memcmp(data.data(), DELTA_MAGIC, 4) == 0
        && (data[4] & DELTA_FLAG_INPLACE) != 0;
}

} // namespace delta
