package delta

import (
	"bytes"
	"testing"
)

// FuzzDecode feeds arbitrary bytes to DecodeDelta, which must return an
// error for malformed input and never panic.
//
//	go test -fuzz=FuzzDecode -fuzztime=300s
func FuzzDecode(f *testing.F) {
	// A DLT\x03 delta from an empty reference to an empty version.
	f.Add([]byte("\x44\x4c\x54\x03\x00" +
		"\x00\x00\x00\x00" +
		"\x00\x00\x00\x00\x00\x00\x00\x00" +
		"\x00\x00\x00\x00\x00\x00\x00\x00" +
		"\x00"))
	// The same in DLT\x04.
	f.Add([]byte("\x44\x4c\x54\x04\x00" +
		"\x00\x00\x00\x00\x00\x00\x00\x00" +
		"\x00\x00\x00\x00\x00\x00\x00\x00" +
		"\x00\x00\x00\x00\x00\x00\x00\x00" +
		"\x00"))
	// A bare magic, a bad magic, and no input.
	f.Add([]byte("DLT\x03"))
	f.Add([]byte("DLT\x04"))
	f.Add([]byte("\xde\xad\xbe\xef"))
	f.Add([]byte{})

	f.Fuzz(func(t *testing.T, data []byte) {
		_, _ = DecodeDelta(data)
	})
}

// FuzzRoundtrip encodes a delta with the greedy algorithm, decodes and
// applies it, and checks that the version is reconstructed exactly.
//
// The first byte of the input divides the rest into a reference and a
// version: the reference is data[1:split] and the version data[split:],
// where split = 1 + data[0]*(len(data)-1)/256. Inputs longer than 4 KiB are
// skipped because the greedy algorithm takes O(|V|*|R|) time.
//
//	go test -fuzz=FuzzRoundtrip -fuzztime=300s
func FuzzRoundtrip(f *testing.F) {
	f.Add([]byte{128, 'h', 'e', 'l', 'l', 'o', ' ', 'w', 'o', 'r', 'l', 'd'})
	f.Add([]byte{0})
	f.Add([]byte{255, 'a', 'b', 'c'})

	f.Fuzz(func(t *testing.T, data []byte) {
		if len(data) < 2 || len(data) > 4096 {
			return
		}

		split := 1 + (int(data[0])*(len(data)-1))/256
		if split > len(data) {
			split = len(data)
		}
		refData := data[1:split]
		verData := data[split:]

		opts := DefaultDiffOptions()
		cmds := Diff(AlgorithmGreedy, refData, verData, opts)
		placed := PlaceCommands(cmds)
		srcCrc := Crc64XZ(refData)
		dstCrc := Crc64XZ(verData)
		encoded := EncodeDeltaLarge(placed, false, len(verData), srcCrc, dstCrc, false)

		// The decoder must accept the encoder's output.
		result, err := DecodeDelta(encoded)
		if err != nil {
			t.Fatalf("decode failed on valid encoder output: %v", err)
		}

		if result.SrcCrc != srcCrc {
			t.Fatalf("src_crc did not round-trip: got %x, want %x", result.SrcCrc, srcCrc)
		}
		if result.DstCrc != dstCrc {
			t.Fatalf("dst_crc did not round-trip: got %x, want %x", result.DstCrc, dstCrc)
		}
		if result.VersionSize != len(verData) {
			t.Fatalf("version_size did not round-trip: got %d, want %d", result.VersionSize, len(verData))
		}

		out := make([]byte, result.VersionSize)
		ApplyPlacedTo(refData, result.Commands, out)
		if !bytes.Equal(out, verData) {
			t.Fatalf("reconstructed output differs from version")
		}
	})
}
