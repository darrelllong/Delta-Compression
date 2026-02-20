/*
 * inplace.c — In-place delta conversion (Burns, Long, Stockmeyer —
 *             IEEE TKDE 2003)
 *
 * A CRWI (Copy-Read/Write-Intersection) digraph is built over the copy
 * commands: edge i→j means copy i reads from a region that copy j will
 * overwrite, so i must execute before j.  When the digraph is acyclic, a
 * topological order provides a valid serial schedule with no conversion
 * needed.  A cycle i₁→i₂→…→iₖ→i₁ represents a circular dependency with
 * no valid schedule; breaking it materializes one copy as a literal add
 * (reading its bytes from R before the buffer is modified).
 * Tarjan SCC (O(n+E)) + per-SCC Kahn topological sort + cycle breaking.
 * R.E. Tarjan, SIAM J. Comput., 1(2):146-160, June 1972.
 */

#include "delta.h"

#include <stdlib.h>
#include <string.h>

/* ── Copy info for building the digraph ────────────────────────────── */

typedef struct {
	size_t idx, src, dst, length;
} copy_info_t;

/* ── Dynamic arrays used by find_cycle ─────────────────────────────── */

typedef struct { size_t v; size_t ni; } stk_entry_t;

/* ── (dst, idx) pair used to sort writes by destination ─────────────── */

typedef struct { size_t dst; size_t idx; } write_pair_t;

static int cmp_write_pair(const void *a, const void *b)
{
	size_t da = ((const write_pair_t *)a)->dst;
	size_t db = ((const write_pair_t *)b)->dst;
	return (da > db) - (da < db);
}

typedef struct {
	size_t *data;
	size_t  len;
	size_t  cap;
} size_buf_t;

typedef struct {
	stk_entry_t *data;
	size_t       len;
	size_t       cap;
} stk_buf_t;

static void size_buf_init(size_buf_t *b) { b->data = NULL; b->len = 0; b->cap = 0; }
static void size_buf_free(size_buf_t *b) { free(b->data); b->data = NULL; b->len = 0; b->cap = 0; }
static void size_buf_push(size_buf_t *b, size_t v) {
	if (b->len == b->cap) {
		b->cap = b->cap ? b->cap * 2 : 16;
		b->data = delta_realloc(b->data, b->cap * sizeof(*b->data));
	}
	b->data[b->len++] = v;
}

static void stk_buf_init(stk_buf_t *b) { b->data = NULL; b->len = 0; b->cap = 0; }
static void stk_buf_free(stk_buf_t *b) { free(b->data); b->data = NULL; b->len = 0; b->cap = 0; }
static void stk_buf_push(stk_buf_t *b, size_t v, size_t ni) {
	if (b->len == b->cap) {
		b->cap = b->cap ? b->cap * 2 : 16;
		b->data = delta_realloc(b->data, b->cap * sizeof(*b->data));
	}
	b->data[b->len].v  = v;
	b->data[b->len].ni = ni;
	b->len++;
}

/* ── SCC result: flat vertex array + per-SCC offsets (sentinel at end) ── */

typedef struct {
	size_t *data;    /* concatenated SCC vertices (sinks first) */
	size_t *offsets; /* offsets[i]..offsets[i+1) = SCC i vertices */
	size_t  n_sccs;
} scc_result_t;

static void scc_result_init(scc_result_t *s)
{
	s->data = NULL;
	s->offsets = NULL;
	s->n_sccs = 0;
}

static void scc_result_free(scc_result_t *s)
{
	free(s->data);
	free(s->offsets);
	s->data    = NULL;
	s->offsets = NULL;
	s->n_sccs  = 0;
}

/* ── Tarjan SCC (iterative) ─────────────────────────────────────────── */
/*
 * Iterative Tarjan's algorithm.  Returns SCCs in reverse topological
 * order (sinks first); caller iterates in reverse for source-first order.
 * R.E. Tarjan, SIAM J. Comput., 1(2):146-160, June 1972.
 */
