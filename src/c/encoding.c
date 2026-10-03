// The binary delta format, described in delta.h.

#include "internal.h"

#include <stdarg.h>

static const uint8_t magic_small[4] = {'D', 'L', 'T', 0x03};
static const uint8_t magic_large[4] = {'D', 'L', 'T', 0x04};

// Integers are big-endian, 4 or 8 bytes wide.
enum { U32 = 4, U64 = 8 };

static inline uint8_t *
put_u32(uint8_t *p, uint32_t val)
{
	p[0] = (uint8_t)(val >> 24);
	p[1] = (uint8_t)(val >> 16);
	p[2] = (uint8_t)(val >> 8);
	p[3] = (uint8_t)val;
	return p + 4;
}

static inline uint32_t
get_u32(const uint8_t *p)
{
	return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 |
	       (uint32_t)p[2] << 8 | (uint32_t)p[3];
}

static inline uint8_t *
put_uint(uint8_t *p, uint64_t val, int width)
{
	if (width == U64) {
		p = put_u32(p, (uint32_t)(val >> 32));
	}
	return put_u32(p, (uint32_t)val);
}

static inline uint64_t
get_uint(const uint8_t *p, int width)
{
	if (width == U64) {
		return (uint64_t)get_u32(p) << 32 | get_u32(p + 4);
	}
	return get_u32(p);
}

void
delta_buffer_init(delta_buffer_t *buf)
{
	buf->data = NULL;
	buf->len = 0;
}

void
delta_buffer_free(delta_buffer_t *buf)
{
	free(buf->data);
	delta_buffer_init(buf);
}

// Encoding.

// encoded_bound is the most bytes a delta of the commands can take: the
// large header, a type byte and three 8-byte fields for each command, the
// literal data, and END.
static size_t
encoded_bound(const delta_placed_commands_t *cmds)
{
	size_t n = DELTA_HEADER_SIZE_LARGE + cmds->len * (1 + 3 * U64) + 1;
	for (size_t i = 0; i < cmds->len; i++) {
		if (cmds->data[i].tag == PCMD_ADD) {
			n += cmds->data[i].add.length;
		}
	}
	return n;
}

static uint8_t *
put_header(uint8_t *p, const uint8_t magic[4], bool inplace,
           size_t version_size, int width,
           const uint8_t src_crc[DELTA_CRC_SIZE],
           const uint8_t dst_crc[DELTA_CRC_SIZE])
{
	memcpy(p, magic, 4);
	p += 4;
	*p++ = inplace ? DELTA_FLAG_INPLACE : 0;
	p = put_uint(p, version_size, width);
	memcpy(p, src_crc, DELTA_CRC_SIZE);
	p += DELTA_CRC_SIZE;
	memcpy(p, dst_crc, DELTA_CRC_SIZE);
	return p + DELTA_CRC_SIZE;
}

// put_command writes cmd with fields of the given width.  The type byte of
// each kind of command is its 32-bit code or its 64-bit one.
static uint8_t *
put_command(uint8_t *p, const delta_placed_command_t *cmd, int width)
{
	bool big = width == U64;

	switch (cmd->tag) {
	case PCMD_COPY:
		*p++ = big ? DELTA_CMD_BIGCOPY : DELTA_CMD_COPY;
		p = put_uint(p, cmd->copy.src, width);
		p = put_uint(p, cmd->copy.dst, width);
		p = put_uint(p, cmd->copy.length, width);
		break;
	case PCMD_MOVE:
		*p++ = big ? DELTA_CMD_BIGMOVE : DELTA_CMD_MOVE;
		p = put_uint(p, cmd->move.src, width);
		p = put_uint(p, cmd->move.dst, width);
		p = put_uint(p, cmd->move.length, width);
		break;
	case PCMD_ADD:
		*p++ = big ? DELTA_CMD_BIGADD : DELTA_CMD_ADD;
		p = put_uint(p, cmd->add.dst, width);
		p = put_uint(p, cmd->add.length, width);
		if (cmd->add.length > 0) {
			memcpy(p, cmd->add.data, cmd->add.length);
		}
		p += cmd->add.length;
		break;
	}
	return p;
}

static void
check_u32(size_t val, const char *field)
{
	if (val > UINT32_MAX) {
		fprintf(stderr,
		        "delta_encode: %s exceeds 4 GiB (32-bit format limit)\n",
		        field);
		exit(1);
	}
}

