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

// ── Big-endian u32 helpers ────────────────────────────────────────────

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
	for (i = 0; i < cmds->len; i++) {
		const delta_placed_command_t *cmd = &cmds->data[i];
		if (cmd->tag == PCMD_COPY) {
			*p++ = DELTA_CMD_COPY;
			write_u32_be(&p, (uint32_t)cmd->copy.src);
			write_u32_be(&p, (uint32_t)cmd->copy.dst);
			write_u32_be(&p, (uint32_t)cmd->copy.length);
		} else {
			*p++ = DELTA_CMD_ADD;
			write_u32_be(&p, (uint32_t)cmd->add.dst);
			write_u32_be(&p, (uint32_t)cmd->add.length);
			memcpy(p, cmd->add.data, cmd->add.length);
			p += cmd->add.length;
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

// ── Decode ────────────────────────────────────────────────────────────

delta_decode_result_t
delta_decode(const uint8_t *data, size_t len)
{
	delta_decode_result_t result;
	size_t pos;

	delta_decode_result_init(&result);

	if (len < DELTA_HEADER_SIZE
	    || memcmp(data, DELTA_MAGIC, sizeof(DELTA_MAGIC)) != 0) {
		fprintf(stderr, "delta_decode: not a delta file\n");
		exit(1);
	}

	result.inplace = (data[sizeof(DELTA_MAGIC)] & DELTA_FLAG_INPLACE) != 0;
	result.version_size = read_u32_be(&data[sizeof(DELTA_MAGIC) + 1]);
	{
		size_t crc_off = sizeof(DELTA_MAGIC) + 1 + DELTA_U32_SIZE;
		memcpy(result.src_crc, &data[crc_off], DELTA_CRC_SIZE);
		memcpy(result.dst_crc, &data[crc_off + DELTA_CRC_SIZE],
		       DELTA_CRC_SIZE);
	}
	pos = DELTA_HEADER_SIZE;

	while (pos < len) {
		uint8_t t = data[pos++];
		delta_placed_command_t cmd = {0};

		if (t == DELTA_CMD_END) {
			if (pos != len) {
				decode_fail(&result, "trailing data after END");
			}
			return result;
		}

		if (t == DELTA_CMD_COPY) {
			if (pos + DELTA_COPY_PAYLOAD > len) {
				decode_fail(&result, "truncated COPY");
			}
			cmd.tag = PCMD_COPY;
			cmd.copy.src = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
			cmd.copy.dst = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
			cmd.copy.length = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
			if (cmd.copy.dst > result.version_size ||
			    cmd.copy.length > result.version_size - cmd.copy.dst) {
				decode_fail(&result, "COPY writes past version size");
			}
		} else if (t == DELTA_CMD_ADD) {
			size_t dlen;
			if (pos + DELTA_ADD_HEADER > len) {
				decode_fail(&result, "truncated ADD");
			}
			cmd.tag = PCMD_ADD;
			cmd.add.dst = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
			dlen = read_u32_be(&data[pos]); pos += DELTA_U32_SIZE;
			cmd.add.length = dlen;
			if (cmd.add.dst > result.version_size ||
			    dlen > result.version_size - cmd.add.dst) {
				decode_fail(&result, "ADD writes past version size");
			}
			if (pos + dlen > len) {
				decode_fail(&result, "truncated ADD data");
			}
			cmd.add.data = delta_malloc(dlen);
			if (dlen > 0) {
				memcpy(cmd.add.data, &data[pos], dlen);
			}
			pos += dlen;
		} else {
			decode_fail(&result, "unknown command type");
		}

		delta_placed_commands_push(&result.commands, cmd);
	}

	decode_fail(&result, "missing END");
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
