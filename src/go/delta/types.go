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
	AlgorithmGreedy     Algorithm = iota
	AlgorithmOnepass
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
	CyclePolicyLocalmin CyclePolicy = iota
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

// CopyCmd instructs the decoder to copy length bytes from R starting at offset.
type CopyCmd struct {
	Offset int
	Length int
}

func (CopyCmd) isCommand() {}

// AddCmd instructs the decoder to emit literal bytes.
type AddCmd struct {
	Data []byte
}

func (AddCmd) isCommand() {}

// ── Placed commands (explicit src/dst for binary encoding) ──

// PlacedCommand is a command with explicit source and destination offsets.
// Implemented by PlacedCopy and PlacedAdd.
type PlacedCommand interface {
	isPlacedCommand()
	Dst() int
}

// PlacedCopy copies length bytes from Src to Dst in the output buffer.
type PlacedCopy struct {
	Src    int
	DstOff int
	Length int
}

func (PlacedCopy) isPlacedCommand() {}
func (c PlacedCopy) Dst() int       { return c.DstOff }

// PlacedAdd emits literal Data at DstOff in the output buffer.
type PlacedAdd struct {
	DstOff int
	Data   []byte
}

func (PlacedAdd) isPlacedCommand() {}
func (a PlacedAdd) Dst() int       { return a.DstOff }

// ── Diff options ──

// DiffOptions holds tunable parameters for diff algorithms.
type DiffOptions struct {
	P        int  // seed length
	Q        int  // hash table floor size
	BufCap   int  // correcting lookback buffer capacity
	Verbose  bool
	UseSplay bool
	MaxTable int  // auto-size ceiling
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
	NumCommands    int
	NumCopies      int
	NumAdds        int
	CopyBytes      int64
	AddBytes       int64
	TotalOutputBytes int64
}
