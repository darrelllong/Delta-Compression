// Command lists: growth, summaries, placement, validation and application.

#include "internal.h"

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
	for (size_t i = 0; i < c->len; i++) {
		if (c->data[i].tag == CMD_ADD) {
			free(c->data[i].add.data);
		}
	}
	free(c->data);
	delta_commands_init(c);
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
	for (size_t i = 0; i < c->len; i++) {
		if (c->data[i].tag == PCMD_ADD) {
			free(c->data[i].add.data);
		}
	}
	free(c->data);
	delta_placed_commands_init(c);
}

delta_summary_t
delta_summary(const delta_commands_t *cmds)
{
	delta_summary_t s = { .num_commands = cmds->len };

	for (size_t i = 0; i < cmds->len; i++) {
		const delta_command_t *cmd = &cmds->data[i];
		if (cmd->tag == CMD_COPY) {
			s.num_copies++;
			s.copy_bytes += cmd->copy.length;
		} else {
			s.num_adds++;
			s.add_bytes += cmd->add.length;
		}
	}
	s.total_output_bytes = s.copy_bytes + s.add_bytes;
	return s;
}

delta_summary_t
delta_placed_summary(const delta_placed_commands_t *cmds)
{
	delta_summary_t s = { .num_commands = cmds->len };

	for (size_t i = 0; i < cmds->len; i++) {
		const delta_placed_command_t *cmd = &cmds->data[i];
		switch (cmd->tag) {
		case PCMD_COPY:
			s.num_copies++;
			s.copy_bytes += cmd->copy.length;
			break;
		case PCMD_MOVE:
			s.num_copies++;
			s.copy_bytes += cmd->move.length;
			break;
		case PCMD_ADD:
			s.num_adds++;
			s.add_bytes += cmd->add.length;
			break;
		}
	}
	s.total_output_bytes = s.copy_bytes + s.add_bytes;
	return s;
}

size_t
delta_output_size(const delta_commands_t *cmds)
{
	return delta_summary(cmds).total_output_bytes;
}

delta_placed_commands_t
delta_place_commands(const delta_commands_t *cmds)
{
	delta_placed_commands_t placed;
	size_t dst = 0;

	delta_placed_commands_init(&placed);
	for (size_t i = 0; i < cmds->len; i++) {
		const delta_command_t *cmd = &cmds->data[i];
		delta_placed_command_t pc;
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
			pc.add.data = delta_memdup(cmd->add.data, cmd->add.length);
			dst += cmd->add.length;
		}
		delta_placed_commands_push(&placed, pc);
	}
	return placed;
}

delta_commands_t
delta_unplace_commands(const delta_placed_commands_t *placed)
{
	delta_commands_t cmds;
	delta_commands_init(&cmds);
	if (placed->len == 0) {
		return cmds;
	}

	dst_index_t *order = delta_malloc(placed->len * sizeof(*order));
	for (size_t i = 0; i < placed->len; i++) {
		const delta_placed_command_t *pc = &placed->data[i];
		switch (pc->tag) {
		case PCMD_COPY:
			order[i].dst = pc->copy.dst;
			break;
		case PCMD_ADD:
			order[i].dst = pc->add.dst;
			break;
		case PCMD_MOVE:
			fprintf(stderr,
			        "delta_unplace_commands: MOVE has no algorithm-level"
			        " equivalent; MOVE commands are DLT\\x04-only\n");
			exit(1);
		}
		order[i].idx = i;
	}
	qsort(order, placed->len, sizeof(*order), delta_cmp_dst_index);

	for (size_t i = 0; i < placed->len; i++) {
		const delta_placed_command_t *pc = &placed->data[order[i].idx];
		if (pc->tag == PCMD_COPY) {
			delta_push_copy(&cmds, pc->copy.src, pc->copy.length);
		} else {
			delta_push_add(&cmds, pc->add.data, pc->add.length);
		}
	}
	free(order);
	return cmds;
}

