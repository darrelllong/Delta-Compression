#include "delta/encoding.h"
#include "overloaded.h"

#include <cstring>
#include <initializer_list>
#include <limits>
#include <string>

namespace delta {

using detail::overloaded;
using Crc = std::array<uint8_t, DELTA_CRC_SIZE>;

namespace {

bool has_magic(std::span<const uint8_t> data, const uint8_t* magic) {
    return data.size() >= DELTA_MAGIC_SIZE
        && std::memcmp(data.data(), magic, DELTA_MAGIC_SIZE) == 0;
}

void put_u32(std::vector<uint8_t>& out, uint32_t val) {
    for (int shift = 24; shift >= 0; shift -= 8) {
        out.push_back(static_cast<uint8_t>(val >> shift));
    }
}

void put_u64(std::vector<uint8_t>& out, uint64_t val) {
    for (int shift = 56; shift >= 0; shift -= 8) {
        out.push_back(static_cast<uint8_t>(val >> shift));
    }
}

void put_bytes(std::vector<uint8_t>& out, std::span<const uint8_t> bytes) {
    out.insert(out.end(), bytes.begin(), bytes.end());
}

uint32_t to_u32(size_t val, const char* field) {
    if (val > UINT32_MAX) {
        throw DeltaError(std::string(field) + " exceeds 4 GiB (32-bit format limit)");
    }
    return static_cast<uint32_t>(val);
}

bool fits_u32(std::initializer_list<size_t> fields) {
    for (size_t f : fields) {
        if (f > UINT32_MAX) { return false; }
    }
    return true;
}

/// Appends a DLT\x04 command: the opcode of its 32-bit or 64-bit form, then
/// the fields at that width.
void put_command(std::vector<uint8_t>& out, uint8_t op, uint8_t big_op,
                 bool force_large, std::initializer_list<size_t> fields) {
    if (!force_large && fits_u32(fields)) {
        out.push_back(op);
        for (size_t f : fields) { put_u32(out, static_cast<uint32_t>(f)); }
    } else {
        out.push_back(big_op);
        for (size_t f : fields) { put_u64(out, f); }
    }
}

/// Reads big-endian fields from a delta, throwing DeltaError at its end.
class Reader {
public:
    explicit Reader(std::span<const uint8_t> data) : data_(data) {}

    bool at_end() const { return pos_ == data_.size(); }

    std::span<const uint8_t> take(size_t n) {
        if (n > data_.size() - pos_) {
            throw DeltaError("unexpected end of delta data");
        }
        pos_ += n;
        return data_.subspan(pos_ - n, n);
    }

    uint8_t u8() { return take(1)[0]; }

    /// A u64 field if wide, else a u32 field.
    size_t field(bool wide) {
        uint64_t val = 0;
        for (uint8_t byte : take(wide ? DELTA_U64_SIZE : DELTA_U32_SIZE)) {
            val = val << 8 | byte;
        }
        if (val > std::numeric_limits<size_t>::max()) {
            throw DeltaError("field overflows size_t on this platform");
        }
        return static_cast<size_t>(val);
    }

