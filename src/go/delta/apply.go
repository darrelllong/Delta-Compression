package delta

import (
	"container/heap"
	"fmt"
	"sort"
)

// Command placement, application, and in-place reordering.
//
// PlaceCommands: assign sequential destinations (Section 2.1.1)
// MakeInplace:   CRWI digraph + topological sort (Burns et al. 2003)

// OutputSize computes the total output size of algorithm commands.
func OutputSize(commands []Command) int {
	size := 0
	for _, cmd := range commands {
		switch c := cmd.(type) {
		case CopyCmd:
			size += c.Length
		case AddCmd:
			size += len(c.Data)
		}
	}
	return size
}

// PlaceCommands converts algorithm commands to placed commands with sequential destinations.
func PlaceCommands(commands []Command) []PlacedCommand {
	placed := make([]PlacedCommand, 0, len(commands))
	dst := 0
	for _, cmd := range commands {
		switch c := cmd.(type) {
		case CopyCmd:
			placed = append(placed, PlacedCopy{Src: c.Offset, DstOff: dst, Length: c.Length})
			dst += c.Length
		case AddCmd:
			placed = append(placed, PlacedAdd{DstOff: dst, Data: c.Data})
			dst += len(c.Data)
		}
	}
	return placed
}

// ApplyPlacedTo applies placed commands in standard mode: read from r, write to out.
func ApplyPlacedTo(r []byte, commands []PlacedCommand, out []byte) int {
	maxWritten := 0
	for _, cmd := range commands {
		switch c := cmd.(type) {
		case PlacedCopy:
			copy(out[c.DstOff:], r[c.Src:c.Src+c.Length])
			if end := c.DstOff + c.Length; end > maxWritten {
				maxWritten = end
			}
		case PlacedAdd:
			copy(out[c.DstOff:], c.Data)
			if end := c.DstOff + len(c.Data); end > maxWritten {
				maxWritten = end
			}
		}
	}
	return maxWritten
}

// ApplyPlacedInplaceTo applies placed commands in-place within a single buffer.
// Go's builtin copy handles overlapping slices correctly (memmove semantics).
func ApplyPlacedInplaceTo(commands []PlacedCommand, buf []byte) {
	for _, cmd := range commands {
		switch c := cmd.(type) {
		case PlacedCopy:
			copy(buf[c.DstOff:], buf[c.Src:c.Src+c.Length])
		case PlacedAdd:
			copy(buf[c.DstOff:], c.Data)
		}
	}
}

// ValidatePlacedCommands checks whether placed commands fit within the
// destination size and readable source window for apply.
func ValidatePlacedCommands(commands []PlacedCommand, referenceSize, versionSize int, inplace bool) error {
	if referenceSize < 0 || versionSize < 0 {
		return fmt.Errorf("negative buffer size")
	}
	sourceLimit := referenceSize
	if inplace && versionSize > sourceLimit {
		sourceLimit = versionSize
	}
	for _, cmd := range commands {
		switch c := cmd.(type) {
		case PlacedCopy:
			if err := validateApplyRange(c.DstOff, c.Length, versionSize, "copy destination"); err != nil {
				return err
			}
			if err := validateApplyRange(c.Src, c.Length, sourceLimit, "copy source"); err != nil {
				return err
			}
		case PlacedAdd:
			if err := validateApplyRange(c.DstOff, len(c.Data), versionSize, "add destination"); err != nil {
				return err
			}
		}
	}
	return nil
}

// ApplyDelta reconstructs the version from reference + algorithm commands.
func ApplyDelta(r []byte, commands []Command) []byte {
	out := make([]byte, OutputSize(commands))
	pos := 0
	for _, cmd := range commands {
		switch c := cmd.(type) {
		case CopyCmd:
			copy(out[pos:], r[c.Offset:c.Offset+c.Length])
			pos += c.Length
		case AddCmd:
			copy(out[pos:], c.Data)
			pos += len(c.Data)
		}
	}
	return out
}

// ApplyDeltaInplace applies placed in-place commands to a buffer initialized with r.
func ApplyDeltaInplace(r []byte, commands []PlacedCommand, versionSize int) []byte {
	bufSize := len(r)
	if versionSize > bufSize {
		bufSize = versionSize
	}
	buf := make([]byte, bufSize)
	copy(buf, r)
	ApplyPlacedInplaceTo(commands, buf)
	if len(buf) != versionSize {
		return buf[:versionSize]
	}
	return buf
}

