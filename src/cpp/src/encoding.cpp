#include "delta/encoding.h"

#include <array>
#include <bit>
#include <cstring>

namespace delta {

// Big-endian u32 helpers
static inline void write_u32_be(std::vector<uint8_t>& out, uint32_t val) {
    if constexpr (std::endian::native == std::endian::little) {
        val = __builtin_bswap32(val);
    }
    const uint8_t* p = reinterpret_cast<const uint8_t*>(&val);
    out.insert(out.end(), p, p + DELTA_U32_SIZE);
}

static inline uint32_t read_u32_be(const uint8_t* p) {
    uint32_t val;
    std::memcpy(&val, p, DELTA_U32_SIZE);
    if constexpr (std::endian::native == std::endian::little) {
        val = __builtin_bswap32(val);
    }
    return val;
}

std::vector<uint8_t> encode_delta(
    const std::vector<PlacedCommand>& commands,
    bool inplace,
    size_t version_size,
    const std::array<uint8_t, DELTA_HASH_SIZE>& src_hash,
    const std::array<uint8_t, DELTA_HASH_SIZE>& dst_hash) {

    std::vector<uint8_t> out;
    out.insert(out.end(), DELTA_MAGIC, DELTA_MAGIC + DELTA_MAGIC_SIZE);
    out.push_back(inplace ? DELTA_FLAG_INPLACE : 0);
    write_u32_be(out, static_cast<uint32_t>(version_size));
    out.insert(out.end(), src_hash.begin(), src_hash.end());
    out.insert(out.end(), dst_hash.begin(), dst_hash.end());

    for (const auto& cmd : commands) {
        if (auto* c = std::get_if<PlacedCopy>(&cmd)) {
            out.push_back(DELTA_CMD_COPY);
            write_u32_be(out, static_cast<uint32_t>(c->src));
            write_u32_be(out, static_cast<uint32_t>(c->dst));
            write_u32_be(out, static_cast<uint32_t>(c->length));
        } else if (auto* a = std::get_if<PlacedAdd>(&cmd)) {
            out.push_back(DELTA_CMD_ADD);
            write_u32_be(out, static_cast<uint32_t>(a->dst));
            write_u32_be(out, static_cast<uint32_t>(a->data.size()));
            out.insert(out.end(), a->data.begin(), a->data.end());
        }
    }

    out.push_back(DELTA_CMD_END);
    return out;
}

std::tuple<std::vector<PlacedCommand>, bool, size_t,
           std::array<uint8_t, DELTA_HASH_SIZE>,
           std::array<uint8_t, DELTA_HASH_SIZE>> decode_delta(
    std::span<const uint8_t> data) {

    if (data.size() < DELTA_HEADER_SIZE
        || std::memcmp(data.data(), DELTA_MAGIC, DELTA_MAGIC_SIZE) != 0) {
        throw DeltaError("not a delta file");
    }

    bool inplace = (data[DELTA_MAGIC_SIZE] & DELTA_FLAG_INPLACE) != 0;
    size_t version_size = read_u32_be(&data[DELTA_MAGIC_SIZE + 1]);

    const size_t hash_offset = DELTA_MAGIC_SIZE + 1 + DELTA_U32_SIZE;
    std::array<uint8_t, DELTA_HASH_SIZE> src_hash{}, dst_hash{};
    std::memcpy(src_hash.data(), &data[hash_offset], DELTA_HASH_SIZE);
    std::memcpy(dst_hash.data(), &data[hash_offset + DELTA_HASH_SIZE], DELTA_HASH_SIZE);

    size_t pos = DELTA_HEADER_SIZE;
    std::vector<PlacedCommand> commands;

    while (pos < data.size()) {
        uint8_t t = data[pos];
        ++pos;

        switch (t) {
        case DELTA_CMD_END:
            return {std::move(commands), inplace, version_size, src_hash, dst_hash};

        case DELTA_CMD_COPY: {
            if (pos + DELTA_COPY_PAYLOAD > data.size()) {
                throw DeltaError("unexpected end of delta data");
            }
            size_t src = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
            size_t dst = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
            size_t length = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
            commands.emplace_back(PlacedCopy{src, dst, length});
            break;
        }

        case DELTA_CMD_ADD: {
            if (pos + DELTA_ADD_HEADER > data.size()) {
                throw DeltaError("unexpected end of delta data");
            }
            size_t dst = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
            size_t length = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
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

    return {std::move(commands), inplace, version_size, src_hash, dst_hash};
}

bool is_inplace_delta(std::span<const uint8_t> data) {
    return data.size() >= DELTA_MAGIC_SIZE + 1
        && std::memcmp(data.data(), DELTA_MAGIC, DELTA_MAGIC_SIZE) == 0
        && (data[DELTA_MAGIC_SIZE] & DELTA_FLAG_INPLACE) != 0;
}

} // namespace delta
