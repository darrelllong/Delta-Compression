/*
 * inplace.c — In-place delta conversion (Burns, Long, Stockmeyer —
 *             IEEE TKDE 2003)
 *
 * High-level flow (functions listed in call order below):
 *
 *   1. Parse commands → copies[] + adds_t (assign sequential write offsets).
 *   2. build_crwi_digraph(): sort copies by dst, binary-search read intervals
 *      to find all CRWI edges.  O(n log n + E).
 *   3. run_kahn():
 *        a. tarjan_scc() → build_scc_list(): SCC decomposition.  O(n + E).
 *        b. Global Kahn on a min-heap keyed by (copy length, index);
 *           when the heap stalls, pick_victim() finds the shortest copy in
 *           a cycle and materialises it as a literal add.
 *           Total cycle-breaking work O(n + E) via three amortisations:
 *           scc_id filter, color=2 persistence, scan resumption.
 *   4. Assemble result: topo-ordered copies, then all adds.
 *
 * CRWI edge i→j: copy i reads from a region that copy j will overwrite,
 * so i must run before j.  A cycle → circular dependency; break it by
 * materialising the shortest copy as a literal (bytes read from R before
 * any overwrite occurs).
 *
 * R.E. Tarjan, SIAM J. Comput., 1(2):146-160, June 1972.
 */

#include "delta.h"

#include <stdlib.h>
#include <string.h>

/* ── copy_info_t ─────────────────────────────────────────────────────── */

typedef struct {
	size_t idx, src, dst, length;
} copy_info_t;

/* ── write_pair_t (sort copies by write destination) ────────────────── */

typedef struct { size_t dst; size_t idx; } write_pair_t;

static int cmp_write_pair(const void *a, const void *b)
{
	size_t da = ((const write_pair_t *)a)->dst;
	size_t db = ((const write_pair_t *)b)->dst;
	return (da > db) - (da < db);
}

/* ── size_buf_t (dynamic array of size_t) ───────────────────────────── */

typedef struct { size_t *data; size_t len; size_t cap; } size_buf_t;

static void size_buf_init(size_buf_t *b) { b->data = NULL; b->len = 0; b->cap = 0; }
static void size_buf_free(size_buf_t *b) { free(b->data); b->data = NULL; b->len = 0; b->cap = 0; }
static void size_buf_push(size_buf_t *b, size_t v)
{
	if (b->len == b->cap) {
		b->cap = b->cap ? b->cap * 2 : 16;
		b->data = delta_realloc(b->data, b->cap * sizeof(*b->data));
	}
	b->data[b->len++] = v;
}

/* ── stk_buf_t (DFS call-stack frames) ──────────────────────────────── */

typedef struct { size_t v; size_t ni; } stk_entry_t;
typedef struct { stk_entry_t *data; size_t len; size_t cap; } stk_buf_t;

static void stk_buf_init(stk_buf_t *b) { b->data = NULL; b->len = 0; b->cap = 0; }
static void stk_buf_free(stk_buf_t *b) { free(b->data); b->data = NULL; b->len = 0; b->cap = 0; }
static void stk_buf_push(stk_buf_t *b, size_t v, size_t ni)
{
	if (b->len == b->cap) {
		b->cap = b->cap ? b->cap * 2 : 16;
		b->data = delta_realloc(b->data, b->cap * sizeof(*b->data));
	}
	b->data[b->len].v  = v;
	b->data[b->len].ni = ni;
	b->len++;
}

/* ── adj_list_t (CRWI digraph adjacency list) ───────────────────────── */

typedef struct {
	size_t **nbrs;    /* nbrs[i][0..nbr_len[i]) = out-neighbours of i */
	size_t  *nbr_len;
	size_t  *nbr_cap;
	size_t   n;
} adj_list_t;

static adj_list_t adj_list_alloc(size_t n)
{
	adj_list_t a;
	a.nbrs    = delta_calloc(n, sizeof(*a.nbrs));
	a.nbr_len = delta_calloc(n, sizeof(*a.nbr_len));
	a.nbr_cap = delta_calloc(n, sizeof(*a.nbr_cap));
	a.n       = n;
	return a;
}

