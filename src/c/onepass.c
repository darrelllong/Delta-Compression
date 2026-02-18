/*
 * onepass.c — One-Pass differencing algorithm (Section 4.1, Figure 3)
 *
 * Interleaved scan of R and V with dual hash tables (or splay trees)
 * and version-based logical flushing.
 */

#include "delta.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ── Splay tree value type for onepass: (offset, version) ──────────── */

typedef struct {
	size_t offset;
	uint64_t version;
} op_splay_val_t;

/* ── Hash table slot: (fingerprint, offset, version) ───────────────── */

typedef struct {
	uint64_t fp;
	size_t   offset;
	uint64_t version;
	bool     occupied;
} op_slot_t;

/* ── One-pass algorithm ────────────────────────────────────────────── */

delta_commands_t
delta_diff_onepass(const uint8_t *r, size_t r_len,
                   const uint8_t *v, size_t v_len,
                   const delta_diff_options_t *opts)
{
	delta_commands_t commands;
	size_t num_seeds;
	uint64_t ver = 0;
	size_t r_c, v_c, v_s;
	delta_rolling_hash_t rh_v, rh_r;
	int rh_v_valid = 0, rh_r_valid = 0;
	size_t rh_v_pos = 0, rh_r_pos = 0;
	size_t dbg_positions = 0, dbg_lookups = 0, dbg_matches = 0;

	size_t p = opts->p;
	size_t q = opts->q;
	bool verbose = delta_flag_get(opts->flags, DELTA_OPT_VERBOSE);
	bool use_splay = delta_flag_get(opts->flags, DELTA_OPT_SPLAY);
	size_t min_copy = opts->min_copy;

	/* Hash table path */
	op_slot_t *h_v_ht = NULL, *h_r_ht = NULL;

	/* Splay tree path */
	delta_splay_t h_v_sp, h_r_sp;

	delta_commands_init(&commands);
	if (v_len == 0) { return commands; }

	/* --min-copy raises the seed length */
	if (min_copy > 0 && min_copy > p) { p = min_copy; }

	/* Auto-size hash table: one slot per p-byte chunk of R */
	num_seeds = (r_len >= p) ? (r_len - p + 1) : 0;
	q = delta_next_prime(q > num_seeds / p ? q : num_seeds / p);

	if (verbose) {
		fprintf(stderr,
		        "onepass: %s, q=%zu, |R|=%zu, |V|=%zu, seed_len=%zu\n",
		        use_splay ? "splay tree" : "hash table",
		        q, r_len, v_len, p);
	}

	/* Step (1): initialize lookup structures */
	if (use_splay) {
		delta_splay_init(&h_v_sp, sizeof(op_splay_val_t));
		delta_splay_init(&h_r_sp, sizeof(op_splay_val_t));
	} else {
		h_v_ht = delta_calloc(q, sizeof(*h_v_ht));
		h_r_ht = delta_calloc(q, sizeof(*h_r_ht));
	}

	/* Step (2): initialize scan pointers */
	r_c = 0;
	v_c = 0;
	v_s = 0;

	if (v_len >= p) {
		delta_rh_init(&rh_v, v, 0, p);
		rh_v_valid = 1; rh_v_pos = 0;
	}
	if (r_len >= p) {
		delta_rh_init(&rh_r, r, 0, p);
		rh_r_valid = 1; rh_r_pos = 0;
	}

	for (;;) {
		bool can_v, can_r;
		uint64_t fp_v = 0, fp_r = 0;
		int have_fp_v = 0, have_fp_r = 0;
		bool match_found = false;
		size_t r_m = 0, v_m = 0;

		/* Step (3): check for end of V and R */
		can_v = (v_c + p <= v_len);
		can_r = (r_c + p <= r_len);
		if (!can_v && !can_r) { break; }
		dbg_positions++;

		/* Compute fingerprints */
		if (can_v) {
			fp_v = delta_rh_advance(&rh_v, &rh_v_valid, &rh_v_pos,
			                        v, v_c, p);
			have_fp_v = 1;
		}
		if (can_r) {
			fp_r = delta_rh_advance(&rh_r, &rh_r_valid, &rh_r_pos,
			                        r, r_c, p);
			have_fp_r = 1;
		}

		/* Step (4a): store fingerprints (retain-existing policy) */
		if (use_splay) {
			if (have_fp_v) {
				op_splay_val_t sv;
				op_splay_val_t *existing;
				existing = delta_splay_find(&h_v_sp, fp_v);
				if (!existing || ((op_splay_val_t *)existing)->version != ver) {
					sv.offset = v_c;
					sv.version = ver;
					delta_splay_insert(&h_v_sp, fp_v, &sv);
				}
			}
			if (have_fp_r) {
				op_splay_val_t sv;
				op_splay_val_t *existing;
				existing = delta_splay_find(&h_r_sp, fp_r);
				if (!existing || ((op_splay_val_t *)existing)->version != ver) {
					sv.offset = r_c;
					sv.version = ver;
					delta_splay_insert(&h_r_sp, fp_r, &sv);
				}
			}
		} else {
			if (have_fp_v) {
				size_t idx = (size_t)(fp_v % (uint64_t)q);
				if (!h_v_ht[idx].occupied || h_v_ht[idx].version != ver) {
					/* retain-existing only within current version */
				} else {
					goto skip_v_store;
				}
				h_v_ht[idx].fp = fp_v;
				h_v_ht[idx].offset = v_c;
				h_v_ht[idx].version = ver;
				h_v_ht[idx].occupied = true;
skip_v_store:;
			}
			if (have_fp_r) {
				size_t idx = (size_t)(fp_r % (uint64_t)q);
				if (h_r_ht[idx].occupied && h_r_ht[idx].version == ver) {
					goto skip_r_store;
				}
				h_r_ht[idx].fp = fp_r;
				h_r_ht[idx].offset = r_c;
				h_r_ht[idx].version = ver;
				h_r_ht[idx].occupied = true;
skip_r_store:;
			}
		}

		/* Step (4b): cross-lookup for matching seed */
		if (have_fp_r) {
			if (use_splay) {
				op_splay_val_t *sv = delta_splay_find(&h_v_sp, fp_r);
				if (sv && sv->version == ver) {
					dbg_lookups++;
					if (memcmp(&r[r_c], &v[sv->offset], p) == 0) {
						r_m = r_c;
						v_m = sv->offset;
						match_found = true;
					}
				}
			} else {
				size_t idx = (size_t)(fp_r % (uint64_t)q);
				if (h_v_ht[idx].occupied &&
				    h_v_ht[idx].version == ver &&
				    h_v_ht[idx].fp == fp_r) {
					dbg_lookups++;
					if (memcmp(&r[r_c], &v[h_v_ht[idx].offset], p) == 0) {
						r_m = r_c;
						v_m = h_v_ht[idx].offset;
						match_found = true;
					}
				}
			}
		}

		if (!match_found && have_fp_v) {
			if (use_splay) {
				op_splay_val_t *sv = delta_splay_find(&h_r_sp, fp_v);
				if (sv && sv->version == ver) {
					dbg_lookups++;
					if (memcmp(&v[v_c], &r[sv->offset], p) == 0) {
						v_m = v_c;
						r_m = sv->offset;
						match_found = true;
					}
				}
			} else {
				size_t idx = (size_t)(fp_v % (uint64_t)q);
				if (h_r_ht[idx].occupied &&
				    h_r_ht[idx].version == ver &&
				    h_r_ht[idx].fp == fp_v) {
					dbg_lookups++;
					if (memcmp(&v[v_c], &r[h_r_ht[idx].offset], p) == 0) {
						v_m = v_c;
						r_m = h_r_ht[idx].offset;
						match_found = true;
					}
				}
			}
		}

		if (!match_found) {
			v_c++;
			r_c++;
			continue;
		}
		dbg_matches++;

		/* Step (5): extend match forward */
		{
			size_t ml = 0;
			while (v_m + ml < v_len && r_m + ml < r_len &&
			       v[v_m + ml] == r[r_m + ml]) {
				ml++;
			}

			if (ml < p) {
				v_c++;
				r_c++;
				continue;
			}

			/* Step (6): encode */
			if (v_s < v_m) {
				delta_command_t cmd;
				cmd.tag = CMD_ADD;
				cmd.add.length = v_m - v_s;
				cmd.add.data = delta_malloc(cmd.add.length);
				memcpy(cmd.add.data, &v[v_s], cmd.add.length);
				delta_commands_push(&commands, cmd);
			}
			{
				delta_command_t cmd;
				cmd.tag = CMD_COPY;
				cmd.copy.offset = r_m;
				cmd.copy.length = ml;
				delta_commands_push(&commands, cmd);
			}
			v_s = v_m + ml;

			/* Step (7): advance past matched region, flush tables */
			v_c = v_m + ml;
			r_c = r_m + ml;
			ver++;
		}
	}

	/* Step (8): trailing add */
	if (v_s < v_len) {
		delta_command_t cmd;
		cmd.tag = CMD_ADD;
		cmd.add.length = v_len - v_s;
		cmd.add.data = delta_malloc(cmd.add.length);
		memcpy(cmd.add.data, &v[v_s], cmd.add.length);
		delta_commands_push(&commands, cmd);
	}

	if (verbose) {
		double hit_pct = dbg_lookups > 0
		    ? (double)dbg_matches / dbg_lookups * 100.0 : 0.0;
		fprintf(stderr,
		        "  scan: %zu positions, %zu lookups, %zu matches (flushes)\n"
		        "  scan: hit rate %.1f%% (of lookups)\n",
		        dbg_positions, dbg_lookups, dbg_matches, hit_pct);
		delta_print_command_stats(&commands);
	}

	/* Cleanup */
	if (use_splay) {
		delta_splay_free(&h_v_sp);
		delta_splay_free(&h_r_sp);
	} else {
		free(h_v_ht);
		free(h_r_ht);
	}

	return commands;
}
