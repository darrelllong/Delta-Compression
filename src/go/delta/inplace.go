package delta

import (
	"bytes"
	"container/heap"
	"sort"
)

// MakeInplace reorders the commands of a delta so that it can be applied in
// the buffer that holds the reference r, with no second buffer (Burns, Long
// and Stockmeyer, IEEE TKDE 2003).
//
// A copy that reads bytes another copy overwrites must run first. These
// constraints form a digraph on the copies, the CRWI digraph (Section 4.2 of
// that paper). MakeInplace emits the copies in a topological order of it.
// Where a cycle makes that impossible it converts one copy on the cycle,
// chosen by policy, to an add of the bytes it would have copied. The adds
// follow all the copies, since nothing reads what they write.
func MakeInplace(r []byte, commands []Command, policy CyclePolicy) []PlacedCommand {
	if len(commands) == 0 {
		return nil
	}

	var copies []placedCopy
	var adds []PlacedAdd
	dst := 0
	for _, cmd := range commands {
		switch c := cmd.(type) {
		case CopyCmd:
			copies = append(copies, placedCopy{src: c.Offset, dst: dst, length: c.Length})
			dst += c.Length
		case AddCmd:
			adds = append(adds, PlacedAdd{DstOff: dst, Data: bytes.Clone(c.Data)})
			dst += len(c.Data)
		}
	}

	s := newScheduler(copies, policy)
	order, converted := s.run()

	result := make([]PlacedCommand, 0, len(commands))
	for _, i := range order {
		c := copies[i]
		result = append(result, PlacedCopy{Src: c.src, DstOff: c.dst, Length: c.length})
	}
	for _, a := range adds {
		result = append(result, a)
	}
	for _, i := range converted {
		c := copies[i]
		result = append(result, PlacedAdd{DstOff: c.dst, Data: bytes.Clone(r[c.src : c.src+c.length])})
	}
	return result
}

// A placedCopy is a copy command and its destination. Copies are identified
// by their index in order of destination.
type placedCopy struct {
	src, dst, length int
}

// crwiEdges returns the CRWI digraph as adjacency lists: edges[i] holds
// every j != i such that copy i reads a byte that copy j writes, so that i
// must run before j. It takes O(n log n + E) time.
func crwiEdges(copies []placedCopy) [][]int {
	n := len(copies)
	edges := make([][]int, n)

	// The copies write disjoint intervals, so in order of destination the
	// ones that intersect a given read interval are consecutive.
	byDst := make([]int, n)
	for i := range byDst {
		byDst[i] = i
	}
	sort.Slice(byDst, func(a, b int) bool {
		return copies[byDst[a]].dst < copies[byDst[b]].dst
	})
	starts := make([]int, n)
	for k, i := range byDst {
		starts[k] = copies[i].dst
	}

	for i, c := range copies {
		// Writes lo through hi-1 start within the read interval.
		lo := sort.SearchInts(starts, c.src)
		hi := lo + sort.SearchInts(starts[lo:], c.src+c.length)
		// The write before them starts earlier but may extend into it.
		if lo > 0 {
			if j := byDst[lo-1]; j != i && copies[j].dst+copies[j].length > c.src {
				edges[i] = append(edges[i], j)
			}
		}
		for _, j := range byDst[lo:hi] {
			if j != i {
				edges[i] = append(edges[i], j)
			}
		}
	}
	return edges
}

// A dfsFrame is a vertex on a depth-first search path and the index of the
// next of its edges to follow.
type dfsFrame struct{ v, next int }

// tarjanSCC returns the strongly connected components of the digraph, each
// before any component that has an edge into it (R. E. Tarjan, SIAM J.
// Comput. 1(2), 1972). The search is iterative so that a long path cannot
// exhaust the stack.
func tarjanSCC(edges [][]int) [][]int {
	const unvisited = -1
	n := len(edges)
	index := make([]int, n)
	for i := range index {
		index[i] = unvisited
	}
	lowlink := make([]int, n)
	onStack := make([]bool, n)
	var stack []int
	var path []dfsFrame
	var sccs [][]int
	counter := 0

	visit := func(v int) {
		index[v], lowlink[v] = counter, counter
		counter++
		onStack[v] = true
		stack = append(stack, v)
		path = append(path, dfsFrame{v: v})
	}

	for root := range edges {
		if index[root] != unvisited {
			continue
		}
		visit(root)
		for len(path) > 0 {
			f := &path[len(path)-1]
			v := f.v
			if f.next < len(edges[v]) {
				w := edges[v][f.next]
				f.next++
				if index[w] == unvisited {
					visit(w)
				} else if onStack[w] {
					lowlink[v] = min(lowlink[v], index[w])
				}
				continue
			}
			path = path[:len(path)-1]
			if len(path) > 0 {
				parent := path[len(path)-1].v
				lowlink[parent] = min(lowlink[parent], lowlink[v])
			}
			if lowlink[v] == index[v] { // v is the root of a component
				var scc []int
				for {
					w := stack[len(stack)-1]
					stack = stack[:len(stack)-1]
					onStack[w] = false
					scc = append(scc, w)
					if w == v {
						break
					}
				}
				sccs = append(sccs, scc)
			}
		}
	}
	return sccs
}

// A scheduler orders the copies by Kahn's algorithm, breaking cycles as it
// meets them.
type scheduler struct {
	copies []placedCopy
	edges  [][]int
	policy CyclePolicy
	inDeg  []int     // unsettled predecessors of each copy
	done   []bool    // the copy has been scheduled or converted
	ready  readyHeap // unsettled copies with no unsettled predecessor

	firstLeft int // CyclePolicyConstant: no copy below this index is unsettled

	// CyclePolicyLocalmin looks for cycles one component at a time.
	sccs   [][]int // components of more than one vertex
	sccOf  []int   // index in sccs of each copy's component, or -1
	active []int   // unsettled copies in each component
	color  []uint8 // state of each copy in the cycle search
	scc    int     // component being searched
	scan   int     // position in sccs[scc] of the next search root
}

