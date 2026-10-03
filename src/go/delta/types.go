// Package delta implements differential compression: it encodes a version
// file as a sequence of copies from a reference file and literal adds.
//
// The differencing algorithms are those of Ajtai, Burns, Fagin, Long and
// Stockmeyer, "Compactly Encoding Unstructured Inputs with Differential
// Compression", JACM 49(3), 2002; section and figure numbers in this
// package refer to that paper unless stated otherwise. The conversion to a
// delta that can be applied in place is that of Burns, Long and Stockmeyer,
// "In-Place Reconstruction of Version Differences", IEEE TKDE 15(4), 2003.
package delta

// Algorithm parameters (Sections 2.1.2 and 2.1.3).
const (
	SeedLen      = 16            // default seed length p, in bytes
	TableSize    = 1048573       // default hash table size q: the largest prime below 2^20
	MaxTableSize = 1073741827    // default ceiling on an auto-sized table: a prime near 2^30
	HashBase     = 263           // base of the fingerprint polynomial
	HashMod      = (1 << 61) - 1 // modulus of the fingerprint polynomial: a Mersenne prime
	DeltaBufCap  = 256           // default depth of the correcting algorithm's buffer, in commands
)

// The binary delta format. All integers are big-endian.
//
// DLT\x03 has a 25-byte header and 32-bit command fields. DLT\x04 has a
// 29-byte header with a 64-bit version size, and adds 64-bit forms of the
// commands and a MOVE that copies from output already written.
const (
	DeltaMagic       = "DLT\x03"
	DeltaMagicLarge  = "DLT\x04"
	DeltaFlagInplace = byte(0x01)

	DeltaCmdEnd     = 0
	DeltaCmdCopy    = 1 // src:u32 dst:u32 len:u32
	DeltaCmdAdd     = 2 // dst:u32 len:u32 data
	DeltaCmdBigCopy = 3 // src:u64 dst:u64 len:u64; DLT\x04 only
	DeltaCmdBigAdd  = 4 // dst:u64 len:u64 data; DLT\x04 only
	DeltaCmdMove    = 5 // src:u32 dst:u32 len:u32; DLT\x04 only
	DeltaCmdBigMove = 6 // src:u64 dst:u64 len:u64; DLT\x04 only

	DeltaCrcSize         = 8  // a CRC-64/XZ digest
	DeltaHeaderSize      = 25 // magic(4) flags(1) version_size(4) src_crc(8) dst_crc(8)
	DeltaHeaderSizeLarge = 29 // magic(4) flags(1) version_size(8) src_crc(8) dst_crc(8)
	DeltaU32Size         = 4
	DeltaU64Size         = 8
	DeltaCopyPayload     = 12 // src(4) dst(4) len(4)
	DeltaAddHeader       = 8  // dst(4) len(4)
	DeltaBigCopyPayload  = 24 // src(8) dst(8) len(8)
	DeltaBigAddHeader    = 16 // dst(8) len(8)
)

// Algorithm selects a differencing algorithm.
type Algorithm int

const (
	// AlgorithmGreedy indexes every seed of R and takes the longest match
	// at each position of V, in O(|V|*|R|) time and O(|R|) space (Section 3).
	AlgorithmGreedy Algorithm = iota
	// AlgorithmOnepass scans R and V together in linear time and in space
	// set by the table size; it does not find blocks that have changed order
	// (Section 4).
	AlgorithmOnepass
	// AlgorithmCorrecting indexes checkpointed seeds of R, then scans V,
	// revising earlier commands when a later match covers them (Sections 7-8).
	AlgorithmCorrecting
)

// String returns the name of the algorithm as the command line spells it.
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

// CyclePolicy selects which copy MakeInplace converts to an add in order to
// break a cycle (Burns et al., Section 4.3).
type CyclePolicy int

const (
	// CyclePolicyLocalmin converts the shortest copy on a cycle, adding the
	// fewest literal bytes.
	CyclePolicyLocalmin CyclePolicy = iota
	// CyclePolicyConstant converts the first copy not yet scheduled, without
	// looking for a cycle.
	CyclePolicyConstant
)

// String returns the name of the policy as the command line spells it.
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

// A Command is one step of a delta as a differencing algorithm produces it
// (Section 2.1.1): a CopyCmd or an AddCmd. Commands write the version from
// left to right, so they carry no destination.
type Command interface {
	isCommand()
}

// CopyCmd copies Length bytes of the reference starting at Offset.
type CopyCmd struct {
	Offset int
	Length int
}

func (CopyCmd) isCommand() {}

// AddCmd emits the literal bytes Data.
type AddCmd struct {
	Data []byte
}

func (AddCmd) isCommand() {}

// A PlacedCommand is a command with an explicit destination, as stored in a
// delta file: a PlacedCopy, PlacedAdd or PlacedMove. Placed commands may be
// applied in any order that respects their reads and writes.
type PlacedCommand interface {
	isPlacedCommand()
	Dst() int // offset in the output at which the command writes
}

// PlacedCopy copies Length bytes from offset Src of the reference to offset
// DstOff of the output. In an in-place delta the reference and the output
// are the same buffer.
type PlacedCopy struct {
	Src    int
	DstOff int
	Length int
}

func (PlacedCopy) isPlacedCommand() {}

// Dst returns DstOff.
func (c PlacedCopy) Dst() int { return c.DstOff }

// PlacedAdd writes Data at offset DstOff of the output.
type PlacedAdd struct {
	DstOff int
	Data   []byte
}

func (PlacedAdd) isPlacedCommand() {}

// Dst returns DstOff.
func (a PlacedAdd) Dst() int { return a.DstOff }

// PlacedMove copies Length bytes from offset Src of the output to offset
// DstOff of the output. The source must already have been written:
// Src+Length <= DstOff. Only the DLT\x04 format can represent it.
type PlacedMove struct {
	Src    int
	DstOff int
	Length int
}

func (PlacedMove) isPlacedCommand() {}

// Dst returns DstOff.
func (m PlacedMove) Dst() int { return m.DstOff }

// DiffOptions holds the parameters of the differencing algorithms.
type DiffOptions struct {
	P        int  // seed length: the fingerprint window and the shortest match (Section 2.1.2)
	Q        int  // hash table size; a floor, since tables grow with the reference
	BufCap   int  // commands the correcting algorithm keeps open to revision (Section 5.2)
	Verbose  bool // print statistics to standard error
	UseSplay bool // index fingerprints in a splay tree instead of a hash table
	MaxTable int  // ceiling on the correcting algorithm's table size; <= 0 means MaxTableSize
}

// DefaultDiffOptions returns the default parameters.
func DefaultDiffOptions() DiffOptions {
	return DiffOptions{
		P:        SeedLen,
		Q:        TableSize,
		BufCap:   DeltaBufCap,
		MaxTable: MaxTableSize,
	}
}

// PlacedSummary counts the commands of a delta and the bytes they produce.
// A PlacedMove counts as a copy.
type PlacedSummary struct {
	NumCommands      int
	NumCopies        int
	NumAdds          int
	CopyBytes        int64
	AddBytes         int64
	TotalOutputBytes int64 // CopyBytes + AddBytes
}
