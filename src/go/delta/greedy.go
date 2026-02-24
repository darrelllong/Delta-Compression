package delta

import (
	"fmt"
	"os"
)

// diffGreedy implements the greedy algorithm (Section 3.1, Figure 2).
//
// Finds an optimal delta encoding under the simple cost measure.
// (Optimality proof: Section 3.3, Theorem 1.)
// Time: O(|V| * |R|) worst case. Space: O(|R|).
func diffGreedy(r, v []byte, opts DiffOptions) []Command {
	if len(v) == 0 {
		return nil
	}

	p := opts.P
	verbose := opts.Verbose
	useSplay := opts.UseSplay

	// Step (1): build lookup structure for R keyed by full fingerprint.
	// Maps fingerprint → list of R offsets with that fingerprint.
	var hrHt map[int64][]int
	var hrSp *SplayTree[[]int]

	if useSplay {
		hrSp = &SplayTree[[]int]{}
	} else {
		hrHt = make(map[int64][]int)
	}

	insertOffset := func(fp int64, offset int) {
		if useSplay {
			existing, ok := hrSp.Find(fp)
			if ok {
				hrSp.Insert(fp, append(existing, offset))
			} else {
				hrSp.Insert(fp, []int{offset})
			}
		} else {
			hrHt[fp] = append(hrHt[fp], offset)
		}
	}

	lookupOffsets := func(fp int64) []int {
		if useSplay {
			v, _ := hrSp.Find(fp)
			return v
		}
		return hrHt[fp]
	}

	if len(r) >= p {
		rh := NewRollingHash(r, 0, p)
		insertOffset(rh.Value(), 0)
		for a := 1; a <= len(r)-p; a++ {
			rh.Roll(int(r[a-1]&0xFF), int(r[a+p-1]&0xFF))
			insertOffset(rh.Value(), a)
		}
	}

	if verbose {
		structName := "hash table"
		if useSplay {
			structName = "splay tree"
		}
		fmt.Fprintf(os.Stderr, "greedy: %s, |R|=%d, |V|=%d, seed_len=%d\n",
			structName, len(r), len(v), p)
	}

	// Step (2): initialize scan pointers.
	vC, vS := 0, 0
	var rhV *RollingHash
	rhVPos := 0
	if len(v) >= p {
		rhV = NewRollingHash(v, 0, p)
	}

	var commands []Command

	for vC+p <= len(v) {
		// Step (3)+(4): compute fingerprint at vC.
		if rhV == nil {
			break
		}
		if vC == rhVPos {
			// already positioned
		} else if vC == rhVPos+1 {
			rhV.Roll(int(v[vC-1]&0xFF), int(v[vC+p-1]&0xFF))
			rhVPos = vC
		} else {
			rhV = NewRollingHash(v, vC, p)
			rhVPos = vC
		}
		fpV := rhV.Value()

		// Steps (4)+(5): find the longest matching substring.
		bestRm := -1
		bestLen := 0

		for _, rCand := range lookupOffsets(fpV) {
			if !regionEquals(r, rCand, v, vC, p) {
				continue
			}
			ml := p
			for vC+ml < len(v) && rCand+ml < len(r) && v[vC+ml] == r[rCand+ml] {
				ml++
			}
			if ml > bestLen {
				bestLen = ml
				bestRm = rCand
			}
		}

		if bestLen < p {
			vC++
			continue
		}

		// Step (6): encode.
		if vS < vC {
			data := make([]byte, vC-vS)
			copy(data, v[vS:vC])
			commands = append(commands, AddCmd{Data: data})
		}
		commands = append(commands, CopyCmd{Offset: bestRm, Length: bestLen})
		vS = vC + bestLen

		// Step (7): advance past matched region.
		vC += bestLen
	}

	// Step (8): trailing add.
	if vS < len(v) {
		data := make([]byte, len(v)-vS)
		copy(data, v[vS:])
		commands = append(commands, AddCmd{Data: data})
	}

	if verbose {
		printStats(commands)
	}
	return commands
}