static void adj_list_free(adj_list_t *a)
{
	size_t i;
	for (i = 0; i < a->n; i++) { free(a->nbrs[i]); }
	free(a->nbrs); free(a->nbr_len); free(a->nbr_cap);
}

static void adj_list_push(adj_list_t *a, size_t i, size_t j)
{
	if (a->nbr_len[i] == a->nbr_cap[i]) {
		a->nbr_cap[i] = a->nbr_cap[i] ? a->nbr_cap[i] * 2 : 4;
		a->nbrs[i] = delta_realloc(a->nbrs[i],
		                 a->nbr_cap[i] * sizeof(*a->nbrs[i]));
	}
	a->nbrs[i][a->nbr_len[i]++] = j;
}

/* ── adds_t (accumulator for literal adds) ──────────────────────────── */

typedef struct {
	size_t   *dsts;
	uint8_t **datas;  /* heap-allocated; ownership transferred to result */
	size_t   *lens;
	size_t    len;
	size_t    cap;
} adds_t;

static void adds_init(adds_t *a)
{
	a->dsts = NULL; a->datas = NULL; a->lens = NULL;
	a->len  = 0;    a->cap   = 0;
}

static void adds_push(adds_t *a, size_t dst, uint8_t *data, size_t len)
{
	if (a->len == a->cap) {
		a->cap   = a->cap ? a->cap * 2 : 16;
		a->dsts  = delta_realloc(a->dsts,  a->cap * sizeof(*a->dsts));
		a->datas = delta_realloc(a->datas, a->cap * sizeof(*a->datas));
		a->lens  = delta_realloc(a->lens,  a->cap * sizeof(*a->lens));
	}
	a->dsts [a->len] = dst;
	a->datas[a->len] = data;
	a->lens [a->len] = len;
	a->len++;
}

/* ── scc_result_t (raw Tarjan output) ───────────────────────────────── */

typedef struct {
	size_t *data;    /* concatenated SCC vertices (sinks first) */
	size_t *offsets; /* offsets[i]..offsets[i+1) = SCC i vertices */
	size_t  n_sccs;
} scc_result_t;

static void scc_result_init(scc_result_t *s)
	{ s->data = NULL; s->offsets = NULL; s->n_sccs = 0; }
static void scc_result_free(scc_result_t *s)
	{ free(s->data); free(s->offsets); s->data = NULL; s->offsets = NULL; s->n_sccs = 0; }

/* ── scc_list_t (non-trivial SCCs, source-first, with active counts) ── */

typedef struct {
	size_t *verts;   /* concatenated SCC vertices */
	size_t *offs;    /* offs[i]..offs[i+1) = SCC i */
	size_t *active;  /* number of unremoved vertices in SCC i */
	size_t *id;      /* id[v] = SCC index; SIZE_MAX = trivial (no cycle) */
	size_t  len;     /* number of non-trivial SCCs */
} scc_list_t;

static void scc_list_free(scc_list_t *sl)
	{ free(sl->verts); free(sl->offs); free(sl->active); free(sl->id); }

/* ── minheap_t (min-heap keyed by (copy length, index)) ─────────────── */
/* Secondary key on index makes tie-breaking deterministic across runs. */

typedef struct { size_t len; size_t idx; } heap_entry_t;
typedef struct { heap_entry_t *data; size_t len; size_t cap; } minheap_t;

static bool heap_lt(heap_entry_t a, heap_entry_t b)
	{ return a.len < b.len || (a.len == b.len && a.idx < b.idx); }

static void minheap_init(minheap_t *h, size_t cap)
	{ h->data = delta_malloc(cap * sizeof(*h->data)); h->len = 0; h->cap = cap; }

static void minheap_free(minheap_t *h) { free(h->data); }

