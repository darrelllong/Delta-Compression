// The correcting 1.5-pass algorithm (Section 7, Figure 8) with checkpointing
// (Section 8).  One pass over R enters its seeds in a table, first offset
// wins; a scan of V looks each seed up and extends a match in both
// directions.  A match that reaches back into V already encoded replaces
// what was encoded there (tail correction, Section 5.1), for which the most
// recent commands are held in a buffer before they are emitted.
//
// Checkpointing bounds the table for any |R|: only seeds whose fingerprint
// falls in one residue class mod m are entered or looked up, with m chosen
// so that about half the table fills.  Backward extension recovers the part
// of a match that precedes its first checkpoint seed.

#include "internal.h"

// The table is keyed by the whole fingerprint.  The hash table probes
// linearly from the slot the checkpoint assigns.
typedef struct {
	uint64_t fp; // EMPTY if the slot is free
	size_t   offset;
} slot_t;

#define EMPTY UINT64_MAX // not a fingerprint: those are below 2^61

// Checkpoint parameters (Section 8.1).  A fingerprint fp is reduced to
// f = fp mod f_size; it is a checkpoint if f mod m == k, and its home slot
// is then f / m.
typedef struct {
	size_t   cap;    // table slots, |C| in the paper
	uint64_t f_size; // |F|
	uint64_t m;
	uint64_t k;
} checkpoint_t;

static inline bool
is_checkpoint(checkpoint_t cp, uint64_t fp, size_t *home)
{
	uint64_t f = fp % cp.f_size;
	if (f % cp.m != cp.k) {
		return false;
	}
	*home = (size_t)(f / cp.m);
	return true;
}

static checkpoint_t
choose_checkpoint(const uint8_t *v, size_t v_len, size_t num_seeds,
                  const delta_diff_options_t *opts)
{
	size_t p = opts->p;
	size_t max_table = opts->max_table > 0
	    ? opts->max_table : DELTA_MAX_TABLE_SIZE;
	checkpoint_t cp;

	size_t want = 2 * num_seeds / p;
	if (want < opts->q) {
		want = opts->q;
	}
	if (want > max_table) {
		want = max_table;
	}
	cp.cap = delta_next_prime(want);
	cp.f_size = num_seeds > 0 ? delta_next_prime(2 * num_seeds) : 1;
	cp.m = (cp.f_size + cp.cap - 1) / cp.cap; // at least 1

	// Any class would do for R alone.  Taking the class of a seed from
	// the middle of V makes sure V has a checkpoint there (p. 348).
	cp.k = 0;
	if (v_len >= p) {
		size_t mid = v_len / 2 < v_len - p ? v_len / 2 : v_len - p;
		cp.k = delta_fingerprint(v, mid, p) % cp.f_size % cp.m;
	}
	return cp;
}

typedef struct {
	slot_t       *slots; // NULL when the tree is in use
	size_t        cap;
	delta_splay_t tree;  // fingerprint -> offset
} table_t;

typedef struct {
	size_t passed; // seeds of R that are checkpoints
	size_t stored;
	size_t probes; // slots passed over, or seeds found already present
} build_stats_t;

// table_store enters offset under fp unless fp is already present.
static void
table_store(table_t *t, uint64_t fp, size_t home, size_t offset,
            build_stats_t *st)
{
	if (!t->slots) {
		size_t *old = delta_splay_insert_or_get(&t->tree, fp, &offset);
		if (*old == offset) {
			st->stored++;
		} else {
			st->probes++;
		}
		return;
	}
	size_t i = home;
	while (t->slots[i].fp != EMPTY) {
		if (t->slots[i].fp == fp) {
			return;
		}
		if (++i == t->cap) {
			i = 0;
		}
		st->probes++;
		if (i == home) {
			return; // full
		}
	}
	t->slots[i].fp = fp;
	t->slots[i].offset = offset;
	st->stored++;
}

static bool
table_lookup(table_t *t, uint64_t fp, size_t home, size_t *offset)
{
	if (!t->slots) {
		const size_t *val = delta_splay_find(&t->tree, fp);
		if (val) {
			*offset = *val;
		}
		return val != NULL;
	}
	size_t i = home;
	while (t->slots[i].fp != EMPTY) {
		if (t->slots[i].fp == fp) {
			*offset = t->slots[i].offset;
			return true;
		}
		if (++i == t->cap) {
			i = 0;
		}
		if (i == home) {
			break;
		}
	}
	return false;
}