static scc_result_t
tarjan_scc(size_t **adj, size_t *adj_len, size_t n)
{
	scc_result_t res;
	scc_result_init(&res);
	size_t *idx_arr;    /* index_arr[v] = discovery index, SIZE_MAX = unvisited */
	size_t *lowlink;
	bool   *on_stk;
	size_buf_t tarjan_stack;
	stk_buf_t  call_stack;
	size_t *scc_data    = NULL;
	size_t  scc_data_len = 0, scc_data_cap = 0;
	size_t *scc_offsets  = NULL;
	size_t  n_sccs = 0, sccs_cap = 0;
	size_t  counter = 0;
	size_t  start;

	idx_arr = delta_malloc(n * sizeof(*idx_arr));
	memset(idx_arr, 0xFF, n * sizeof(*idx_arr)); /* SIZE_MAX */
	lowlink = delta_malloc(n * sizeof(*lowlink));
	on_stk  = delta_calloc(n, sizeof(*on_stk));
	size_buf_init(&tarjan_stack);
	stk_buf_init(&call_stack);

	for (start = 0; start < n; start++) {
		if (idx_arr[start] != SIZE_MAX) { continue; }

		idx_arr[start] = lowlink[start] = counter++;
		on_stk[start] = true;
		size_buf_push(&tarjan_stack, start);
		stk_buf_push(&call_stack, start, 0);

		while (call_stack.len > 0) {
			size_t v  = call_stack.data[call_stack.len - 1].v;
			size_t ni = call_stack.data[call_stack.len - 1].ni;

			if (ni < adj_len[v]) {
				size_t w = adj[v][ni];
				call_stack.data[call_stack.len - 1].ni++;

				if (idx_arr[w] == SIZE_MAX) {
					/* Tree edge: descend into w */
					idx_arr[w] = lowlink[w] = counter++;
					on_stk[w] = true;
					size_buf_push(&tarjan_stack, w);
					stk_buf_push(&call_stack, w, 0);
				} else if (on_stk[w]) {
					/* Back-edge into current SCC */
					if (idx_arr[w] < lowlink[v]) {
						lowlink[v] = idx_arr[w];
					}
				}
			} else {
				/* Done with v — backtrack */
				call_stack.len--;
				if (call_stack.len > 0) {
					size_t parent = call_stack.data[call_stack.len - 1].v;
					if (lowlink[v] < lowlink[parent]) {
						lowlink[parent] = lowlink[v];
					}
				}
				/* Root of an SCC? */
				if (lowlink[v] == idx_arr[v]) {
					size_t w;
					/* Record SCC offset */
					if (n_sccs + 1 >= sccs_cap) {
						sccs_cap = sccs_cap ? sccs_cap * 2 : 16;
						scc_offsets = delta_realloc(scc_offsets,
						    (sccs_cap + 1) * sizeof(*scc_offsets));
					}
					scc_offsets[n_sccs] = scc_data_len;
					/* Pop SCC members from Tarjan stack */
					do {
						w = tarjan_stack.data[--tarjan_stack.len];
						on_stk[w] = false;
						if (scc_data_len == scc_data_cap) {
							scc_data_cap = scc_data_cap ? scc_data_cap * 2 : 16;
							scc_data = delta_realloc(scc_data,
							    scc_data_cap * sizeof(*scc_data));
						}
						scc_data[scc_data_len++] = w;
					} while (w != v);
					n_sccs++;
				}
			}
		}
	}
	/* Sentinel offset */
	if (n_sccs + 1 > sccs_cap) {
		scc_offsets = delta_realloc(scc_offsets,
		    (n_sccs + 1) * sizeof(*scc_offsets));
	}
	scc_offsets[n_sccs] = scc_data_len;

	free(idx_arr); free(lowlink); free(on_stk);
	size_buf_free(&tarjan_stack);
	stk_buf_free(&call_stack);

	res.data    = scc_data;
	res.offsets = scc_offsets;
	res.n_sccs  = n_sccs;
	return res;
}

