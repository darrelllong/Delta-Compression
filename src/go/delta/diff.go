package delta

import (
	"bytes"
	"fmt"
	"os"
	"sort"
)

// DiffErr computes a delta that builds v from r with the given algorithm.
// The commands reproduce v from left to right. It returns an error if algo
// is not a known algorithm.
func DiffErr(algo Algorithm, r, v []byte, opts DiffOptions) ([]Command, error) {
	switch algo {
	case AlgorithmGreedy:
		return diffGreedy(r, v, opts), nil
	case AlgorithmOnepass:
		return diffOnepass(r, v, opts), nil
	case AlgorithmCorrecting:
		return diffCorrecting(r, v, opts), nil
	default:
		return nil, fmt.Errorf("unknown algorithm: %d", int(algo))
	}
}

// Diff is like DiffErr but returns nil if algo is not a known algorithm.
func Diff(algo Algorithm, r, v []byte, opts DiffOptions) []Command {
	cmds, _ := DiffErr(algo, r, v, opts)
	return cmds
}

// DiffDefaultErr is DiffErr with DefaultDiffOptions.
func DiffDefaultErr(algo Algorithm, r, v []byte) ([]Command, error) {
	return DiffErr(algo, r, v, DefaultDiffOptions())
}

// DiffDefault is Diff with DefaultDiffOptions.
func DiffDefault(algo Algorithm, r, v []byte) []Command {
	return Diff(algo, r, v, DefaultDiffOptions())
}

// numSeeds returns the number of p-byte seeds in n bytes.
func numSeeds(n, p int) int {
	if n < p {
		return 0
	}
	return n - p + 1
}

// literal returns an add of a copy of data, so that the command does not
// alias the caller's version buffer.
func literal(data []byte) AddCmd {
	return AddCmd{Data: bytes.Clone(data)}
}

// commonPrefix returns the length of the longest common prefix of a and b.
func commonPrefix(a, b []byte) int {
	if len(b) < len(a) {
		a = a[:len(b)]
	}
	b = b[:len(a)]
	for i, c := range a {
		if b[i] != c {
			return i
		}
	}
	return len(a)
}

// commonSuffix returns the length of the longest common suffix of a and b.
func commonSuffix(a, b []byte) int {
	n := 0
	for n < len(a) && n < len(b) && a[len(a)-1-n] == b[len(b)-1-n] {
		n++
	}
	return n
}

// lookupName names the fingerprint index in verbose output.
func lookupName(useSplay bool) string {
	if useSplay {
		return "splay tree"
	}
	return "hash table"
}

// printStats writes a summary of commands to standard error.
func printStats(commands []Command) {
	var copyLens []int
	var copyBytes, addBytes int64
	numAdds := 0
	for _, cmd := range commands {
		switch c := cmd.(type) {
		case CopyCmd:
			copyBytes += int64(c.Length)
			copyLens = append(copyLens, c.Length)
		case AddCmd:
			addBytes += int64(len(c.Data))
			numAdds++
		}
	}
	total := copyBytes + addBytes
	coverage := 0.0
	if total > 0 {
		coverage = float64(copyBytes) * 100 / float64(total)
	}
	fmt.Fprintf(os.Stderr, "  result: %d copies (%d bytes), %d adds (%d bytes)\n"+
		"  result: copy coverage %.1f%%, output %d bytes\n",
		len(copyLens), copyBytes, numAdds, addBytes, coverage, total)
	if len(copyLens) == 0 {
		return
	}
	sort.Ints(copyLens)
	fmt.Fprintf(os.Stderr, "  copies: %d regions, min=%d max=%d mean=%.1f median=%d bytes\n",
		len(copyLens), copyLens[0], copyLens[len(copyLens)-1],
		float64(copyBytes)/float64(len(copyLens)), copyLens[len(copyLens)/2])
}
