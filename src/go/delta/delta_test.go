package delta

import (
	"bytes"
	"math"
	"math/rand"
	"os"
	"testing"
)

// ── helpers ──────────────────────────────────────────────────────────────────

var allAlgos = []Algorithm{AlgorithmGreedy, AlgorithmOnepass, AlgorithmCorrecting}
var allPolicies = []CyclePolicy{CyclePolicyLocalmin, CyclePolicyConstant}

var zeroHash [8]byte

// mustEncode calls EncodeDelta and panics on error. Test inputs are always
// well below the 4 GiB format limit, so errors here indicate a code bug.
func mustEncode(t *testing.T, commands []PlacedCommand, inplace bool, versionSize int, srcCrc, dstCrc [8]byte) []byte {
	t.Helper()
	out, err := EncodeDelta(commands, inplace, versionSize, srcCrc, dstCrc)
	if err != nil {
		t.Fatalf("EncodeDelta: %v", err)
	}
	return out
}

func opts(p int) DiffOptions {
	o := DefaultDiffOptions()
	o.P = p
	return o
}

func concat(parts ...[]byte) []byte {
	n := 0
	for _, p := range parts {
		n += len(p)
	}
	out := make([]byte, 0, n)
	for _, p := range parts {
		out = append(out, p...)
	}
	return out
}

func repeat(data []byte, n int) []byte {
	out := make([]byte, len(data)*n)
	for i := 0; i < n; i++ {
		copy(out[i*len(data):], data)
	}
	return out
}

func b(s string) []byte { return []byte(s) }

// roundtrip: diff → place → encode → decode → applyPlacedTo.
func roundtrip(t *testing.T, algo Algorithm, r, v []byte, p int) []byte {
	t.Helper()
	cmds := Diff(algo, r, v, opts(p))
	placed := PlaceCommands(cmds)
	delta := mustEncode(t, placed, false, OutputSize(cmds), zeroHash, zeroHash)
	res, err := DecodeDelta(delta)
	if err != nil {
		t.Fatalf("DecodeDelta: %v", err)
	}
	out := make([]byte, res.VersionSize)
	ApplyPlacedTo(r, res.Commands, out)
	return out
}

// inplaceRoundtrip: diff → makeInplace → applyDeltaInplace (no binary I/O).
func inplaceRoundtrip(t *testing.T, algo Algorithm, r, v []byte, pol CyclePolicy, p int) []byte {
	t.Helper()
	cmds := Diff(algo, r, v, opts(p))
	ip := MakeInplace(r, cmds, pol)
	return ApplyDeltaInplace(r, ip, len(v))
}

// inplaceBinaryRoundtrip: diff → makeInplace → encode → decode → applyDeltaInplace.
func inplaceBinaryRoundtrip(t *testing.T, algo Algorithm, r, v []byte, pol CyclePolicy, p int) []byte {
	t.Helper()
	cmds := Diff(algo, r, v, opts(p))
	ip := MakeInplace(r, cmds, pol)
	delta := mustEncode(t, ip, true, len(v), zeroHash, zeroHash)
	res, err := DecodeDelta(delta)
	if err != nil {
		t.Fatalf("DecodeDelta: %v", err)
	}
	return ApplyDeltaInplace(r, res.Commands, res.VersionSize)
}

// viaInplaceSubcommand: encode standard → decode → unplace → makeInplace → encode(inplace).
func viaInplaceSubcommand(t *testing.T, algo Algorithm, r, v []byte, pol CyclePolicy, p int) []byte {
	t.Helper()
	cmds := Diff(algo, r, v, opts(p))
	placed := PlaceCommands(cmds)
	standard := mustEncode(t, placed, false, len(v), zeroHash, zeroHash)
	res, err := DecodeDelta(standard)
	if err != nil {
		t.Fatalf("DecodeDelta: %v", err)
	}
	if res.Inplace {
		t.Fatal("standard delta should not be flagged as inplace")
	}
	cmds2 := UnplaceCommands(res.Commands)
	ip := MakeInplace(r, cmds2, pol)
	return mustEncode(t, ip, true, res.VersionSize, zeroHash, zeroHash)
}

// makeBlocks returns eight variable-length blocks with deterministic byte patterns.
func makeBlocks() [][]byte {
	sizes := []int{200, 500, 1234, 3000, 800, 4999, 1500, 2750}
	blocks := make([][]byte, len(sizes))
	for i, sz := range sizes {
		blk := make([]byte, sz)
		for j := 0; j < sz; j++ {
			blk[j] = byte((i*37 + j) & 0xFF)
		}
		blocks[i] = blk
	}
	return blocks
}

func blocksRef(blocks [][]byte) []byte { return concat(blocks...) }

func shuffle(arr []int, rng *rand.Rand) {
	for i := len(arr) - 1; i > 0; i-- {
		j := rng.Intn(i + 1)
		arr[i], arr[j] = arr[j], arr[i]
	}
}

// ── standard differencing ─────────────────────────────────────────────────

func TestPaperExample(t *testing.T) {
	r := b("ABCDEFGHIJKLMNOP")
	v := b("QWIJKLMNOBCDEFGHZDEFGHIJKL")
	for _, algo := range allAlgos {
		t.Run(algo.String(), func(t *testing.T) {
			cmds := Diff(algo, r, v, opts(2))
			got := ApplyDelta(r, cmds)
			if !bytes.Equal(v, got) {
				t.Fatalf("paper example: got %d bytes, want %d", len(got), len(v))
			}
		})
	}
}

func TestIdentical(t *testing.T) {
	data := repeat(b("The quick brown fox jumps over the lazy dog."), 10)
	for _, algo := range allAlgos {
		t.Run(algo.String(), func(t *testing.T) {
			cmds := Diff(algo, data, data, opts(2))
			got := ApplyDelta(data, cmds)
			if !bytes.Equal(data, got) {
				t.Fatal("identical roundtrip failed")
			}
			for _, cmd := range cmds {
				if _, ok := cmd.(AddCmd); ok {
					t.Fatal("identical input should produce no adds")
				}
			}
		})
	}
}

func TestCompletelyDifferent(t *testing.T) {
	r := make([]byte, 512)
	v := make([]byte, 512)
	for i := 0; i < 512; i++ {
		r[i] = byte(i & 0xFF)
		v[i] = byte((255 - (i & 0xFF)) & 0xFF)
	}
	for _, algo := range allAlgos {
		t.Run(algo.String(), func(t *testing.T) {
			got := ApplyDelta(r, Diff(algo, r, v, opts(2)))
			if !bytes.Equal(v, got) {
				t.Fatal("completely different: roundtrip failed")
			}
		})
	}
}

func TestEmptyVersion(t *testing.T) {
	r := b("hello")
	for _, algo := range allAlgos {
		t.Run(algo.String(), func(t *testing.T) {
			cmds := Diff(algo, r, nil, opts(2))
			if len(cmds) != 0 {
				t.Fatal("empty version should produce no commands")
			}
			if got := ApplyDelta(r, cmds); len(got) != 0 {
				t.Fatal("empty version apply should return empty")
			}
		})
	}
}

func TestEmptyReference(t *testing.T) {
	v := b("hello world")
	for _, algo := range allAlgos {
		t.Run(algo.String(), func(t *testing.T) {
			got := ApplyDelta(nil, Diff(algo, nil, v, opts(2)))
			if !bytes.Equal(v, got) {
				t.Fatal("empty reference roundtrip failed")
			}
		})
	}
}