static void
table_build(table_t *t, checkpoint_t cp, const uint8_t *r,
            size_t num_seeds, size_t p, bool use_splay, build_stats_t *st)
{
	t->cap = cp.cap;
	t->slots = NULL;
	if (use_splay) {
		delta_splay_init(&t->tree, sizeof(size_t));
	} else {
		t->slots = delta_malloc(t->cap * sizeof(*t->slots));
		memset(t->slots, 0xFF, t->cap * sizeof(*t->slots)); // all EMPTY
	}

	if (num_seeds == 0) {
		return;
	}
	delta_rolling_hash_t h;
	delta_rh_init(&h, r, 0, p);
	for (size_t a = 0; a < num_seeds; a++) {
		size_t home;
		if (a > 0) {
			delta_rh_roll(&h, r[a - 1], r[a + p - 1]);
		}
		if (is_checkpoint(cp, h.value, &home)) {
			st->passed++;
			table_store(t, h.value, home, a, st);
		}
	}
}

static void
table_free(table_t *t)
{
	if (t->slots) {
		free(t->slots);
	} else {
		delta_splay_free(&t->tree);
	}
}

// The correction buffer: a queue of the latest commands, each covering
// v[v_start, v_end).  An add's bytes are those of V, so an entry needs no
// copy of them until it is emitted.
typedef struct {
	size_t v_start;
	size_t v_end;
	bool   is_copy;
	size_t r_offset; // copies only
} pending_t;

typedef struct {
	pending_t *ring;
	size_t     cap;
	size_t     head; // index of the oldest entry
	size_t     len;
	const uint8_t    *v;
	delta_commands_t *out;
} buffer_t;

static pending_t *
buffer_last(buffer_t *b)
{
	return &b->ring[(b->head + b->len - 1) % b->cap];
}

static void
buffer_emit_oldest(buffer_t *b)
{
	const pending_t *e = &b->ring[b->head];
	size_t len = e->v_end - e->v_start;

	if (e->is_copy) {
		delta_push_copy(b->out, e->r_offset, len);
	} else {
		delta_push_add(b->out, &b->v[e->v_start], len);
	}
	b->head = (b->head + 1) % b->cap;
	b->len--;
}

static void
buffer_push(buffer_t *b, pending_t e)
{
	if (b->len == b->cap) {
		buffer_emit_oldest(b);
	}
	b->ring[(b->head + b->len) % b->cap] = e;
	b->len++;
}

// buffer_correct makes room for a match covering v[v_m, match_end) that
// begins before v_s, the end of what is encoded.  Working back from the
// newest command, it drops those the match covers entirely and shortens an
// add that the match covers in part.  A copy covered in part stays whole.
// It returns the position in V from which the match is still needed.
static size_t
buffer_correct(buffer_t *b, size_t v_m, size_t match_end, size_t v_s)
{
	size_t start = v_s;

	while (b->len > 0) {
		pending_t *last = buffer_last(b);
		if (last->v_start >= v_m && last->v_end <= match_end) {
			start = last->v_start;
			b->len--;
			continue;
		}
		if (last->v_start < v_m && last->v_end > v_m && !last->is_copy) {
			last->v_end = v_m;
			start = v_m;
		}
		break;
	}
	return start;
}

