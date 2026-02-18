/*
 * correcting.c — Correcting 1.5-Pass algorithm (Section 7, Figure 8)
 *                with fingerprint-based checkpointing (Section 8)
 */

#include "delta.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ── Lookback buffer entry (Section 5.2) ───────────────────────────── */

typedef struct buf_entry {
	size_t v_start;
	size_t v_end;
	delta_command_t cmd;
	bool dummy;
	struct buf_entry *next;
	struct buf_entry *prev;
} buf_entry_t;

/* Simple deque via doubly-linked list */
typedef struct {
	buf_entry_t *head;
	buf_entry_t *tail;
	size_t len;
} buf_deque_t;

static void deque_init(buf_deque_t *d) { d->head = d->tail = NULL; d->len = 0; }

static void
deque_push_back(buf_deque_t *d, buf_entry_t *e)
{
	e->next = NULL;
	e->prev = d->tail;
	if (d->tail) { d->tail->next = e; } else { d->head = e; }
	d->tail = e;
	d->len++;
}

static buf_entry_t *
deque_pop_front(buf_deque_t *d)
{
	buf_entry_t *e = d->head;
	if (!e) { return NULL; }
	d->head = e->next;
	if (d->head) { d->head->prev = NULL; } else { d->tail = NULL; }
	d->len--;
	return e;
}

static buf_entry_t *
deque_pop_back(buf_deque_t *d)
{
	buf_entry_t *e = d->tail;
	if (!e) { return NULL; }
	d->tail = e->prev;
	if (d->tail) { d->tail->next = NULL; } else { d->head = NULL; }
	d->len--;
	return e;
}

/* ── Hash table slot for correcting: (full_fp, offset) ─────────────── */

typedef struct {
	uint64_t fp;
	size_t offset;
	bool occupied;
} corr_slot_t;

/* ── Splay value for correcting ────────────────────────────────────── */

typedef struct {
	uint64_t fp;
	size_t offset;
} corr_splay_val_t;

/* ── Correcting algorithm ──────────────────────────────────────────── */