func TestBinaryRoundtrip(t *testing.T) {
	r := repeat(b("ABCDEFGHIJKLMNOPQRSTUVWXYZ"), 100)
	v := repeat(b("0123EFGHIJKLMNOPQRS456ABCDEFGHIJKL789"), 100)
	for _, algo := range allAlgos {
		t.Run(algo.String(), func(t *testing.T) {
			got := roundtrip(t, algo, r, v, 4)
			if !bytes.Equal(v, got) {
				t.Fatal("binary roundtrip failed")
			}
		})
	}
}

func TestBinaryEncodingRoundtrip(t *testing.T) {
	placed := []PlacedCommand{
		PlacedAdd{DstOff: 0, Data: []byte{100, 101, 102}},
		PlacedCopy{Src: 888, DstOff: 3, Length: 488},
	}
	encoded := mustEncode(t, placed, false, 491, zeroHash, zeroHash)
	res, err := DecodeDelta(encoded)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if res.Inplace {
		t.Fatal("should not be inplace")
	}
	if res.VersionSize != 491 {
		t.Fatalf("version size: got %d, want 491", res.VersionSize)
	}
	if len(res.Commands) != 2 {
		t.Fatalf("command count: got %d, want 2", len(res.Commands))
	}
	a := res.Commands[0].(PlacedAdd)
	if a.DstOff != 0 || !bytes.Equal(a.Data, []byte{100, 101, 102}) {
		t.Fatal("add command fields wrong")
	}
	c := res.Commands[1].(PlacedCopy)
	if c.Src != 888 || c.DstOff != 3 || c.Length != 488 {
		t.Fatal("copy command fields wrong")
	}
}

func TestBinaryEncodingInplaceFlag(t *testing.T) {
	placed := []PlacedCommand{PlacedCopy{Src: 0, DstOff: 10, Length: 5}}
	standard := mustEncode(t, placed, false, 15, zeroHash, zeroHash)
	inplace := mustEncode(t, placed, true, 15, zeroHash, zeroHash)
	if IsInplaceDelta(standard) {
		t.Fatal("standard should not be inplace")
	}
	if !IsInplaceDelta(inplace) {
		t.Fatal("inplace should be detected")
	}
	r1, _ := DecodeDelta(standard)
	r2, _ := DecodeDelta(inplace)
	if r1.Inplace {
		t.Fatal("standard decoded inplace flag wrong")
	}
	if !r2.Inplace {
		t.Fatal("inplace decoded inplace flag wrong")
	}
	if r1.VersionSize != r2.VersionSize {
		t.Fatal("version sizes differ")
	}
}

func TestDecodeRejectsMissingEnd(t *testing.T) {
	encoded := mustEncode(t, nil, false, 0, zeroHash, zeroHash)
	_, err := DecodeDelta(encoded[:len(encoded)-1])
	if err == nil || err.Error() != "missing END command" {
		t.Fatalf("got %v, want missing END command", err)
	}
}

func TestDecodeRejectsTrailingData(t *testing.T) {
	encoded := append(mustEncode(t, nil, false, 0, zeroHash, zeroHash), 0x7f)
	_, err := DecodeDelta(encoded)
	if err == nil || err.Error() != "trailing data after END" {
		t.Fatalf("got %v, want trailing data after END", err)
	}
}

func TestDecodeRejectsCopyPastVersionSize(t *testing.T) {
	encoded := mustEncode(t, []PlacedCommand{
		PlacedCopy{Src: 0, DstOff: 1, Length: 2},
	}, false, 2, zeroHash, zeroHash)
	_, err := DecodeDelta(encoded)
	if err == nil || err.Error() != "copy command exceeds version size" {
		t.Fatalf("got %v, want copy command exceeds version size", err)
	}
}

func TestValidatePlacedCommandsRejectsSourceOverflow(t *testing.T) {
	err := ValidatePlacedCommands([]PlacedCommand{
		PlacedCopy{Src: 1, DstOff: 0, Length: 2},
	}, 2, 2, false)
	if err == nil || err.Error() != "copy source out of range" {
		t.Fatalf("got %v, want copy source out of range", err)
	}
}

func TestDiffErrRejectsUnknownAlgorithm(t *testing.T) {
	_, err := DiffErr(Algorithm(99), b("abc"), b("xyz"), DefaultDiffOptions())
	if err == nil || err.Error() != "unknown algorithm: 99" {
		t.Fatalf("got %v, want unknown algorithm error", err)
	}
	if cmds := Diff(Algorithm(99), b("abc"), b("xyz"), DefaultDiffOptions()); cmds != nil {
		t.Fatalf("Diff should return nil for unknown algorithm, got %v", cmds)
	}
}

func TestRealDataRoundtrip(t *testing.T) {
	r, err := os.ReadFile("../../../README.md")
	if err != nil {
		t.Fatalf("read README: %v", err)
	}
	v, err := os.ReadFile("../../../HOWTO.md")
	if err != nil {
		t.Fatalf("read HOWTO: %v", err)
	}
	for _, algo := range allAlgos {
		t.Run(algo.String(), func(t *testing.T) {
			got := roundtrip(t, algo, r, v, 8)
			if !bytes.Equal(v, got) {
				t.Fatal("real-data roundtrip failed")
			}
		})
	}
}

func TestBigPayloadCopyRoundtrip(t *testing.T) {
	placed := []PlacedCommand{PlacedCopy{Src: 100_000, DstOff: 0, Length: 50_000}}
	encoded := mustEncode(t, placed, false, 50_000, zeroHash, zeroHash)
	res, _ := DecodeDelta(encoded)
	if len(res.Commands) != 1 {
		t.Fatalf("command count: got %d", len(res.Commands))
	}
	c := res.Commands[0].(PlacedCopy)
	if c.Src != 100_000 || c.DstOff != 0 || c.Length != 50_000 {
		t.Fatal("copy fields wrong")
	}
}

func TestBigPayloadAddRoundtrip(t *testing.T) {
	bigData := make([]byte, 256*4)
	for i := range bigData {
		bigData[i] = byte(i & 0xFF)
	}
	placed := []PlacedCommand{PlacedAdd{DstOff: 0, Data: bigData}}
	encoded := mustEncode(t, placed, false, len(bigData), zeroHash, zeroHash)
	res, _ := DecodeDelta(encoded)
	if len(res.Commands) != 1 {
		t.Fatalf("command count: got %d", len(res.Commands))
	}
	a := res.Commands[0].(PlacedAdd)
	if a.DstOff != 0 || !bytes.Equal(a.Data, bigData) {
		t.Fatal("add fields wrong")
	}
}

func TestBackwardExtension(t *testing.T) {
	block := repeat(b("ABCDEFGHIJKLMNOP"), 20)
	r := concat(b("____"), block, b("____"))
	v := concat(b("**"), block, b("**"))
	for _, algo := range allAlgos {
		t.Run(algo.String(), func(t *testing.T) {
			got := ApplyDelta(r, Diff(algo, r, v, opts(4)))
			if !bytes.Equal(v, got) {
				t.Fatal("backward extension failed")
			}
		})
	}
}

func TestTransposition(t *testing.T) {
	x := repeat(b("FIRST_BLOCK_DATA_"), 10)
	y := repeat(b("SECOND_BLOCK_DATA"), 10)
	r := concat(x, y)
	v := concat(y, x)
	for _, algo := range allAlgos {
		t.Run(algo.String(), func(t *testing.T) {
			got := ApplyDelta(r, Diff(algo, r, v, opts(4)))
			if !bytes.Equal(v, got) {
				t.Fatal("transposition failed")
			}
		})
	}
}