func validateApplyRange(start, length, limit int, name string) error {
	if start < 0 || length < 0 {
		return fmt.Errorf("%s out of range", name)
	}
	if start > limit || length > limit-start {
		return fmt.Errorf("%s out of range", name)
	}
	return nil
}

// UnplaceCommands converts placed commands back to algorithm commands.
// Commands are sorted by destination offset to recover original sequential order.
func UnplaceCommands(placed []PlacedCommand) []Command {
	sorted := make([]PlacedCommand, len(placed))
	copy(sorted, placed)
	sort.Slice(sorted, func(i, j int) bool {
		return sorted[i].Dst() < sorted[j].Dst()
	})
	commands := make([]Command, len(sorted))
	for i, cmd := range sorted {
		switch c := cmd.(type) {
		case PlacedCopy:
			commands[i] = CopyCmd{Offset: c.Src, Length: c.Length}
		case PlacedAdd:
			commands[i] = AddCmd{Data: c.Data}
		}
	}
	return commands
}

// ── MakeInplace ──

// copyRec holds the fields of a copy command for makeInplace processing.
type copyRec struct {
	idx    int
	src    int
	dst    int
	length int
}

// MakeInplace converts standard delta commands to in-place executable commands.
//
// A CRWI (Copy-Read/Write-Intersection) edge i→j means copy i reads
// from a region that copy j will overwrite, so i must execute before j.
// When the digraph is acyclic, a topological order gives a valid serial
// schedule. A cycle creates a circular dependency; breaking it materializes
// one copy as a literal add (reading source bytes from R before they are
// overwritten).
//
// Algorithm (Burns, Long, Stockmeyer, IEEE TKDE 2003):
//  1. Annotate each command with its write offset
//  2. Build CRWI digraph on copy commands (Section 4.2)
//  3. Topological sort (Kahn); when heap empties with remaining nodes,
//     find the cycle and convert the minimum-length copy to an add
//  4. Output: copies in topological order, then all adds
func MakeInplace(r []byte, commands []Command, policy CyclePolicy) []PlacedCommand {
	if len(commands) == 0 {
		return nil
	}

	// Step 1: compute write offsets.
	var copies []copyRec
	var adds []PlacedAdd
	writePos := 0

	for _, cmd := range commands {
		switch c := cmd.(type) {
		case CopyCmd:
			copies = append(copies, copyRec{idx: len(copies), src: c.Offset, dst: writePos, length: c.Length})
			writePos += c.Length
		case AddCmd:
			data := make([]byte, len(c.Data))
			copy(data, c.Data)
			adds = append(adds, PlacedAdd{DstOff: writePos, Data: data})
			writePos += len(c.Data)
		}
	}

	n := len(copies)
	if n == 0 {
		result := make([]PlacedCommand, len(adds))
		for i, a := range adds {
			result[i] = a
		}
		return result
	}

	// Step 2: build CRWI digraph.
	// O(n log n + E) sweep-line: sort writes by start, then for each read
	// interval binary-search into the sorted writes to find overlaps.
	adj := make([][]int, n)

	writeSorted := make([]int, n)
	for i := range writeSorted {
		writeSorted[i] = i
	}
	sort.Slice(writeSorted, func(a, b int) bool {
		return copies[writeSorted[a]].dst < copies[writeSorted[b]].dst
	})
	writeStarts := make([]int, n)
	for k, idx := range writeSorted {
		writeStarts[k] = copies[idx].dst
	}

	for i := 0; i < n; i++ {
		si := copies[i].src
		li := copies[i].length
		readEnd := si + li

		lo := sort.SearchInts(writeStarts, si)
		hi := sort.SearchInts(writeStarts[lo:], readEnd) + lo

		if lo > 0 {
			j := writeSorted[lo-1]
			if j != i {
				dj := copies[j].dst
				lj := copies[j].length
				if dj+lj > si {
					adj[i] = append(adj[i], j)
				}
			}
		}
		for k := lo; k < hi; k++ {
			j := writeSorted[k]
			if j != i {
				adj[i] = append(adj[i], j)
			}
		}
	}

	// Step 3: Kahn topological sort with Tarjan-scoped cycle breaking.
	sccs := tarjanSCC(adj, n)

	inDeg := make([]int, n)
	for i := 0; i < n; i++ {
		for _, j := range adj[i] {
			inDeg[j]++
		}
	}

	// sccID[v] = index into sccList for non-trivial SCCs; -1 for trivial.
	sccID := make([]int, n)
	for i := range sccID {
		sccID[i] = -1
	}
	var sccList [][]int
	for _, scc := range sccs {
		if len(scc) > 1 {
			sid := len(sccList)
			for _, v := range scc {
				sccID[v] = sid
			}
			sccList = append(sccList, scc)
		}
	}
	// sccActive[sid] = number of unremoved vertices in sccList[sid].
	sccActive := make([]int, len(sccList))
	for sid, scc := range sccList {
		sccActive[sid] = len(scc)
	}

	removed := make([]bool, n)
	var topoOrder []int
	color := make([]int, n) // 0=unvisited, 1=on-path, 2=done
	sccPtr := 0
	scanPos := 0

	h := &pairHeap{}
	heap.Init(h)
	for i := 0; i < n; i++ {
		if inDeg[i] == 0 {
			heap.Push(h, [2]int{copies[i].length, i})
		}
	}
	processed := 0

	for processed < n {
		// Drain all ready vertices.
		for h.Len() > 0 {
			entry := heap.Pop(h).([2]int)
			v := entry[1]
			if removed[v] {
				continue
			}
			removed[v] = true
			topoOrder = append(topoOrder, v)
			processed++
			if sccID[v] != -1 {
				sccActive[sccID[v]]--
			}
			for _, w := range adj[v] {
				if !removed[w] {
					inDeg[w]--
					if inDeg[w] == 0 {
						heap.Push(h, [2]int{copies[w].length, w})
					}
				}
			}
		}

		if processed >= n {
			break
		}

		// Kahn stalled: all remaining vertices are in CRWI cycles.
		victim := -1
		if policy == CyclePolicyConstant {
			for i := 0; i < n; i++ {
				if !removed[i] {
					victim = i
					break
				}
			}
		} else { // LOCALMIN
			for victim == -1 {
				for sccPtr < len(sccList) && sccActive[sccPtr] == 0 {
					sccPtr++
					scanPos = 0
				}
				if sccPtr >= len(sccList) {
					for i := 0; i < n; i++ {
						if !removed[i] {
							victim = i
							break
						}
					}
					break
				}
				cycle := findCycleInSCC(adj, sccList[sccPtr], sccPtr, sccID, removed, color, &scanPos)
				if cycle != nil {
					victim = cycle[0]
					for _, v := range cycle {
						if copies[v].length < copies[victim].length ||
							(copies[v].length == copies[victim].length && v < victim) {
							victim = v
						}
					}
				} else {
					sccPtr++
					scanPos = 0
				}
			}
		}

		// Convert victim: materialize copy data as literal add.
		ci := copies[victim]
		data := make([]byte, ci.length)
		copy(data, r[ci.src:ci.src+ci.length])
		adds = append(adds, PlacedAdd{DstOff: ci.dst, Data: data})
		removed[victim] = true
		processed++
		if sccID[victim] != -1 {
			sccActive[sccID[victim]]--
		}
		for _, w := range adj[victim] {
			if !removed[w] {
				inDeg[w]--
				if inDeg[w] == 0 {
					heap.Push(h, [2]int{copies[w].length, w})
				}
			}
		}
	}

	// Step 4: assemble result — copies in topo order, then all adds.
	result := make([]PlacedCommand, 0, len(topoOrder)+len(adds))
	for _, i := range topoOrder {
		ci := copies[i]
		result = append(result, PlacedCopy{Src: ci.src, DstOff: ci.dst, Length: ci.length})
	}
	for _, a := range adds {
		result = append(result, a)
	}
	return result
}

