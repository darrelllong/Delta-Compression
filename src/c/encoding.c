// encoding.c — Unified binary delta format encode/decode
//
// Format:
//   Header: magic (4 bytes) + flags (1 byte) + version_size (u32 BE)
//   Commands:
//     END:  type=0
//     COPY: type=1, src:u32be, dst:u32be, len:u32be
//     ADD:  type=2, dst:u32be, len:u32be, data

#include "delta.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void
decode_fail(delta_decode_result_t *result, const char *message)
{
	fprintf(stderr, "delta_decode: %s\n", message);
	delta_decode_result_free(result);
	exit(1);
}

// ── 32-bit overflow guard ─────────────────────────────────────────────

static void
check_u32(size_t val, const char *field)
{
	if (val > UINT32_MAX) {
		fprintf(stderr, "delta_encode: %s exceeds 4 GiB (32-bit format limit)\n", field);
		exit(1);
	}
}

// ── Big-endian integer helpers ────────────────────────────────────────

static void
write_u32_be(uint8_t **p, uint32_t val)
{
	(*p)[0] = (uint8_t)(val >> 24);
	(*p)[1] = (uint8_t)(val >> 16);
	(*p)[2] = (uint8_t)(val >> 8);
	(*p)[3] = (uint8_t)(val);
	*p += DELTA_U32_SIZE;
}

static uint32_t
read_u32_be(const uint8_t *p)
{
	return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) |
	       ((uint32_t)p[2] << 8)  | (uint32_t)p[3];
}

static void
write_u64_be(uint8_t **p, uint64_t val)
{
	(*p)[0] = (uint8_t)(val >> 56);
	(*p)[1] = (uint8_t)(val >> 48);
	(*p)[2] = (uint8_t)(val >> 40);
	(*p)[3] = (uint8_t)(val >> 32);
	(*p)[4] = (uint8_t)(val >> 24);
	(*p)[5] = (uint8_t)(val >> 16);
	(*p)[6] = (uint8_t)(val >> 8);
	(*p)[7] = (uint8_t)(val);
	*p += DELTA_U64_SIZE;
}

static uint64_t
read_u64_be(const uint8_t *p)
{
	return ((uint64_t)p[0] << 56) | ((uint64_t)p[1] << 48) |
	       ((uint64_t)p[2] << 40) | ((uint64_t)p[3] << 32) |
	       ((uint64_t)p[4] << 24) | ((uint64_t)p[5] << 16) |
	       ((uint64_t)p[6] << 8)  | (uint64_t)p[7];
}

// ── Encode ────────────────────────────────────────────────────────────

delta_buffer_t
delta_encode(const delta_placed_commands_t *cmds, bool inplace,
             size_t version_size,
             const uint8_t src_crc[DELTA_CRC_SIZE],
             const uint8_t dst_crc[DELTA_CRC_SIZE])
{
	// Estimate size: header + per-cmd overhead
	size_t est = DELTA_HEADER_SIZE + cmds->len * 14 + 1;
	size_t i;
	uint8_t *buf, *p;

	for (i = 0; i < cmds->len; i++) {
		if (cmds->data[i].tag == PCMD_ADD) {
			est += cmds->data[i].add.length;
		}
	}

	buf = delta_malloc(est);
	p = buf;

	// Header
	memcpy(p, DELTA_MAGIC, sizeof(DELTA_MAGIC));
	p += sizeof(DELTA_MAGIC);
	*p++ = inplace ? DELTA_FLAG_INPLACE : 0;
	write_u32_be(&p, (uint32_t)version_size);
	memcpy(p, src_crc, DELTA_CRC_SIZE); p += DELTA_CRC_SIZE;
	memcpy(p, dst_crc, DELTA_CRC_SIZE); p += DELTA_CRC_SIZE;

	// Commands
	check_u32(version_size, "version_size");
	for (i = 0; i < cmds->len; i++) {
		const delta_placed_command_t *cmd = &cmds->data[i];
		if (cmd->tag == PCMD_COPY) {
			check_u32(cmd->copy.src,    "copy src offset");
			check_u32(cmd->copy.dst,    "copy dst offset");
			check_u32(cmd->copy.length, "copy length");
			*p++ = DELTA_CMD_COPY;
			write_u32_be(&p, (uint32_t)cmd->copy.src);
			write_u32_be(&p, (uint32_t)cmd->copy.dst);
			write_u32_be(&p, (uint32_t)cmd->copy.length);
		} else if (cmd->tag == PCMD_ADD) {
			check_u32(cmd->add.dst,    "add dst offset");
			check_u32(cmd->add.length, "add length");
			*p++ = DELTA_CMD_ADD;
			write_u32_be(&p, (uint32_t)cmd->add.dst);
			write_u32_be(&p, (uint32_t)cmd->add.length);
			memcpy(p, cmd->add.data, cmd->add.length);
			p += cmd->add.length;
		} else {
			fprintf(stderr,
			        "delta_encode: MOVE commands require DLT\\x04 format;"
			        " use delta_encode_large\n");
			exit(1);
		}
	}

	*p++ = DELTA_CMD_END;

	delta_buffer_t result;
	result.data = buf;
	result.len = (size_t)(p - buf);
	return result;
}