delta_commands_t
delta_diff_correcting(const uint8_t *r, size_t r_len,
                      const uint8_t *v, size_t v_len,
                      const delta_diff_options_t *opts)
{
	delta_commands_t commands;
	size_t num_seeds;
	size_t cap;
	uint64_t f_size, m, k;
	size_t v_c, v_s;
	delta_rolling_hash_t rh_build, rh_v;
	int rh_v_valid = 0;
	size_t rh_v_pos = 0;
	buf_deque_t buf;
	size_t dbg_build_passed = 0, dbg_build_stored = 0;
	size_t dbg_build_skipped = 0;
	size_t dbg_scan_checkpoints = 0, dbg_scan_match = 0;
	size_t dbg_scan_fp_mismatch = 0, dbg_scan_byte_mismatch = 0;

	size_t p = opts->p;
	size_t q = opts->q;
	size_t buf_cap = opts->buf_cap;
	bool verbose = delta_flag_get(opts->flags, DELTA_OPT_VERBOSE);
	bool use_splay = delta_flag_get(opts->flags, DELTA_OPT_SPLAY);
	size_t min_copy = opts->min_copy;

	/* Hash table path */
	corr_slot_t *h_r_ht = NULL;

	/* Splay tree path */
	delta_splay_t h_r_sp;

	delta_commands_init(&commands);
	if (v_len == 0) { return commands; }

	/* --min-copy raises the seed length */
	if (min_copy > 0 && min_copy > p) { p = min_copy; }

	/* Checkpointing parameters (Section 8.1, pp. 347-348) */
	num_seeds = (r_len >= p) ? (r_len - p + 1) : 0;
	cap = num_seeds > 0
	    ? delta_next_prime(q > 2 * num_seeds / p ? q : 2 * num_seeds / p)
	    : delta_next_prime(q);
	f_size = num_seeds > 0
	    ? (uint64_t)delta_next_prime(2 * num_seeds)
	    : 1;
	m = (f_size <= (uint64_t)cap) ? 1 : (f_size + (uint64_t)cap - 1) / (uint64_t)cap;

	/* Biased k (p. 348) */
	k = 0;
	if (v_len >= p) {
		uint64_t fp_k = delta_fingerprint(v, v_len / 2, p);
		k = fp_k % f_size % m;
	}

	if (verbose) {
		uint64_t expected = m > 0 ? (uint64_t)num_seeds / m : 0;
		uint64_t occ_est = cap > 0 ? expected * 100 / (uint64_t)cap : 0;
		fprintf(stderr,
		        "correcting: %s, |C|=%zu |F|=%llu m=%llu k=%llu\n"
		        "  checkpoint gap=%llu bytes, expected fill ~%llu "
		        "(~%llu%% table occupancy)\n"
		        "  table memory ~%zu MB\n",
		        use_splay ? "splay tree" : "hash table",
		        cap, (unsigned long long)f_size,
		        (unsigned long long)m, (unsigned long long)k,
		        (unsigned long long)m, (unsigned long long)expected,
		        (unsigned long long)occ_est,
		        cap * 24 / 1048576);
	}

	/* Step (1): Build lookup structure for R (first-found policy) */
	if (use_splay) {
		delta_splay_init(&h_r_sp, sizeof(corr_splay_val_t));
	} else {
		h_r_ht = delta_calloc(cap, sizeof(*h_r_ht));
	}

	if (num_seeds > 0) {
		size_t a;
		delta_rh_init(&rh_build, r, 0, p);
		for (a = 0; a < num_seeds; a++) {
			uint64_t fp, f;
			if (a == 0) {
				fp = rh_build.value;
			} else {
				delta_rh_roll(&rh_build, r[a - 1], r[a + p - 1]);
				fp = rh_build.value;
			}
			f = fp % f_size;
			if (f % m != k) { continue; }
			dbg_build_passed++;

			if (use_splay) {
				corr_splay_val_t sv = {fp, a};
				corr_splay_val_t *existing;
				existing = delta_splay_insert_or_get(
					&h_r_sp, fp, &sv);
				if (existing->offset == a) {
					dbg_build_stored++;
				} else {
					dbg_build_skipped++;
				}
			} else {
				size_t i = (size_t)(f / m);
				if (i >= cap) { continue; }
				if (!h_r_ht[i].occupied) {
					h_r_ht[i].fp = fp;
					h_r_ht[i].offset = a;
					h_r_ht[i].occupied = true;
					dbg_build_stored++;
				} else {
					dbg_build_skipped++;
				}
			}
		}
	}

	if (verbose) {
		double passed_pct = num_seeds > 0
		    ? (double)dbg_build_passed / num_seeds * 100.0 : 0.0;
		size_t stored_count = use_splay ? h_r_sp.size : dbg_build_stored;
		double occ_pct = cap > 0
		    ? (double)stored_count / cap * 100.0 : 0.0;
		fprintf(stderr,
		        "  build: %zu seeds, %zu passed checkpoint (%.2f%%), "
		        "%zu stored, %zu collisions\n"
		        "  build: table occupancy %zu/%zu (%.1f%%)\n",
		        num_seeds, dbg_build_passed, passed_pct,
		        dbg_build_stored, dbg_build_skipped,
		        stored_count, cap, occ_pct);
	}

	/* Lookback buffer (Section 5.2) */
	deque_init(&buf);

	/* Step (2): initialize scan pointers */
	v_c = 0;
	v_s = 0;

	if (v_len >= p) {
		delta_rh_init(&rh_v, v, 0, p);
		rh_v_valid = 1;
		rh_v_pos = 0;
	}

	for (;;) {
		uint64_t fp_v, f_v;
		size_t r_offset;
		size_t fwd, bwd, v_m_val, r_m, ml, match_end;
		bool found;

		/* Step (3): check for end of V */
		if (v_c + p > v_len) { break; }

		/* Step (4): generate fingerprint, apply checkpoint test */
		fp_v = delta_rh_advance(&rh_v, &rh_v_valid, &rh_v_pos,
		                        v, v_c, p);

		f_v = fp_v % f_size;
		if (f_v % m != k) {
			v_c++;
			continue;
		}

		/* Checkpoint passed — look up R */
		dbg_scan_checkpoints++;
		found = false;

		if (use_splay) {
			corr_splay_val_t *sv = delta_splay_find(&h_r_sp, fp_v);
			if (sv) {
				if (sv->fp == fp_v) {
					if (memcmp(&r[sv->offset], &v[v_c], p) != 0) {
						dbg_scan_byte_mismatch++;
						v_c++;
						continue;
					}
					dbg_scan_match++;
					r_offset = sv->offset;
					found = true;
				} else {
					dbg_scan_fp_mismatch++;
				}
			}
		} else {
			size_t i = (size_t)(f_v / m);
			if (i < cap && h_r_ht[i].occupied) {
				if (h_r_ht[i].fp == fp_v) {
					if (memcmp(&r[h_r_ht[i].offset],
					           &v[v_c], p) != 0) {
						dbg_scan_byte_mismatch++;
						v_c++;
						continue;
					}
					dbg_scan_match++;
					r_offset = h_r_ht[i].offset;
					found = true;
				} else {
					dbg_scan_fp_mismatch++;
				}
			}
		}

		if (!found) {
			v_c++;
			continue;
		}

		/* Step (5): extend match forwards and backwards */
		fwd = p;
		while (v_c + fwd < v_len && r_offset + fwd < r_len &&
		       v[v_c + fwd] == r[r_offset + fwd]) {
			fwd++;
		}

		bwd = 0;
		while (v_c >= bwd + 1 && r_offset >= bwd + 1 &&
		       v[v_c - bwd - 1] == r[r_offset - bwd - 1]) {
			bwd++;
		}

		v_m_val = v_c - bwd;
		r_m = r_offset - bwd;
		ml = bwd + fwd;
		match_end = v_m_val + ml;

		if (ml < p) {
			v_c++;
			continue;
		}

		/* Step (6): encode with correction */
		if (v_s <= v_m_val) {
			/* (6a) match in unencoded suffix */
			if (v_s < v_m_val) {
				if (buf.len >= buf_cap) {
					buf_entry_t *oldest = deque_pop_front(&buf);
					if (oldest && !oldest->dummy) {
						delta_commands_push(&commands,
						                    oldest->cmd);
					} else if (oldest && oldest->dummy &&
					           oldest->cmd.tag == CMD_ADD) {
						free(oldest->cmd.add.data);
					}
					free(oldest);
				}
				{
					buf_entry_t *e = delta_malloc(sizeof(*e));
					e->v_start = v_s;
					e->v_end = v_m_val;
					e->cmd.tag = CMD_ADD;
					e->cmd.add.length = v_m_val - v_s;
					e->cmd.add.data = delta_malloc(e->cmd.add.length);
					memcpy(e->cmd.add.data, &v[v_s],
					       e->cmd.add.length);
					e->dummy = false;
					deque_push_back(&buf, e);
				}
			}
			if (buf.len >= buf_cap) {
				buf_entry_t *oldest = deque_pop_front(&buf);
				if (oldest && !oldest->dummy) {
					delta_commands_push(&commands, oldest->cmd);
				} else if (oldest && oldest->dummy &&
				           oldest->cmd.tag == CMD_ADD) {
					free(oldest->cmd.add.data);
				}
				free(oldest);
			}
			{
				buf_entry_t *e = delta_malloc(sizeof(*e));
				e->v_start = v_m_val;
				e->v_end = match_end;
				e->cmd.tag = CMD_COPY;
				e->cmd.copy.offset = r_m;
				e->cmd.copy.length = ml;
				e->dummy = false;
				deque_push_back(&buf, e);
			}
			v_s = match_end;
		} else {
			/* (6b) tail correction (Section 5.1, p. 339) */
			size_t effective_start = v_s;

			while (buf.tail) {
				buf_entry_t *tail = buf.tail;
				if (tail->dummy) {
					buf_entry_t *e = deque_pop_back(&buf);
					if (e->cmd.tag == CMD_ADD) {
						free(e->cmd.add.data);
					}
					free(e);
					continue;
				}
				if (tail->v_start >= v_m_val &&
				    tail->v_end <= match_end) {
					effective_start = effective_start < tail->v_start
					    ? effective_start : tail->v_start;
					{
						buf_entry_t *e = deque_pop_back(&buf);
						if (e->cmd.tag == CMD_ADD) {
							free(e->cmd.add.data);
						}
						free(e);
					}
					continue;
				}
				if (tail->v_end > v_m_val &&
				    tail->v_start < v_m_val) {
					if (tail->cmd.tag == CMD_ADD) {
						size_t keep = v_m_val - tail->v_start;
						if (keep > 0) {
							uint8_t *newdata = delta_malloc(keep);
							memcpy(newdata,
							       &v[tail->v_start],
							       keep);
							free(tail->cmd.add.data);
							tail->cmd.add.data = newdata;
							tail->cmd.add.length = keep;
							tail->v_end = v_m_val;
						} else {
							buf_entry_t *e = deque_pop_back(&buf);
							free(e->cmd.add.data);
							free(e);
						}
						effective_start = effective_start < v_m_val
						    ? effective_start : v_m_val;
					}
					break;
				}
				break;
			}

			{
				size_t adj = effective_start - v_m_val;
				size_t new_len = match_end - effective_start;
				if (new_len > 0) {
					if (buf.len >= buf_cap) {
						buf_entry_t *oldest = deque_pop_front(&buf);
						if (oldest && !oldest->dummy) {
							delta_commands_push(&commands,
							                    oldest->cmd);
						} else if (oldest && oldest->dummy &&
						           oldest->cmd.tag == CMD_ADD) {
							free(oldest->cmd.add.data);
						}
						free(oldest);
					}
					{
						buf_entry_t *e = delta_malloc(sizeof(*e));
						e->v_start = effective_start;
						e->v_end = match_end;
						e->cmd.tag = CMD_COPY;
						e->cmd.copy.offset = r_m + adj;
						e->cmd.copy.length = new_len;
						e->dummy = false;
						deque_push_back(&buf, e);
					}
				}
			}
			v_s = match_end;
		}

		/* Step (7): advance past matched region */
		v_c = match_end;
	}

	/* Step (8): flush buffer and trailing add */
	while (buf.head) {
		buf_entry_t *e = deque_pop_front(&buf);
		if (!e->dummy) {
			delta_commands_push(&commands, e->cmd);
		} else if (e->cmd.tag == CMD_ADD) {
			free(e->cmd.add.data);
		}
		free(e);
	}
	if (v_s < v_len) {
		delta_command_t cmd;
		cmd.tag = CMD_ADD;
		cmd.add.length = v_len - v_s;
		cmd.add.data = delta_malloc(cmd.add.length);
		memcpy(cmd.add.data, &v[v_s], cmd.add.length);
		delta_commands_push(&commands, cmd);
	}

	if (verbose) {
		size_t v_seeds = (v_len >= p) ? (v_len - p + 1) : 0;
		double cp_pct = v_seeds > 0
		    ? (double)dbg_scan_checkpoints / v_seeds * 100.0 : 0.0;
		double hit_pct = dbg_scan_checkpoints > 0
		    ? (double)dbg_scan_match / dbg_scan_checkpoints * 100.0
		    : 0.0;
		fprintf(stderr,
		        "  scan: %zu V positions, %zu checkpoints (%.3f%%), "
		        "%zu matches\n"
		        "  scan: hit rate %.1f%% (of checkpoints), "
		        "fp collisions %zu, byte mismatches %zu\n",
		        v_seeds, dbg_scan_checkpoints, cp_pct, dbg_scan_match,
		        hit_pct, dbg_scan_fp_mismatch, dbg_scan_byte_mismatch);
		delta_print_command_stats(&commands);
	}

	/* Cleanup */
	if (use_splay) {
		delta_splay_free(&h_r_sp);
	} else {
		free(h_r_ht);
	}

	return commands;
}

