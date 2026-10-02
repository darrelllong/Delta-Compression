// Helpers shared by the library's source files; not part of its interface.

#ifndef DELTA_INTERNAL_H
#define DELTA_INTERNAL_H

#include "delta.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// Allocation that does not fail: these abort when memory runs out.

static inline void *
delta_realloc(void *ptr, size_t size)
{
	void *p = realloc(ptr, size);
	if (!p && size > 0) {
		fprintf(stderr, "delta: out of memory (realloc %zu bytes)\n",
		        size);
		abort();
	}
	return p;
}

static inline void *
delta_malloc(size_t size)
{
	void *p = malloc(size);
	if (!p && size > 0) {
		fprintf(stderr, "delta: out of memory (malloc %zu bytes)\n",
		        size);
		abort();
	}
	return p;
}

static inline void *
delta_calloc(size_t count, size_t size)
{
	void *p = calloc(count, size);
	if (!p && count > 0 && size > 0) {
		fprintf(stderr,
		        "delta: out of memory (calloc %zu x %zu bytes)\n",
		        count, size);
		abort();
	}
	return p;
}

// delta_memdup returns a copy of len bytes that the caller frees.
static inline uint8_t *
delta_memdup(const uint8_t *src, size_t len)
{
	uint8_t *p = delta_malloc(len);
	if (len > 0) {
		memcpy(p, src, len);
	}
	return p;
}

static inline void
delta_push_copy(delta_commands_t *c, size_t offset, size_t length)
{
	delta_command_t cmd = { .tag = CMD_COPY,
	    .copy = { .offset = offset, .length = length } };
	delta_commands_push(c, cmd);
}

// delta_push_add appends an add of a copy of data.
static inline void
delta_push_add(delta_commands_t *c, const uint8_t *data, size_t length)
{
	delta_command_t cmd = { .tag = CMD_ADD,
	    .add = { .data = delta_memdup(data, length), .length = length } };
	delta_commands_push(c, cmd);
}

// A seed_hash_t yields the fingerprints of the seeds of one string as a scan
// moves through it.
typedef struct {
	delta_rolling_hash_t rh;
	size_t pos;
	int    valid;
} seed_hash_t;

// seed_hash_at returns the fingerprint of the p bytes of data at pos.  It is
// constant time when pos is one past the previous call's.
static inline uint64_t
seed_hash_at(seed_hash_t *s, const uint8_t *data, size_t pos, size_t p)
{
	return delta_rh_advance(&s->rh, &s->valid, &s->pos, data, pos, p);
}

// delta_num_seeds is the number of p-byte seeds in a string of len bytes.
static inline size_t
delta_num_seeds(size_t len, size_t p)
{
	return len >= p ? len - p + 1 : 0;
}

// delta_extend returns the length of the match between r at i and v at j,
// given that the first n bytes are already known to match.
static inline size_t
delta_extend(const uint8_t *r, size_t r_len, size_t i,
             const uint8_t *v, size_t v_len, size_t j, size_t n)
{
	while (i + n < r_len && j + n < v_len && r[i + n] == v[j + n]) {
		n++;
	}
	return n;
}

// A dst_index_t pairs a command's destination with its position in a list,
// for sorting commands by destination.
typedef struct {
	size_t dst;
	size_t idx;
} dst_index_t;

// delta_cmp_dst_index orders by destination, then by position.
int delta_cmp_dst_index(const void *a, const void *b);

#endif // DELTA_INTERNAL_H