// ── Encode V4 ─────────────────────────────────────────────────────────

delta_buffer_t
delta_encode_large(const delta_placed_commands_t *cmds, bool inplace,
                size_t version_size,
                const uint8_t src_crc[DELTA_CRC_SIZE],
                const uint8_t dst_crc[DELTA_CRC_SIZE],
                bool force_large)
{
	// Estimate size: V4 header + per-cmd big overhead
	size_t est = DELTA_HEADER_SIZE_LARGE + cmds->len * 26 + 1;
	size_t i;
	uint8_t *buf, *p;

	for (i = 0; i < cmds->len; i++) {
		if (cmds->data[i].tag == PCMD_ADD) {
			est += cmds->data[i].add.length;
		}
	}

	buf = delta_malloc(est);
	p = buf;

	// V4 header: magic(4) + flags(1) + version_size(u64 BE) + crcs(16)
	memcpy(p, DELTA_MAGIC_LARGE, sizeof(DELTA_MAGIC_LARGE));
	p += sizeof(DELTA_MAGIC_LARGE);
	*p++ = inplace ? DELTA_FLAG_INPLACE : 0;
	write_u64_be(&p, (uint64_t)version_size);
	memcpy(p, src_crc, DELTA_CRC_SIZE); p += DELTA_CRC_SIZE;
	memcpy(p, dst_crc, DELTA_CRC_SIZE); p += DELTA_CRC_SIZE;

	for (i = 0; i < cmds->len; i++) {
		const delta_placed_command_t *cmd = &cmds->data[i];
		if (cmd->tag == PCMD_COPY) {
			if (!force_large &&
			    cmd->copy.src  <= UINT32_MAX &&
			    cmd->copy.dst  <= UINT32_MAX &&
			    cmd->copy.length <= UINT32_MAX) {
				*p++ = DELTA_CMD_COPY;
				write_u32_be(&p, (uint32_t)cmd->copy.src);
				write_u32_be(&p, (uint32_t)cmd->copy.dst);
				write_u32_be(&p, (uint32_t)cmd->copy.length);
			} else {
				*p++ = DELTA_CMD_BIGCOPY;
				write_u64_be(&p, (uint64_t)cmd->copy.src);
				write_u64_be(&p, (uint64_t)cmd->copy.dst);
				write_u64_be(&p, (uint64_t)cmd->copy.length);
			}
		} else if (cmd->tag == PCMD_ADD) {
			if (!force_large &&
			    cmd->add.dst    <= UINT32_MAX &&
			    cmd->add.length <= UINT32_MAX) {
				*p++ = DELTA_CMD_ADD;
				write_u32_be(&p, (uint32_t)cmd->add.dst);
				write_u32_be(&p, (uint32_t)cmd->add.length);
			} else {
				*p++ = DELTA_CMD_BIGADD;
				write_u64_be(&p, (uint64_t)cmd->add.dst);
				write_u64_be(&p, (uint64_t)cmd->add.length);
			}
			memcpy(p, cmd->add.data, cmd->add.length);
			p += cmd->add.length;
		} else { /* PCMD_MOVE */
			if (!force_large &&
			    cmd->move.src  <= UINT32_MAX &&
			    cmd->move.dst  <= UINT32_MAX &&
			    cmd->move.length <= UINT32_MAX) {
				*p++ = DELTA_CMD_MOVE;
				write_u32_be(&p, (uint32_t)cmd->move.src);
				write_u32_be(&p, (uint32_t)cmd->move.dst);
				write_u32_be(&p, (uint32_t)cmd->move.length);
			} else {
				*p++ = DELTA_CMD_BIGMOVE;
				write_u64_be(&p, (uint64_t)cmd->move.src);
				write_u64_be(&p, (uint64_t)cmd->move.dst);
				write_u64_be(&p, (uint64_t)cmd->move.length);
			}
		}
	}

	*p++ = DELTA_CMD_END;

	delta_buffer_t result;
	result.data = buf;
	result.len = (size_t)(p - buf);
	return result;
}

