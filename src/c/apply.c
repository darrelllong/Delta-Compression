// apply.c — Command placement, application, and summary statistics

#include "delta.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// ── Dynamic array helpers ─────────────────────────────────────────────

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
		c->data = delta_realloc(c->data, c->cap * sizeof(*c->data));
	}
	c->data[c->len++] = cmd;
}

void
delta_commands_free(delta_commands_t *c)
{
	size_t i;
	for (i = 0; i < c->len; i++) {
		if (c->data[i].tag == CMD_ADD) {
			free(c->data[i].add.data);
		}
	}
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
		c->data = delta_realloc(c->data, c->cap * sizeof(*c->data));
	}
	c->data[c->len++] = cmd;
}

void
delta_placed_commands_free(delta_placed_commands_t *c)
{
	size_t i;
	for (i = 0; i < c->len; i++) {
		if (c->data[i].tag == PCMD_ADD) {
			free(c->data[i].add.data);
		}
	}
	free(c->data);
	c->data = NULL;
	c->len = c->cap = 0;
}

// ── Summary statistics ─────────────────────────────────────────────────

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

// ── Output size ────────────────────────────────────────────────────────

size_t
delta_output_size(const delta_commands_t *cmds)
{
	size_t total = 0;
	size_t i;
	for (i = 0; i < cmds->len; i++) {
		if (cmds->data[i].tag == CMD_COPY) {
			total += cmds->data[i].copy.length;
		} else {
			total += cmds->data[i].add.length;
		}
	}
	return total;
}

// ── Place commands with sequential destinations ────────────────────────

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
			pc.add.data = delta_malloc(cmd->add.length);
			memcpy(pc.add.data, cmd->add.data, cmd->add.length);
			dst += cmd->add.length;
		}
		delta_placed_commands_push(&placed, pc);
	}
	return placed;
}

// ── Unplace: convert placed commands back to algorithm commands ─────────

static size_t
pcmd_dst(const delta_placed_command_t *cmd)
{
	return cmd->tag == PCMD_COPY ? cmd->copy.dst : cmd->add.dst;
}

static void
qsort_indices(size_t *idx, size_t n, const delta_placed_command_t *data)
{
	size_t i, j, tmp, pivot;
	if (n < 2) { return; }
	// Move middle element to last as pivot — avoids O(n²) on sorted input.
	tmp = idx[n / 2]; idx[n / 2] = idx[n - 1]; idx[n - 1] = tmp;
	pivot = pcmd_dst(&data[idx[n - 1]]);
	i = 0;
	for (j = 0; j < n - 1; j++) {
		if (pcmd_dst(&data[idx[j]]) <= pivot) {
			tmp = idx[i]; idx[i] = idx[j]; idx[j] = tmp;
			i++;
		}
	}
	tmp = idx[i]; idx[i] = idx[n - 1]; idx[n - 1] = tmp;
	qsort_indices(idx, i, data);
	qsort_indices(idx + i + 1, n - i - 1, data);
}

delta_commands_t
delta_unplace_commands(const delta_placed_commands_t *placed)
{
	delta_commands_t cmds;
	size_t i;
	size_t *indices;

	delta_commands_init(&cmds);
	if (placed->len == 0) { return cmds; }

	indices = delta_malloc(placed->len * sizeof(*indices));
	for (i = 0; i < placed->len; i++) { indices[i] = i; }

	qsort_indices(indices, placed->len, placed->data);

	for (i = 0; i < placed->len; i++) {
		const delta_placed_command_t *pc = &placed->data[indices[i]];
		delta_command_t cmd;
		if (pc->tag == PCMD_COPY) {
			cmd.tag = CMD_COPY;
			cmd.copy.offset = pc->copy.src;
			cmd.copy.length = pc->copy.length;
		} else {
			cmd.tag = CMD_ADD;
			cmd.add.length = pc->add.length;
			cmd.add.data = delta_malloc(pc->add.length);
			memcpy(cmd.add.data, pc->add.data, pc->add.length);
		}
		delta_commands_push(&cmds, cmd);
	}
	free(indices);
	return cmds;
}

// ── Bounds validation ───────────────────────────────────────────────────

void
delta_validate_placed_commands(const delta_placed_commands_t *cmds,
                               size_t reference_size,
                               size_t version_size,
                               bool inplace)
{
	size_t source_limit = reference_size;
	size_t i;

	if (inplace && version_size > source_limit) {
		source_limit = version_size;
	}

	for (i = 0; i < cmds->len; i++) {
		const delta_placed_command_t *cmd = &cmds->data[i];
		size_t dst;
		size_t cmd_len;

		if (cmd->tag == PCMD_COPY) {
			dst = cmd->copy.dst;
			cmd_len = cmd->copy.length;
			if (cmd->copy.src > source_limit ||
			    cmd_len > source_limit - cmd->copy.src) {
				fprintf(stderr,
				        "delta: COPY command %zu reads past source "
				        "(src=%zu len=%zu limit=%zu)\n",
				        i, cmd->copy.src, cmd_len, source_limit);
				exit(1);
			}
		} else {
			dst = cmd->add.dst;
			cmd_len = cmd->add.length;
		}

		if (dst > version_size || cmd_len > version_size - dst) {
			fprintf(stderr,
			        "delta: command %zu writes past version size "
			        "(dst=%zu len=%zu version=%zu)\n",
			        i, dst, cmd_len, version_size);
			exit(1);
		}
	}
}

// ── Apply placed commands (standard mode) ─────────────────────────────

delta_buffer_t
delta_apply_placed(const uint8_t *r, const delta_placed_commands_t *cmds,
                   size_t version_size)
{
	uint8_t *out = delta_calloc(version_size, 1);
	size_t i;
	for (i = 0; i < cmds->len; i++) {
		const delta_placed_command_t *cmd = &cmds->data[i];
		if (cmd->tag == PCMD_COPY) {
			memcpy(&out[cmd->copy.dst], &r[cmd->copy.src],
			       cmd->copy.length);
		} else {
			memcpy(&out[cmd->add.dst], cmd->add.data,
			       cmd->add.length);
		}
	}
	delta_buffer_t result;
	result.data = out;
	result.len = version_size;
	return result;
}

// ── Apply placed commands in-place (memmove-safe) ─────────────────────

void
delta_apply_placed_inplace(const delta_placed_commands_t *cmds, uint8_t *buf)
{
	size_t i;
	for (i = 0; i < cmds->len; i++) {
		const delta_placed_command_t *cmd = &cmds->data[i];
		if (cmd->tag == PCMD_COPY) {
			memmove(&buf[cmd->copy.dst], &buf[cmd->copy.src],
			        cmd->copy.length);
		} else {
			memcpy(&buf[cmd->add.dst], cmd->add.data,
			       cmd->add.length);
		}
	}
}

// ── Reconstruct version from R + in-place commands ────────────────────

delta_buffer_t
delta_apply_delta_inplace(const uint8_t *r, size_t r_len,
                          const delta_placed_commands_t *cmds,
                          size_t version_size)
{
	size_t buf_size = r_len > version_size ? r_len : version_size;
	uint8_t *buf = delta_calloc(buf_size, 1);
	memcpy(buf, r, r_len);
	delta_apply_placed_inplace(cmds, buf);
	delta_buffer_t result;
	result.data = buf;
	result.len = version_size;
	return result;
}
