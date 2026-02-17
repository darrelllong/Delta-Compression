/*
 * apply.c — Command placement, application, and summary statistics
 */

#include "delta.h"

#include <stdlib.h>
#include <string.h>

/* ── Dynamic array helpers ─────────────────────────────────────────── */

void
delta_commands_init(delta_commands_t *c)
{
	c->data = NULL;
	c->len = 0;
	c->cap = 0;
}

void
delta_commands_push(delta_commands_t *c, delta_command_t cmd)
{
	if (c->len == c->cap) {
		c->cap = c->cap ? c->cap * 2 : 16;
		c->data = realloc(c->data, c->cap * sizeof(*c->data));
	}
	c->data[c->len++] = cmd;
}

void
delta_commands_free(delta_commands_t *c)
{
	size_t i;
	for (i = 0; i < c->len; i++)
		if (c->data[i].tag == CMD_ADD)
			free(c->data[i].add.data);
	free(c->data);
	c->data = NULL;
	c->len = c->cap = 0;
}

void
delta_placed_commands_init(delta_placed_commands_t *c)
{
	c->data = NULL;
	c->len = 0;
	c->cap = 0;
}

void
delta_placed_commands_push(delta_placed_commands_t *c,
                           delta_placed_command_t cmd)
{
	if (c->len == c->cap) {
		c->cap = c->cap ? c->cap * 2 : 16;
		c->data = realloc(c->data, c->cap * sizeof(*c->data));
	}
	c->data[c->len++] = cmd;
}

void
delta_placed_commands_free(delta_placed_commands_t *c)
{
	size_t i;
	for (i = 0; i < c->len; i++)
		if (c->data[i].tag == PCMD_ADD)
			free(c->data[i].add.data);
	free(c->data);
	c->data = NULL;
	c->len = c->cap = 0;
}

/* ── Summary statistics ────────────────────────────────────────────── */

delta_summary_t
delta_summary(const delta_commands_t *cmds)
{
	delta_summary_t s = {0};
	size_t i;
	s.num_commands = cmds->len;
	for (i = 0; i < cmds->len; i++) {
		if (cmds->data[i].tag == CMD_COPY) {
			s.num_copies++;
			s.copy_bytes += cmds->data[i].copy.length;
		} else {
			s.num_adds++;
			s.add_bytes += cmds->data[i].add.length;
		}
	}
	s.total_output_bytes = s.copy_bytes + s.add_bytes;
	return s;
}

delta_summary_t
delta_placed_summary(const delta_placed_commands_t *cmds)
{
	delta_summary_t s = {0};
	size_t i;
	s.num_commands = cmds->len;
	for (i = 0; i < cmds->len; i++) {
		if (cmds->data[i].tag == PCMD_COPY) {
			s.num_copies++;
			s.copy_bytes += cmds->data[i].copy.length;
		} else {
			s.num_adds++;
			s.add_bytes += cmds->data[i].add.length;
		}
	}
	s.total_output_bytes = s.copy_bytes + s.add_bytes;
	return s;
}

/* ── Output size ───────────────────────────────────────────────────── */

size_t
delta_output_size(const delta_commands_t *cmds)
{
	size_t total = 0;
	size_t i;
	for (i = 0; i < cmds->len; i++) {
		if (cmds->data[i].tag == CMD_COPY)
			total += cmds->data[i].copy.length;
		else
			total += cmds->data[i].add.length;
	}
	return total;
}

/* ── Place commands with sequential destinations ───────────────────── */

delta_placed_commands_t
delta_place_commands(const delta_commands_t *cmds)
{
	delta_placed_commands_t placed;
	size_t dst = 0;
	size_t i;

	delta_placed_commands_init(&placed);
	for (i = 0; i < cmds->len; i++) {
		delta_placed_command_t pc;
		const delta_command_t *cmd = &cmds->data[i];
		if (cmd->tag == CMD_COPY) {
			pc.tag = PCMD_COPY;
			pc.copy.src = cmd->copy.offset;
			pc.copy.dst = dst;
			pc.copy.length = cmd->copy.length;
			dst += cmd->copy.length;
		} else {
			pc.tag = PCMD_ADD;
			pc.add.dst = dst;
			pc.add.length = cmd->add.length;
			pc.add.data = malloc(cmd->add.length);
			memcpy(pc.add.data, cmd->add.data, cmd->add.length);
			dst += cmd->add.length;
		}
		delta_placed_commands_push(&placed, pc);
	}
	return placed;
}

/* ── Apply placed commands (standard mode) ─────────────────────────── */

size_t
delta_apply_placed(const uint8_t *r, const delta_placed_commands_t *cmds,
                   uint8_t *out)
{
	size_t max_written = 0;
	size_t i;
	for (i = 0; i < cmds->len; i++) {
		const delta_placed_command_t *cmd = &cmds->data[i];
		if (cmd->tag == PCMD_COPY) {
			memcpy(&out[cmd->copy.dst], &r[cmd->copy.src],
			       cmd->copy.length);
			size_t end = cmd->copy.dst + cmd->copy.length;
			if (end > max_written) max_written = end;
		} else {
			memcpy(&out[cmd->add.dst], cmd->add.data,
			       cmd->add.length);
			size_t end = cmd->add.dst + cmd->add.length;
			if (end > max_written) max_written = end;
		}
	}
	return max_written;
}

/* ── Apply placed commands in-place (memmove-safe) ─────────────────── */

void
delta_apply_placed_inplace(const delta_placed_commands_t *cmds, uint8_t *buf)
{
	size_t i;
	for (i = 0; i < cmds->len; i++) {
		const delta_placed_command_t *cmd = &cmds->data[i];
		if (cmd->tag == PCMD_COPY)
			memmove(&buf[cmd->copy.dst], &buf[cmd->copy.src],
			        cmd->copy.length);
		else
			memcpy(&buf[cmd->add.dst], cmd->add.data,
			       cmd->add.length);
	}
}

/* ── Reconstruct version from R + in-place commands ────────────────── */

uint8_t *
delta_apply_delta_inplace(const uint8_t *r, size_t r_len,
                          const delta_placed_commands_t *cmds,
                          size_t version_size)
{
	size_t buf_size = r_len > version_size ? r_len : version_size;
	uint8_t *buf = calloc(buf_size, 1);
	memcpy(buf, r, r_len);
	delta_apply_placed_inplace(cmds, buf);
	/* Truncate to version_size by returning buf; caller knows the size. */
	return buf;
}
