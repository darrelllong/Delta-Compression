#include "delta/encoding.h"

#include <array>
#include <cstring>
#include <limits>
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

// Guard against uint64_t → size_t truncation on 32-bit platforms.
static size_t check_u64_fits(uint64_t val, const char* field) {
    if (val > std::numeric_limits<size_t>::max()) {
        throw DeltaError(std::string(field) + " overflows size_t on this platform");
    }
    return static_cast<size_t>(val);
}

// ── Big-endian I/O helpers ────────────────────────────────────────────────

static inline void write_u32_be(std::vector<uint8_t>& out, uint32_t val) {
    out.push_back(static_cast<uint8_t>(val >> 24));
    out.push_back(static_cast<uint8_t>(val >> 16));
    out.push_back(static_cast<uint8_t>(val >>  8));
    out.push_back(static_cast<uint8_t>(val));
}

static inline void write_u64_be(std::vector<uint8_t>& out, uint64_t val) {
    out.push_back(static_cast<uint8_t>(val >> 56));
    out.push_back(static_cast<uint8_t>(val >> 48));
    out.push_back(static_cast<uint8_t>(val >> 40));
    out.push_back(static_cast<uint8_t>(val >> 32));
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

static inline uint64_t read_u64_be(const uint8_t* p) {
    return (static_cast<uint64_t>(p[0]) << 56)
         | (static_cast<uint64_t>(p[1]) << 48)
         | (static_cast<uint64_t>(p[2]) << 40)
         | (static_cast<uint64_t>(p[3]) << 32)
         | (static_cast<uint64_t>(p[4]) << 24)
         | (static_cast<uint64_t>(p[5]) << 16)
         | (static_cast<uint64_t>(p[6]) <<  8)
         |  static_cast<uint64_t>(p[7]);
}

// ── Shared u32 command parsers (used by both decoders) ───────────────────

static PlacedCopy parse_copy(std::span<const uint8_t> data, size_t& pos, size_t version_size) {
    if (pos + DELTA_COPY_PAYLOAD > data.size())
        throw DeltaError("unexpected end of delta data");
    size_t src    = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
    size_t dst    = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
    size_t length = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
    validate_placed_range(dst, length, version_size, "copy");
    return {src, dst, length};
}

static PlacedAdd parse_add(std::span<const uint8_t> data, size_t& pos, size_t version_size) {
    if (pos + DELTA_ADD_HEADER > data.size())
        throw DeltaError("unexpected end of delta data");
    size_t dst    = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
    size_t length = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
    if (pos + length > data.size())
        throw DeltaError("unexpected end of delta data");
    validate_placed_range(dst, length, version_size, "add");
    std::vector<uint8_t> payload(data.begin() + pos, data.begin() + pos + length);
    pos += length;
    return {dst, std::move(payload)};
}

// ── Encode ────────────────────────────────────────────────────────────────

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
        } else if (std::get_if<PlacedMove>(&cmd)) {
            throw DeltaError("PlacedMove requires DLT\\x04 format; use encode_delta_large");
        }
    }

    out.push_back(DELTA_CMD_END);
    return out;
}