static void minheap_push(minheap_t *h, heap_entry_t e)
{
	if (h->len == h->cap) {
		h->cap *= 2;
		h->data = delta_realloc(h->data, h->cap * sizeof(*h->data));
	}
	size_t k = h->len++;
	while (k > 0) {
		size_t p = (k - 1) / 2;
		if (!heap_lt(e, h->data[p])) break;
		h->data[k] = h->data[p]; k = p;
	}
	h->data[k] = e;
}

static heap_entry_t minheap_pop(minheap_t *h)
{
	heap_entry_t out  = h->data[0];
	heap_entry_t last = h->data[--h->len];
	size_t k = 0;
	for (;;) {
		size_t s = k, l = 2*k+1, r = 2*k+2;
		if (l < h->len && heap_lt(h->data[l], h->data[s])) s = l;
		if (r < h->len && heap_lt(h->data[r], h->data[s])) s = r;
		if (s == k) break;
		h->data[k] = h->data[s]; k = s;
	}
	h->data[k] = last;
	return out;
}

/* ── tarjan_scc ─────────────────────────────────────────────────────── */
/*
 * Iterative Tarjan's algorithm.  Returns SCCs in reverse topological
 * order (sinks first); build_scc_list() reverses to source-first.
 */
static scc_result_t
tarjan_scc(const adj_list_t *adj)
{
	size_t       n = adj->n;
	scc_result_t res; scc_result_init(&res);

	size_t *idx_arr = delta_malloc(n * sizeof(*idx_arr));
	memset(idx_arr, 0xFF, n * sizeof(*idx_arr));  /* SIZE_MAX = unvisited */
	size_t *lowlink = delta_malloc(n * sizeof(*lowlink));
	bool   *on_stk  = delta_calloc(n, sizeof(*on_stk));

	size_buf_t tarjan_stack; size_buf_init(&tarjan_stack);
	stk_buf_t  call_stack;   stk_buf_init(&call_stack);
	size_buf_t scc_data;     size_buf_init(&scc_data);
	size_buf_t scc_offs;     size_buf_init(&scc_offs);
	size_t counter = 0, n_sccs = 0, start;

	for (start = 0; start < n; start++) {
		if (idx_arr[start] != SIZE_MAX) { continue; }

		idx_arr[start] = lowlink[start] = counter++;
		on_stk[start] = true;
		size_buf_push(&tarjan_stack, start);
		stk_buf_push(&call_stack, start, 0);

		while (call_stack.len > 0) {
			size_t v  = call_stack.data[call_stack.len - 1].v;
			size_t ni = call_stack.data[call_stack.len - 1].ni;

			if (ni < adj->nbr_len[v]) {
				size_t w = adj->nbrs[v][ni];
				call_stack.data[call_stack.len - 1].ni++;
				if (idx_arr[w] == SIZE_MAX) {
					/* Tree edge: descend into w */
					idx_arr[w] = lowlink[w] = counter++;
					on_stk[w] = true;
					size_buf_push(&tarjan_stack, w);
					stk_buf_push(&call_stack, w, 0);
				} else if (on_stk[w]) {
					/* Back-edge into current SCC */
					if (idx_arr[w] < lowlink[v]) { lowlink[v] = idx_arr[w]; }
				}
			} else {
				/* Done with v — backtrack */
				call_stack.len--;
				if (call_stack.len > 0) {
					size_t parent = call_stack.data[call_stack.len - 1].v;
					if (lowlink[v] < lowlink[parent]) { lowlink[parent] = lowlink[v]; }
				}
				/* Root of an SCC: pop its members */
				if (lowlink[v] == idx_arr[v]) {
					size_t w;
					size_buf_push(&scc_offs, scc_data.len);
					do {
						w = tarjan_stack.data[--tarjan_stack.len];
						on_stk[w] = false;
						size_buf_push(&scc_data, w);
					} while (w != v);
					n_sccs++;
				}
			}
		}
	}
	size_buf_push(&scc_offs, scc_data.len);  /* sentinel */

	free(idx_arr); free(lowlink); free(on_stk);
	size_buf_free(&tarjan_stack);
	stk_buf_free(&call_stack);

	/* Transfer ownership of scc_data/scc_offs buffers to result */
	res.data = scc_data.data; res.offsets = scc_offs.data; res.n_sccs = n_sccs;
	return res;
}

