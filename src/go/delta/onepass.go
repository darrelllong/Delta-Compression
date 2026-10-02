package delta

import (
	"bytes"
	"fmt"
	"os"
)

// A onepassTable maps a fingerprint to the offset of the first seed stored
// with it since the table was last emptied. Each entry records the version
// at which it was stored and only entries of the current version count, so
// advancing the version empties the table in constant time.
//
// The hash table has one slot for each residue of the fingerprint modulo the
// table size, and the first seed to claim a slot keeps it; the splay tree
// has an entry for every fingerprint.
//
// The slots' versions are kept apart from their seeds because most lookups
// find a slot that is not current and read nothing else; the versions alone
// are far more likely to be in the cache.
type onepassTable struct {
	version []uint64 // versions start at 1, so a zero slot is never current
	seed    []onepassSeed
	tree    *SplayTree[onepassEntry] // used in place of the slots if not nil
}

type onepassSeed struct {
	fp     uint64
	offset int
}

type onepassEntry struct {
	offset  int
	version uint64
}

func newOnepassTable(q int, useSplay bool) *onepassTable {
	if useSplay {
		return &onepassTable{tree: new(SplayTree[onepassEntry])}
	}
	return &onepassTable{version: make([]uint64, q), seed: make([]onepassSeed, q)}
}

// store records the seed at offset unless its place is taken by a seed of
// the current version.
func (t *onepassTable) store(fp uint64, offset int, version uint64) {
	if t.tree != nil {
		if e := t.tree.At(fp); e.version != version {
			*e = onepassEntry{offset, version}
		}
		return
	}
	i := fp % uint64(len(t.version))
	if t.version[i] != version {
		t.version[i] = version
		t.seed[i] = onepassSeed{fp, offset}
	}
}

// lookup returns the offset of the seed stored with fingerprint fp at the
// current version, if there is one.
func (t *onepassTable) lookup(fp, version uint64) (offset int, ok bool) {
	if t.tree != nil {
		e, found := t.tree.Find(fp)
		return e.offset, found && e.version == version
	}
	i := fp % uint64(len(t.version))
	if t.version[i] != version || t.seed[i].fp != fp {
		return 0, false
	}
	return t.seed[i].offset, true
}

// diffOnepass is the one-pass algorithm (Section 4.1, Figure 3). It scans R
// and V in step, entering the seed at each position into that string's
// table and looking it up in the other's. On a match it emits a copy, moves
// both scans past the match, and empties both tables. It takes time linear
// in the input and space that depends only on the table size, but because
// it never looks back it misses blocks that appear in a different order in
// V (Section 4.3).
func diffOnepass(r, v []byte, opts DiffOptions) []Command {
	if len(v) == 0 {
		return nil
	}
	p := opts.P

	// One slot for every p bytes of R, but at least opts.Q.
	q := NextPrime(max(opts.Q, numSeeds(len(r), p)/p))

	if opts.Verbose {
		fmt.Fprintf(os.Stderr, "onepass: %s, q=%d, |R|=%d, |V|=%d, seed_len=%d\n",
			lookupName(opts.UseSplay), q, len(r), len(v), p)
	}

	tableR := newOnepassTable(q, opts.UseSplay)
	tableV := newOnepassTable(q, opts.UseSplay)
	hashR := newRollingHash(r, p)
	hashV := newRollingHash(v, p)

	var commands []Command
	version := uint64(1)
	rC, vC := 0, 0 // scan positions
	vS := 0        // start of the bytes of V not yet encoded
	for {
		haveR := rC+p <= len(r)
		haveV := vC+p <= len(v)
		if !haveR && !haveV {
			break
		}
		var fpR, fpV uint64
		if haveV {
			fpV = hashV.at(vC)
			tableV.store(fpV, vC, version)
		}
		if haveR {
			fpR = hashR.at(rC)
			tableR.store(fpR, rC, version)
		}

		// A match pairs the seed at one scan position with a seed of the
		// other string that is already in its table.
		rM, vM, found := 0, 0, false
		if haveR {
			if off, ok := tableV.lookup(fpR, version); ok && bytes.Equal(r[rC:rC+p], v[off:off+p]) {
				rM, vM, found = rC, off, true
			}
		}
		if !found && haveV {
			if off, ok := tableR.lookup(fpV, version); ok && bytes.Equal(v[vC:vC+p], r[off:off+p]) {
				rM, vM, found = off, vC, true
			}
		}
		if !found {
			rC++
			vC++
			continue
		}

		n := commonPrefix(r[rM:], v[vM:])
		if vS < vM {
			commands = append(commands, literal(v[vS:vM]))
		}
		commands = append(commands, CopyCmd{Offset: rM, Length: n})
		rC, vC = rM+n, vM+n
		vS = vC
		version++
	}
	if vS < len(v) {
		commands = append(commands, literal(v[vS:]))
	}

	if opts.Verbose {
		printStats(commands)
	}
	return commands
}