std::vector<uint8_t> encode_delta_large(
    const std::vector<PlacedCommand>& commands,
    bool inplace,
    size_t version_size,
    const std::array<uint8_t, DELTA_CRC_SIZE>& src_crc,
    const std::array<uint8_t, DELTA_CRC_SIZE>& dst_crc,
    bool force_large) {

    std::vector<uint8_t> out;
    out.insert(out.end(), DELTA_MAGIC_LARGE, DELTA_MAGIC_LARGE + DELTA_MAGIC_SIZE);
    out.push_back(inplace ? DELTA_FLAG_INPLACE : 0);
    write_u64_be(out, static_cast<uint64_t>(version_size));
    out.insert(out.end(), src_crc.begin(), src_crc.end());
    out.insert(out.end(), dst_crc.begin(), dst_crc.end());

    for (const auto& cmd : commands) {
        if (auto* c = std::get_if<PlacedCopy>(&cmd)) {
            if (!force_large && c->src <= UINT32_MAX && c->dst <= UINT32_MAX && c->length <= UINT32_MAX) {
                out.push_back(DELTA_CMD_COPY);
                write_u32_be(out, static_cast<uint32_t>(c->src));
                write_u32_be(out, static_cast<uint32_t>(c->dst));
                write_u32_be(out, static_cast<uint32_t>(c->length));
            } else {
                out.push_back(DELTA_CMD_BIGCOPY);
                write_u64_be(out, static_cast<uint64_t>(c->src));
                write_u64_be(out, static_cast<uint64_t>(c->dst));
                write_u64_be(out, static_cast<uint64_t>(c->length));
            }
        } else if (auto* a = std::get_if<PlacedAdd>(&cmd)) {
            if (!force_large && a->dst <= UINT32_MAX && a->data.size() <= UINT32_MAX) {
                out.push_back(DELTA_CMD_ADD);
                write_u32_be(out, static_cast<uint32_t>(a->dst));
                write_u32_be(out, static_cast<uint32_t>(a->data.size()));
            } else {
                out.push_back(DELTA_CMD_BIGADD);
                write_u64_be(out, static_cast<uint64_t>(a->dst));
                write_u64_be(out, static_cast<uint64_t>(a->data.size()));
            }
            out.insert(out.end(), a->data.begin(), a->data.end());
        } else if (auto* m = std::get_if<PlacedMove>(&cmd)) {
            if (!force_large && m->src <= UINT32_MAX && m->dst <= UINT32_MAX && m->length <= UINT32_MAX) {
                out.push_back(DELTA_CMD_MOVE);
                write_u32_be(out, static_cast<uint32_t>(m->src));
                write_u32_be(out, static_cast<uint32_t>(m->dst));
                write_u32_be(out, static_cast<uint32_t>(m->length));
            } else {
                out.push_back(DELTA_CMD_BIGMOVE);
                write_u64_be(out, static_cast<uint64_t>(m->src));
                write_u64_be(out, static_cast<uint64_t>(m->dst));
                write_u64_be(out, static_cast<uint64_t>(m->length));
            }
        }
    }

    out.push_back(DELTA_CMD_END);
    return out;
}

// ── Decode ────────────────────────────────────────────────────────────────

using DecodeResult = std::tuple<std::vector<PlacedCommand>, bool, size_t,
                                std::array<uint8_t, DELTA_CRC_SIZE>,
                                std::array<uint8_t, DELTA_CRC_SIZE>>;

static DecodeResult decode_delta_small(std::span<const uint8_t> data) {
    if (data.size() < DELTA_HEADER_SIZE)
        throw DeltaError("not a delta file");

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
        uint8_t t = data[pos++];
        switch (t) {
        case DELTA_CMD_END:
            saw_end = true;
            break;
        case DELTA_CMD_COPY:
            commands.emplace_back(parse_copy(data, pos, version_size));
            break;
        case DELTA_CMD_ADD:
            commands.emplace_back(parse_add(data, pos, version_size));
            break;
        case DELTA_CMD_BIGCOPY:
        case DELTA_CMD_BIGADD:
        case DELTA_CMD_MOVE:
        case DELTA_CMD_BIGMOVE:
            throw DeltaError("command type " + std::to_string(t) + " requires DLT\\x04 format");
        default:
            throw DeltaError("unknown command type: " + std::to_string(t));
        }
        if (saw_end) break;
    }

    if (!saw_end)
        throw DeltaError("missing END command");
    if (pos != data.size())
        throw DeltaError("trailing data after END");
    return {std::move(commands), inplace, version_size, src_crc, dst_crc};
}