/* ── find_cycle_in_scc ──────────────────────────────────────────────── */
/*
 * Find a cycle in the active subgraph of one SCC.
 *
 * Three amortizations give O(|SCC| + E_SCC) total work per SCC:
 *   1. scc_id filter: O(1) per neighbor, no O(|SCC|) set/clear.
 *   2. color persistence: color=2 (fully explored) persists across calls;
 *      vertex removal can only reduce edges, so it is monotone-correct.
 *   3. *scan_start: outer loop resumes where it left off — O(|SCC|) total.
 *
 * Returns 1 with *cycle_out / *cycle_len_out populated (caller frees).
 * Returns 0 if the active subgraph of this SCC is acyclic.
 * color[] path entries are reset to 0 on cycle found; color=2 persists.
 */
static int
find_cycle_in_scc(const adj_list_t *adj,
                  const size_t *scc_verts, size_t scc_sz,
                  size_t sid, const size_t *scc_id,
                  const bool *done, uint8_t *color,
                  size_t *scan_start,
                  size_t **cycle_out, size_t *cycle_len_out)
{
	size_buf_t path; size_buf_init(&path);
	stk_buf_t  stk;  stk_buf_init(&stk);
	size_t scan = *scan_start;

	while (scan < scc_sz) {
		size_t start = scc_verts[scan];
		if (done[start] || color[start] != 0) { scan++; continue; }

		color[start] = 1;
		size_buf_push(&path, start);
		stk_buf_push(&stk, start, 0);

		while (stk.len > 0) {
			size_t v  = stk.data[stk.len - 1].v;
			size_t ni = stk.data[stk.len - 1].ni;
			bool   advanced = false;
			size_t k;

			while (ni < adj->nbr_len[v]) {
				size_t w = adj->nbrs[v][ni++];
				if (scc_id[w] != sid || done[w]) { continue; }
				if (color[w] == 1) {
					/* Back-edge: cycle found.  w is on the current path
					 * (color[w]==1 was just confirmed), so the scan below
					 * is guaranteed to terminate before path.len. */
					size_t pos = 0;
					while (path.data[pos] != w) { pos++; }
					*cycle_len_out = path.len - pos;
					*cycle_out = delta_malloc(
					    *cycle_len_out * sizeof(**cycle_out));
					memcpy(*cycle_out, path.data + pos,
					    *cycle_len_out * sizeof(**cycle_out));
					for (k = 0; k < path.len; k++) { color[path.data[k]] = 0; }
					*scan_start = scan;
					size_buf_free(&path); stk_buf_free(&stk);
					return 1;
				}
				if (color[w] == 0) {
					stk.data[stk.len - 1].ni = ni;
					color[w] = 1;
					size_buf_push(&path, w);
					stk_buf_push(&stk, w, 0);
					advanced = true;
					break;
				}
			}
			if (!advanced) {
				stk.len--;
				color[v] = 2;  /* Fully explored — persists across calls */
				path.len--;
			}
		}
		scan++;
	}

	*scan_start = scan;
	size_buf_free(&path); stk_buf_free(&stk);
	return 0;
}

/* ── build_crwi_digraph ─────────────────────────────────────────────── */
/*
 * Build the CRWI digraph over n copy commands.
 *
 * O(n log n + E) sweep-line: sort writes by dst, then for each read
 * interval use two binary searches to find all overlapping writes.
 * Write destinations are non-overlapping (each output byte written once),
 * so the overlapping writes form a contiguous range [lo, hi) in sorted
 * order, plus at most one write at lo-1 that starts before si but
 * extends into it.
 */
