// In-place conversion (Burns, Long and Stockmeyer, IEEE TKDE 15(4), 2003).
//
// Applied in place, a copy that reads bytes an earlier command has written
// reads the wrong bytes.  Copy i conflicts with copy j when i reads from the
// interval j writes; then i must run before j.  These conflicts make a
// digraph on the copies (the CRWI digraph), and any topological order of it
// is safe.  A cycle has no such order, so one copy on it is converted to an
// add of the bytes it would have read, which conflicts with nothing.  Adds
// run last.
//
// The order produced is the topological order that always takes next the
// ready copy with the least (length, index); it is the same for every
// implementation.  Cycles are sought only within strongly connected
// components (Tarjan, SIAM J. Comput. 1(2), 1972), and only when no copy is
// ready.

#include "internal.h"

#define NONE SIZE_MAX

typedef struct {
	size_t src;
	size_t dst;
	size_t length;
} copy_t;

// The digraph in compressed form: the successors of i are
// to[first[i]] .. to[first[i+1] - 1].
typedef struct {
	size_t  n;
	size_t *first; // n + 1 entries
	size_t *to;
} graph_t;

static void
graph_free(graph_t *g)
{
	free(g->first);
	free(g->to);
}

// lower_bound returns the least k in [lo, hi) with w[k].dst >= key, or hi.
static size_t
lower_bound(const dst_index_t *w, size_t lo, size_t hi, size_t key)
{
	while (lo < hi) {
		size_t mid = lo + (hi - lo) / 2;
		if (w[mid].dst < key) {
			lo = mid + 1;
		} else {
			hi = mid;
		}
	}
	return lo;
}

// build_graph finds the conflicts in O(n log n + edges).  Writes do not
// overlap, so with the copies sorted by destination, those whose writes
// meet the read interval [src, src+length) are consecutive: the ones that
// start inside it, and possibly the one just before, if it runs into it.
static graph_t
build_graph(const copy_t *copies, size_t n)
{
	dst_index_t *w = delta_malloc(n * sizeof(*w));
	for (size_t i = 0; i < n; i++) {
		w[i].dst = copies[i].dst;
		w[i].idx = i;
	}
	qsort(w, n, sizeof(*w), delta_cmp_dst_index);

	graph_t g = { .n = n };
	g.first = delta_malloc((n + 1) * sizeof(*g.first));
	size_t len = 0, cap = 0;

	for (size_t i = 0; i < n; i++) {
		size_t src = copies[i].src;
		size_t lo = lower_bound(w, 0, n, src);
		size_t hi = lower_bound(w, lo, n, src + copies[i].length);
		size_t before = lo;
		if (lo > 0) {
			const copy_t *prev = &copies[w[lo - 1].idx];
			if (prev->dst + prev->length > src) {
				before = lo - 1;
			}
		}

		if (len + (hi - before) > cap) {
			cap = 2 * cap + (hi - before);
			g.to = delta_realloc(g.to, cap * sizeof(*g.to));
		}
		g.first[i] = len;
		for (size_t k = before; k < hi; k++) {
			if (w[k].idx != i) {
				g.to[len++] = w[k].idx;
			}
		}
	}
	g.first[n] = len;
	free(w);
	return g;
}

// A frame_t is a vertex on a depth-first search path and how many of its
// edges have been followed.
typedef struct {
	size_t v;
	size_t edge;
} frame_t;

// The components that can hold a cycle, those of more than one vertex, in
// the order Tarjan's algorithm completes them: reverse topological.  Cycles
// are broken in this order, which fixes the order of the adds they produce;
// every implementation uses it, so that their deltas are identical.
typedef struct {
	size_t  len;
	size_t *verts;  // the components' vertices, component by component
	size_t *first;  // component c is verts[first[c]] .. verts[first[c+1] - 1]
	size_t *active; // active[c]: vertices of c not yet ordered or converted
	size_t *id;     // id[v]: the component of v, or NONE if v is alone
} sccs_t;

static void
sccs_free(sccs_t *s)
{
	free(s->verts);
	free(s->first);
	free(s->active);
	free(s->id);
}