static DecodeResult decode_delta_large(std::span<const uint8_t> data) {
    if (data.size() < DELTA_HEADER_SIZE_LARGE)
        throw DeltaError("not a delta file");

    bool inplace = (data[DELTA_MAGIC_SIZE] & DELTA_FLAG_INPLACE) != 0;
    size_t version_size = check_u64_fits(read_u64_be(&data[DELTA_MAGIC_SIZE + 1]), "version_size");

    const size_t crc_offset = DELTA_MAGIC_SIZE + 1 + DELTA_U64_SIZE;
    std::array<uint8_t, DELTA_CRC_SIZE> src_crc{}, dst_crc{};
    std::memcpy(src_crc.data(), &data[crc_offset], DELTA_CRC_SIZE);
    std::memcpy(dst_crc.data(), &data[crc_offset + DELTA_CRC_SIZE], DELTA_CRC_SIZE);

    size_t pos = DELTA_HEADER_SIZE_LARGE;
    std::vector<PlacedCommand> commands;
    bool saw_end = false;

    while (pos < data.size()) {
        uint8_t t = data[pos++];
        switch (t) {
        case DELTA_CMD_END:
            saw_end = true;
            break;
        case DELTA_CMD_COPY:
            commands.emplace_back(parse_copy(data, pos, version_size));
            break;
        case DELTA_CMD_ADD:
            commands.emplace_back(parse_add(data, pos, version_size));
            break;
        case DELTA_CMD_BIGCOPY: {
            if (pos + DELTA_BIGCOPY_PAYLOAD > data.size())
                throw DeltaError("unexpected end of delta data");
            size_t src    = check_u64_fits(read_u64_be(&data[pos]), "bigcopy src");    pos += DELTA_U64_SIZE;
            size_t dst    = check_u64_fits(read_u64_be(&data[pos]), "bigcopy dst");    pos += DELTA_U64_SIZE;
            size_t length = check_u64_fits(read_u64_be(&data[pos]), "bigcopy length"); pos += DELTA_U64_SIZE;
            validate_placed_range(dst, length, version_size, "bigcopy");
            commands.emplace_back(PlacedCopy{src, dst, length});
            break;
        }
        case DELTA_CMD_BIGADD: {
            if (pos + DELTA_BIGADD_HEADER > data.size())
                throw DeltaError("unexpected end of delta data");
            size_t dst    = check_u64_fits(read_u64_be(&data[pos]), "bigadd dst");    pos += DELTA_U64_SIZE;
            size_t length = check_u64_fits(read_u64_be(&data[pos]), "bigadd length"); pos += DELTA_U64_SIZE;
            if (pos + length > data.size())
                throw DeltaError("unexpected end of delta data");
            validate_placed_range(dst, length, version_size, "bigadd");
            std::vector<uint8_t> payload(data.begin() + pos, data.begin() + pos + length);
            pos += length;
            commands.emplace_back(PlacedAdd{dst, std::move(payload)});
            break;
        }
        case DELTA_CMD_MOVE: {
            if (pos + DELTA_COPY_PAYLOAD > data.size())
                throw DeltaError("unexpected end of delta data");
            size_t src    = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
            size_t dst    = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
            size_t length = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
            validate_placed_range(dst, length, version_size, "move");
            if (src + length > dst)
                throw DeltaError("move src+length > dst: encoder ordering constraint violated");
            commands.emplace_back(PlacedMove{src, dst, length});
            break;
        }
        case DELTA_CMD_BIGMOVE: {
            if (pos + DELTA_BIGCOPY_PAYLOAD > data.size())
                throw DeltaError("unexpected end of delta data");
            size_t src    = check_u64_fits(read_u64_be(&data[pos]), "bigmove src");    pos += DELTA_U64_SIZE;
            size_t dst    = check_u64_fits(read_u64_be(&data[pos]), "bigmove dst");    pos += DELTA_U64_SIZE;
            size_t length = check_u64_fits(read_u64_be(&data[pos]), "bigmove length"); pos += DELTA_U64_SIZE;
            validate_placed_range(dst, length, version_size, "bigmove");
            if (src + length > dst)
                throw DeltaError("bigmove src+length > dst: encoder ordering constraint violated");
            commands.emplace_back(PlacedMove{src, dst, length});
            break;
        }
        default:
            throw DeltaError("unknown command type: " + std::to_string(t));
        }
        if (saw_end) break;
    }

    if (!saw_end)
        throw DeltaError("missing END command");
    if (pos != data.size())
        throw DeltaError("trailing data after END");
    return {std::move(commands), inplace, version_size, src_crc, dst_crc};
}

DecodeResult decode_delta(std::span<const uint8_t> data) {
    if (data.size() < DELTA_MAGIC_SIZE)
        throw DeltaError("not a delta file");
    if (std::memcmp(data.data(), DELTA_MAGIC, DELTA_MAGIC_SIZE) == 0)
        return decode_delta_small(data);
    if (std::memcmp(data.data(), DELTA_MAGIC_LARGE, DELTA_MAGIC_SIZE) == 0)
        return decode_delta_large(data);
    throw DeltaError("not a delta file");
}

bool is_inplace_delta(std::span<const uint8_t> data) {
    if (data.size() < DELTA_MAGIC_SIZE + 1)
        return false;
    bool small_magic = std::memcmp(data.data(), DELTA_MAGIC,       DELTA_MAGIC_SIZE) == 0;
    bool large_magic = std::memcmp(data.data(), DELTA_MAGIC_LARGE, DELTA_MAGIC_SIZE) == 0;
    return (small_magic || large_magic) && (data[DELTA_MAGIC_SIZE] & DELTA_FLAG_INPLACE) != 0;
}

} // namespace delta
