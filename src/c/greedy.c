// The greedy algorithm (Section 3.1, Figure 2): index every seed of R, then
// at each position of V take the longest match that any seed with the same
// fingerprint begins.  Optimal if p <= 2 (Section 3.3); a longer seed misses
// the matches shorter than itself.  Quadratic in the worst case.

#include "internal.h"

#define NIL SIZE_MAX

// The index chains the offsets in R whose seeds share a hash bucket, or, in
// the splay tree, a fingerprint.  Chains run in increasing offset, so among
// matches of equal length the scan takes the earliest in R.
typedef struct {
	uint64_t fp;   // fingerprint of the seed at this offset
	size_t   next; // the offset after this one in its chain, or NIL
} seed_t;

// The ends of one fingerprint's chain.
typedef struct {
	size_t head;
	size_t tail;
} chain_t;

typedef struct {
	seed_t *seed;  // indexed by offset in R
	size_t *head;  // hash table: first offset of each bucket's chain
	size_t *tail;  //             and last
	size_t  nbuckets;
	delta_splay_t tree; // splay tree: fingerprint -> chain_t
	bool    use_splay;
} seed_index_t;

static void
index_append(seed_index_t *ix, uint64_t fp, size_t a)
{
	size_t *tail;

	ix->seed[a].fp = fp;
	ix->seed[a].next = NIL;
	if (ix->use_splay) {
		chain_t fresh = { a, NIL };
		chain_t *c = delta_splay_insert_or_get(&ix->tree, fp, &fresh);
		tail = &c->tail;
	} else {
		size_t b = fp % ix->nbuckets;
		if (ix->tail[b] == NIL) {
			ix->head[b] = a;
		}
		tail = &ix->tail[b];
	}
	if (*tail != NIL) {
		ix->seed[*tail].next = a;
	}
	*tail = a;
}

static void
index_build(seed_index_t *ix, const uint8_t *r, size_t num_seeds, size_t p,
            bool use_splay)
{
	ix->use_splay = use_splay;
	ix->seed = delta_malloc(num_seeds * sizeof(*ix->seed));
	ix->head = ix->tail = NULL;
	if (use_splay) {
		delta_splay_init(&ix->tree, sizeof(chain_t));
	} else {
		// About one bucket for each p seeds.
		ix->nbuckets = delta_next_prime(num_seeds / p + 1);
		ix->head = delta_malloc(ix->nbuckets * sizeof(*ix->head));
		ix->tail = delta_malloc(ix->nbuckets * sizeof(*ix->tail));
		for (size_t b = 0; b < ix->nbuckets; b++) {
			ix->head[b] = ix->tail[b] = NIL;
		}
	}

	if (num_seeds == 0) {
		return;
	}
	delta_rolling_hash_t h;
	delta_rh_init(&h, r, 0, p);
	for (size_t a = 0; a < num_seeds; a++) {
		if (a > 0) {
			delta_rh_roll(&h, r[a - 1], r[a + p - 1]);
		}
		index_append(ix, h.value, a);
	}
}

// index_chain returns the first offset of the chain that holds every seed
// with fingerprint fp.  The chain may hold other fingerprints too.
static size_t
index_chain(seed_index_t *ix, uint64_t fp)
{
	if (ix->use_splay) {
		chain_t *c = delta_splay_find(&ix->tree, fp);
		return c ? c->head : NIL;
	}
	return ix->head[fp % ix->nbuckets];
}

static void
index_free(seed_index_t *ix)
{
	if (ix->use_splay) {
		delta_splay_free(&ix->tree);
	}
	free(ix->seed);
	free(ix->head);
	free(ix->tail);
}

delta_commands_t
delta_diff_greedy(const uint8_t *r, size_t r_len,
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

	seed_index_t ix;
	index_build(&ix, r, delta_num_seeds(r_len, p), p, use_splay);

	if (verbose) {
		fprintf(stderr, "greedy: %s, |R|=%zu, |V|=%zu, seed_len=%zu\n",
		        use_splay ? "splay tree" : "hash table",
		        r_len, v_len, p);
	}

	// v_c is the scan position; V before v_s is already encoded.
	size_t v_c = 0, v_s = 0;
	seed_hash_t h = {0};

	while (v_c + p <= v_len) {
		uint64_t fp = seed_hash_at(&h, v, v_c, p);
		size_t best_len = 0, best_off = 0;

		for (size_t a = index_chain(&ix, fp); a != NIL;
		     a = ix.seed[a].next) {
			if (ix.seed[a].fp != fp ||
			    memcmp(&r[a], &v[v_c], p) != 0) {
				continue;
			}
			size_t len = delta_extend(r, r_len, a, v, v_len, v_c, p);
			if (len > best_len) {
				best_len = len;
				best_off = a;
			}
		}
		if (best_len == 0) {
			v_c++;
			continue;
		}

		if (v_s < v_c) {
			delta_push_add(&commands, &v[v_s], v_c - v_s);
		}
		delta_push_copy(&commands, best_off, best_len);
		v_c += best_len;
		v_s = v_c;
	}
	if (v_s < v_len) {
		delta_push_add(&commands, &v[v_s], v_len - v_s);
	}

	if (verbose) {
		delta_print_command_stats(&commands);
	}
	index_free(&ix);
	return commands;
}