// find_sccs is Tarjan's algorithm with an explicit stack.
static sccs_t
find_sccs(const graph_t *g)
{
	size_t n = g->n;
	size_t *index = delta_malloc(n * sizeof(*index));
	size_t *lowlink = delta_malloc(n * sizeof(*lowlink));
	bool *on_stack = delta_calloc(n, sizeof(*on_stack));
	size_t *stack = delta_malloc(n * sizeof(*stack));
	frame_t *path = delta_malloc(n * sizeof(*path));
	// Every component, in the order completed.
	size_t *all = delta_malloc(n * sizeof(*all));
	size_t *all_first = delta_malloc((n + 1) * sizeof(*all_first));
	size_t counter = 0, stack_len = 0, all_len = 0, ncomp = 0;

	for (size_t i = 0; i < n; i++) {
		index[i] = NONE;
	}
	for (size_t root = 0; root < n; root++) {
		if (index[root] != NONE) {
			continue;
		}
		size_t depth = 0;
		path[depth++] = (frame_t){ root, 0 };
		index[root] = lowlink[root] = counter++;
		stack[stack_len++] = root;
		on_stack[root] = true;

		while (depth > 0) {
			frame_t *f = &path[depth - 1];
			size_t v = f->v;

			if (g->first[v] + f->edge < g->first[v + 1]) {
				size_t w = g->to[g->first[v] + f->edge++];
				if (index[w] == NONE) {
					path[depth++] = (frame_t){ w, 0 };
					index[w] = lowlink[w] = counter++;
					stack[stack_len++] = w;
					on_stack[w] = true;
				} else if (on_stack[w] && index[w] < lowlink[v]) {
					lowlink[v] = index[w];
				}
				continue;
			}

			depth--;
			if (depth > 0) {
				size_t parent = path[depth - 1].v;
				if (lowlink[v] < lowlink[parent]) {
					lowlink[parent] = lowlink[v];
				}
			}
			if (lowlink[v] == index[v]) {
				size_t w;
				all_first[ncomp++] = all_len;
				do {
					w = stack[--stack_len];
					on_stack[w] = false;
					all[all_len++] = w;
				} while (w != v);
			}
		}
	}
	all_first[ncomp] = all_len;
	free(index);
	free(lowlink);
	free(on_stack);
	free(stack);
	free(path);

	sccs_t s = { .len = 0 };
	s.verts = delta_malloc(n * sizeof(*s.verts));
	s.first = delta_malloc((n + 1) * sizeof(*s.first));
	s.active = delta_malloc(n * sizeof(*s.active));
	s.id = delta_malloc(n * sizeof(*s.id));
	for (size_t i = 0; i < n; i++) {
		s.id[i] = NONE;
	}
	size_t nverts = 0;
	for (size_t c = 0; c < ncomp; c++) {
		size_t size = all_first[c + 1] - all_first[c];
		if (size < 2) {
			continue;
		}
		s.first[s.len] = nverts;
		s.active[s.len] = size;
		for (size_t k = all_first[c]; k < all_first[c + 1]; k++) {
			s.id[all[k]] = s.len;
			s.verts[nverts++] = all[k];
		}
		s.len++;
	}
	s.first[s.len] = nverts;
	free(all);
	free(all_first);
	return s;
}

typedef struct {
	size_t length;
	size_t idx;
} ready_t;

enum { UNSEEN, ON_PATH, CLEAR };

// The state of the ordering.
typedef struct {
	const graph_t *g;
	const copy_t  *copies;
	sccs_t         sccs;
	size_t        *in_degree; // counting only edges from vertices not done
	bool          *done;      // ordered, or converted to an add

	// Ready copies: a binary min-heap on (length, index).  Each copy is
	// ready once, so n entries suffice.
	ready_t *heap;
	size_t   heap_len;

	// The search for cycles.  Removing vertices cannot create a cycle, so
	// what one search rules out stays ruled out: a vertex marked CLEAR is
	// on no cycle for good, and the scan for a starting vertex in the
	// component under search, sccs.verts[..][scan], never backs up.  The
	// searches of one component therefore cost O(vertices + edges) plus
	// the lengths of the cycles found.
	uint8_t *mark;       // UNSEEN, ON_PATH or CLEAR
	frame_t *path;
	size_t   comp;       // the first component that may still hold a cycle
	size_t   scan;

	size_t   next_undone; // no vertex below this one is still to do
} order_t;