func TestScatteredModifications(t *testing.T) {
	rng := rand.New(rand.NewSource(42))
	r := make([]byte, 2000)
	rng.Read(r)
	v := make([]byte, len(r))
	copy(v, r)
	for i := 0; i < 100; i++ {
		v[rng.Intn(len(v))] = byte(rng.Intn(256))
	}
	for _, algo := range allAlgos {
		t.Run(algo.String(), func(t *testing.T) {
			got := roundtrip(t, algo, r, v, 4)
			if !bytes.Equal(v, got) {
				t.Fatal("scattered modifications failed")
			}
		})
	}
}

// ── in-place basics ──────────────────────────────────────────────────────────

func TestInplacePaperExample(t *testing.T) {
	r := b("ABCDEFGHIJKLMNOP")
	v := b("QWIJKLMNOBCDEFGHZDEFGHIJKL")
	for _, algo := range allAlgos {
		for _, pol := range allPolicies {
			t.Run(algo.String()+"/"+pol.String(), func(t *testing.T) {
				got := inplaceRoundtrip(t, algo, r, v, pol, 2)
				if !bytes.Equal(v, got) {
					t.Fatal("inplace paper example failed")
				}
			})
		}
	}
}

func TestInplaceBinaryRoundtrip(t *testing.T) {
	r := repeat(b("ABCDEFGHIJKLMNOPQRSTUVWXYZ"), 100)
	v := repeat(b("0123EFGHIJKLMNOPQRS456ABCDEFGHIJKL789"), 100)
	for _, algo := range allAlgos {
		for _, pol := range allPolicies {
			t.Run(algo.String()+"/"+pol.String(), func(t *testing.T) {
				got := inplaceBinaryRoundtrip(t, algo, r, v, pol, 4)
				if !bytes.Equal(v, got) {
					t.Fatal("inplace binary roundtrip failed")
				}
			})
		}
	}
}

func TestInplaceSimpleTransposition(t *testing.T) {
	x := repeat(b("FIRST_BLOCK_DATA_"), 20)
	y := repeat(b("SECOND_BLOCK_DATA"), 20)
	r := concat(x, y)
	v := concat(y, x)
	for _, algo := range allAlgos {
		for _, pol := range allPolicies {
			t.Run(algo.String()+"/"+pol.String(), func(t *testing.T) {
				got := inplaceRoundtrip(t, algo, r, v, pol, 4)
				if !bytes.Equal(v, got) {
					t.Fatal("inplace simple transposition failed")
				}
			})
		}
	}
}

func TestInplaceVersionLarger(t *testing.T) {
	r := repeat(b("ABCDEFGH"), 50)
	v := concat(repeat(b("XXABCDEFGH"), 50), repeat(b("YYABCDEFGH"), 50))
	for _, algo := range allAlgos {
		for _, pol := range allPolicies {
			t.Run(algo.String()+"/"+pol.String(), func(t *testing.T) {
				got := inplaceRoundtrip(t, algo, r, v, pol, 4)
				if !bytes.Equal(v, got) {
					t.Fatal("inplace version larger failed")
				}
			})
		}
	}
}

func TestInplaceVersionSmaller(t *testing.T) {
	r := repeat(b("ABCDEFGHIJKLMNOP"), 100)
	v := repeat(b("EFGHIJKL"), 50)
	for _, algo := range allAlgos {
		for _, pol := range allPolicies {
			t.Run(algo.String()+"/"+pol.String(), func(t *testing.T) {
				got := inplaceRoundtrip(t, algo, r, v, pol, 4)
				if !bytes.Equal(v, got) {
					t.Fatal("inplace version smaller failed")
				}
			})
		}
	}
}

func TestInplaceIdentical(t *testing.T) {
	data := repeat(b("The quick brown fox jumps over the lazy dog."), 10)
	for _, algo := range allAlgos {
		for _, pol := range allPolicies {
			t.Run(algo.String()+"/"+pol.String(), func(t *testing.T) {
				got := inplaceRoundtrip(t, algo, data, data, pol, 2)
				if !bytes.Equal(data, got) {
					t.Fatal("inplace identical failed")
				}
			})
		}
	}
}

func TestInplaceEmptyVersion(t *testing.T) {
	r := b("hello")
	for _, algo := range allAlgos {
		t.Run(algo.String(), func(t *testing.T) {
			cmds := Diff(algo, r, nil, opts(2))
			ip := MakeInplace(r, cmds, CyclePolicyLocalmin)
			got := ApplyDeltaInplace(r, ip, 0)
			if len(got) != 0 {
				t.Fatal("inplace empty version should return empty")
			}
		})
	}
}

func TestInplaceScattered(t *testing.T) {
	rng := rand.New(rand.NewSource(99))
	r := make([]byte, 2000)
	rng.Read(r)
	v := make([]byte, len(r))
	copy(v, r)
	for i := 0; i < 100; i++ {
		v[rng.Intn(len(v))] = byte(rng.Intn(256))
	}
	for _, algo := range allAlgos {
		for _, pol := range allPolicies {
			t.Run(algo.String()+"/"+pol.String(), func(t *testing.T) {
				got := inplaceBinaryRoundtrip(t, algo, r, v, pol, 4)
				if !bytes.Equal(v, got) {
					t.Fatal("inplace scattered failed")
				}
			})
		}
	}
}

func TestStandardNotDetectedAsInplace(t *testing.T) {
	r := repeat(b("ABCDEFGH"), 10)
	v := repeat(b("EFGHABCD"), 10)
	cmds := Diff(AlgorithmGreedy, r, v, opts(2))
	placed := PlaceCommands(cmds)
	delta := mustEncode(t, placed, false, len(v), zeroHash, zeroHash)
	if IsInplaceDelta(delta) {
		t.Fatal("standard should not be detected as inplace")
	}
}

func TestInplaceDetected(t *testing.T) {
	r := repeat(b("ABCDEFGH"), 10)
	v := repeat(b("EFGHABCD"), 10)
	cmds := Diff(AlgorithmGreedy, r, v, opts(2))
	ip := MakeInplace(r, cmds, CyclePolicyLocalmin)
	delta := mustEncode(t, ip, true, len(v), zeroHash, zeroHash)
	if !IsInplaceDelta(delta) {
		t.Fatal("inplace delta should be detected")
	}
}

// ── in-place variable-length blocks ──────────────────────────────────────────

func TestInplaceVarlenPermutation(t *testing.T) {
	blocks := makeBlocks()
	r := blocksRef(blocks)
	rng := rand.New(rand.NewSource(2003))
	perm := []int{0, 1, 2, 3, 4, 5, 6, 7}
	shuffle(perm, rng)
	parts := make([][]byte, len(perm))
	for i, pi := range perm {
		parts[i] = blocks[pi]
	}
	v := concat(parts...)
	for _, algo := range allAlgos {
		for _, pol := range allPolicies {
			t.Run(algo.String()+"/"+pol.String(), func(t *testing.T) {
				got := inplaceRoundtrip(t, algo, r, v, pol, 4)
				if !bytes.Equal(v, got) {
					t.Fatal("varlen permutation failed")
				}
			})
		}
	}
}

func TestInplaceVarlenReverse(t *testing.T) {
	blocks := makeBlocks()
	r := blocksRef(blocks)
	rev := make([][]byte, len(blocks))
	for i, blk := range blocks {
		rev[len(blocks)-1-i] = blk
	}
	v := concat(rev...)
	for _, algo := range allAlgos {
		for _, pol := range allPolicies {
			t.Run(algo.String()+"/"+pol.String(), func(t *testing.T) {
				got := inplaceRoundtrip(t, algo, r, v, pol, 4)
				if !bytes.Equal(v, got) {
					t.Fatal("varlen reverse failed")
				}
			})
		}
	}
}