// in_bounds reports whether [start, start+len) lies within [0, limit),
// without overflow.
static bool
in_bounds(size_t start, size_t len, size_t limit)
{
	return start <= limit && len <= limit - start;
}

void
delta_validate_placed_commands(const delta_placed_commands_t *cmds,
                               size_t reference_size,
                               size_t version_size,
                               bool inplace)
{
	size_t source_limit = reference_size;
	if (inplace && version_size > source_limit) {
		source_limit = version_size;
	}

	for (size_t i = 0; i < cmds->len; i++) {
		const delta_placed_command_t *cmd = &cmds->data[i];
		size_t dst = 0, len = 0;

		switch (cmd->tag) {
		case PCMD_COPY:
			dst = cmd->copy.dst;
			len = cmd->copy.length;
			if (!in_bounds(cmd->copy.src, len, source_limit)) {
				fprintf(stderr,
				        "delta: COPY command %zu reads past source "
				        "(src=%zu len=%zu limit=%zu)\n",
				        i, cmd->copy.src, len, source_limit);
				exit(1);
			}
			break;
		case PCMD_MOVE:
			dst = cmd->move.dst;
			len = cmd->move.length;
			if (!in_bounds(cmd->move.src, len, version_size)) {
				fprintf(stderr,
				        "delta: MOVE command %zu reads past version size "
				        "(src=%zu len=%zu vs=%zu)\n",
				        i, cmd->move.src, len, version_size);
				exit(1);
			}
			// A move may read only what lies before its destination.
			if (cmd->move.src + len > dst) {
				fprintf(stderr,
				        "delta: MOVE command %zu src+len > dst "
				        "(src=%zu len=%zu dst=%zu)\n",
				        i, cmd->move.src, len, dst);
				exit(1);
			}
			break;
		case PCMD_ADD:
			dst = cmd->add.dst;
			len = cmd->add.length;
			break;
		}

		if (!in_bounds(dst, len, version_size)) {
			fprintf(stderr,
			        "delta: command %zu writes past version size "
			        "(dst=%zu len=%zu version=%zu)\n",
			        i, dst, len, version_size);
			exit(1);
		}
	}
}

// apply runs the commands, writing to out.  Copies read from src, moves from
// out.  memmove serves both the standard case, where src and out are
// distinct, and the in-place case, where they are one buffer.
static void
apply(const delta_placed_commands_t *cmds, const uint8_t *src, uint8_t *out)
{
	for (size_t i = 0; i < cmds->len; i++) {
		const delta_placed_command_t *cmd = &cmds->data[i];
		switch (cmd->tag) {
		case PCMD_COPY:
			if (cmd->copy.length > 0) {
				memmove(&out[cmd->copy.dst], &src[cmd->copy.src],
				        cmd->copy.length);
			}
			break;
		case PCMD_MOVE:
			if (cmd->move.length > 0) {
				memmove(&out[cmd->move.dst], &out[cmd->move.src],
				        cmd->move.length);
			}
			break;
		case PCMD_ADD:
			if (cmd->add.length > 0) {
				memcpy(&out[cmd->add.dst], cmd->add.data,
				       cmd->add.length);
			}
			break;
		}
	}
}

delta_buffer_t
delta_apply_placed(const uint8_t *r, const delta_placed_commands_t *cmds,
                   size_t version_size)
{
	delta_buffer_t out = { delta_calloc(version_size, 1), version_size };
	apply(cmds, r, out.data);
	return out;
}

void
delta_apply_placed_inplace(const delta_placed_commands_t *cmds, uint8_t *buf)
{
	apply(cmds, buf, buf);
}

delta_buffer_t
delta_apply_delta_inplace(const uint8_t *r, size_t r_len,
                          const delta_placed_commands_t *cmds,
                          size_t version_size)
{
	size_t size = r_len > version_size ? r_len : version_size;
	delta_buffer_t out = { delta_calloc(size, 1), version_size };
	if (r_len > 0) {
		memcpy(out.data, r, r_len);
	}
	apply(cmds, out.data, out.data);
	return out;
}