static bool
ready_less(ready_t a, ready_t b)
{
	return a.length < b.length || (a.length == b.length && a.idx < b.idx);
}

static void
ready_push(order_t *o, size_t v)
{
	ready_t e = { o->copies[v].length, v };
	size_t k = o->heap_len++;

	while (k > 0) {
		size_t parent = (k - 1) / 2;
		if (!ready_less(e, o->heap[parent])) {
			break;
		}
		o->heap[k] = o->heap[parent];
		k = parent;
	}
	o->heap[k] = e;
}

static size_t
ready_pop(order_t *o)
{
	size_t top = o->heap[0].idx;
	ready_t last = o->heap[--o->heap_len];
	size_t k = 0;

	for (;;) {
		size_t child = 2 * k + 1;
		if (child >= o->heap_len) {
			break;
		}
		if (child + 1 < o->heap_len &&
		    ready_less(o->heap[child + 1], o->heap[child])) {
			child++;
		}
		if (!ready_less(o->heap[child], last)) {
			break;
		}
		o->heap[k] = o->heap[child];
		k = child;
	}
	o->heap[k] = last;
	return top;
}

// retire takes v out of the graph, which may make its successors ready.
static void
retire(order_t *o, size_t v)
{
	const graph_t *g = o->g;

	o->done[v] = true;
	if (o->sccs.id[v] != NONE) {
		o->sccs.active[o->sccs.id[v]]--;
	}
	for (size_t k = g->first[v]; k < g->first[v + 1]; k++) {
		size_t w = g->to[k];
		if (!o->done[w] && --o->in_degree[w] == 0) {
			ready_push(o, w);
		}
	}
}

// find_cycle searches component o->comp, from o->scan on, for a cycle among
// the vertices not done.  It returns the cycle's length and leaves its
// vertices in o->path[*start ...], or returns 0 if there is none.
static size_t
find_cycle(order_t *o, size_t *start)
{
	const graph_t *g = o->g;
	const size_t *verts = &o->sccs.verts[o->sccs.first[o->comp]];
	size_t size = o->sccs.first[o->comp + 1] - o->sccs.first[o->comp];

	for (; o->scan < size; o->scan++) {
		size_t root = verts[o->scan];
		if (o->done[root] || o->mark[root] != UNSEEN) {
			continue;
		}
		size_t depth = 0;
		o->path[depth++] = (frame_t){ root, 0 };
		o->mark[root] = ON_PATH;

		while (depth > 0) {
			frame_t *f = &o->path[depth - 1];
			size_t v = f->v;
			bool descended = false;

			while (g->first[v] + f->edge < g->first[v + 1]) {
				size_t w = g->to[g->first[v] + f->edge++];
				if (o->sccs.id[w] != o->comp || o->done[w]) {
					continue;
				}
				if (o->mark[w] == ON_PATH) {
					// The path from w down to v, and the
					// edge back, are a cycle.  Nothing on
					// the path is ruled out; the cycle is
					// about to be broken, and the scan
					// resumes at the same root.
					size_t at = 0;
					while (o->path[at].v != w) {
						at++;
					}
					for (size_t k = 0; k < depth; k++) {
						o->mark[o->path[k].v] = UNSEEN;
					}
					*start = at;
					return depth - at;
				}
				if (o->mark[w] == UNSEEN) {
					o->path[depth++] = (frame_t){ w, 0 };
					o->mark[w] = ON_PATH;
					descended = true;
					break;
				}
			}
			if (!descended) {
				o->mark[v] = CLEAR;
				depth--;
			}
		}
	}
	return 0;
}

// first_undone returns the lowest-numbered vertex not done.  There is one.
static size_t
first_undone(order_t *o)
{
	while (o->done[o->next_undone]) {
		o->next_undone++;
	}
	return o->next_undone;
}