func TestInplaceVarlenJunk(t *testing.T) {
	blocks := makeBlocks()
	r := blocksRef(blocks)
	rng := rand.New(rand.NewSource(20030))
	junk := make([]byte, 300)
	rng.Read(junk)
	perm := []int{0, 1, 2, 3, 4, 5, 6, 7}
	shuffle(perm, rng)
	var parts [][]byte
	for _, pi := range perm {
		parts = append(parts, blocks[pi])
		junkLen := 50 + rng.Intn(251)
		if junkLen > len(junk) {
			junkLen = len(junk)
		}
		junkCopy := make([]byte, junkLen)
		copy(junkCopy, junk[:junkLen])
		parts = append(parts, junkCopy)
	}
	v := concat(parts...)
	for _, algo := range allAlgos {
		for _, pol := range allPolicies {
			t.Run(algo.String()+"/"+pol.String(), func(t *testing.T) {
				got := inplaceRoundtrip(t, algo, r, v, pol, 4)
				if !bytes.Equal(v, got) {
					t.Fatal("varlen junk failed")
				}
			})
		}
	}
}

func TestInplaceVarlenDropDup(t *testing.T) {
	blocks := makeBlocks()
	r := blocksRef(blocks)
	v := concat(blocks[3], blocks[0], blocks[0], blocks[5], blocks[3])
	for _, algo := range allAlgos {
		for _, pol := range allPolicies {
			t.Run(algo.String()+"/"+pol.String(), func(t *testing.T) {
				got := inplaceRoundtrip(t, algo, r, v, pol, 4)
				if !bytes.Equal(v, got) {
					t.Fatal("varlen drop+dup failed")
				}
			})
		}
	}
}

func TestInplaceVarlenDoubleSized(t *testing.T) {
	blocks := makeBlocks()
	r := blocksRef(blocks)
	rng := rand.New(rand.NewSource(7001))
	p1 := []int{0, 1, 2, 3, 4, 5, 6, 7}
	p2 := []int{0, 1, 2, 3, 4, 5, 6, 7}
	shuffle(p1, rng)
	shuffle(p2, rng)
	var parts [][]byte
	for _, i := range p1 {
		parts = append(parts, blocks[i])
	}
	for _, i := range p2 {
		parts = append(parts, blocks[i])
	}
	v := concat(parts...)
	for _, algo := range allAlgos {
		for _, pol := range allPolicies {
			t.Run(algo.String()+"/"+pol.String(), func(t *testing.T) {
				got := inplaceRoundtrip(t, algo, r, v, pol, 4)
				if !bytes.Equal(v, got) {
					t.Fatal("varlen double-sized failed")
				}
			})
		}
	}
}

func TestInplaceVarlenSubset(t *testing.T) {
	blocks := makeBlocks()
	r := blocksRef(blocks)
	v := concat(blocks[6], blocks[2])
	for _, algo := range allAlgos {
		for _, pol := range allPolicies {
			t.Run(algo.String()+"/"+pol.String(), func(t *testing.T) {
				got := inplaceRoundtrip(t, algo, r, v, pol, 4)
				if !bytes.Equal(v, got) {
					t.Fatal("varlen subset failed")
				}
			})
		}
	}
}

func TestInplaceVarlenHalfBlockScramble(t *testing.T) {
	blocks := makeBlocks()
	r := blocksRef(blocks)
	var halves [][]byte
	for _, blk := range blocks {
		mid := len(blk) / 2
		halves = append(halves, blk[:mid])
		halves = append(halves, blk[mid:])
	}
	rng := rand.New(rand.NewSource(5555))
	perm := make([]int, len(halves))
	for i := range perm {
		perm[i] = i
	}
	shuffle(perm, rng)
	parts := make([][]byte, len(perm))
	for i, pi := range perm {
		parts[i] = halves[pi]
	}
	v := concat(parts...)
	for _, algo := range allAlgos {
		for _, pol := range allPolicies {
			t.Run(algo.String()+"/"+pol.String()+"/direct", func(t *testing.T) {
				got := inplaceRoundtrip(t, algo, r, v, pol, 4)
				if !bytes.Equal(v, got) {
					t.Fatal("half-block scramble (direct) failed")
				}
			})
			t.Run(algo.String()+"/"+pol.String()+"/binary", func(t *testing.T) {
				got := inplaceBinaryRoundtrip(t, algo, r, v, pol, 4)
				if !bytes.Equal(v, got) {
					t.Fatal("half-block scramble (binary) failed")
				}
			})
		}
	}
}

func TestInplaceVarlenRandomTrials(t *testing.T) {
	blocks := makeBlocks()
	r := blocksRef(blocks)
	rng := rand.New(rand.NewSource(9999))
	trials := make([][]int, 20)
	for tr := 0; tr < 20; tr++ {
		k := 3 + rng.Intn(6)
		indices := []int{0, 1, 2, 3, 4, 5, 6, 7}
		shuffle(indices, rng)
		trial := make([]int, k)
		copy(trial, indices[:k])
		shuffle(trial, rng)
		trials[tr] = trial
	}
	for _, algo := range allAlgos {
		for _, pol := range allPolicies {
			for tr := 0; tr < 20; tr++ {
				parts := make([][]byte, len(trials[tr]))
				for i, idx := range trials[tr] {
					parts[i] = blocks[idx]
				}
				v := concat(parts...)
				t.Run(algo.String()+"/"+pol.String(), func(t *testing.T) {
					got := inplaceRoundtrip(t, algo, r, v, pol, 4)
					if !bytes.Equal(v, got) {
						t.Fatalf("random trial %d failed", tr)
					}
				})
			}
		}
	}
}

// ── cycle policy ──────────────────────────────────────────────────────────────

func TestLocalminPicksSmallest(t *testing.T) {
	blocks := makeBlocks()
	r := blocksRef(blocks)
	rev := make([][]byte, len(blocks))
	for i, blk := range blocks {
		rev[len(blocks)-1-i] = blk
	}
	v := concat(rev...)
	cmds := Diff(AlgorithmGreedy, r, v, opts(4))
	ipConst := MakeInplace(r, cmds, CyclePolicyConstant)
	ipLmin := MakeInplace(r, cmds, CyclePolicyLocalmin)

	var addConst, addLmin int64
	for _, cmd := range ipConst {
		if a, ok := cmd.(PlacedAdd); ok {
			addConst += int64(len(a.Data))
		}
	}
	for _, cmd := range ipLmin {
		if a, ok := cmd.(PlacedAdd); ok {
			addLmin += int64(len(a.Data))
		}
	}
	if addLmin > addConst {
		t.Fatalf("localmin (%d) should produce <= add bytes as constant (%d)", addLmin, addConst)
	}
}

// ── checkpointing ─────────────────────────────────────────────────────────────

func TestCorrectingCheckpointingTinyTable(t *testing.T) {
	r := repeat(b("ABCDEFGHIJKLMNOP"), 20)
	v := concat(r[:160], b("XXXXYYYY"), r[160:])
	o := DefaultDiffOptions()
	o.P = 16
	o.Q = 7
	cmds := Diff(AlgorithmCorrecting, r, v, o)
	got := ApplyDelta(r, cmds)
	if !bytes.Equal(v, got) {
		t.Fatal("correcting q=7 tiny table failed")
	}
}