delta_commands_t
delta_diff_correcting(const uint8_t *r, size_t r_len,
                      const uint8_t *v, size_t v_len,
                      const delta_diff_options_t *opts)
{
	size_t p = opts->p;
	bool verbose = delta_flag_get(opts->flags, DELTA_OPT_VERBOSE);
	bool use_splay = delta_flag_get(opts->flags, DELTA_OPT_SPLAY);

	delta_commands_t commands;
	delta_commands_init(&commands);
	if (v_len == 0) {
		return commands;
	}

	size_t num_seeds = delta_num_seeds(r_len, p);
	checkpoint_t cp = choose_checkpoint(v, v_len, num_seeds, opts);

	if (verbose) {
		uint64_t expected = num_seeds / cp.m;
		fprintf(stderr,
		        "correcting: %s, |C|=%zu |F|=%llu m=%llu k=%llu\n"
		        "  checkpoint gap=%llu bytes, expected fill ~%llu "
		        "(~%llu%% table occupancy)\n"
		        "  table memory ~%zu MB\n",
		        use_splay ? "splay tree" : "hash table",
		        cp.cap, (unsigned long long)cp.f_size,
		        (unsigned long long)cp.m, (unsigned long long)cp.k,
		        (unsigned long long)cp.m, (unsigned long long)expected,
		        (unsigned long long)(expected * 100 / cp.cap),
		        cp.cap * sizeof(slot_t) / 1048576);
	}

	table_t table;
	build_stats_t built = {0};
	table_build(&table, cp, r, num_seeds, p, use_splay, &built);

	if (verbose) {
		double passed_pct = num_seeds > 0
		    ? (double)built.passed / num_seeds * 100.0 : 0.0;
		size_t occupied = use_splay ? table.tree.size : built.stored;
		fprintf(stderr,
		        "  build: %zu seeds, %zu passed checkpoint (%.2f%%), "
		        "%zu stored, %zu extra probes\n"
		        "  build: table occupancy %zu/%zu (%.1f%%)\n",
		        num_seeds, built.passed, passed_pct,
		        built.stored, built.probes,
		        occupied, cp.cap, (double)occupied / cp.cap * 100.0);
	}

	buffer_t buf = { .cap = opts->buf_cap > 0 ? opts->buf_cap : 1,
	                 .v = v, .out = &commands };
	buf.ring = delta_malloc(buf.cap * sizeof(*buf.ring));

	// v_c is the scan position; V before v_s is already encoded.  The
	// scan never falls behind: v_c >= v_s.
	size_t v_c = 0, v_s = 0;
	seed_hash_t h = {0};
	size_t checkpoints = 0, matches = 0, byte_mismatches = 0;

	while (v_c + p <= v_len) {
		uint64_t fp = seed_hash_at(&h, v, v_c, p);
		size_t home, r_off;

		if (!is_checkpoint(cp, fp, &home)) {
			v_c++;
			continue;
		}
		checkpoints++;
		if (!table_lookup(&table, fp, home, &r_off)) {
			v_c++;
			continue;
		}
		if (memcmp(&r[r_off], &v[v_c], p) != 0) {
			byte_mismatches++;
			v_c++;
			continue;
		}
		matches++;

		size_t fwd = delta_extend(r, r_len, r_off, v, v_len, v_c, p);
		size_t bwd = 0;
		while (bwd < v_c && bwd < r_off &&
		       v[v_c - bwd - 1] == r[r_off - bwd - 1]) {
			bwd++;
		}
		size_t v_m = v_c - bwd;
		size_t r_m = r_off - bwd;
		size_t match_end = v_c + fwd;

		if (v_m >= v_s) {
			if (v_m > v_s) {
				buffer_push(&buf,
				    (pending_t){ .v_start = v_s, .v_end = v_m });
			}
		} else {
			size_t start = buffer_correct(&buf, v_m, match_end, v_s);
			r_m += start - v_m;
			v_m = start;
		}
		buffer_push(&buf, (pending_t){ .v_start = v_m,
		    .v_end = match_end, .is_copy = true, .r_offset = r_m });
		v_c = v_s = match_end;
	}

	while (buf.len > 0) {
		buffer_emit_oldest(&buf);
	}
	free(buf.ring);
	if (v_s < v_len) {
		delta_push_add(&commands, &v[v_s], v_len - v_s);
	}

	if (verbose) {
		size_t v_seeds = delta_num_seeds(v_len, p);
		double cp_pct = v_seeds > 0
		    ? (double)checkpoints / v_seeds * 100.0 : 0.0;
		double hit_pct = checkpoints > 0
		    ? (double)matches / checkpoints * 100.0 : 0.0;
		// A lookup is by the whole fingerprint, so what it finds can
		// differ from the seed only in its bytes: no collisions of
		// fingerprints are counted.
		fprintf(stderr,
		        "  scan: %zu V positions, %zu checkpoints (%.3f%%), "
		        "%zu matches\n"
		        "  scan: hit rate %.1f%% (of checkpoints), "
		        "fp collisions 0, byte mismatches %zu\n",
		        v_seeds, checkpoints, cp_pct, matches,
		        hit_pct, byte_mismatches);
		delta_print_command_stats(&commands);
	}

	table_free(&table);
	return commands;
}