// ── delta_buffer_t constructor / destructor ───────────────────────────

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
	buf->data = NULL;
	buf->len = 0;
}

// ── Decode helpers ────────────────────────────────────────────────────

static void
validate_range(delta_decode_result_t *result, size_t dst, size_t length,
               size_t version_size, const char *kind)
{
	if (dst > version_size || length > version_size - dst) {
		char msg[128];
		snprintf(msg, sizeof(msg), "%s writes past version size", kind);
		decode_fail(result, msg);
	}
}

// consume_copy_u32 / consume_add_u32: shared between V3 and V4 loops.

static size_t
consume_copy_u32(delta_decode_result_t *result,
                 const uint8_t *data, size_t len, size_t pos,
                 delta_placed_command_t *cmd)
{
	if (pos + DELTA_COPY_PAYLOAD > len)
		decode_fail(result, "truncated COPY");
	cmd->tag = PCMD_COPY;
	cmd->copy.src    = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
	cmd->copy.dst    = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
	cmd->copy.length = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
	validate_range(result, cmd->copy.dst, cmd->copy.length,
	               result->version_size, "COPY");
	return pos;
}

static size_t
consume_add_u32(delta_decode_result_t *result,
                const uint8_t *data, size_t len, size_t pos,
                delta_placed_command_t *cmd)
{
	size_t dlen;
	if (pos + DELTA_ADD_HEADER > len)
		decode_fail(result, "truncated ADD");
	cmd->tag     = PCMD_ADD;
	cmd->add.dst = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
	dlen         = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
	cmd->add.length = dlen;
	validate_range(result, cmd->add.dst, dlen, result->version_size, "ADD");
	if (pos + dlen > len)
		decode_fail(result, "truncated ADD data");
	cmd->add.data = delta_malloc(dlen);
	if (dlen > 0) memcpy(cmd->add.data, &data[pos], dlen);
	return pos + dlen;
}

// ── Decode small (u32-only) command loop — DLT\x03 ───────────────────

static void
decode_commands_small(delta_decode_result_t *result,
                   const uint8_t *data, size_t len, size_t pos)
{
	while (pos < len) {
		uint8_t t = data[pos++];
		delta_placed_command_t cmd;
		memset(&cmd, 0, sizeof(cmd));

		if (t == DELTA_CMD_END) {
			if (pos != len)
				decode_fail(result, "trailing data after END");
			return;
		}

		if (t == DELTA_CMD_COPY) {
			pos = consume_copy_u32(result, data, len, pos, &cmd);
		} else if (t == DELTA_CMD_ADD) {
			pos = consume_add_u32(result, data, len, pos, &cmd);
		} else if (t == DELTA_CMD_BIGCOPY || t == DELTA_CMD_BIGADD ||
		           t == DELTA_CMD_MOVE    || t == DELTA_CMD_BIGMOVE) {
			decode_fail(result, "command type requires DLT\\x04 format");
		} else {
			decode_fail(result, "unknown command type");
		}

		delta_placed_commands_push(&result->commands, cmd);
	}
	decode_fail(result, "missing END");
}

// ── Decode large (u32+u64+MOVE) command loop — DLT\x04 ───────────────