func TestCorrectingCheckpointingVariousSizes(t *testing.T) {
	r := make([]byte, 2000)
	for i := range r {
		r[i] = byte(i & 0xFF)
	}
	v := make([]byte, 2050)
	copy(v, r[:500])
	for i := 500; i < 550; i++ {
		v[i] = 0xFF
	}
	copy(v[550:], r[500:])
	for _, q := range []int{7, 31, 101, 1009, TableSize} {
		t.Run("q="+itoa(q), func(t *testing.T) {
			o := DefaultDiffOptions()
			o.P = 16
			o.Q = q
			cmds := Diff(AlgorithmCorrecting, r, v, o)
			got := ApplyDelta(r, cmds)
			if !bytes.Equal(v, got) {
				t.Fatalf("correcting q=%d failed", q)
			}
		})
	}
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	buf := make([]byte, 0, 10)
	for n > 0 {
		buf = append([]byte{byte('0' + n%10)}, buf...)
		n /= 10
	}
	return string(buf)
}

// ── CRC-64/XZ ────────────────────────────────────────────────────────────────

func TestCrc64Empty(t *testing.T) {
	got := Crc64XZ(nil)
	if got != [8]byte{} {
		t.Fatalf("CRC64 empty: got %x, want 0", got)
	}
}

func TestCrc64CheckValue(t *testing.T) {
	// CRC-64/XZ of "123456789" = 0x995DC9BBDF1939FA.
	want := [8]byte{0x99, 0x5D, 0xC9, 0xBB, 0xDF, 0x19, 0x39, 0xFA}
	got := Crc64XZ([]byte("123456789"))
	if got != want {
		t.Fatalf("CRC64 check value: got %x, want %x", got, want)
	}
}

// ── primality ────────────────────────────────────────────────────────────────

func TestNextPrimeIsPrime(t *testing.T) {
	if !IsPrime(TableSize) {
		t.Fatal("TABLE_SIZE should be prime")
	}
	if !IsPrime(NextPrime(1048574)) {
		t.Fatal("nextPrime(1048574) should be prime")
	}
	if NextPrime(1048573) != 1048573 {
		t.Fatal("nextPrime of a prime should be itself")
	}
}

// ── inplace subcommand path ───────────────────────────────────────────────────

func TestInplaceSubcommandRoundtrip(t *testing.T) {
	rs := [][]byte{b("ABCDEF"), b("AAABBBCCC"), b("the quick brown fox"),
		b("ABCDEF"), b("hello world"), {}}
	vs := [][]byte{b("FEDCBA"), b("CCCBBBAAA"), b("the quick brown cat"),
		b("ABCDEF"), {}, b("hello world")}
	for i := range rs {
		r, v := rs[i], vs[i]
		for _, algo := range allAlgos {
			for _, pol := range allPolicies {
				t.Run(algo.String()+"/"+pol.String(), func(t *testing.T) {
					ipDelta := viaInplaceSubcommand(t, algo, r, v, pol, 2)
					res, err := DecodeDelta(ipDelta)
					if err != nil {
						t.Fatalf("decode: %v", err)
					}
					recovered := ApplyDeltaInplace(r, res.Commands, len(v))
					if !bytes.Equal(v, recovered) {
						t.Fatalf("subcommand roundtrip case %d failed", i)
					}
				})
			}
		}
	}
}

func TestInplaceSubcommandIdempotent(t *testing.T) {
	r := b("ABCDEFGHIJ")
	v := b("JIHGFEDCBA")
	for _, algo := range allAlgos {
		for _, pol := range allPolicies {
			t.Run(algo.String()+"/"+pol.String(), func(t *testing.T) {
				cmds := Diff(algo, r, v, opts(2))
				ip := MakeInplace(r, cmds, pol)
				ipDelta := mustEncode(t, ip, true, len(v), zeroHash, zeroHash)
				res, _ := DecodeDelta(ipDelta)
				if !res.Inplace {
					t.Fatal("inplace delta should be detected as inplace")
				}
			})
		}
	}
}

func TestInplaceSubcommandEquivDirect(t *testing.T) {
	rs := [][]byte{b("ABCDEF"), b("AAABBBCCC"),
		b("the quick brown fox"), b("ABCDEFGHIJKLMNOP")}
	vs := [][]byte{b("FEDCBA"), b("CCCBBBAAA"),
		b("the quick brown cat"), b("PONMLKJIHGFEDCBA")}
	for i := range rs {
		r, v := rs[i], vs[i]
		for _, algo := range allAlgos {
			for _, pol := range allPolicies {
				t.Run(algo.String()+"/"+pol.String(), func(t *testing.T) {
					// Direct path.
					cmds := Diff(algo, r, v, opts(2))
					ipDirect := MakeInplace(r, cmds, pol)
					directBytes := mustEncode(t, ipDirect, true, len(v), zeroHash, zeroHash)
					// Subcommand path.
					subBytes := viaInplaceSubcommand(t, algo, r, v, pol, 2)
					if !bytes.Equal(directBytes, subBytes) {
						t.Fatalf("subcommand vs direct mismatch at case %d", i)
					}
				})
			}
		}
	}
}

// ── splay tree ────────────────────────────────────────────────────────────────

func TestSplayRoundtrip(t *testing.T) {
	r := repeat(b("ABCDEFGHIJKLMNOPQRSTUVWXYZ"), 100)
	v := repeat(b("0123EFGHIJKLMNOPQRS456ABCDEFGHIJKL789"), 100)
	for _, algo := range allAlgos {
		t.Run(algo.String(), func(t *testing.T) {
			o := opts(4)
			o.UseSplay = true
			cmds := Diff(algo, r, v, o)
			got := ApplyDelta(r, cmds)
			if !bytes.Equal(v, got) {
				t.Fatal("splay roundtrip failed")
			}
		})
	}
}

// ── edge cases and boundaries ─────────────────────────────────────────────────

func TestSingleByte(t *testing.T) {
	one := []byte{0x41}
	other := []byte{0x42}
	var empty []byte
	for _, algo := range allAlgos {
		t.Run(algo.String(), func(t *testing.T) {
			if got := ApplyDelta(one, Diff(algo, one, one, opts(1))); !bytes.Equal(one, got) {
				t.Fatal("1b same failed")
			}
			if got := ApplyDelta(one, Diff(algo, one, other, opts(1))); !bytes.Equal(other, got) {
				t.Fatal("1b differ failed")
			}
			if got := ApplyDelta(one, Diff(algo, one, empty, opts(1))); len(got) != 0 {
				t.Fatal("1b r=1 v=0 failed")
			}
			if got := ApplyDelta(empty, Diff(algo, empty, one, opts(1))); !bytes.Equal(one, got) {
				t.Fatal("1b r=0 v=1 failed")
			}
		})
	}
}

func TestBoundaryByteMutations(t *testing.T) {
	n := 64
	p := 4
	r := make([]byte, n)
	for i := range r {
		r[i] = byte(i)
	}
	vFirst := make([]byte, n)
	copy(vFirst, r)
	vFirst[0] ^= 0xFF
	vLast := make([]byte, n)
	copy(vLast, r)
	vLast[n-1] ^= 0xFF
	vAppend := make([]byte, n+1)
	copy(vAppend, r)
	vAppend[n] = 0x5A
	vDrop := r[:n-1]
	for _, algo := range allAlgos {
		t.Run(algo.String(), func(t *testing.T) {
			if got := roundtrip(t, algo, r, vFirst, p); !bytes.Equal(vFirst, got) {
				t.Fatal("byte[0] flipped failed")
			}
			if got := roundtrip(t, algo, r, vLast, p); !bytes.Equal(vLast, got) {
				t.Fatal("byte[n-1] flipped failed")
			}
			if got := roundtrip(t, algo, r, vAppend, p); !bytes.Equal(vAppend, got) {
				t.Fatal("one byte appended failed")
			}
			if got := roundtrip(t, algo, r, vDrop, p); !bytes.Equal(vDrop, got) {
				t.Fatal("last byte dropped failed")
			}
		})
	}
}