// States of a vertex in the cycle search.
const (
	unexplored = iota
	onPath     // on the current search path
	acyclic    // no cycle is reachable from it
)

func newScheduler(copies []placedCopy, policy CyclePolicy) *scheduler {
	n := len(copies)
	s := &scheduler{
		copies: copies,
		edges:  crwiEdges(copies),
		policy: policy,
		inDeg:  make([]int, n),
		done:   make([]bool, n),
		sccOf:  make([]int, n),
		color:  make([]uint8, n),
	}
	for _, succ := range s.edges {
		for _, j := range succ {
			s.inDeg[j]++
		}
	}
	for i := range s.sccOf {
		s.sccOf[i] = -1
	}
	for _, scc := range tarjanSCC(s.edges) {
		if len(scc) == 1 {
			continue
		}
		for _, v := range scc {
			s.sccOf[v] = len(s.sccs)
		}
		s.sccs = append(s.sccs, scc)
		s.active = append(s.active, len(scc))
	}
	return s
}

// run returns the copies to perform, in order, and the copies to convert to
// adds, in the order they were chosen.
func (s *scheduler) run() (order, converted []int) {
	for i, d := range s.inDeg {
		if d == 0 {
			heap.Push(&s.ready, readyCopy{s.copies[i].length, i})
		}
	}
	for settled := 0; settled < len(s.copies); settled++ {
		var v int
		if len(s.ready) > 0 {
			// Of the copies that may run now, take the shortest, and the
			// earliest of those, so that the order is deterministic.
			v = heap.Pop(&s.ready).(readyCopy).index
			order = append(order, v)
		} else {
			// Every unsettled copy is on a cycle or follows one.
			v = s.victim()
			converted = append(converted, v)
		}
		s.done[v] = true
		if c := s.sccOf[v]; c >= 0 {
			s.active[c]--
		}
		for _, w := range s.edges[v] {
			if s.done[w] {
				continue
			}
			if s.inDeg[w]--; s.inDeg[w] == 0 {
				heap.Push(&s.ready, readyCopy{s.copies[w].length, w})
			}
		}
	}
	return order, converted
}

// victim chooses the copy to convert when no copy is ready.
func (s *scheduler) victim() int {
	if s.policy == CyclePolicyLocalmin {
		for ; s.scc < len(s.sccs); s.scc, s.scan = s.scc+1, 0 {
			if s.active[s.scc] == 0 {
				continue
			}
			if cycle := s.findCycle(); cycle != nil {
				return s.shortest(cycle)
			}
		}
	}
	for s.done[s.firstLeft] {
		s.firstLeft++
	}
	return s.firstLeft
}

// shortest returns the shortest copy among vs, and the lowest index among
// equals.
func (s *scheduler) shortest(vs []int) int {
	best := vs[0]
	for _, v := range vs[1:] {
		if lv, lb := s.copies[v].length, s.copies[best].length; lv < lb || lv == lb && v < best {
			best = v
		}
	}
	return best
}

// findCycle returns the vertices of a cycle among the unsettled copies of
// the current component, or nil if there is none.
//
// All calls for one component together take time linear in its size. The
// search follows only edges inside the component. A vertex found acyclic
// stays so, because settling copies only removes edges. And the scan for a
// search root resumes where the previous call stopped.
func (s *scheduler) findCycle() []int {
	scc := s.sccs[s.scc]
	for ; s.scan < len(scc); s.scan++ {
		root := scc[s.scan]
		if s.done[root] || s.color[root] != unexplored {
			continue
		}
		s.color[root] = onPath
		path := []dfsFrame{{v: root}}
		for len(path) > 0 {
			f := &path[len(path)-1]
			if f.next == len(s.edges[f.v]) {
				s.color[f.v] = acyclic
				path = path[:len(path)-1]
				continue
			}
			w := s.edges[f.v][f.next]
			f.next++
			if s.sccOf[w] != s.scc || s.done[w] {
				continue
			}
			switch s.color[w] {
			case unexplored:
				s.color[w] = onPath
				path = append(path, dfsFrame{v: w})
			case onPath:
				// The cycle is the path from w onward. Converting the
				// victim may break the path anywhere, so all of it must
				// be searched again.
				for _, f := range path {
					s.color[f.v] = unexplored
				}
				var cycle []int
				for i := len(path) - 1; ; i-- {
					cycle = append(cycle, path[i].v)
					if path[i].v == w {
						return cycle
					}
				}
			}
		}
	}
	return nil
}

// A readyCopy is a copy that may run now.
type readyCopy struct {
	length, index int
}

// readyHeap is a min-heap of readyCopy by (length, index), for
// container/heap.
type readyHeap []readyCopy

func (h readyHeap) Len() int { return len(h) }
func (h readyHeap) Less(i, j int) bool {
	if h[i].length != h[j].length {
		return h[i].length < h[j].length
	}
	return h[i].index < h[j].index
}
func (h readyHeap) Swap(i, j int) { h[i], h[j] = h[j], h[i] }
func (h *readyHeap) Push(x any)   { *h = append(*h, x.(readyCopy)) }
func (h *readyHeap) Pop() any {
	old := *h
	x := old[len(old)-1]
	*h = old[:len(old)-1]
	return x
}