// pick_victim chooses the copy to convert when copies remain and none is
// ready, which means the remaining graph has a cycle.
static size_t
pick_victim(order_t *o, delta_cycle_policy_t policy)
{
	if (policy == POLICY_CONSTANT) {
		return first_undone(o);
	}

	for (; o->comp < o->sccs.len; o->comp++, o->scan = 0) {
		if (o->sccs.active[o->comp] == 0) {
			continue;
		}
		size_t start;
		size_t len = find_cycle(o, &start);
		if (len == 0) {
			continue;
		}
		size_t victim = o->path[start].v;
		for (size_t k = start + 1; k < start + len; k++) {
			size_t v = o->path[k].v;
			size_t lv = o->copies[v].length;
			size_t lvictim = o->copies[victim].length;
			if (lv < lvictim || (lv == lvictim && v < victim)) {
				victim = v;
			}
		}
		return victim;
	}
	// Not reached: the first component with a vertex left has no
	// unfinished predecessor outside it, so it holds the cycle.
	return first_undone(o);
}

// order_copies appends the copies to out in a safe order, and to adds those
// it had to convert, with the bytes they read from r.
static void
order_copies(const graph_t *g, const copy_t *copies, const uint8_t *r,
             delta_cycle_policy_t policy,
             delta_placed_commands_t *out, delta_placed_commands_t *adds)
{
	size_t n = g->n;
	order_t o = { .g = g, .copies = copies, .sccs = find_sccs(g) };
	o.in_degree = delta_calloc(n, sizeof(*o.in_degree));
	o.done = delta_calloc(n, sizeof(*o.done));
	o.heap = delta_malloc(n * sizeof(*o.heap));
	o.mark = delta_calloc(n, sizeof(*o.mark));
	o.path = delta_malloc(n * sizeof(*o.path));

	for (size_t k = 0; k < g->first[n]; k++) {
		o.in_degree[g->to[k]]++;
	}
	for (size_t v = 0; v < n; v++) {
		if (o.in_degree[v] == 0) {
			ready_push(&o, v);
		}
	}

	for (size_t remaining = n; remaining > 0; remaining--) {
		delta_placed_command_t pc;
		size_t v;

		if (o.heap_len > 0) {
			v = ready_pop(&o);
			pc.tag = PCMD_COPY;
			pc.copy.src = copies[v].src;
			pc.copy.dst = copies[v].dst;
			pc.copy.length = copies[v].length;
			delta_placed_commands_push(out, pc);
		} else {
			v = pick_victim(&o, policy);
			pc.tag = PCMD_ADD;
			pc.add.dst = copies[v].dst;
			pc.add.length = copies[v].length;
			pc.add.data = delta_memdup(&r[copies[v].src],
			                           copies[v].length);
			delta_placed_commands_push(adds, pc);
		}
		retire(&o, v);
	}

	sccs_free(&o.sccs);
	free(o.in_degree);
	free(o.done);
	free(o.heap);
	free(o.mark);
	free(o.path);
}

delta_placed_commands_t
delta_make_inplace(const uint8_t *r, size_t r_len,
                   const delta_commands_t *cmds,
                   delta_cycle_policy_t policy)
{
	(void)r_len;

	size_t n = delta_summary(cmds).num_copies;
	copy_t *copies = delta_malloc(n * sizeof(*copies));
	delta_placed_commands_t result, adds;
	delta_placed_commands_init(&result);
	delta_placed_commands_init(&adds);

	size_t dst = 0, ncopies = 0;
	for (size_t i = 0; i < cmds->len; i++) {
		const delta_command_t *cmd = &cmds->data[i];
		if (cmd->tag == CMD_COPY) {
			copies[ncopies++] = (copy_t){ cmd->copy.offset, dst,
			                              cmd->copy.length };
			dst += cmd->copy.length;
		} else {
			delta_placed_command_t pc = { .tag = PCMD_ADD };
			pc.add.dst = dst;
			pc.add.length = cmd->add.length;
			pc.add.data = delta_memdup(cmd->add.data, cmd->add.length);
			delta_placed_commands_push(&adds, pc);
			dst += cmd->add.length;
		}
	}

	if (n > 0) {
		graph_t g = build_graph(copies, n);
		order_copies(&g, copies, r, policy, &result, &adds);
		graph_free(&g);
	}
	free(copies);

	// The adds move to the result, data and all.
	for (size_t i = 0; i < adds.len; i++) {
		delta_placed_commands_push(&result, adds.data[i]);
	}
	free(adds.data);
	return result;
}