func TestRefShorterThanSeed(t *testing.T) {
	p := 8
	v := []byte{0x10, 0x11, 0x12, 0x13, 0xAA, 0xBB, 0xCC, 0xDD}
	for rLen := 0; rLen < p; rLen++ {
		r := make([]byte, rLen)
		for i := range r {
			r[i] = byte(i + 1)
		}
		for _, algo := range allAlgos {
			got := ApplyDelta(r, Diff(algo, r, v, opts(p)))
			if !bytes.Equal(v, got) {
				t.Fatalf("%s ref shorter than seed rLen=%d failed", algo, rLen)
			}
		}
	}
}

func TestSizeSweep(t *testing.T) {
	p := 4
	sizes := []int{0, 1, 2, 3, p - 1, p, p + 1, 2*p - 1, 2 * p, 2*p + 1,
		63, 64, 65, 127, 128, 129, 255, 256, 257, 511, 512, 513}
	bigRef := make([]byte, 600)
	for i := range bigRef {
		bigRef[i] = byte(i * 3 & 0xFF)
	}
	for _, vLen := range sizes {
		for _, algo := range allAlgos {
			vPrefix := bigRef[:vLen]
			if got := roundtrip(t, algo, bigRef, vPrefix, p); !bytes.Equal(vPrefix, got) {
				t.Fatalf("%s vLen=%d prefix ver failed", algo, vLen)
			}
			vNew := make([]byte, vLen)
			for i := range vNew {
				vNew[i] = byte(i*7 + 1&0xFF)
			}
			if got := roundtrip(t, algo, bigRef, vNew, p); !bytes.Equal(vNew, got) {
				t.Fatalf("%s vLen=%d all-new ver failed", algo, vLen)
			}
		}
	}
	fixedVer := bigRef[:64]
	for _, rLen := range sizes {
		for _, algo := range allAlgos {
			r := bigRef[:rLen]
			if got := roundtrip(t, algo, r, fixedVer, p); !bytes.Equal(fixedVer, got) {
				t.Fatalf("%s rLen=%d fixed ver failed", algo, rLen)
			}
		}
	}
}

func TestEncodingVersionSizeBoundaries(t *testing.T) {
	sizes := []int{0, 1, 127, 128, 255, 256, 257,
		32767, 32768, 32769,
		65535, 65536, 65537,
		8388607, 8388608, 8388609,
		16777215, 16777216, 16777217}
	for _, sz := range sizes {
		encoded := mustEncode(t, nil, false, sz, zeroHash, zeroHash)
		res, err := DecodeDelta(encoded)
		if err != nil {
			t.Fatalf("version_size=%d decode error: %v", sz, err)
		}
		if res.VersionSize != sz {
			t.Fatalf("version_size=%d: got %d", sz, res.VersionSize)
		}
		if len(res.Commands) != 0 {
			t.Fatalf("version_size=%d: expected no commands, got %d", sz, len(res.Commands))
		}
	}
}

func TestEncodingCommandFieldBoundaries(t *testing.T) {
	offsets := []int{0, 1, 127, 128, 255, 256, 257, 65535, 65536, 65537}
	for _, src := range offsets {
		placed := []PlacedCommand{PlacedCopy{Src: src, DstOff: 0, Length: 1}}
		res, _ := DecodeDelta(mustEncode(t, placed, false, 1, zeroHash, zeroHash))
		c := res.Commands[0].(PlacedCopy)
		if c.Src != src || c.DstOff != 0 || c.Length != 1 {
			t.Fatalf("copy src=%d fields wrong", src)
		}
	}
	for _, dst := range offsets {
		placed := []PlacedCommand{PlacedCopy{Src: 0, DstOff: dst, Length: 1}}
		res, _ := DecodeDelta(mustEncode(t, placed, false, dst+1, zeroHash, zeroHash))
		c := res.Commands[0].(PlacedCopy)
		if c.DstOff != dst {
			t.Fatalf("copy dst=%d wrong: got %d", dst, c.DstOff)
		}
	}
	for _, length := range []int{1, 127, 128, 255, 256, 257, 65535, 65536} {
		placed := []PlacedCommand{PlacedCopy{Src: 0, DstOff: 0, Length: length}}
		res, _ := DecodeDelta(mustEncode(t, placed, false, length, zeroHash, zeroHash))
		c := res.Commands[0].(PlacedCopy)
		if c.Length != length {
			t.Fatalf("copy length=%d wrong: got %d", length, c.Length)
		}
	}
	for _, dst := range offsets {
		placed := []PlacedCommand{PlacedAdd{DstOff: dst, Data: []byte{0xFF}}}
		res, _ := DecodeDelta(mustEncode(t, placed, false, dst+1, zeroHash, zeroHash))
		a := res.Commands[0].(PlacedAdd)
		if a.DstOff != dst || !bytes.Equal(a.Data, []byte{0xFF}) {
			t.Fatalf("add dst=%d wrong", dst)
		}
	}
}

func TestInplaceVersionOneLargerTight(t *testing.T) {
	for _, n := range []int{1, 2, 3, 4, 7, 8, 15, 16, 17, 31, 32, 63, 64} {
		r := make([]byte, n)
		for i := range r {
			r[i] = byte(i & 0xFF)
		}
		v := append(make([]byte, n), 0x5A)
		copy(v, r)
		v[n] = 0x5A
		for _, algo := range allAlgos {
			for _, pol := range allPolicies {
				got := inplaceRoundtrip(t, algo, r, v, pol, 2)
				if !bytes.Equal(v, got) {
					t.Fatalf("%s/%s inplace |V|=|R|+1 n=%d failed", algo, pol, n)
				}
			}
		}
	}
}

func TestInplaceVersionOneSmallerTight(t *testing.T) {
	for _, n := range []int{2, 3, 4, 5, 8, 9, 15, 16, 17, 31, 32, 65} {
		r := make([]byte, n)
		for i := range r {
			r[i] = byte(i & 0xFF)
		}
		v := r[:n-1]
		for _, algo := range allAlgos {
			for _, pol := range allPolicies {
				got := inplaceRoundtrip(t, algo, r, v, pol, 2)
				if !bytes.Equal(v, got) {
					t.Fatalf("%s/%s inplace |V|=|R|-1 n=%d failed", algo, pol, n)
				}
			}
		}
	}
}

func TestInplaceVersionSameSizeTight(t *testing.T) {
	for _, n := range []int{2, 4, 8, 16, 32, 64, 128, 256} {
		r := make([]byte, n)
		for i := range r {
			r[i] = byte(i & 0xFF)
		}
		half := n / 2
		v := make([]byte, n)
		copy(v, r[half:])
		copy(v[half:], r[:half])
		for _, algo := range allAlgos {
			for _, pol := range allPolicies {
				got := inplaceRoundtrip(t, algo, r, v, pol, 2)
				if !bytes.Equal(v, got) {
					t.Fatalf("%s/%s inplace same-size swap n=%d failed", algo, pol, n)
				}
			}
		}
	}
}