delta_buffer_t
delta_encode(const delta_placed_commands_t *cmds, bool inplace,
             size_t version_size,
             const uint8_t src_crc[DELTA_CRC_SIZE],
             const uint8_t dst_crc[DELTA_CRC_SIZE])
{
	check_u32(version_size, "version_size");
	for (size_t i = 0; i < cmds->len; i++) {
		const delta_placed_command_t *cmd = &cmds->data[i];
		switch (cmd->tag) {
		case PCMD_COPY:
			check_u32(cmd->copy.src, "copy src offset");
			check_u32(cmd->copy.dst, "copy dst offset");
			check_u32(cmd->copy.length, "copy length");
			break;
		case PCMD_ADD:
			check_u32(cmd->add.dst, "add dst offset");
			check_u32(cmd->add.length, "add length");
			break;
		case PCMD_MOVE:
			fprintf(stderr,
			        "delta_encode: MOVE commands require DLT\\x04 format;"
			        " use delta_encode_large\n");
			exit(1);
		}
	}

	uint8_t *buf = delta_malloc(encoded_bound(cmds));
	uint8_t *p = put_header(buf, magic_small, inplace, version_size, U32,
	                        src_crc, dst_crc);
	for (size_t i = 0; i < cmds->len; i++) {
		p = put_command(p, &cmds->data[i], U32);
	}
	*p++ = DELTA_CMD_END;
	return (delta_buffer_t){ buf, (size_t)(p - buf) };
}

static bool
fits_u32(const delta_placed_command_t *cmd)
{
	switch (cmd->tag) {
	case PCMD_COPY:
		return cmd->copy.src <= UINT32_MAX &&
		       cmd->copy.dst <= UINT32_MAX &&
		       cmd->copy.length <= UINT32_MAX;
	case PCMD_MOVE:
		return cmd->move.src <= UINT32_MAX &&
		       cmd->move.dst <= UINT32_MAX &&
		       cmd->move.length <= UINT32_MAX;
	case PCMD_ADD:
		return cmd->add.dst <= UINT32_MAX &&
		       cmd->add.length <= UINT32_MAX;
	}
	return false;
}

delta_buffer_t
delta_encode_large(const delta_placed_commands_t *cmds, bool inplace,
                   size_t version_size,
                   const uint8_t src_crc[DELTA_CRC_SIZE],
                   const uint8_t dst_crc[DELTA_CRC_SIZE],
                   bool force_large)
{
	uint8_t *buf = delta_malloc(encoded_bound(cmds));
	uint8_t *p = put_header(buf, magic_large, inplace, version_size, U64,
	                        src_crc, dst_crc);
	for (size_t i = 0; i < cmds->len; i++) {
		const delta_placed_command_t *cmd = &cmds->data[i];
		p = put_command(p, cmd,
		                !force_large && fits_u32(cmd) ? U32 : U64);
	}
	*p++ = DELTA_CMD_END;
	return (delta_buffer_t){ buf, (size_t)(p - buf) };
}

// Decoding.

void
delta_decode_result_init(delta_decode_result_t *dr)
{
	delta_placed_commands_init(&dr->commands);
	dr->inplace = false;
	dr->version_size = 0;
	memset(dr->src_crc, 0, DELTA_CRC_SIZE);
	memset(dr->dst_crc, 0, DELTA_CRC_SIZE);
}

void
delta_decode_result_free(delta_decode_result_t *dr)
{
	delta_placed_commands_free(&dr->commands);
}

// A decoder_t is the state of one delta_decode call.
typedef struct {
	const uint8_t *data;
	size_t len;
	size_t pos;
	delta_decode_result_t *result;
} decoder_t;

static _Noreturn void fail(decoder_t *d, const char *fmt, ...)
	__attribute__((format(printf, 2, 3)));

static _Noreturn void
fail(decoder_t *d, const char *fmt, ...)
{
	va_list ap;

	fputs("delta_decode: ", stderr);
	va_start(ap, fmt);
	vfprintf(stderr, fmt, ap);
	va_end(ap);
	fputc('\n', stderr);
	delta_decode_result_free(d->result);
	exit(1);
}