/* ── Find cycle in SCC (amortized) ─────────────────────────────────── */
/*
 * Find a cycle in the active subgraph of one SCC.
 *
 * Three amortizations give O(|SCC| + E_SCC) total work per SCC:
 *   1. scc_id filter: O(1) per neighbor check, no O(|SCC|) set/clear.
 *   2. color persistence: color=2 (fully explored) persists across calls;
 *      vertex removal can only reduce edges, so color=2 is monotone-correct.
 *   3. *scan_start: outer loop resumes where it left off, O(|SCC|) total.
 *
 * Returns 1 if a cycle was found (*cycle_out and *cycle_len populated, caller
 * must free *cycle_out).  Returns 0 if no cycle (SCC is acyclic).
 * color[] entries for path vertices are reset to 0 on cycle found.
 * color=2 entries persist in both cases (monotone-correct).
 */
static int
find_cycle_in_scc(size_t **adj, size_t *adj_len,
                  size_t *scc_verts, size_t scc_sz,
                  size_t sid, size_t *scc_id,
                  bool *done, uint8_t *color,
                  size_t *scan_start,
                  size_t **cycle_out, size_t *cycle_len_out)
{
	size_buf_t path;
	stk_buf_t  stk;
	size_t     scan = *scan_start;

	size_buf_init(&path);
	stk_buf_init(&stk);

	while (scan < scc_sz) {
		size_t start = scc_verts[scan];
		if (done[start] || color[start] != 0) { scan++; continue; }

		color[start] = 1;
		size_buf_push(&path, start);
		stk_buf_push(&stk, start, 0);

		while (stk.len > 0) {
			size_t v  = stk.data[stk.len - 1].v;
			size_t ni = stk.data[stk.len - 1].ni;
			bool advanced = false;
			size_t k;

			while (ni < adj_len[v]) {
				size_t w = adj[v][ni++];
				if (scc_id[w] != sid || done[w]) { continue; }
				if (color[w] == 1) {
					/* Back-edge: cycle found */
					size_t pos = 0;
					while (path.data[pos] != w) { pos++; }
					*cycle_len_out = path.len - pos;
					*cycle_out = delta_malloc(
					    *cycle_len_out * sizeof(**cycle_out));
					memcpy(*cycle_out, path.data + pos,
					    *cycle_len_out * sizeof(**cycle_out));
					for (k = 0; k < path.len; k++) {
						color[path.data[k]] = 0;
					}
					*scan_start = scan;
					size_buf_free(&path);
					stk_buf_free(&stk);
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
				color[v] = 2; /* Fully explored — persists across calls */
				path.len--;
			}
		}
		/* start's reachable SCC-subgraph explored; no cycle */
		scan++;
	}

	*scan_start = scan;
	size_buf_free(&path);
	stk_buf_free(&stk);
	return 0;
}

/* ── Make in-place ─────────────────────────────────────────────────── */

delta_placed_commands_t
delta_make_inplace(const uint8_t *r, size_t r_len,
                   const delta_commands_t *cmds,
                   delta_cycle_policy_t policy)
{
	delta_placed_commands_t result;
	copy_info_t *copies = NULL;
	size_t n_copies = 0, n_copies_cap = 0;
	size_t *add_dsts = NULL;
	uint8_t **add_datas = NULL;
	size_t *add_lens = NULL;
	size_t n_adds = 0, n_adds_cap = 0;
	size_t write_pos = 0;
	size_t i, j, n;
	size_t **adj;
	size_t *adj_len, *adj_cap;
	size_t *topo_order;
	size_t topo_len = 0;

	delta_placed_commands_init(&result);

	if (cmds->len == 0) { return result; }

	(void)r_len;

	/* Step 1: separate copies and adds, compute write offsets */
	for (i = 0; i < cmds->len; i++) {
		const delta_command_t *cmd = &cmds->data[i];
		if (cmd->tag == CMD_COPY) {
			if (n_copies == n_copies_cap) {
				n_copies_cap = n_copies_cap ? n_copies_cap * 2 : 16;
				copies = delta_realloc(copies,
				                 n_copies_cap * sizeof(*copies));
			}
			copies[n_copies].idx = n_copies;
			copies[n_copies].src = cmd->copy.offset;
			copies[n_copies].dst = write_pos;
			copies[n_copies].length = cmd->copy.length;
			n_copies++;
			write_pos += cmd->copy.length;
		} else {
			if (n_adds == n_adds_cap) {
				n_adds_cap = n_adds_cap ? n_adds_cap * 2 : 16;
				add_dsts = delta_realloc(add_dsts,
				                   n_adds_cap * sizeof(*add_dsts));
				add_datas = delta_realloc(add_datas,
				                    n_adds_cap * sizeof(*add_datas));
				add_lens = delta_realloc(add_lens,
				                   n_adds_cap * sizeof(*add_lens));
			}
			add_dsts[n_adds] = write_pos;
			add_datas[n_adds] = delta_malloc(cmd->add.length);
			memcpy(add_datas[n_adds], cmd->add.data, cmd->add.length);
			add_lens[n_adds] = cmd->add.length;
			n_adds++;
			write_pos += cmd->add.length;
		}
	}

	n = n_copies;
	if (n == 0) {
		for (i = 0; i < n_adds; i++) {
			delta_placed_command_t pc;
			pc.tag = PCMD_ADD;
			pc.add.dst = add_dsts[i];
			pc.add.data = add_datas[i];
			pc.add.length = add_lens[i];
			delta_placed_commands_push(&result, pc);
		}
		free(copies); free(add_dsts); free(add_datas); free(add_lens);
		return result;
	}

	/* Step 2: build CRWI digraph */
	adj = delta_calloc(n, sizeof(*adj));
	adj_len = delta_calloc(n, sizeof(*adj_len));
	adj_cap = delta_calloc(n, sizeof(*adj_cap));

	/* O(n log n + E) sweep-line: sort writes by dst, then for each read
	 * interval use two binary searches to find all overlapping writes.
	 * dst intervals are non-overlapping (each output byte written once),
	 * so the overlapping writes form a contiguous range in sorted order:
	 *   [lo, hi)  — writes whose dst start falls within [si, read_end)
	 * plus at most one write at lo-1 that starts before si but extends
	 * past it. */
	{
		write_pair_t *pairs   = delta_malloc(n * sizeof(*pairs));
		size_t       *write_sorted = delta_malloc(n * sizeof(*write_sorted));
		size_t       *write_starts = delta_malloc(n * sizeof(*write_starts));

		for (i = 0; i < n; i++) {
			pairs[i].dst = copies[i].dst;
			pairs[i].idx = i;
		}
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
			size_t lo_lo = 0, lo_hi = n;
			while (lo_lo < lo_hi) {
				size_t mid = lo_lo + (lo_hi - lo_lo) / 2;
				if (write_starts[mid] < si) lo_lo = mid + 1;
				else lo_hi = mid;
			}
			size_t lo = lo_lo;

			/* hi = first k with write_starts[k] >= read_end */
			size_t hi_lo = lo, hi_hi = n;
			while (hi_lo < hi_hi) {
				size_t mid = hi_lo + (hi_hi - hi_lo) / 2;
				if (write_starts[mid] < read_end) hi_lo = mid + 1;
				else hi_hi = mid;
			}
			size_t hi = hi_lo;

#define ADD_EDGE(jj) \
			do { \
				if (adj_len[i] == adj_cap[i]) { \
					adj_cap[i] = adj_cap[i] ? adj_cap[i] * 2 : 4; \
					adj[i] = delta_realloc(adj[i], \
					    adj_cap[i] * sizeof(*adj[i])); \
				} \
				adj[i][adj_len[i]++] = (jj); \
			} while (0)

			/* Write at lo-1 starts before si; overlaps iff end > si */
			if (lo > 0) {
				size_t jj = write_sorted[lo - 1];
				if (jj != i &&
				    copies[jj].dst + copies[jj].length > si) {
					ADD_EDGE(jj);
				}
			}
			/* All writes in [lo, hi) start within [si, read_end) */
			for (j = lo; j < hi; j++) {
				size_t jj = write_sorted[j];
				if (jj != i) { ADD_EDGE(jj); }
			}
#undef ADD_EDGE
		}
		free(write_sorted);
		free(write_starts);
	}

	/* Step 3: Kahn topological sort with Tarjan-scoped cycle breaking.
	 * Global Kahn preserves the cascade effect (victim conversion decrements
	 * in_deg globally, potentially freeing vertices across SCC boundaries).
	 * find_cycle_in_scc restricts DFS to one SCC via three amortizations:
	 * scc_id filter, color=2 persistence, scan_start resumption.
	 * Total cycle-breaking work: O(n+E).
	 * R.E. Tarjan, SIAM J. Comput., 1(2):146-160, June 1972. */
	{
		scc_result_t sccs = tarjan_scc(adj, adj_len, n);

		/* Build global in_deg */
		size_t *in_deg = delta_calloc(n, sizeof(*in_deg));
		{
			size_t ii;
			for (ii = 0; ii < n; ii++) {
				for (j = 0; j < adj_len[ii]; j++) {
					in_deg[adj[ii][j]]++;
				}
			}
		}

		/* Build scc_id and scc_list (non-trivial SCCs in source-first order).
		 * scc_list_verts / scc_list_offs mirror the scc_result layout but
		 * only include non-trivial SCCs, ordered sources-first. */
		size_t *scc_id = delta_malloc(n * sizeof(*scc_id));
		memset(scc_id, 0xFF, n * sizeof(*scc_id)); /* SIZE_MAX = trivial */

		size_t scc_list_len = 0;
		{
			size_t si;
			for (si = 0; si < sccs.n_sccs; si++) {
				if (sccs.offsets[si + 1] - sccs.offsets[si] > 1) {
					scc_list_len++;
				}
			}
		}

		/* scc_list_verts / scc_list_offs: non-trivial SCCs, sources first */
		size_t *scc_list_verts = delta_malloc(
		    (n + 1)          * sizeof(*scc_list_verts));
		size_t *scc_list_offs  = delta_malloc(
		    (scc_list_len + 1) * sizeof(*scc_list_offs));
		size_t *scc_active     = delta_calloc(
		    (scc_list_len > 0 ? scc_list_len : 1), sizeof(*scc_active));
		{
			size_t si, k = 0, vpos = 0;
			/* source-first = reverse of Tarjan sinks-first emission */
			for (si = sccs.n_sccs; si-- > 0; ) {
				size_t scc_sz = sccs.offsets[si + 1] - sccs.offsets[si];
				if (scc_sz <= 1) { continue; }
				size_t *sv = sccs.data + sccs.offsets[si];
				size_t vi;
				scc_list_offs[k] = vpos;
				for (vi = 0; vi < scc_sz; vi++) {
					scc_id[sv[vi]] = k;
					scc_list_verts[vpos++] = sv[vi];
				}
				scc_active[k] = scc_sz;
				k++;
			}
			scc_list_offs[k] = vpos;
		}

		bool    *done     = delta_calloc(n, sizeof(*done));
		uint8_t *color    = delta_calloc(n, sizeof(*color));
		size_t   scc_ptr  = 0;
		size_t   scan_pos = 0;

		topo_order = delta_malloc(n * sizeof(*topo_order));

		/* Min-heap entries: (copy_length, vertex_index) */
		typedef struct { size_t len; size_t idx; } heap_entry_t;
		size_t heap_len = 0, heap_cap = n + 1;
		heap_entry_t *heap = delta_malloc(heap_cap * sizeof(*heap));

		#define HEAP_PARENT(k) (((k) - 1) / 2)
		#define HEAP_LEFT(k)   (2 * (k) + 1)
		#define HEAP_RIGHT(k)  (2 * (k) + 2)
		#define HEAP_LT(a, b)  ((a).len < (b).len || \
		                         ((a).len == (b).len && (a).idx < (b).idx))

		#define HEAP_PUSH(e) do {                              \
			if (heap_len == heap_cap) {                    \
				heap_cap *= 2;                         \
				heap = delta_realloc(heap,             \
				    heap_cap * sizeof(*heap));         \
			}                                              \
			heap[heap_len] = (e);                          \
			size_t _k = heap_len++;                        \
			while (_k > 0 && HEAP_LT(heap[_k],            \
			                  heap[HEAP_PARENT(_k)])) {    \
				heap_entry_t _tmp = heap[_k];          \
				heap[_k] = heap[HEAP_PARENT(_k)];      \
				heap[HEAP_PARENT(_k)] = _tmp;          \
				_k = HEAP_PARENT(_k);                  \
			}                                              \
		} while (0)

		#define HEAP_POP(out) do {                             \
			(out) = heap[0];                               \
			heap[0] = heap[--heap_len];                    \
			size_t _k = 0;                                 \
			for (;;) {                                     \
				size_t _s = _k;                        \
				size_t _l = HEAP_LEFT(_k);             \
				size_t _r = HEAP_RIGHT(_k);            \
				if (_l < heap_len &&                   \
				    HEAP_LT(heap[_l], heap[_s]))       \
					_s = _l;                       \
				if (_r < heap_len &&                   \
				    HEAP_LT(heap[_r], heap[_s]))       \
					_s = _r;                       \
				if (_s == _k) break;                   \
				heap_entry_t _tmp = heap[_k];          \
				heap[_k] = heap[_s];                   \
				heap[_s] = _tmp;                       \
				_k = _s;                               \
			}                                              \
		} while (0)

		/* Seed heap with zero in-degree vertices */
		{
			size_t ii;
			for (ii = 0; ii < n; ii++) {
				if (in_deg[ii] == 0) {
					heap_entry_t e = { copies[ii].length, ii };
					HEAP_PUSH(e);
				}
			}
		}

		size_t processed = 0;

		while (processed < n) {
			/* Drain all ready vertices */
			while (heap_len > 0) {
				heap_entry_t top;
				HEAP_POP(top);
				size_t v = top.idx;
				if (done[v]) { continue; }
				done[v] = true;
				topo_order[topo_len++] = v;
				processed++;
				if (scc_id[v] != SIZE_MAX) { scc_active[scc_id[v]]--; }
				for (j = 0; j < adj_len[v]; j++) {
					size_t w = adj[v][j];
					if (!done[w]) {
						in_deg[w]--;
						if (in_deg[w] == 0) {
							heap_entry_t e = {
							    copies[w].length, w
							};
							HEAP_PUSH(e);
						}
					}
				}
			}

			if (processed >= n) { break; }

			/* Kahn stalled: choose victim */
			size_t victim = n; /* invalid sentinel */

			if (policy == POLICY_CONSTANT) {
				size_t ii;
				for (ii = 0; ii < n && victim == n; ii++) {
					if (!done[ii]) { victim = ii; }
				}
			} else { /* POLICY_LOCALMIN */
				while (victim == n) {
					while (scc_ptr < scc_list_len &&
					       scc_active[scc_ptr] == 0) {
						scc_ptr++;
						scan_pos = 0;
					}
					if (scc_ptr >= scc_list_len) {
						/* Safety fallback */
						size_t ii;
						for (ii = 0; ii < n && victim == n; ii++) {
							if (!done[ii]) { victim = ii; }
						}
						break;
					}
					size_t *sv     = scc_list_verts +
					                  scc_list_offs[scc_ptr];
					size_t  sv_len = scc_list_offs[scc_ptr + 1] -
					                  scc_list_offs[scc_ptr];
					size_t *cyc     = NULL;
					size_t  cyc_len = 0;
					int found = find_cycle_in_scc(
					    adj, adj_len, sv, sv_len,
					    scc_ptr, scc_id, done, color,
					    &scan_pos, &cyc, &cyc_len);
					if (found) {
						size_t ci;
						victim = cyc[0];
						for (ci = 1; ci < cyc_len; ci++) {
							size_t v = cyc[ci];
							if (copies[v].length < copies[victim].length ||
							    (copies[v].length == copies[victim].length
							     && v < victim)) {
								victim = v;
							}
						}
						free(cyc);
					} else {
						/* SCC's remaining subgraph is acyclic; advance */
						scc_ptr++;
						scan_pos = 0;
					}
				}
			}

			/* Convert victim: copy -> add (materialize) */
			if (n_adds == n_adds_cap) {
				n_adds_cap = n_adds_cap ? n_adds_cap * 2 : 16;
				add_dsts  = delta_realloc(add_dsts,
				                 n_adds_cap * sizeof(*add_dsts));
				add_datas = delta_realloc(add_datas,
				                 n_adds_cap * sizeof(*add_datas));
				add_lens  = delta_realloc(add_lens,
				                 n_adds_cap * sizeof(*add_lens));
			}
			add_dsts[n_adds]  = copies[victim].dst;
			add_datas[n_adds] = delta_malloc(copies[victim].length);
			memcpy(add_datas[n_adds], &r[copies[victim].src],
			       copies[victim].length);
			add_lens[n_adds] = copies[victim].length;
			n_adds++;

			done[victim] = true;
			processed++;
			if (scc_id[victim] != SIZE_MAX) { scc_active[scc_id[victim]]--; }
			for (j = 0; j < adj_len[victim]; j++) {
				size_t w = adj[victim][j];
				if (!done[w]) {
					in_deg[w]--;
					if (in_deg[w] == 0) {
						heap_entry_t e = { copies[w].length, w };
						HEAP_PUSH(e);
					}
				}
			}
		}

		#undef HEAP_PARENT
		#undef HEAP_LEFT
		#undef HEAP_RIGHT
		#undef HEAP_LT
		#undef HEAP_PUSH
		#undef HEAP_POP

		scc_result_free(&sccs);
		free(in_deg); free(scc_id);
		free(scc_list_verts); free(scc_list_offs); free(scc_active);
		free(done); free(color); free(heap);
	}

	/* Step 4: assemble result — copies in topo order, then adds */
	for (i = 0; i < topo_len; i++) {
		delta_placed_command_t pc;
		pc.tag = PCMD_COPY;
		pc.copy.src = copies[topo_order[i]].src;
		pc.copy.dst = copies[topo_order[i]].dst;
		pc.copy.length = copies[topo_order[i]].length;
		delta_placed_commands_push(&result, pc);
	}
	for (i = 0; i < n_adds; i++) {
		delta_placed_command_t pc;
		pc.tag = PCMD_ADD;
		pc.add.dst = add_dsts[i];
		pc.add.data = add_datas[i]; /* ownership transferred */
		pc.add.length = add_lens[i];
		delta_placed_commands_push(&result, pc);
	}

	/* Cleanup */
	for (i = 0; i < n; i++) { free(adj[i]); }
	free(adj); free(adj_len); free(adj_cap);
	free(topo_order);
	free(copies); free(add_dsts); free(add_datas); free(add_lens);

	return result;
}