/* ── Dispatcher ────────────────────────────────────────────────────── */

delta_commands_t
delta_diff(delta_algorithm_t algo,
           const uint8_t *r, size_t r_len,
           const uint8_t *v, size_t v_len,
           const delta_diff_options_t *opts)
{
	switch (algo) {
	case ALGO_GREEDY:
		return delta_diff_greedy(r, r_len, v, v_len, opts);
	case ALGO_ONEPASS:
		return delta_diff_onepass(r, r_len, v, v_len, opts);
	case ALGO_CORRECTING:
		return delta_diff_correcting(r, r_len, v, v_len, opts);
	}
	/* unreachable */
	{
		delta_commands_t empty;
		delta_commands_init(&empty);
		return empty;
	}
}

/* ── Shared verbose stats ──────────────────────────────────────────── */

void
delta_print_command_stats(const delta_commands_t *cmds)
{
	size_t *lens = NULL;
	size_t nlens = 0, lens_cap = 0;
	size_t total_copy = 0, total_add = 0;
	size_t num_copies = 0, num_adds = 0;
	size_t total_out, i, j;
	double copy_pct;

	for (i = 0; i < cmds->len; i++) {
		if (cmds->data[i].tag == CMD_COPY) {
			size_t l = cmds->data[i].copy.length;
			total_copy += l;
			num_copies++;
			if (nlens == lens_cap) {
				lens_cap = lens_cap ? lens_cap * 2 : 16;
				lens = delta_realloc(lens, lens_cap * sizeof(*lens));
			}
			lens[nlens++] = l;
		} else {
			total_add += cmds->data[i].add.length;
			num_adds++;
		}
	}
	total_out = total_copy + total_add;
	copy_pct = total_out > 0
	    ? (double)total_copy / total_out * 100.0 : 0.0;
	fprintf(stderr,
	        "  result: %zu copies (%zu bytes), %zu adds (%zu bytes)\n"
	        "  result: copy coverage %.1f%%, output %zu bytes\n",
	        num_copies, total_copy, num_adds, total_add,
	        copy_pct, total_out);

	if (nlens > 0) {
		double mean = (double)total_copy / nlens;
		size_t median;
		for (i = 1; i < nlens; i++) {
			size_t key = lens[i];
			j = i;
			while (j > 0 && lens[j - 1] > key) {
				lens[j] = lens[j - 1];
				j--;
			}
			lens[j] = key;
		}
		median = lens[nlens / 2];
		fprintf(stderr,
		        "  copies: %zu regions, min=%zu max=%zu "
		        "mean=%.1f median=%zu bytes\n",
		        nlens, lens[0], lens[nlens - 1], mean, median);
	}
	free(lens);
}