static adj_list_t
build_crwi_digraph(const copy_info_t *copies, size_t n)
{
	adj_list_t    adj          = adj_list_alloc(n);
	write_pair_t *pairs        = delta_malloc(n * sizeof(*pairs));
	size_t       *write_sorted = delta_malloc(n * sizeof(*write_sorted));
	size_t       *write_starts = delta_malloc(n * sizeof(*write_starts));
	size_t i, j, k;

	for (i = 0; i < n; i++) { pairs[i].dst = copies[i].dst; pairs[i].idx = i; }
	qsort(pairs, n, sizeof(*pairs), cmp_write_pair);
	for (i = 0; i < n; i++) {
		write_sorted[i] = pairs[i].idx;
		write_starts[i] = pairs[i].dst;
	}
	free(pairs);

	for (i = 0; i < n; i++) {
		size_t si       = copies[i].src;
		size_t read_end = si + copies[i].length;

		/* lo = first k with write_starts[k] >= si */
		size_t lo;
		{ size_t a = 0, b = n;
		  while (a < b) { size_t m = a + (b-a)/2;
		                  if (write_starts[m] < si) a = m+1; else b = m; }
		  lo = a; }

		/* hi = first k with write_starts[k] >= read_end */
		size_t hi;
		{ size_t a = lo, b = n;
		  while (a < b) { size_t m = a + (b-a)/2;
		                  if (write_starts[m] < read_end) a = m+1; else b = m; }
		  hi = a; }

		/* Write at lo-1 starts before si; overlaps iff its end > si */
		if (lo > 0) {
			j = write_sorted[lo - 1];
			if (j != i && copies[j].dst + copies[j].length > si) {
				adj_list_push(&adj, i, j);
			}
		}
		/* All writes in [lo, hi) start within [si, read_end) */
		for (k = lo; k < hi; k++) {
			j = write_sorted[k];
			if (j != i) { adj_list_push(&adj, i, j); }
		}
	}

	free(write_sorted); free(write_starts);
	return adj;
}

/* ── build_scc_list ─────────────────────────────────────────────────── */
/*
 * Build a working SCC list from raw Tarjan output.
 * Filters to non-trivial SCCs (length > 1) and reverses to source-first
 * order (Tarjan emits sinks first).  Initialises active[] to SCC sizes
 * for tracking how many vertices remain unprocessed per SCC.
 */
static scc_list_t
build_scc_list(const scc_result_t *sccs, size_t n)
{
	scc_list_t sl;
	size_t i, j, k, vpos;

	sl.id = delta_malloc(n * sizeof(*sl.id));
	memset(sl.id, 0xFF, n * sizeof(*sl.id));  /* SIZE_MAX = trivial */

	sl.len = 0;
	for (i = 0; i < sccs->n_sccs; i++) {
		if (sccs->offsets[i+1] - sccs->offsets[i] > 1) { sl.len++; }
	}

	sl.verts  = delta_malloc(n * sizeof(*sl.verts));
	sl.offs   = delta_malloc((sl.len + 1) * sizeof(*sl.offs));
	sl.active = delta_calloc(sl.len > 0 ? sl.len : 1, sizeof(*sl.active));

	/* Source-first = reverse of Tarjan's sinks-first emission */
	k = 0; vpos = 0;
	for (i = sccs->n_sccs; i-- > 0; ) {
		size_t scc_sz = sccs->offsets[i+1] - sccs->offsets[i];
		if (scc_sz <= 1) { continue; }
		size_t *sv = sccs->data + sccs->offsets[i];
		sl.offs[k] = vpos;
		for (j = 0; j < scc_sz; j++) {
			sl.id[sv[j]] = k;
			sl.verts[vpos++] = sv[j];
		}
		sl.active[k] = scc_sz;
		k++;
	}
	sl.offs[k] = vpos;  /* sentinel */
	return sl;
}

/* ── pick_victim ────────────────────────────────────────────────────── */
/*
 * Select a copy to materialise as a literal add in order to break a
 * CRWI cycle.  scc_cursor and scan_pos are in/out: they track position
 * within the SCC list across successive calls so per-SCC DFS work is
 * amortised over the full Kahn run.
 *
 * Returns the index into copies[] of the chosen victim.
 */