// get_field reads the next field.  The caller has checked that it is there.
static inline size_t
get_field(decoder_t *d, int width)
{
	uint64_t val = get_uint(&d->data[d->pos], width);
	d->pos += width;
	return (size_t)val;
}

// decode_command reads a command whose type byte has been consumed and
// appends it to the result.  The type byte determines the kind of command,
// the width of its fields, and its name in messages.
static inline void
decode_command(decoder_t *d, delta_pcmd_tag_t tag, int width,
               const char *name)
{
	size_t nfields = tag == PCMD_ADD ? 2 : 3;
	delta_placed_command_t cmd = { .tag = tag };
	size_t dst, length;

	if (nfields * width > d->len - d->pos) {
		fail(d, "truncated %s", name);
	}
	switch (tag) {
	case PCMD_COPY:
		cmd.copy.src = get_field(d, width);
		cmd.copy.dst = dst = get_field(d, width);
		cmd.copy.length = length = get_field(d, width);
		break;
	case PCMD_MOVE:
		cmd.move.src = get_field(d, width);
		cmd.move.dst = dst = get_field(d, width);
		cmd.move.length = length = get_field(d, width);
		break;
	default:
		cmd.add.dst = dst = get_field(d, width);
		cmd.add.length = length = get_field(d, width);
		break;
	}

	size_t version_size = d->result->version_size;
	if (dst > version_size || length > version_size - dst) {
		fail(d, "%s writes past version size", name);
	}
	if (tag == PCMD_ADD) {
		if (length > d->len - d->pos) {
			fail(d, "truncated %s data", name);
		}
		cmd.add.data = delta_memdup(&d->data[d->pos], length);
		d->pos += length;
	}
	delta_placed_commands_push(&d->result->commands, cmd);
}

// decode_commands reads commands up to END, which must be the last byte.
// The small format admits only COPY and ADD.
static void
decode_commands(decoder_t *d, bool large)
{
	while (d->pos < d->len) {
		uint8_t t = d->data[d->pos++];

		if (!large && t > DELTA_CMD_ADD && t <= DELTA_CMD_BIGMOVE) {
			fail(d, "command type requires DLT\\x04 format");
		}
		switch (t) {
		case DELTA_CMD_END:
			if (d->pos != d->len) {
				fail(d, "trailing data after END");
			}
			return;
		case DELTA_CMD_COPY:
			decode_command(d, PCMD_COPY, U32, "COPY");
			break;
		case DELTA_CMD_ADD:
			decode_command(d, PCMD_ADD, U32, "ADD");
			break;
		case DELTA_CMD_BIGCOPY:
			decode_command(d, PCMD_COPY, U64, "BIGCOPY");
			break;
		case DELTA_CMD_BIGADD:
			decode_command(d, PCMD_ADD, U64, "BIGADD");
			break;
		case DELTA_CMD_MOVE:
			decode_command(d, PCMD_MOVE, U32, "MOVE");
			break;
		case DELTA_CMD_BIGMOVE:
			decode_command(d, PCMD_MOVE, U64, "BIGMOVE");
			break;
		default:
			fail(d, "unknown command type");
		}
	}
	fail(d, "missing END");
}

delta_decode_result_t
delta_decode(const uint8_t *data, size_t len)
{
	delta_decode_result_t result;
	delta_decode_result_init(&result);
	decoder_t d = { data, len, 0, &result };

	bool large;
	if (len >= 4 && memcmp(data, magic_small, 4) == 0) {
		large = false;
	} else if (len >= 4 && memcmp(data, magic_large, 4) == 0) {
		large = true;
	} else {
		fail(&d, "not a delta file");
	}
	if (len < (large ? DELTA_HEADER_SIZE_LARGE : DELTA_HEADER_SIZE)) {
		fail(&d, "not a delta file");
	}

	int width = large ? U64 : U32;
	result.inplace = (data[4] & DELTA_FLAG_INPLACE) != 0;
	result.version_size = (size_t)get_uint(&data[5], width);
	d.pos = 5 + width;
	memcpy(result.src_crc, &data[d.pos], DELTA_CRC_SIZE);
	d.pos += DELTA_CRC_SIZE;
	memcpy(result.dst_crc, &data[d.pos], DELTA_CRC_SIZE);
	d.pos += DELTA_CRC_SIZE;

	decode_commands(&d, large);
	return result;
}