func TestInplaceVersionOneByteMin(t *testing.T) {
	r := make([]byte, 64)
	for i := range r {
		r[i] = byte(i)
	}
	vCopy := []byte{r[32]}
	vAdd := []byte{0xAB}
	for _, algo := range allAlgos {
		for _, pol := range allPolicies {
			got := inplaceRoundtrip(t, algo, r, vCopy, pol, 2)
			if !bytes.Equal(vCopy, got) {
				t.Fatalf("%s/%s inplace v=1 byte (copy) failed", algo, pol)
			}
			got = inplaceRoundtrip(t, algo, r, vAdd, pol, 2)
			if !bytes.Equal(vAdd, got) {
				t.Fatalf("%s/%s inplace v=1 byte (add) failed", algo, pol)
			}
		}
	}
}

func TestSeedLengthBoundaries(t *testing.T) {
	r := b("ABCDEFGHIJKLMNOP")
	v := b("QWIJKLMNOBCDEFGHZDEFGHIJKL")
	for _, p := range []int{1, 2, len(r), len(r) + 1} {
		for _, algo := range allAlgos {
			got := ApplyDelta(r, Diff(algo, r, v, opts(p)))
			if !bytes.Equal(v, got) {
				t.Fatalf("%s p=%d failed", algo, p)
			}
		}
	}
	vShort := b("QW")
	vLong := b("QWIJKLMNOBCDEFGHZDEFGHIJKLMNOPQRSTUVWXYZ")
	for _, algo := range allAlgos {
		if got := ApplyDelta(r, Diff(algo, r, vShort, opts(len(r)+1))); !bytes.Equal(vShort, got) {
			t.Fatalf("%s p>|r| short ver failed", algo)
		}
		if got := ApplyDelta(r, Diff(algo, r, vLong, opts(len(r)+1))); !bytes.Equal(vLong, got) {
			t.Fatalf("%s p>|r| long ver failed", algo)
		}
	}
}

func TestEncodeDeltaRejectsVersionSizeOverflow(t *testing.T) {
	_, err := EncodeDelta(nil, false, math.MaxUint32+1, zeroHash, zeroHash)
	if err == nil {
		t.Fatal("expected error for version_size > uint32 max, got nil")
	}
}

func TestEncodeDeltaRejectsCopySrcOverflow(t *testing.T) {
	cmd := PlacedCopy{Src: math.MaxUint32 + 1, DstOff: 0, Length: 1}
	_, err := EncodeDelta([]PlacedCommand{cmd}, false, 1, zeroHash, zeroHash)
	if err == nil {
		t.Fatal("expected error for copy src > uint32 max, got nil")
	}
}

func TestEncodeDeltaRejectsCopyDstOverflow(t *testing.T) {
	cmd := PlacedCopy{Src: 0, DstOff: math.MaxUint32 + 1, Length: 1}
	_, err := EncodeDelta([]PlacedCommand{cmd}, false, 1, zeroHash, zeroHash)
	if err == nil {
		t.Fatal("expected error for copy dst > uint32 max, got nil")
	}
}

func TestEncodeDeltaRejectsCopyLengthOverflow(t *testing.T) {
	cmd := PlacedCopy{Src: 0, DstOff: 0, Length: math.MaxUint32 + 1}
	_, err := EncodeDelta([]PlacedCommand{cmd}, false, 1, zeroHash, zeroHash)
	if err == nil {
		t.Fatal("expected error for copy length > uint32 max, got nil")
	}
}

func TestEncodeDeltaRejectsAddDstOverflow(t *testing.T) {
	cmd := PlacedAdd{DstOff: math.MaxUint32 + 1, Data: []byte{0}}
	_, err := EncodeDelta([]PlacedCommand{cmd}, false, 1, zeroHash, zeroHash)
	if err == nil {
		t.Fatal("expected error for add dst > uint32 max, got nil")
	}
}

// ── DLT\x04 (large format) tests ─────────────────────────────────────────────

func TestLargeHeaderMagic(t *testing.T) {
	out := EncodeDeltaLarge(nil, false, 0, zeroHash, zeroHash)
	if string(out[:4]) != DeltaMagicLarge {
		t.Fatalf("expected magic %q, got %q", DeltaMagicLarge, string(out[:4]))
	}
}

func TestLargeHeaderSize(t *testing.T) {
	// Empty delta: 29-byte header + 1-byte END = 30 bytes.
	out := EncodeDeltaLarge(nil, false, 0, zeroHash, zeroHash)
	if len(out) != DeltaHeaderSizeLarge+1 {
		t.Fatalf("expected %d bytes, got %d", DeltaHeaderSizeLarge+1, len(out))
	}
}

func TestLargeHeaderVersionSizeU64(t *testing.T) {
	big := 1<<32 + 999
	out := EncodeDeltaLarge(nil, false, big, zeroHash, zeroHash)
	res, err := DecodeDelta(out)
	if err != nil {
		t.Fatalf("DecodeDelta: %v", err)
	}
	if res.VersionSize != big {
		t.Fatalf("version_size: got %d, want %d", res.VersionSize, big)
	}
}

func TestLargeInplaceFlag(t *testing.T) {
	out := EncodeDeltaLarge(nil, true, 0, zeroHash, zeroHash)
	if !IsInplaceDelta(out) {
		t.Fatal("inplace flag not detected")
	}
	out2 := EncodeDeltaLarge(nil, false, 0, zeroHash, zeroHash)
	if IsInplaceDelta(out2) {
		t.Fatal("inplace flag falsely detected")
	}
}

func TestLargeCopySmallRoundtrip(t *testing.T) {
	r := b("ABCDEFGH")
	cmds := []PlacedCommand{PlacedCopy{Src: 0, DstOff: 0, Length: 8}}
	out := EncodeDeltaLarge(cmds, false, 8, zeroHash, zeroHash)
	res, err := DecodeDelta(out)
	if err != nil {
		t.Fatalf("DecodeDelta: %v", err)
	}
	got := make([]byte, res.VersionSize)
	ApplyPlacedTo(r, res.Commands, got)
	if !bytes.Equal(got, r) {
		t.Fatalf("got %q, want %q", got, r)
	}
}

func TestLargeBigCopyCommandByte(t *testing.T) {
	// src > U32_MAX forces BIGCOPY; verify command byte = 3.
	bigSrc := math.MaxUint32 + 1
	cmds := []PlacedCommand{PlacedCopy{Src: bigSrc, DstOff: 0, Length: 1}}
	out := EncodeDeltaLarge(cmds, false, 1, zeroHash, zeroHash)
	if out[DeltaHeaderSizeLarge] != DeltaCmdBigCopy {
		t.Fatalf("expected BIGCOPY(3), got %d", out[DeltaHeaderSizeLarge])
	}
}

func TestLargeAddRoundtrip(t *testing.T) {
	cmds := []PlacedCommand{PlacedAdd{DstOff: 0, Data: b("hello")}}
	out := EncodeDeltaLarge(cmds, false, 5, zeroHash, zeroHash)
	res, err := DecodeDelta(out)
	if err != nil {
		t.Fatalf("DecodeDelta: %v", err)
	}
	got := make([]byte, res.VersionSize)
	ApplyPlacedTo(nil, res.Commands, got)
	if !bytes.Equal(got, b("hello")) {
		t.Fatalf("got %q, want %q", got, b("hello"))
	}
}