static size_t
pick_victim(const adj_list_t *adj,
            const copy_info_t *copies, size_t n,
            delta_cycle_policy_t policy,
            const scc_list_t *sl,
            const bool *done, uint8_t *color,
            size_t *scc_cursor, size_t *scan_pos)
{
	size_t victim = n;  /* n = invalid sentinel */
	size_t i;

	if (policy == POLICY_CONSTANT) {
		for (i = 0; i < n && victim == n; i++) {
			if (!done[i]) { victim = i; }
		}
		return victim;
	}

	/* POLICY_LOCALMIN: find the shortest copy in an active cycle */
	while (victim == n) {
		while (*scc_cursor < sl->len && sl->active[*scc_cursor] == 0) {
			(*scc_cursor)++; *scan_pos = 0;
		}
		if (*scc_cursor >= sl->len) {
			/* Safety fallback: pick any remaining vertex */
			for (i = 0; i < n && victim == n; i++) {
				if (!done[i]) { victim = i; }
			}
			break;
		}
		size_t *sv      = sl->verts + sl->offs[*scc_cursor];
		size_t  sv_len  = sl->offs[*scc_cursor + 1] - sl->offs[*scc_cursor];
		size_t *cycle   = NULL;
		size_t  cyc_len = 0;
		if (find_cycle_in_scc(adj, sv, sv_len, *scc_cursor, sl->id,
		                       done, color, scan_pos, &cycle, &cyc_len)) {
			size_t ci;
			victim = cycle[0];
			for (ci = 1; ci < cyc_len; ci++) {
				size_t v = cycle[ci];
				if (copies[v].length < copies[victim].length ||
				    (copies[v].length == copies[victim].length && v < victim)) {
					victim = v;
				}
			}
			free(cycle);
		} else {
			(*scc_cursor)++; *scan_pos = 0;
		}
	}
	return victim;
}

/* ── run_kahn ───────────────────────────────────────────────────────── */
/*
 * Global Kahn topological sort with SCC-scoped cycle breaking.
 * Fills topo_order[0..return-value) with copy indices in topological
 * order.  Materialised victims are appended to *adds.
 */
static size_t
run_kahn(const adj_list_t *adj,
          const copy_info_t *copies, size_t n,
          const uint8_t *r,
          delta_cycle_policy_t policy,
          adds_t *adds,
          size_t *topo_order)
{
	scc_result_t raw  = tarjan_scc(adj);
	scc_list_t   sl   = build_scc_list(&raw, n);
	scc_result_free(&raw);

	size_t   *in_deg    = delta_calloc(n, sizeof(*in_deg));
	bool     *done      = delta_calloc(n, sizeof(*done));
	uint8_t  *color     = delta_calloc(n, sizeof(*color));
	size_t    scc_cursor = 0, scan_pos = 0;
	size_t    topo_len = 0, processed = 0;
	size_t    i, k;

	for (i = 0; i < n; i++) {
		for (k = 0; k < adj->nbr_len[i]; k++) { in_deg[adj->nbrs[i][k]]++; }
	}

	minheap_t heap; minheap_init(&heap, n + 1);
	for (i = 0; i < n; i++) {
		if (in_deg[i] == 0) {
			heap_entry_t e = { copies[i].length, i };
			minheap_push(&heap, e);
		}
	}

	while (processed < n) {
		/* Drain all zero-in-degree vertices */
		while (heap.len > 0) {
			heap_entry_t top = minheap_pop(&heap);
			size_t v = top.idx;
			if (done[v]) { continue; }
			done[v] = true;
			topo_order[topo_len++] = v;
			processed++;
			if (sl.id[v] != SIZE_MAX) { sl.active[sl.id[v]]--; }
			for (k = 0; k < adj->nbr_len[v]; k++) {
				size_t w = adj->nbrs[v][k];
				if (!done[w] && --in_deg[w] == 0) {
					heap_entry_t e = { copies[w].length, w };
					minheap_push(&heap, e);
				}
			}
		}

		if (processed >= n) { break; }

		/* Heap stalled — materialise the cycle victim */
		size_t victim = pick_victim(adj, copies, n, policy, &sl,
		                             done, color, &scc_cursor, &scan_pos);
		{
			uint8_t *mat = delta_malloc(copies[victim].length);
			memcpy(mat, r + copies[victim].src, copies[victim].length);
			adds_push(adds, copies[victim].dst, mat, copies[victim].length);
		}
		done[victim] = true;
		processed++;
		if (sl.id[victim] != SIZE_MAX) { sl.active[sl.id[victim]]--; }
		for (k = 0; k < adj->nbr_len[victim]; k++) {
			size_t w = adj->nbrs[victim][k];
			if (!done[w] && --in_deg[w] == 0) {
				heap_entry_t e = { copies[w].length, w };
				minheap_push(&heap, e);
			}
		}
	}

	scc_list_free(&sl);
	free(in_deg); free(done); free(color); minheap_free(&heap);
	return topo_len;
}