// tarjanSCC computes SCCs using iterative Tarjan's algorithm.
// Returns SCCs in reverse topological order (sinks first).
// R.E. Tarjan, SIAM Journal on Computing, 1(2):146-160, June 1972.
func tarjanSCC(adj [][]int, n int) [][]int {
	index := make([]int, n)
	for i := range index {
		index[i] = -1 // -1 = unvisited
	}
	lowlink := make([]int, n)
	onStack := make([]bool, n)
	var tarjanStack []int
	var sccs [][]int
	counter := 0

	type frame struct{ v, ni int }
	var callStack []frame

	for start := 0; start < n; start++ {
		if index[start] != -1 {
			continue
		}
		index[start] = counter
		lowlink[start] = counter
		counter++
		onStack[start] = true
		tarjanStack = append(tarjanStack, start)
		callStack = append(callStack, frame{start, 0})

		for len(callStack) > 0 {
			fr := &callStack[len(callStack)-1]
			v := fr.v
			neighbors := adj[v]

			if fr.ni < len(neighbors) {
				w := neighbors[fr.ni]
				fr.ni++
				if index[w] == -1 {
					index[w] = counter
					lowlink[w] = counter
					counter++
					onStack[w] = true
					tarjanStack = append(tarjanStack, w)
					callStack = append(callStack, frame{w, 0})
				} else if onStack[w] {
					if index[w] < lowlink[v] {
						lowlink[v] = index[w]
					}
				}
			} else {
				callStack = callStack[:len(callStack)-1]
				if len(callStack) > 0 {
					parent := callStack[len(callStack)-1].v
					if lowlink[v] < lowlink[parent] {
						lowlink[parent] = lowlink[v]
					}
				}
				if lowlink[v] == index[v] {
					var scc []int
					for {
						w := tarjanStack[len(tarjanStack)-1]
						tarjanStack = tarjanStack[:len(tarjanStack)-1]
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
	}
	return sccs
}

// findCycleInSCC finds a cycle in the active subgraph of one SCC.
// Three amortizations give O(|SCC| + E_SCC) total work per SCC.
func findCycleInSCC(adj [][]int, scc []int, sid int, sccID []int,
	removed []bool, color []int, scanStart *int) []int {

	var path []int
	scan := *scanStart

	type frame struct{ v, ni int }

outer:
	for scan < len(scc) {
		start := scc[scan]
		if removed[start] || color[start] != 0 {
			scan++
			continue
		}

		color[start] = 1
		path = append(path, start)
		stack := []frame{{start, 0}}

		for len(stack) > 0 {
			fr := &stack[len(stack)-1]
			v := fr.v
			neighbors := adj[v]

			advanced := false
			for fr.ni < len(neighbors) {
				w := neighbors[fr.ni]
				fr.ni++
				if sccID[w] != sid || removed[w] {
					continue
				}
				if color[w] == 1 {
					// Found a cycle.
					pos := -1
					for pi, u := range path {
						if u == w {
							pos = pi
							break
						}
					}
					cycle := make([]int, len(path)-pos)
					copy(cycle, path[pos:])
					for _, u := range path {
						color[u] = 0
					}
					*scanStart = scan
					return cycle
				}
				if color[w] == 0 {
					color[w] = 1
					path = append(path, w)
					stack = append(stack, frame{w, 0})
					advanced = true
					break
				}
			}
			if !advanced {
				stack = stack[:len(stack)-1]
				color[v] = 2 // fully explored — persists across calls
				if len(path) > 0 {
					path = path[:len(path)-1]
				}
			}
		}
		scan++
		continue outer // suppress "label not used" if loop changes
	}

	*scanStart = scan
	return nil
}

// PlacedSummaryOf computes statistics for a list of placed commands.
func PlacedSummaryOf(commands []PlacedCommand) PlacedSummary {
	var numCopies, numAdds int
	var copyBytes, addBytes int64
	for _, cmd := range commands {
		switch c := cmd.(type) {
		case PlacedCopy:
			numCopies++
			copyBytes += int64(c.Length)
		case PlacedAdd:
			numAdds++
			addBytes += int64(len(c.Data))
		}
	}
	return PlacedSummary{
		NumCommands:      len(commands),
		NumCopies:        numCopies,
		NumAdds:          numAdds,
		CopyBytes:        copyBytes,
		AddBytes:         addBytes,
		TotalOutputBytes: copyBytes + addBytes,
	}
}

// ── Min-heap for (length, index) pairs ──

type pairHeap [][2]int

func (h pairHeap) Len() int { return len(h) }
func (h pairHeap) Less(i, j int) bool {
	if h[i][0] != h[j][0] {
		return h[i][0] < h[j][0]
	}
	return h[i][1] < h[j][1]
}
func (h pairHeap) Swap(i, j int)       { h[i], h[j] = h[j], h[i] }
func (h *pairHeap) Push(x interface{}) { *h = append(*h, x.([2]int)) }
func (h *pairHeap) Pop() interface{} {
	old := *h
	n := len(old)
	x := old[n-1]
	*h = old[:n-1]
	return x
}
