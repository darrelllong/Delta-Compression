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
 * Kahn's topological sort + iterative-DFS cycle detection + cycle breaking.
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
static void size_buf_free(size_buf_t *b) { free(b->data); }
static void size_buf_push(size_buf_t *b, size_t v) {
	if (b->len == b->cap) {
		b->cap = b->cap ? b->cap * 2 : 16;
		b->data = delta_realloc(b->data, b->cap * sizeof(*b->data));
	}
	b->data[b->len++] = v;
}

static void stk_buf_init(stk_buf_t *b) { b->data = NULL; b->len = 0; b->cap = 0; }
static void stk_buf_free(stk_buf_t *b) { free(b->data); }
static void stk_buf_push(stk_buf_t *b, size_t v, size_t ni) {
	if (b->len == b->cap) {
		b->cap = b->cap ? b->cap * 2 : 16;
		b->data = delta_realloc(b->data, b->cap * sizeof(*b->data));
	}
	b->data[b->len].v  = v;
	b->data[b->len].ni = ni;
	b->len++;
}

/* ── Find a cycle in the subgraph of non-removed vertices ──────────── */
/*
 * Iterative DFS with three-color marking (0=unvisited, 1=on-path, 2=done).
 * A back-edge to an on-path vertex signals a cycle.
 * Returns a heap-allocated array of cycle vertex indices (caller frees),
 * or NULL if the subgraph is acyclic.  *cycle_len is set on success.
 */