static void
decode_commands_large(delta_decode_result_t *result,
                   const uint8_t *data, size_t len, size_t pos)
{
	while (pos < len) {
		uint8_t t = data[pos++];
		delta_placed_command_t cmd;
		memset(&cmd, 0, sizeof(cmd));

		if (t == DELTA_CMD_END) {
			if (pos != len)
				decode_fail(result, "trailing data after END");
			return;
		}

		if (t == DELTA_CMD_COPY) {
			pos = consume_copy_u32(result, data, len, pos, &cmd);
		} else if (t == DELTA_CMD_ADD) {
			pos = consume_add_u32(result, data, len, pos, &cmd);
		} else if (t == DELTA_CMD_BIGCOPY) {
			if (pos + DELTA_BIGCOPY_PAYLOAD > len)
				decode_fail(result, "truncated BIGCOPY");
			cmd.tag = PCMD_COPY;
			cmd.copy.src    = (size_t)read_u64_be(&data[pos]); pos += DELTA_U64_SIZE;
			cmd.copy.dst    = (size_t)read_u64_be(&data[pos]); pos += DELTA_U64_SIZE;
			cmd.copy.length = (size_t)read_u64_be(&data[pos]); pos += DELTA_U64_SIZE;
			validate_range(result, cmd.copy.dst, cmd.copy.length,
			               result->version_size, "BIGCOPY");
		} else if (t == DELTA_CMD_BIGADD) {
			size_t dlen;
			if (pos + DELTA_BIGADD_HEADER > len)
				decode_fail(result, "truncated BIGADD");
			cmd.tag     = PCMD_ADD;
			cmd.add.dst = (size_t)read_u64_be(&data[pos]); pos += DELTA_U64_SIZE;
			dlen        = (size_t)read_u64_be(&data[pos]); pos += DELTA_U64_SIZE;
			cmd.add.length = dlen;
			validate_range(result, cmd.add.dst, dlen,
			               result->version_size, "BIGADD");
			if (pos + dlen > len)
				decode_fail(result, "truncated BIGADD data");
			cmd.add.data = delta_malloc(dlen);
			if (dlen > 0) memcpy(cmd.add.data, &data[pos], dlen);
			pos += dlen;
		} else if (t == DELTA_CMD_MOVE) {
			if (pos + DELTA_COPY_PAYLOAD > len)
				decode_fail(result, "truncated MOVE");
			cmd.tag = PCMD_MOVE;
			cmd.move.src    = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
			cmd.move.dst    = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
			cmd.move.length = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
			validate_range(result, cmd.move.dst, cmd.move.length,
			               result->version_size, "MOVE");
		} else if (t == DELTA_CMD_BIGMOVE) {
			if (pos + DELTA_BIGCOPY_PAYLOAD > len)
				decode_fail(result, "truncated BIGMOVE");
			cmd.tag = PCMD_MOVE;
			cmd.move.src    = (size_t)read_u64_be(&data[pos]); pos += DELTA_U64_SIZE;
			cmd.move.dst    = (size_t)read_u64_be(&data[pos]); pos += DELTA_U64_SIZE;
			cmd.move.length = (size_t)read_u64_be(&data[pos]); pos += DELTA_U64_SIZE;
			validate_range(result, cmd.move.dst, cmd.move.length,
			               result->version_size, "BIGMOVE");
		} else {
			decode_fail(result, "unknown command type");
		}

		delta_placed_commands_push(&result->commands, cmd);
	}
	decode_fail(result, "missing END");
}

// ── Decode ────────────────────────────────────────────────────────────

delta_decode_result_t
delta_decode(const uint8_t *data, size_t len)
{
	delta_decode_result_t result;
	delta_decode_result_init(&result);

	if (len < 4) {
		fprintf(stderr, "delta_decode: not a delta file\n");
		exit(1);
	}

	if (memcmp(data, DELTA_MAGIC, 4) == 0) {
		// DLT\x03
		if (len < DELTA_HEADER_SIZE) {
			fprintf(stderr, "delta_decode: not a delta file\n");
			exit(1);
		}
		result.inplace = (data[4] & DELTA_FLAG_INPLACE) != 0;
		result.version_size = read_u32_be(&data[5]);
		memcpy(result.src_crc, &data[9],                   DELTA_CRC_SIZE);
		memcpy(result.dst_crc, &data[9 + DELTA_CRC_SIZE],  DELTA_CRC_SIZE);
		decode_commands_small(&result, data, len, DELTA_HEADER_SIZE);
	} else if (memcmp(data, DELTA_MAGIC_LARGE, 4) == 0) {
		// DLT\x04
		if (len < DELTA_HEADER_SIZE_LARGE) {
			fprintf(stderr, "delta_decode: not a delta file\n");
			exit(1);
		}
		result.inplace = (data[4] & DELTA_FLAG_INPLACE) != 0;
		result.version_size = (size_t)read_u64_be(&data[5]);
		memcpy(result.src_crc, &data[13],                   DELTA_CRC_SIZE);
		memcpy(result.dst_crc, &data[13 + DELTA_CRC_SIZE],  DELTA_CRC_SIZE);
		decode_commands_large(&result, data, len, DELTA_HEADER_SIZE_LARGE);
	} else {
		fprintf(stderr, "delta_decode: not a delta file\n");
		exit(1);
	}

	return result;
}

// ── delta_decode_result_t constructor / destructor ────────────────────

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
