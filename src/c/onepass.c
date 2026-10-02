// The one-pass algorithm (Section 4.1, Figure 3): scan R and V together,
// entering each seed in a table for its own string and looking it up in the
// table for the other.  After a match both scans resume past it and both
// tables are emptied, so the tables stay small and time is linear; a match
// that lies behind either scan is not found.

#include "internal.h"

// Emptying a table is done by counting: an entry is live only if its version
// is the current one.  Versions start at 1, so zeroed memory is empty.
typedef struct {
	uint64_t fp;
	size_t   offset;
	uint64_t version;
} slot_t;

typedef struct {
	size_t   offset;
	uint64_t version;
} tree_val_t;

// A table maps a fingerprint to the first offset that had it since the table
// was last emptied.  The hash table has one slot per bucket, and the first
// fingerprint to claim a bucket keeps it; the splay tree keeps them all.
typedef struct {
	slot_t       *slots; // NULL when the tree is in use
	size_t        q;
	delta_splay_t tree;
} table_t;

static void
table_init(table_t *t, size_t q, bool use_splay)
{
	t->q = q;
	t->slots = NULL;
	if (use_splay) {
		delta_splay_init(&t->tree, sizeof(tree_val_t));
	} else {
		t->slots = delta_calloc(q, sizeof(*t->slots));
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

static void
table_store(table_t *t, uint64_t fp, size_t offset, uint64_t version)
{
	if (t->slots) {
		slot_t *s = &t->slots[fp % t->q];
		if (s->version != version) {
			s->fp = fp;
			s->offset = offset;
			s->version = version;
		}
		return;
	}
	tree_val_t *old = delta_splay_find(&t->tree, fp);
	if (!old || old->version != version) {
		tree_val_t val = { offset, version };
		delta_splay_insert(&t->tree, fp, &val);
	}
}

// table_lookup reports whether fp is in the table and, if so, sets *offset.
static bool
table_lookup(table_t *t, uint64_t fp, uint64_t version, size_t *offset)
{
	if (t->slots) {
		const slot_t *s = &t->slots[fp % t->q];
		if (s->version != version || s->fp != fp) {
			return false;
		}
		*offset = s->offset;
		return true;
	}
	const tree_val_t *val = delta_splay_find(&t->tree, fp);
	if (!val || val->version != version) {
		return false;
	}
	*offset = val->offset;
	return true;
}

delta_commands_t
delta_diff_onepass(const uint8_t *r, size_t r_len,
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

	// One slot for each p bytes of R, and no fewer than asked for.
	size_t q = delta_num_seeds(r_len, p) / p;
	q = delta_next_prime(q > opts->q ? q : opts->q);

	if (verbose) {
		fprintf(stderr,
		        "onepass: %s, q=%zu, |R|=%zu, |V|=%zu, seed_len=%zu\n",
		        use_splay ? "splay tree" : "hash table",
		        q, r_len, v_len, p);
	}

	table_t r_table, v_table;
	table_init(&r_table, q, use_splay);
	table_init(&v_table, q, use_splay);
	uint64_t version = 1;

	// r_c and v_c are the scan positions; V before v_s is already encoded.
	size_t r_c = 0, v_c = 0, v_s = 0;
	seed_hash_t r_hash = {0}, v_hash = {0};
	size_t positions = 0, lookups = 0, matches = 0;

	while (v_c + p <= v_len || r_c + p <= r_len) {
		bool have_r = r_c + p <= r_len;
		bool have_v = v_c + p <= v_len;
		uint64_t fp_r = 0, fp_v = 0;
		positions++;

		if (have_v) {
			fp_v = seed_hash_at(&v_hash, v, v_c, p);
			table_store(&v_table, fp_v, v_c, version);
		}
		if (have_r) {
			fp_r = seed_hash_at(&r_hash, r, r_c, p);
			table_store(&r_table, fp_r, r_c, version);
		}

		// Look for the seed of R in V, then for the seed of V in R.  A
		// fingerprint found is a match only if the bytes agree.
		size_t r_m = 0, v_m = 0;
		bool matched = false;
		if (have_r && table_lookup(&v_table, fp_r, version, &v_m)) {
			lookups++;
			r_m = r_c;
			matched = memcmp(&r[r_m], &v[v_m], p) == 0;
		}
		if (!matched && have_v &&
		    table_lookup(&r_table, fp_v, version, &r_m)) {
			lookups++;
			v_m = v_c;
			matched = memcmp(&r[r_m], &v[v_m], p) == 0;
		}
		if (!matched) {
			r_c++;
			v_c++;
			continue;
		}
		matches++;

		size_t len = delta_extend(r, r_len, r_m, v, v_len, v_m, p);
		if (v_s < v_m) {
			delta_push_add(&commands, &v[v_s], v_m - v_s);
		}
		delta_push_copy(&commands, r_m, len);
		r_c = r_m + len;
		v_c = v_s = v_m + len;
		version++;
	}
	if (v_s < v_len) {
		delta_push_add(&commands, &v[v_s], v_len - v_s);
	}

	if (verbose) {
		double hit_pct = lookups > 0
		    ? (double)matches / lookups * 100.0 : 0.0;
		fprintf(stderr,
		        "  scan: %zu positions, %zu lookups, %zu matches (flushes)\n"
		        "  scan: hit rate %.1f%% (of lookups)\n",
		        positions, lookups, matches, hit_pct);
		delta_print_command_stats(&commands);
	}

	table_free(&r_table);
	table_free(&v_table);
	return commands;
}
