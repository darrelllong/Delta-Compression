// Package delta implements differential compression (Ajtai et al. 2002).
package delta

// Constants (Section 2.1).
const (
	SeedLen      = 16
	TableSize    = 1048573       // largest prime < 2^20
	MaxTableSize = 1073741827    // prime near 2^30; default ceiling for auto-sizing
	HashBase     = 263
	HashMod      = (1 << 61) - 1 // Mersenne prime 2^61-1

	// Binary delta format constants.
	DeltaMagic       = "DLT\x03"
	DeltaFlagInplace = byte(0x01)
	DeltaCmdEnd      = 0
	DeltaCmdCopy     = 1
	DeltaCmdAdd      = 2
	DeltaCrcSize     = 8  // CRC-64/XZ digest bytes
	DeltaHeaderSize  = 25 // magic(4)+flags(1)+version_size(4)+src_crc(8)+dst_crc(8)
	DeltaU32Size     = 4
	DeltaCopyPayload = 12 // src(4)+dst(4)+len(4)
	DeltaAddHeader   = 8  // dst(4)+len(4)
	DeltaBufCap      = 256
)

// Algorithm selects the differencing algorithm.
type Algorithm int

const (
	// AlgorithmGreedy is optimal under simple cost; O(|V|·|R|) time, O(|R|) space (Section 3).
	AlgorithmGreedy Algorithm = iota
	// AlgorithmOnepass uses linear time and near-constant space; concurrent scan of R and V (Section 4).
	AlgorithmOnepass
	// AlgorithmCorrecting is near-optimal, 1.5-pass; hash table with fingerprint checkpointing (Sections 7–8).
	AlgorithmCorrecting
)

func (a Algorithm) String() string {
	switch a {
	case AlgorithmGreedy:
		return "greedy"
	case AlgorithmOnepass:
		return "onepass"
	case AlgorithmCorrecting:
		return "correcting"
	default:
		return "unknown"
	}
}

// CyclePolicy selects the in-place cycle-breaking strategy.
type CyclePolicy int

const (
	// CyclePolicyLocalmin breaks each cycle at the copy with the shortest length, minimising literal bytes added.
	CyclePolicyLocalmin CyclePolicy = iota
	// CyclePolicyConstant breaks each cycle at the first remaining vertex; simpler but ignores copy lengths.
	CyclePolicyConstant
)

func (p CyclePolicy) String() string {
	switch p {
	case CyclePolicyLocalmin:
		return "localmin"
	case CyclePolicyConstant:
		return "constant"
	default:
		return "unknown"
	}
}

// ── Algorithm-level commands (offset into R, no destination yet) ──

// Command is an algorithm-level diff instruction.
// Implemented by CopyCmd and AddCmd.
type Command interface {
	isCommand()
}

// CopyCmd instructs the decoder to copy Length bytes from R starting at Offset.
type CopyCmd struct {
	Offset int // Byte offset of the match in R.
	Length int // Number of bytes to copy.
}

func (CopyCmd) isCommand() {}

// AddCmd instructs the decoder to emit literal bytes.
type AddCmd struct {
	Data []byte // The literal bytes to append.
}

func (AddCmd) isCommand() {}

// ── Placed commands (explicit src/dst for binary encoding) ──

// PlacedCommand is a command with explicit source and destination offsets.
// Implemented by PlacedCopy and PlacedAdd.
type PlacedCommand interface {
	isPlacedCommand()
	Dst() int
}

// PlacedCopy copies Length bytes from Src in R (or the working buffer) to DstOff in the output.
type PlacedCopy struct {
	Src    int // Source byte offset in the reference (or working buffer for in-place).
	DstOff int // Destination byte offset in the output.
	Length int // Number of bytes to copy.
}

func (PlacedCopy) isPlacedCommand() {}
func (c PlacedCopy) Dst() int       { return c.DstOff }

// PlacedAdd writes literal Data to DstOff in the output buffer.
type PlacedAdd struct {
	DstOff int    // Destination byte offset in the output.
	Data   []byte // The literal bytes to write.
}

func (PlacedAdd) isPlacedCommand() {}
func (a PlacedAdd) Dst() int       { return a.DstOff }

// ── Diff options ──

// DiffOptions holds tunable parameters for diff algorithms.
type DiffOptions struct {
	P        int  // Seed length: minimum match length and fingerprint window (Section 2.1.3).
	Q        int  // Hash table capacity floor; algorithms auto-size upward from input length.
	BufCap   int  // Lookback buffer depth for the correcting algorithm (Section 5.2).
	Verbose  bool // Print per-run statistics to stderr when true.
	UseSplay bool // Use a Sleator-Tarjan splay tree instead of a hash table for R lookups.
	MaxTable int  // Auto-sizing ceiling; prevents unbounded memory use on very large inputs.
}

// DefaultDiffOptions returns options with library defaults.
func DefaultDiffOptions() DiffOptions {
	return DiffOptions{
		P:        SeedLen,
		Q:        TableSize,
		BufCap:   DeltaBufCap,
		MaxTable: MaxTableSize,
	}
}

// ── Summary statistics ──

// PlacedSummary holds statistics for a list of placed commands.
type PlacedSummary struct {
	NumCommands      int   // Total number of commands (copies + adds).
	NumCopies        int   // Number of COPY commands.
	NumAdds          int   // Number of ADD commands.
	CopyBytes        int64 // Total bytes reproduced by COPY commands.
	AddBytes         int64 // Total literal bytes in ADD commands.
	TotalOutputBytes int64 // Reconstructed output size (= CopyBytes + AddBytes).
}
