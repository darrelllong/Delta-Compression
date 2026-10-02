package delta

import (
	"bytes"
	"fmt"
	"os"
)

// emptyFP marks a free slot. No fingerprint equals it: they are all less
// than 2^61.
const emptyFP = ^uint64(0)

// A checkpointTable maps the fingerprint of a checkpointed seed of R to the
// seed's offset. If several seeds share a fingerprint the first is kept.
//
// The hash table uses open addressing with linear probing. The fingerprints
// are kept apart from the offsets so that a probe reads only fingerprints.
type checkpointTable struct {
	fp     []uint64 // emptyFP in a free slot
	offset []int
	used   int             // occupied slots
	tree   *SplayTree[int] // offset by fingerprint, in place of hashing; may be nil
}

func newCheckpointTable(size int, useSplay bool) *checkpointTable {
	if useSplay {
		return &checkpointTable{tree: new(SplayTree[int])}
	}
	t := &checkpointTable{fp: make([]uint64, size), offset: make([]int, size)}
	for i := range t.fp {
		t.fp[i] = emptyFP
	}
	return t
}

// insert records offset for fp unless fp is already present or the hash
// table is full. Probing starts at slot home.
func (t *checkpointTable) insert(fp uint64, home, offset int) {
	if t.tree != nil {
		t.tree.InsertOrGet(fp, offset)
		return
	}
	for i := home; t.fp[i] != fp; {
		if t.fp[i] == emptyFP {
			t.fp[i], t.offset[i] = fp, offset
			t.used++
			return
		}
		if i++; i == len(t.fp) {
			i = 0
		}
		if i == home {
			return
		}
	}
}

// lookup returns the offset recorded for fp. Probing starts at slot home.
func (t *checkpointTable) lookup(fp uint64, home int) (offset int, ok bool) {
	if t.tree != nil {
		return t.tree.Find(fp)
	}
	for i := home; t.fp[i] != emptyFP; {
		if t.fp[i] == fp {
			return t.offset[i], true
		}
		if i++; i == len(t.fp) {
			i = 0
		}
		if i == home {
			break
		}
	}
	return 0, false
}

// len returns the number of fingerprints in the table.
func (t *checkpointTable) len() int {
	if t.tree != nil {
		return t.tree.Len()
	}
	return t.used
}

// A pending command encodes v[vStart:vEnd] and may still be revised.
type pending struct {
	vStart, vEnd int
	isCopy       bool
	rOffset      int // source in R, if isCopy
}

// A correctionBuffer holds the most recent commands, which a later match
// may still replace (Section 5.2). Commands leave in order as newer ones
// arrive, and are then final.
type correctionBuffer struct {
	v        []byte
	pending  []pending // contiguous, in increasing order of position in V
	capacity int       // commands held before the oldest becomes final
	out      []Command // final commands
}

// push appends e, first making its oldest command final if the buffer is
// full.
func (b *correctionBuffer) push(e pending) {
	if len(b.pending) > 0 && len(b.pending) >= b.capacity {
		b.emit(b.pending[0])
		b.pending = b.pending[1:]
	}
	b.pending = append(b.pending, e)
}

func (b *correctionBuffer) emit(e pending) {
	if e.isCopy {
		b.out = append(b.out, CopyCmd{Offset: e.rOffset, Length: e.vEnd - e.vStart})
	} else {
		b.out = append(b.out, literal(b.v[e.vStart:e.vEnd]))
	}
}

// correct revises the buffer for a match covering v[vM:vEnd] that begins
// before encoded, the end of the bytes already encoded (tail correction,
// Section 5.1). Working back from the newest command, it removes commands
// that lie wholly within the match and cuts short an add that the match
// enters. It returns the position at which the copy for the match must
// begin: vM if everything the match overlaps could be undone, and otherwise
// the end of the newest command that had to stay, either because it is a
// copy that the match covers only in part or because it has left the
// buffer.
func (b *correctionBuffer) correct(vM, vEnd, encoded int) int {
	start := encoded
	for len(b.pending) > 0 {
		tail := &b.pending[len(b.pending)-1]
		if vM <= tail.vStart && tail.vEnd <= vEnd {
			start = min(start, tail.vStart)
			b.pending = b.pending[:len(b.pending)-1]
			continue
		}
		if !tail.isCopy && tail.vStart < vM && vM < tail.vEnd {
			tail.vEnd = vM
			start = min(start, vM)
		}
		break
	}
	return start
}