/* ── delta_make_inplace ─────────────────────────────────────────────── */

delta_placed_commands_t
delta_make_inplace(const uint8_t *r, size_t r_len,
                   const delta_commands_t *cmds,
                   delta_cycle_policy_t policy)
{
	delta_placed_commands_t result;
	delta_placed_commands_init(&result);
	if (cmds->len == 0) { return result; }
	(void)r_len;

	/* Step 1: separate copies and adds, assign sequential write offsets */
	copy_info_t *copies = NULL;
	size_t n_copies = 0, n_copies_cap = 0;
	adds_t adds; adds_init(&adds);
	size_t write_pos = 0;
	size_t i;

	for (i = 0; i < cmds->len; i++) {
		const delta_command_t *cmd = &cmds->data[i];
		if (cmd->tag == CMD_COPY) {
			if (n_copies == n_copies_cap) {
				n_copies_cap = n_copies_cap ? n_copies_cap * 2 : 16;
				copies = delta_realloc(copies, n_copies_cap * sizeof(*copies));
			}
			copies[n_copies].idx    = n_copies;
			copies[n_copies].src    = cmd->copy.offset;
			copies[n_copies].dst    = write_pos;
			copies[n_copies].length = cmd->copy.length;
			n_copies++;
			write_pos += cmd->copy.length;
		} else {
			uint8_t *data = delta_malloc(cmd->add.length);
			memcpy(data, cmd->add.data, cmd->add.length);
			adds_push(&adds, write_pos, data, cmd->add.length);
			write_pos += cmd->add.length;
		}
	}

	size_t n = n_copies;
	if (n == 0) {
		for (i = 0; i < adds.len; i++) {
			delta_placed_command_t pc;
			pc.tag        = PCMD_ADD;
			pc.add.dst    = adds.dsts[i];
			pc.add.data   = adds.datas[i];
			pc.add.length = adds.lens[i];
			delta_placed_commands_push(&result, pc);
		}
		free(copies); free(adds.dsts); free(adds.datas); free(adds.lens);
		return result;
	}

	/* Step 2: build CRWI digraph */
	adj_list_t adj = build_crwi_digraph(copies, n);

	/* Step 3: Kahn topological sort with cycle breaking */
	size_t *topo_order = delta_malloc(n * sizeof(*topo_order));
	size_t  topo_len   = run_kahn(&adj, copies, n, r, policy, &adds, topo_order);

	/* Step 4: assemble result — copies in topo order, then all adds */
	for (i = 0; i < topo_len; i++) {
		size_t ci = topo_order[i];
		delta_placed_command_t pc;
		pc.tag         = PCMD_COPY;
		pc.copy.src    = copies[ci].src;
		pc.copy.dst    = copies[ci].dst;
		pc.copy.length = copies[ci].length;
		delta_placed_commands_push(&result, pc);
	}
	for (i = 0; i < adds.len; i++) {
		delta_placed_command_t pc;
		pc.tag        = PCMD_ADD;
		pc.add.dst    = adds.dsts[i];
		pc.add.data   = adds.datas[i];  /* ownership transferred */
		pc.add.length = adds.lens[i];
		delta_placed_commands_push(&result, pc);
	}

	adj_list_free(&adj);
	free(topo_order);
	free(copies); free(adds.dsts); free(adds.datas); free(adds.lens);
	return result;
}
