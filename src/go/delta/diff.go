package delta

import (
	"fmt"
	"os"
	"sort"
)

// ── Shared utilities used by all three algorithm implementations ──

// regionEquals compares len bytes of a[aOff:] and b[bOff:].
func regionEquals(a []byte, aOff int, b []byte, bOff int, length int) bool {
	for i := 0; i < length; i++ {
		if a[aOff+i] != b[bOff+i] {
			return false
		}
	}
	return true
}

// printStats writes compression statistics to stderr.
func printStats(commands []Command) {
	var copyLens []int
	var totalCopy, totalAdd int64
	numCopies, numAdds := 0, 0
	for _, cmd := range commands {
		switch c := cmd.(type) {
		case CopyCmd:
			totalCopy += int64(c.Length)
			numCopies++
			copyLens = append(copyLens, c.Length)
		case AddCmd:
			totalAdd += int64(len(c.Data))
			numAdds++
		}
	}
	totalOut := totalCopy + totalAdd
	var copyPct float64
	if totalOut > 0 {
		copyPct = float64(totalCopy) * 100.0 / float64(totalOut)
	}
	fmt.Fprintf(os.Stderr, "  result: %d copies (%d bytes), %d adds (%d bytes)\n"+
		"  result: copy coverage %.1f%%, output %d bytes\n",
		numCopies, totalCopy, numAdds, totalAdd, copyPct, totalOut)
	if len(copyLens) > 0 {
		sort.Ints(copyLens)
		mean := float64(totalCopy) / float64(len(copyLens))
		median := copyLens[len(copyLens)/2]
		fmt.Fprintf(os.Stderr, "  copies: %d regions, min=%d max=%d mean=%.1f median=%d bytes\n",
			len(copyLens), copyLens[0], copyLens[len(copyLens)-1], mean, median)
	}
}

// DiffErr dispatches to the requested differencing algorithm.
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

// Diff dispatches to the requested differencing algorithm.
//
// It returns nil for an unknown algorithm; callers that need explicit error
// handling should use DiffErr.
func Diff(algo Algorithm, r, v []byte, opts DiffOptions) []Command {
	cmds, err := DiffErr(algo, r, v, opts)
	if err != nil {
		return nil
	}
	return cmds
}

// DiffDefaultErr runs the given algorithm with default options.
func DiffDefaultErr(algo Algorithm, r, v []byte) ([]Command, error) {
	return DiffErr(algo, r, v, DefaultDiffOptions())
}

// DiffDefault runs the given algorithm with default options.
func DiffDefault(algo Algorithm, r, v []byte) []Command {
	return Diff(algo, r, v, DefaultDiffOptions())
}
