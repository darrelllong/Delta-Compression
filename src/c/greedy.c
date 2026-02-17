/*
 * greedy.c — Greedy differencing algorithm (Section 3.1, Figure 2)
 *
 * Optimal O(n^2) algorithm: fingerprint every position in R, then scan V
 * and find the longest match at each position.
 */

#include "delta.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ── Chained hash table for greedy: maps fingerprint -> list of offsets ── */

typedef struct greedy_entry {
	uint64_t fp;
	size_t offset;
	struct greedy_entry *next;
} greedy_entry_t;

typedef struct {
	greedy_entry_t **buckets;
	size_t nbuckets;
} greedy_htable_t;

static void
ght_init(greedy_htable_t *ht, size_t nbuckets)
{
	ht->nbuckets = nbuckets;
	ht->buckets = calloc(nbuckets, sizeof(*ht->buckets));
}

static void
ght_insert(greedy_htable_t *ht, uint64_t fp, size_t offset)
{
	size_t idx = (size_t)(fp % (uint64_t)ht->nbuckets);
	greedy_entry_t *e = malloc(sizeof(*e));
	e->fp = fp;
	e->offset = offset;
	e->next = ht->buckets[idx];
	ht->buckets[idx] = e;
}

static void
ght_free(greedy_htable_t *ht)
{
	size_t i;
	for (i = 0; i < ht->nbuckets; i++) {
		greedy_entry_t *e = ht->buckets[i];
		while (e) {
			greedy_entry_t *next = e->next;
			free(e);
			e = next;
		}
	}
	free(ht->buckets);
}

/* ── Splay tree value for greedy: dynamic array of offsets ─────────── */

typedef struct {
	size_t *offsets;
	size_t len;
	size_t cap;
} offset_vec_t;

static void
ov_push(offset_vec_t *ov, size_t offset)
{
	if (ov->len == ov->cap) {
		ov->cap = ov->cap ? ov->cap * 2 : 4;
		ov->offsets = realloc(ov->offsets, ov->cap * sizeof(*ov->offsets));
	}
	ov->offsets[ov->len++] = offset;
}

/* ── Greedy algorithm ──────────────────────────────────────────────── */