static size_t *
find_cycle(size_t **adj, size_t *adj_len, const bool *removed,
           size_t n, size_t *cycle_len)
{
	uint8_t    *color  = NULL;
	size_buf_t  path;
	stk_buf_t   stk;
	size_t     *result = NULL;
	size_t      start;

	*cycle_len = 0;
	color = delta_calloc(n, sizeof(*color));
	size_buf_init(&path);
	stk_buf_init(&stk);

	for (start = 0; start < n; start++) {
		if (removed[start] || color[start] != 0) { continue; }

		color[start] = 1;
		size_buf_push(&path, start);
		stk_buf_push(&stk, start, 0);

		while (stk.len > 0) {
			size_t v  = stk.data[stk.len - 1].v;
			size_t ni = stk.data[stk.len - 1].ni;
			bool   advanced = false;
			size_t j;

			for (j = ni; j < adj_len[v]; j++) {
				size_t w = adj[v][j];
				if (removed[w]) { continue; }
				if (color[w] == 1) {
					/* Back-edge: cycle found. */
					size_t ci;
					for (ci = 0; ci < path.len; ci++) {
						if (path.data[ci] == w) { break; }
					}
					*cycle_len = path.len - ci;
					result = delta_malloc(*cycle_len * sizeof(*result));
					memcpy(result, path.data + ci,
					       *cycle_len * sizeof(*result));
					goto done;
				}
				if (color[w] == 0) {
					stk.data[stk.len - 1].ni = j + 1;
					color[w] = 1;
					size_buf_push(&path, w);
					stk_buf_push(&stk, w, 0);
					advanced = true;
					break;
				}
				/* color[w] == 2: already done, skip */
			}

			if (!advanced) {
				color[v] = 2;
				path.len--;
				stk.len--;
			}
		}
	}

done:
	size_buf_free(&path);
	stk_buf_free(&stk);
	free(color);
	return result;
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
	size_t *in_deg;
	bool *removed;
	size_t *topo_order;
	size_t topo_len = 0;
	size_t processed = 0;

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
	in_deg = delta_calloc(n, sizeof(*in_deg));

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
				in_deg[(jj)]++; \
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

	/* Step 3: topological sort with cycle breaking (Kahn's algorithm)
	 * Priority queue (min-heap) keyed on copy length — always process
	 * the smallest ready copy first for deterministic ordering. */
	removed = delta_calloc(n, sizeof(*removed));
	topo_order = delta_malloc(n * sizeof(*topo_order));

	/* Min-heap entries: (copy_length, vertex_index) */
	typedef struct { size_t len; size_t idx; } heap_entry_t;
	size_t heap_len = 0, heap_cap = n + 1;
	heap_entry_t *heap = delta_malloc(heap_cap * sizeof(*heap));

	/* Heap helpers (0-based indexing) */
	#define HEAP_PARENT(i) (((i) - 1) / 2)
	#define HEAP_LEFT(i)   (2 * (i) + 1)
	#define HEAP_RIGHT(i)  (2 * (i) + 2)
	#define HEAP_LT(a, b)  ((a).len < (b).len || \
	                         ((a).len == (b).len && (a).idx < (b).idx))

	#define HEAP_PUSH(e) do {                              \
		if (heap_len == heap_cap) {                    \
			heap_cap *= 2;                         \
			heap = delta_realloc(heap,                   \
			    heap_cap * sizeof(*heap));          \
		}                                              \
		heap[heap_len] = (e);                          \
		size_t _k = heap_len++;                        \
		while (_k > 0 && HEAP_LT(heap[_k],            \
		                          heap[HEAP_PARENT(_k)])) { \
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

	for (i = 0; i < n; i++) {
		if (in_deg[i] == 0) {
			heap_entry_t e = { copies[i].length, i };
			HEAP_PUSH(e);
		}
	}

	while (processed < n) {
		while (heap_len > 0) {
			heap_entry_t top;
			HEAP_POP(top);
			size_t v = top.idx;
			if (removed[v]) { continue; }
			removed[v] = true;
			topo_order[topo_len++] = v;
			processed++;
			for (j = 0; j < adj_len[v]; j++) {
				size_t w = adj[v][j];
				if (!removed[w]) {
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

		/* Cycle detected — pick a victim */
		{
			size_t victim = 0;
			if (policy == POLICY_CONSTANT) {
				for (i = 0; i < n; i++) {
					if (!removed[i]) { victim = i; break; }
				}
			} else { /* POLICY_LOCALMIN */
				size_t *cycle;
				size_t cycle_len = 0;
				cycle = find_cycle(adj, adj_len, removed, n,
				                   &cycle_len);
				if (cycle && cycle_len > 0) {
					/* (length, index) for deterministic
					 * tie-breaking — same composite key
					 * as the Kahn's PQ above. */
					victim = cycle[0];
					for (j = 1; j < cycle_len; j++) {
						size_t cj = cycle[j];
						if (copies[cj].length <
						    copies[victim].length ||
						    (copies[cj].length ==
						     copies[victim].length &&
						     cj < victim)) {
							victim = cj;
						}
					}
					free(cycle);
				} else {
					for (i = 0; i < n; i++) {
						if (!removed[i]) {
							victim = i;
							break;
						}
					}
				}
			}

			/* Convert victim: copy -> add (materialize) */
			if (n_adds == n_adds_cap) {
				n_adds_cap = n_adds_cap ? n_adds_cap * 2 : 16;
				add_dsts = delta_realloc(add_dsts,
				                   n_adds_cap * sizeof(*add_dsts));
				add_datas = delta_realloc(add_datas,
				                    n_adds_cap * sizeof(*add_datas));
				add_lens = delta_realloc(add_lens,
				                   n_adds_cap * sizeof(*add_lens));
			}
			add_dsts[n_adds] = copies[victim].dst;
			add_datas[n_adds] = delta_malloc(copies[victim].length);
			memcpy(add_datas[n_adds],
			       &r[copies[victim].src], copies[victim].length);
			add_lens[n_adds] = copies[victim].length;
			n_adds++;

			removed[victim] = true;
			processed++;
			for (j = 0; j < adj_len[victim]; j++) {
				size_t w = adj[victim][j];
				if (!removed[w]) {
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
	}

	#undef HEAP_PARENT
	#undef HEAP_LEFT
	#undef HEAP_RIGHT
	#undef HEAP_LT
	#undef HEAP_PUSH
	#undef HEAP_POP

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
	free(in_deg); free(removed);
	free(topo_order); free(heap);
	free(copies); free(add_dsts); free(add_datas); free(add_lens);

	return result;
}