    Crc crc() {
        Crc c;
        std::memcpy(c.data(), take(DELTA_CRC_SIZE).data(), DELTA_CRC_SIZE);
        return c;
    }

private:
    std::span<const uint8_t> data_;
    size_t pos_ = 0;
};

const char* command_name(uint8_t op) {
    switch (op) {
    case DELTA_CMD_COPY:    return "copy";
    case DELTA_CMD_ADD:     return "add";
    case DELTA_CMD_BIGCOPY: return "bigcopy";
    case DELTA_CMD_BIGADD:  return "bigadd";
    case DELTA_CMD_MOVE:    return "move";
    case DELTA_CMD_BIGMOVE: return "bigmove";
    default:                return nullptr;
    }
}

} // namespace

std::vector<uint8_t> encode_delta(
    const std::vector<PlacedCommand>& commands,
    bool inplace,
    size_t version_size,
    const Crc& src_crc,
    const Crc& dst_crc) {

    std::vector<uint8_t> out;
    put_bytes(out, DELTA_MAGIC);
    out.push_back(inplace ? DELTA_FLAG_INPLACE : 0);
    put_u32(out, to_u32(version_size, "version_size"));
    put_bytes(out, src_crc);
    put_bytes(out, dst_crc);

    for (const auto& cmd : commands) {
        std::visit(overloaded{
            [&](const PlacedCopy& c) {
                out.push_back(DELTA_CMD_COPY);
                put_u32(out, to_u32(c.src, "copy src offset"));
                put_u32(out, to_u32(c.dst, "copy dst offset"));
                put_u32(out, to_u32(c.length, "copy length"));
            },
            [&](const PlacedAdd& a) {
                out.push_back(DELTA_CMD_ADD);
                put_u32(out, to_u32(a.dst, "add dst offset"));
                put_u32(out, to_u32(a.data.size(), "add length"));
                put_bytes(out, a.data);
            },
            [&](const PlacedMove&) {
                throw DeltaError("PlacedMove requires DLT\\x04 format; use encode_delta_large");
            },
        }, cmd);
    }

    out.push_back(DELTA_CMD_END);
    return out;
}

std::vector<uint8_t> encode_delta_large(
    const std::vector<PlacedCommand>& commands,
    bool inplace,
    size_t version_size,
    const Crc& src_crc,
    const Crc& dst_crc,
    bool force_large) {

    std::vector<uint8_t> out;
    put_bytes(out, DELTA_MAGIC_LARGE);
    out.push_back(inplace ? DELTA_FLAG_INPLACE : 0);
    put_u64(out, version_size);
    put_bytes(out, src_crc);
    put_bytes(out, dst_crc);

    for (const auto& cmd : commands) {
        std::visit(overloaded{
            [&](const PlacedCopy& c) {
                put_command(out, DELTA_CMD_COPY, DELTA_CMD_BIGCOPY, force_large,
                            {c.src, c.dst, c.length});
            },
            [&](const PlacedAdd& a) {
                put_command(out, DELTA_CMD_ADD, DELTA_CMD_BIGADD, force_large,
                            {a.dst, a.data.size()});
                put_bytes(out, a.data);
            },
            [&](const PlacedMove& m) {
                put_command(out, DELTA_CMD_MOVE, DELTA_CMD_BIGMOVE, force_large,
                            {m.src, m.dst, m.length});
            },
        }, cmd);
    }

    out.push_back(DELTA_CMD_END);
    return out;
}

std::tuple<std::vector<PlacedCommand>, bool, size_t, Crc, Crc> decode_delta(
    std::span<const uint8_t> data) {

    const bool large = has_magic(data, DELTA_MAGIC_LARGE);
    if (!large && !has_magic(data, DELTA_MAGIC)) {
        throw DeltaError("not a delta file");
    }
    if (data.size() < (large ? DELTA_HEADER_SIZE_LARGE : DELTA_HEADER_SIZE)) {
        throw DeltaError("not a delta file");
    }

    Reader in(data);
    in.take(DELTA_MAGIC_SIZE);
    const bool inplace = (in.u8() & DELTA_FLAG_INPLACE) != 0;
    const size_t version_size = in.field(large);
    const Crc src_crc = in.crc();
    const Crc dst_crc = in.crc();

    std::vector<PlacedCommand> commands;
    for (;;) {
        if (in.at_end()) { throw DeltaError("missing END command"); }
        const uint8_t op = in.u8();
        if (op == DELTA_CMD_END) { break; }

        const char* name = command_name(op);
        if (!name) {
            throw DeltaError("unknown command type: " + std::to_string(op));
        }
        if (!large && op != DELTA_CMD_COPY && op != DELTA_CMD_ADD) {
            throw DeltaError("command type " + std::to_string(op) + " requires DLT\\x04 format");
        }
        const bool wide = op == DELTA_CMD_BIGCOPY || op == DELTA_CMD_BIGADD
                       || op == DELTA_CMD_BIGMOVE;
        const bool is_add = op == DELTA_CMD_ADD || op == DELTA_CMD_BIGADD;
        const bool is_move = op == DELTA_CMD_MOVE || op == DELTA_CMD_BIGMOVE;

        const size_t src = is_add ? 0 : in.field(wide);
        const size_t dst = in.field(wide);
        const size_t length = in.field(wide);
        std::span<const uint8_t> literal;
        if (is_add) { literal = in.take(length); }

        if (dst > version_size || length > version_size - dst) {
            throw DeltaError(std::string(name) + " command exceeds version size");
        }
        if (is_add) {
            commands.emplace_back(PlacedAdd{dst, {literal.begin(), literal.end()}});
        } else if (is_move) {
            // Written so that src + length cannot wrap around.
            if (src > dst || length > dst - src) {
                throw DeltaError(std::string(name)
                    + " src+length > dst: encoder ordering constraint violated");
            }
            commands.emplace_back(PlacedMove{src, dst, length});
        } else {
            commands.emplace_back(PlacedCopy{src, dst, length});
        }
    }
    if (!in.at_end()) { throw DeltaError("trailing data after END"); }

    return {std::move(commands), inplace, version_size, src_crc, dst_crc};
}

bool is_inplace_delta(std::span<const uint8_t> data) {
    return data.size() > DELTA_MAGIC_SIZE
        && (has_magic(data, DELTA_MAGIC) || has_magic(data, DELTA_MAGIC_LARGE))
        && (data[DELTA_MAGIC_SIZE] & DELTA_FLAG_INPLACE) != 0;
}

} // namespace delta