// flush makes every buffered command final and returns all the commands.
func (b *correctionBuffer) flush() []Command {
	for _, e := range b.pending {
		b.emit(e)
	}
	b.pending = nil
	return b.out
}

// diffCorrecting is the correcting 1.5-pass algorithm (Section 7, Figure 8)
// with checkpointing (Section 8). A first pass indexes seeds of R; a second
// scans V, extends each match in both directions, and corrects commands
// already issued when a match reaches back over them.
//
// Checkpointing (Section 8.1) keeps the index to about the size of the
// table. A fingerprint is first reduced modulo fSize, a prime about twice
// the number of seeds of R. A seed is a checkpoint if its reduced
// fingerprint f satisfies f mod m == k, where m = ceil(fSize/size). Only
// checkpoints are indexed and looked up, and a checkpoint's home slot is
// f/m.
func diffCorrecting(r, v []byte, opts DiffOptions) []Command {
	if len(v) == 0 {
		return nil
	}
	p := opts.P
	seedsR := numSeeds(len(r), p)

	maxTable := opts.MaxTable
	if maxTable <= 0 {
		maxTable = MaxTableSize
	}
	// Two slots for every p bytes of R, but at least opts.Q and at most
	// maxTable.
	size := NextPrime(min(maxTable, max(opts.Q, 2*seedsR/p)))
	fSize := uint64(1)
	if seedsR > 0 {
		fSize = uint64(NextPrime(2 * seedsR))
	}
	m := (fSize + uint64(size) - 1) / uint64(size)

	// Take k from a seed of V, so that at least one position of V is a
	// checkpoint (p. 348).
	var k uint64
	if len(v) >= p {
		k = Fingerprint(v, min(len(v)/2, len(v)-p), p) % fSize % m
	}

	if opts.Verbose {
		expected := uint64(seedsR) / m
		fmt.Fprintf(os.Stderr,
			"correcting: %s, |C|=%d |F|=%d m=%d k=%d\n"+
				"  checkpoint gap=%d bytes, expected fill ~%d (~%d%% table occupancy)\n",
			lookupName(opts.UseSplay), size, fSize, m, k,
			m, expected, expected*100/uint64(size))
	}

	table := newCheckpointTable(size, opts.UseSplay)
	hashR := newRollingHash(r, p)
	for a := 0; a < seedsR; a++ {
		fp := hashR.at(a)
		if f := fp % fSize; f%m == k {
			table.insert(fp, int(f/m), a)
		}
	}

	if opts.Verbose && seedsR > 0 {
		fmt.Fprintf(os.Stderr, "  build: table occupancy %d/%d (%.1f%%)\n",
			table.len(), size, float64(table.len())*100/float64(size))
	}

	buf := correctionBuffer{v: v, capacity: opts.BufCap}
	hashV := newRollingHash(v, p)
	vS := 0 // end of the bytes of V already encoded
	for vC := 0; vC+p <= len(v); {
		fp := hashV.at(vC)
		f := fp % fSize
		if f%m != k {
			vC++
			continue
		}
		rOff, ok := table.lookup(fp, int(f/m))
		if !ok || !bytes.Equal(r[rOff:rOff+p], v[vC:vC+p]) {
			vC++
			continue
		}

		// Extend the match in both directions. Extending backward can
		// reach bytes already encoded.
		back := commonSuffix(r[:rOff], v[:vC])
		rM, vM := rOff-back, vC-back
		vEnd := vC + p + commonPrefix(r[rOff+p:], v[vC+p:])

		start := vM
		if vS < vM {
			buf.push(pending{vStart: vS, vEnd: vM})
		} else if vS > vM {
			start = buf.correct(vM, vEnd, vS)
		}
		buf.push(pending{vStart: start, vEnd: vEnd, isCopy: true, rOffset: rM + start - vM})
		vS, vC = vEnd, vEnd
	}
	commands := buf.flush()
	if vS < len(v) {
		commands = append(commands, literal(v[vS:]))
	}

	if opts.Verbose {
		printStats(commands)
	}
	return commands
}
