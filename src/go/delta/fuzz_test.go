package delta

import (
	"bytes"
	"testing"
)

// FuzzDecode feeds arbitrary bytes to DecodeDelta.
//
// Invariant: DecodeDelta must never panic regardless of input.
// Returning an error is the expected rejection path for malformed data.
//
// Run:
//
//	go test -fuzz=FuzzDecode -fuzztime=300s
func FuzzDecode(f *testing.F) {
	// Seed corpus: valid V3 empty→empty delta.
	f.Add([]byte("\x44\x4c\x54\x03\x00" +
		"\x00\x00\x00\x00" +
		"\x00\x00\x00\x00\x00\x00\x00\x00" +
		"\x00\x00\x00\x00\x00\x00\x00\x00" +
		"\x00"))
	// Seed corpus: valid V4 empty→empty delta.
	f.Add([]byte("\x44\x4c\x54\x04\x00" +
		"\x00\x00\x00\x00\x00\x00\x00\x00" +
		"\x00\x00\x00\x00\x00\x00\x00\x00" +
		"\x00\x00\x00\x00\x00\x00\x00\x00" +
		"\x00"))
	// Seed corpus: truncated magic, bad magic, empty.
	f.Add([]byte("DLT\x03"))
	f.Add([]byte("DLT\x04"))
	f.Add([]byte("\xde\xad\xbe\xef"))
	f.Add([]byte{})

	f.Fuzz(func(t *testing.T, data []byte) {
		_, _ = DecodeDelta(data)
	})
}

// FuzzRoundtrip runs encode→decode→apply and asserts byte-identical reconstruction.
//
// Input layout: [split_byte | reference... | version...]
//   split = 1 + (split_byte * (len-1)) / 256
//
// Inputs are capped at 4 KiB; diffGreedy is O(|ref|×|ver|).
//
// Run:
//
//	go test -fuzz=FuzzRoundtrip -fuzztime=300s
func FuzzRoundtrip(f *testing.F) {
	// A few interesting (ref, ver) pairs as seeds.
	f.Add([]byte{128, 'h', 'e', 'l', 'l', 'o', ' ', 'w', 'o', 'r', 'l', 'd'})
	f.Add([]byte{0})
	f.Add([]byte{255, 'a', 'b', 'c'})

	f.Fuzz(func(t *testing.T, data []byte) {
		if len(data) < 2 || len(data) > 4096 {
			return
		}

		split := 1 + (int(data[0])*( len(data)-1))/256
		if split > len(data) {
			split = len(data)
		}
		refData := data[1:split]
		verData := data[split:]

		// Encode
		opts := DefaultDiffOptions()
		cmds := Diff(AlgorithmGreedy, refData, verData, opts)
		placed := PlaceCommands(cmds)
		srcCrc := Crc64XZ(refData)
		dstCrc := Crc64XZ(verData)
		encoded := EncodeDeltaLarge(placed, false, len(verData), srcCrc, dstCrc, false)

		// Decode — must not fail on our own output
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

		// Apply
		out := make([]byte, result.VersionSize)
		ApplyPlacedTo(refData, result.Commands, out)
		if !bytes.Equal(out, verData) {
			t.Fatalf("reconstructed output differs from version")
		}
	})
}
