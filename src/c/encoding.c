/*
 * encoding.c — Unified binary delta format encode/decode
 *
 * Format:
 *   Header: magic (4 bytes) + flags (1 byte) + version_size (u32 BE)
 *   Commands:
 *     END:  type=0
 *     COPY: type=1, src:u32be, dst:u32be, len:u32be
 *     ADD:  type=2, dst:u32be, len:u32be, data
 */

#include "delta.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ── Big-endian u32 helpers ────────────────────────────────────────── */

static void
write_u32_be(uint8_t **p, uint32_t val)
{
	(*p)[0] = (uint8_t)(val >> 24);
	(*p)[1] = (uint8_t)(val >> 16);
	(*p)[2] = (uint8_t)(val >> 8);
	(*p)[3] = (uint8_t)(val);
	*p += 4;
}

static uint32_t
read_u32_be(const uint8_t *p)
{
	return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) |
	       ((uint32_t)p[2] << 8)  | (uint32_t)p[3];
}

/* ── Encode ────────────────────────────────────────────────────────── */

delta_buffer_t
delta_encode(const delta_placed_commands_t *cmds, bool inplace,
             size_t version_size)
{
	/* Estimate size: header(9) + per-cmd overhead */
	size_t est = 9 + cmds->len * 14 + 1;
	size_t i;
	uint8_t *buf, *p;

	for (i = 0; i < cmds->len; i++)
		if (cmds->data[i].tag == PCMD_ADD)
			est += cmds->data[i].add.length;

	buf = malloc(est);
	p = buf;

	/* Header */
	memcpy(p, DELTA_MAGIC, 4); p += 4;
	*p++ = inplace ? DELTA_FLAG_INPLACE : 0;
	write_u32_be(&p, (uint32_t)version_size);

	/* Commands */
	for (i = 0; i < cmds->len; i++) {
		const delta_placed_command_t *cmd = &cmds->data[i];
		if (cmd->tag == PCMD_COPY) {
			*p++ = 1;
			write_u32_be(&p, (uint32_t)cmd->copy.src);
			write_u32_be(&p, (uint32_t)cmd->copy.dst);
			write_u32_be(&p, (uint32_t)cmd->copy.length);
		} else {
			*p++ = 2;
			write_u32_be(&p, (uint32_t)cmd->add.dst);
			write_u32_be(&p, (uint32_t)cmd->add.length);
			memcpy(p, cmd->add.data, cmd->add.length);
			p += cmd->add.length;
		}
	}

	*p++ = 0;  /* END */

	delta_buffer_t result;
	result.data = buf;
	result.len = (size_t)(p - buf);
	return result;
}

/* ── Decode ────────────────────────────────────────────────────────── */

delta_decode_result_t
delta_decode(const uint8_t *data, size_t len)
{
	delta_decode_result_t result;
	size_t pos;

	delta_placed_commands_init(&result.commands);
	result.inplace = false;
	result.version_size = 0;

	if (len < 9 || memcmp(data, DELTA_MAGIC, 4) != 0) {
		fprintf(stderr, "delta_decode: not a delta file\n");
		exit(1);
	}

	result.inplace = (data[4] & DELTA_FLAG_INPLACE) != 0;
	result.version_size = read_u32_be(&data[5]);
	pos = 9;

	while (pos < len) {
		uint8_t t = data[pos++];
		delta_placed_command_t cmd;

		if (t == 0) /* END */
			return result;

		if (t == 1) { /* COPY */
			if (pos + 12 > len) {
				fprintf(stderr, "delta_decode: truncated COPY\n");
				exit(1);
			}
			cmd.tag = PCMD_COPY;
			cmd.copy.src = read_u32_be(&data[pos]); pos += 4;
			cmd.copy.dst = read_u32_be(&data[pos]); pos += 4;
			cmd.copy.length = read_u32_be(&data[pos]); pos += 4;
		} else if (t == 2) { /* ADD */
			size_t dlen;
			if (pos + 8 > len) {
				fprintf(stderr, "delta_decode: truncated ADD\n");
				exit(1);
			}
			cmd.tag = PCMD_ADD;
			cmd.add.dst = read_u32_be(&data[pos]); pos += 4;
			dlen = read_u32_be(&data[pos]); pos += 4;
			cmd.add.length = dlen;
			if (pos + dlen > len) {
				fprintf(stderr, "delta_decode: truncated ADD data\n");
				exit(1);
			}
			cmd.add.data = malloc(dlen);
			memcpy(cmd.add.data, &data[pos], dlen);
			pos += dlen;
		} else {
			fprintf(stderr, "delta_decode: unknown command type %d\n", t);
			exit(1);
		}

		delta_placed_commands_push(&result.commands, cmd);
	}

	return result;
}