func TestLargeBigAddCommandByte(t *testing.T) {
	bigDst := math.MaxUint32 + 1
	cmds := []PlacedCommand{PlacedAdd{DstOff: bigDst, Data: b("x")}}
	out := EncodeDeltaLarge(cmds, false, bigDst+1, zeroHash, zeroHash)
	if out[DeltaHeaderSizeLarge] != DeltaCmdBigAdd {
		t.Fatalf("expected BIGADD(4), got %d", out[DeltaHeaderSizeLarge])
	}
}

func TestLargeMoveRoundtrip(t *testing.T) {
	// ADD "ABC" at 0, MOVE src=0 dst=3 len=3 → "ABCABC"
	cmds := []PlacedCommand{
		PlacedAdd{DstOff: 0, Data: b("ABC")},
		PlacedMove{Src: 0, DstOff: 3, Length: 3},
	}
	out := EncodeDeltaLarge(cmds, false, 6, zeroHash, zeroHash)
	res, err := DecodeDelta(out)
	if err != nil {
		t.Fatalf("DecodeDelta: %v", err)
	}
	got := make([]byte, res.VersionSize)
	ApplyPlacedTo(nil, res.Commands, got)
	if !bytes.Equal(got, b("ABCABC")) {
		t.Fatalf("got %q, want %q", got, b("ABCABC"))
	}
}

func TestLargeMoveCommandByte(t *testing.T) {
	cmds := []PlacedCommand{PlacedMove{Src: 0, DstOff: 3, Length: 3}}
	out := EncodeDeltaLarge(cmds, false, 6, zeroHash, zeroHash)
	if out[DeltaHeaderSizeLarge] != DeltaCmdMove {
		t.Fatalf("expected MOVE(5), got %d", out[DeltaHeaderSizeLarge])
	}
}

func TestLargeBigMoveCommandByte(t *testing.T) {
	bigDst := math.MaxUint32 + 1
	cmds := []PlacedCommand{PlacedMove{Src: 0, DstOff: bigDst, Length: 1}}
	out := EncodeDeltaLarge(cmds, false, bigDst+1, zeroHash, zeroHash)
	if out[DeltaHeaderSizeLarge] != DeltaCmdBigMove {
		t.Fatalf("expected BIGMOVE(6), got %d", out[DeltaHeaderSizeLarge])
	}
}

func TestLargeMoveOverlapRejected(t *testing.T) {
	// Construct a hand-crafted delta with MOVE src+length > dst — must be rejected.
	import_struct := func(vsz, src, dst, length int) []byte {
		hdr := append([]byte(DeltaMagicLarge), 0)
		hdr = append(hdr, 0, 0, 0, 0, 0, 0, 0, byte(vsz)) // u64 BE version_size
		hdr = append(hdr, make([]byte, 16)...)              // crcs
		// MOVE src dst len (u32)
		body := []byte{DeltaCmdMove,
			byte(src >> 24), byte(src >> 16), byte(src >> 8), byte(src),
			byte(dst >> 24), byte(dst >> 16), byte(dst >> 8), byte(dst),
			byte(length >> 24), byte(length >> 16), byte(length >> 8), byte(length),
			DeltaCmdEnd,
		}
		return append(hdr, body...)
	}
	data := import_struct(10, 5, 7, 4) // src+length=9 > dst=7
	_, err := DecodeDelta(data)
	if err == nil {
		t.Fatal("expected error for MOVE src+length > dst")
	}
}

func TestSmallRejectsLargeCommandBytes(t *testing.T) {
	// Hand-craft a DLT\x03 file containing a BIGCOPY byte — must be rejected.
	hdr := append([]byte(DeltaMagic), 0)
	hdr = append(hdr, 0, 0, 0, 10) // u32 BE version_size = 10
	hdr = append(hdr, make([]byte, 16)...)
	body := []byte{DeltaCmdBigCopy,
		0, 0, 0, 0, 0, 0, 0, 0, // src u64
		0, 0, 0, 0, 0, 0, 0, 0, // dst u64
		0, 0, 0, 0, 0, 0, 0, 5, // len u64 = 5
		DeltaCmdEnd,
	}
	_, err := DecodeDelta(append(hdr, body...))
	if err == nil {
		t.Fatal("expected error for BIGCOPY in DLT\\x03 stream")
	}
}

func TestUnknownMagicRejected(t *testing.T) {
	bad := append([]byte("DLT\x99"), make([]byte, 30)...)
	_, err := DecodeDelta(bad)
	if err == nil {
		t.Fatal("expected error for unknown magic")
	}
}

func TestLargeAlgoRoundtripGreedy(t *testing.T) {
	r, v := b("hello world"), b("hello earth")
	cmds := Diff(AlgorithmGreedy, r, v, opts(4))
	placed := PlaceCommands(cmds)
	delta := EncodeDeltaLarge(placed, false, len(v), zeroHash, zeroHash)
	if string(delta[:4]) != DeltaMagicLarge {
		t.Fatal("expected DLT\\x04 magic")
	}
	res, err := DecodeDelta(delta)
	if err != nil {
		t.Fatalf("DecodeDelta: %v", err)
	}
	got := make([]byte, res.VersionSize)
	ApplyPlacedTo(r, res.Commands, got)
	if !bytes.Equal(got, v) {
		t.Fatalf("got %q, want %q", got, v)
	}
}

func TestLargeAlgoRoundtripOnepass(t *testing.T) {
	r := repeat(b("abcdefgh"), 10)
	v := append(repeat(b("abcdefgh"), 5), repeat(b("XXXXXXXX"), 5)...)
	cmds := Diff(AlgorithmOnepass, r, v, opts(4))
	placed := PlaceCommands(cmds)
	delta := EncodeDeltaLarge(placed, false, len(v), zeroHash, zeroHash)
	res, err := DecodeDelta(delta)
	if err != nil {
		t.Fatalf("DecodeDelta: %v", err)
	}
	got := make([]byte, res.VersionSize)
	ApplyPlacedTo(r, res.Commands, got)
	if !bytes.Equal(got, v) {
		t.Fatalf("got %q, want %q", got, v)
	}
}

func TestLargeAlgoRoundtripCorrecting(t *testing.T) {
	r, v := b("the quick brown fox"), b("the slow brown fox")
	cmds := Diff(AlgorithmCorrecting, r, v, opts(4))
	placed := PlaceCommands(cmds)
	delta := EncodeDeltaLarge(placed, false, len(v), zeroHash, zeroHash)
	res, err := DecodeDelta(delta)
	if err != nil {
		t.Fatalf("DecodeDelta: %v", err)
	}
	got := make([]byte, res.VersionSize)
	ApplyPlacedTo(r, res.Commands, got)
	if !bytes.Equal(got, v) {
		t.Fatalf("got %q, want %q", got, v)
	}
}

// TestLargeU64TruncationGuard crafts a DLT\x04 stream whose version_size
// field is 2^63 (top bit set). On a 64-bit platform this value exceeds
// math.MaxInt64 so getU64BE rejects it; on a 32-bit platform it would be
// caught even earlier. Either way, DecodeDelta must return a non-nil error.
func TestLargeU64TruncationGuard(t *testing.T) {
	buf := make([]byte, DeltaHeaderSizeLarge+1)
	copy(buf, DeltaMagicLarge)
	buf[5] = 0x80 // version_size = 2^63 (top bit set)
	buf[DeltaHeaderSizeLarge] = DeltaCmdEnd
	_, err := DecodeDelta(buf)
	if err == nil {
		t.Fatal("expected error for version_size with top bit set, got nil")
	}
}
