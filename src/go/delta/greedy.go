package delta

import (
	"fmt"
	"os"
)

// A seedIndex maps a fingerprint to the offsets of every seed of R that has
// it, in increasing order.
type seedIndex struct {
	table map[uint64][]int
	tree  *SplayTree[[]int] // used instead of table when non-nil
}

func newSeedIndex(useSplay bool) *seedIndex {
	if useSplay {
		return &seedIndex{tree: new(SplayTree[[]int])}
	}
	return &seedIndex{table: make(map[uint64][]int)}
}

func (x *seedIndex) add(fp uint64, offset int) {
	if x.tree == nil {
		x.table[fp] = append(x.table[fp], offset)
		return
	}
	offsets := x.tree.At(fp)
	*offsets = append(*offsets, offset)
}

func (x *seedIndex) offsets(fp uint64) []int {
	if x.tree == nil {
		return x.table[fp]
	}
	offsets, _ := x.tree.Find(fp)
	return offsets
}

// diffGreedy is the greedy algorithm (Section 3.1, Figure 2). It indexes
// every seed of R, then at each position of V takes the longest match that
// any seed with the same fingerprint offers. The delta has minimum cost
// under the simple cost measure if p <= 2; a larger p misses matches shorter
// than p (Section 3.3). It takes O(|V|*|R|) time in the worst case and
// O(|R|) space.
func diffGreedy(r, v []byte, opts DiffOptions) []Command {
	if len(v) == 0 {
		return nil
	}
	p := opts.P

	index := newSeedIndex(opts.UseSplay)
	hashR := newRollingHash(r, p)
	for a := 0; a+p <= len(r); a++ {
		index.add(hashR.at(a), a)
	}

	if opts.Verbose {
		fmt.Fprintf(os.Stderr, "greedy: %s, |R|=%d, |V|=%d, seed_len=%d\n",
			lookupName(opts.UseSplay), len(r), len(v), p)
	}

	var commands []Command
	hashV := newRollingHash(v, p)
	vS := 0 // start of the bytes of V not yet encoded
	for vC := 0; vC+p <= len(v); {
		// Fingerprints can collide, so a candidate counts only if it
		// matches at least the seed.
		bestOff, bestLen := 0, 0
		for _, off := range index.offsets(hashV.at(vC)) {
			if n := commonPrefix(r[off:], v[vC:]); n >= p && n > bestLen {
				bestOff, bestLen = off, n
			}
		}
		if bestLen == 0 {
			vC++
			continue
		}

		if vS < vC {
			commands = append(commands, literal(v[vS:vC]))
		}
		commands = append(commands, CopyCmd{Offset: bestOff, Length: bestLen})
		vC += bestLen
		vS = vC
	}
	if vS < len(v) {
		commands = append(commands, literal(v[vS:]))
	}

	if opts.Verbose {
		printStats(commands)
	}
	return commands
}