delta_commands_t
delta_diff_greedy(const uint8_t *r, size_t r_len,
                  const uint8_t *v, size_t v_len,
                  const delta_diff_options_t *opts)
{
	delta_commands_t commands;
	greedy_htable_t ht;
	delta_splay_t splay;
	delta_rolling_hash_t rh_r, rh_v;
	int rh_v_valid = 0;
	size_t rh_v_pos = 0;
	size_t v_c, v_s;
	size_t num_seeds;

	size_t p = opts->p;
	bool verbose = opts->verbose;
	bool use_splay = opts->use_splay;
	size_t min_copy = opts->min_copy;

	delta_commands_init(&commands);
	if (v_len == 0) return commands;

	/* --min-copy raises the seed length */
	if (min_copy > 0 && min_copy > p) p = min_copy;

	num_seeds = (r_len >= p) ? (r_len - p + 1) : 0;

	/* Step (1): Build lookup structure for R */
	if (use_splay) {
		delta_splay_init(&splay, sizeof(offset_vec_t));
		if (num_seeds > 0) {
			size_t a;
			offset_vec_t empty = {NULL, 0, 0};
			delta_rh_init(&rh_r, r, 0, p);
			{
				offset_vec_t *val = delta_splay_insert_or_get(
					&splay, rh_r.value, &empty);
				ov_push(val, 0);
			}
			for (a = 1; a < num_seeds; a++) {
				offset_vec_t *val;
				delta_rh_roll(&rh_r, r[a - 1], r[a + p - 1]);
				empty = (offset_vec_t){NULL, 0, 0};
				val = delta_splay_insert_or_get(
					&splay, rh_r.value, &empty);
				ov_push(val, a);
			}
		}
	} else {
		size_t nbuckets = num_seeds > 0 ? delta_next_prime(num_seeds / p + 1) : 17;
		ght_init(&ht, nbuckets);
		if (num_seeds > 0) {
			size_t a;
			delta_rh_init(&rh_r, r, 0, p);
			ght_insert(&ht, rh_r.value, 0);
			for (a = 1; a < num_seeds; a++) {
				delta_rh_roll(&rh_r, r[a - 1], r[a + p - 1]);
				ght_insert(&ht, rh_r.value, a);
			}
		}
	}

	if (verbose)
		fprintf(stderr, "greedy: %s, |R|=%zu, |V|=%zu, seed_len=%zu\n",
		        use_splay ? "splay tree" : "hash table",
		        r_len, v_len, p);

	/* Step (2): initialize scan pointers */
	v_c = 0;
	v_s = 0;

	if (v_len >= p) {
		delta_rh_init(&rh_v, v, 0, p);
		rh_v_valid = 1;
		rh_v_pos = 0;
	}

	for (;;) {
		uint64_t fp_v;
		size_t best_len = 0, best_rm = 0;

		/* Step (3): check for end of V */
		if (v_c + p > v_len) break;

		/* Compute V fingerprint at v_c */
		if (rh_v_valid && v_c == rh_v_pos) {
			fp_v = rh_v.value;
		} else if (rh_v_valid && v_c == rh_v_pos + 1) {
			delta_rh_roll(&rh_v, v[v_c - 1], v[v_c + p - 1]);
			rh_v_pos = v_c;
			fp_v = rh_v.value;
		} else {
			delta_rh_init(&rh_v, v, v_c, p);
			rh_v_valid = 1;
			rh_v_pos = v_c;
			fp_v = rh_v.value;
		}

		/* Steps (4)+(5): find longest match */
		if (use_splay) {
			offset_vec_t *val = delta_splay_find(&splay, fp_v);
			if (val) {
				size_t k;
				for (k = 0; k < val->len; k++) {
					size_t r_cand = val->offsets[k];
					size_t ml;
					if (memcmp(&r[r_cand], &v[v_c], p) != 0)
						continue;
					ml = p;
					while (v_c + ml < v_len &&
					       r_cand + ml < r_len &&
					       v[v_c + ml] == r[r_cand + ml])
						ml++;
					if (ml > best_len) {
						best_len = ml;
						best_rm = r_cand;
					}
				}
			}
		} else {
			size_t idx = (size_t)(fp_v % (uint64_t)ht.nbuckets);
			greedy_entry_t *e;
			for (e = ht.buckets[idx]; e; e = e->next) {
				size_t ml;
				if (e->fp != fp_v) continue;
				if (memcmp(&r[e->offset], &v[v_c], p) != 0)
					continue;
				ml = p;
				while (v_c + ml < v_len &&
				       e->offset + ml < r_len &&
				       v[v_c + ml] == r[e->offset + ml])
					ml++;
				if (ml > best_len) {
					best_len = ml;
					best_rm = e->offset;
				}
			}
		}

		if (best_len < p) {
			v_c++;
			continue;
		}

		/* Step (6): encode */
		if (v_s < v_c) {
			delta_command_t cmd;
			cmd.tag = CMD_ADD;
			cmd.add.length = v_c - v_s;
			cmd.add.data = malloc(cmd.add.length);
			memcpy(cmd.add.data, &v[v_s], cmd.add.length);
			delta_commands_push(&commands, cmd);
		}
		{
			delta_command_t cmd;
			cmd.tag = CMD_COPY;
			cmd.copy.offset = best_rm;
			cmd.copy.length = best_len;
			delta_commands_push(&commands, cmd);
		}
		v_s = v_c + best_len;

		/* Step (7): advance past matched region */
		v_c += best_len;
	}

	/* Step (8): trailing add */
	if (v_s < v_len) {
		delta_command_t cmd;
		cmd.tag = CMD_ADD;
		cmd.add.length = v_len - v_s;
		cmd.add.data = malloc(cmd.add.length);
		memcpy(cmd.add.data, &v[v_s], cmd.add.length);
		delta_commands_push(&commands, cmd);
	}

	if (verbose)
		delta_print_command_stats(&commands);

	/* Cleanup */
	if (use_splay) {
		/* Free offset vectors inside splay nodes before clearing */
		/* We can't easily walk the splay tree to free inner vecs,
		 * so we accept the leak for now — greedy+splay is rare.
		 * A production version would use an arena allocator. */
		delta_splay_free(&splay);
	} else {
		ght_free(&ht);
	}

	return commands;
}
