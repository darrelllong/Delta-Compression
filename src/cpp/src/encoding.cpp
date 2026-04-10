#include "delta/encoding.h"

#include <array>
#include <cstring>
#include <string>

namespace delta {

static void validate_placed_range(size_t dst, size_t length, size_t version_size, const char* kind) {
    if (dst > version_size || length > version_size - dst) {
        throw DeltaError(std::string(kind) + " command exceeds version size");
    }
}

static void check_u32(size_t val, const char* field) {
    if (val > UINT32_MAX) {
        throw DeltaError(std::string(field) + " exceeds 4 GiB (32-bit format limit)");
    }
}

// Big-endian u32 helpers — portable, no compiler builtins required.
static inline void write_u32_be(std::vector<uint8_t>& out, uint32_t val) {
    out.push_back(static_cast<uint8_t>(val >> 24));
    out.push_back(static_cast<uint8_t>(val >> 16));
    out.push_back(static_cast<uint8_t>(val >>  8));
    out.push_back(static_cast<uint8_t>(val));
}

static inline uint32_t read_u32_be(const uint8_t* p) {
    return (static_cast<uint32_t>(p[0]) << 24)
         | (static_cast<uint32_t>(p[1]) << 16)
         | (static_cast<uint32_t>(p[2]) <<  8)
         |  static_cast<uint32_t>(p[3]);
}

std::vector<uint8_t> encode_delta(
    const std::vector<PlacedCommand>& commands,
    bool inplace,
    size_t version_size,
    const std::array<uint8_t, DELTA_CRC_SIZE>& src_crc,
    const std::array<uint8_t, DELTA_CRC_SIZE>& dst_crc) {

    std::vector<uint8_t> out;
    out.insert(out.end(), DELTA_MAGIC, DELTA_MAGIC + DELTA_MAGIC_SIZE);
    out.push_back(inplace ? DELTA_FLAG_INPLACE : 0);
    check_u32(version_size, "version_size");
    write_u32_be(out, static_cast<uint32_t>(version_size));
    out.insert(out.end(), src_crc.begin(), src_crc.end());
    out.insert(out.end(), dst_crc.begin(), dst_crc.end());

    for (const auto& cmd : commands) {
        if (auto* c = std::get_if<PlacedCopy>(&cmd)) {
            check_u32(c->src,    "copy src offset");
            check_u32(c->dst,    "copy dst offset");
            check_u32(c->length, "copy length");
            out.push_back(DELTA_CMD_COPY);
            write_u32_be(out, static_cast<uint32_t>(c->src));
            write_u32_be(out, static_cast<uint32_t>(c->dst));
            write_u32_be(out, static_cast<uint32_t>(c->length));
        } else if (auto* a = std::get_if<PlacedAdd>(&cmd)) {
            check_u32(a->dst,        "add dst offset");
            check_u32(a->data.size(), "add length");
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
           std::array<uint8_t, DELTA_CRC_SIZE>,
           std::array<uint8_t, DELTA_CRC_SIZE>> decode_delta(
    std::span<const uint8_t> data) {

    if (data.size() < DELTA_HEADER_SIZE
        || std::memcmp(data.data(), DELTA_MAGIC, DELTA_MAGIC_SIZE) != 0) {
        throw DeltaError("not a delta file");
    }

    bool inplace = (data[DELTA_MAGIC_SIZE] & DELTA_FLAG_INPLACE) != 0;
    size_t version_size = read_u32_be(&data[DELTA_MAGIC_SIZE + 1]);

    const size_t crc_offset = DELTA_MAGIC_SIZE + 1 + DELTA_U32_SIZE;
    std::array<uint8_t, DELTA_CRC_SIZE> src_crc{}, dst_crc{};
    std::memcpy(src_crc.data(), &data[crc_offset], DELTA_CRC_SIZE);
    std::memcpy(dst_crc.data(), &data[crc_offset + DELTA_CRC_SIZE], DELTA_CRC_SIZE);

    size_t pos = DELTA_HEADER_SIZE;
    std::vector<PlacedCommand> commands;
    bool saw_end = false;

    while (pos < data.size()) {
        uint8_t t = data[pos];
        ++pos;

        switch (t) {
        case DELTA_CMD_END:
            saw_end = true;
            break;

        case DELTA_CMD_COPY: {
            if (pos + DELTA_COPY_PAYLOAD > data.size()) {
                throw DeltaError("unexpected end of delta data");
            }
            size_t src = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
            size_t dst = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
            size_t length = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
            validate_placed_range(dst, length, version_size, "copy");
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
            validate_placed_range(dst, length, version_size, "add");
            std::vector<uint8_t> add_data(data.begin() + pos,
                                          data.begin() + pos + length);
            pos += length;
            commands.emplace_back(PlacedAdd{dst, std::move(add_data)});
            break;
        }

        default:
            throw DeltaError("unknown command type: " + std::to_string(t));
        }

        if (saw_end) {
            break;
        }
    }

    if (!saw_end) {
        throw DeltaError("missing END command");
    }
    if (pos != data.size()) {
        throw DeltaError("trailing data after END");
    }
    return {std::move(commands), inplace, version_size, src_crc, dst_crc};
}

bool is_inplace_delta(std::span<const uint8_t> data) {
    return data.size() >= DELTA_MAGIC_SIZE + 1
        && std::memcmp(data.data(), DELTA_MAGIC, DELTA_MAGIC_SIZE) == 0
        && (data[DELTA_MAGIC_SIZE] & DELTA_FLAG_INPLACE) != 0;
}

} // namespace delta
