package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"delta/delta"
)

// write creates a file of the test's temporary directory and returns its
// path.
func write(t *testing.T, dir, name string, data []byte) string {
	t.Helper()
	path := filepath.Join(dir, name)
	if err := os.WriteFile(path, data, 0644); err != nil {
		t.Fatal(err)
	}
	return path
}

// standardDelta returns the bytes of a standard delta from r to v.
func standardDelta(t *testing.T, r, v []byte) []byte {
	t.Helper()
	opts := delta.DefaultDiffOptions()
	opts.P = 4
	placed := delta.PlaceCommands(delta.Diff(delta.AlgorithmGreedy, r, v, opts))
	out, err := delta.EncodeDelta(placed, false, len(v), delta.Crc64XZ(r), delta.Crc64XZ(v))
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func TestInplaceConverts(t *testing.T) {
	dir := t.TempDir()
	r := []byte("AAAAAAAABBBBBBBBBBBB")
	v := []byte("BBBBBBBBBBBBAAAAAAAA")
	ref := write(t, dir, "ref", r)
	in := write(t, dir, "in.delta", standardDelta(t, r, v))
	out := filepath.Join(dir, "out.delta")
	if err := run([]string{"inplace", ref, in, out, "--verbose"}); err != nil {
		t.Fatalf("inplace: %v", err)
	}
	data, err := os.ReadFile(out)
	if err != nil {
		t.Fatal(err)
	}
	d, err := delta.DecodeDelta(data)
	if err != nil {
		t.Fatal(err)
	}
	if !d.Inplace {
		t.Fatal("output is not an in-place delta")
	}
	if got := delta.ApplyDeltaInplace(r, d.Commands, d.VersionSize); !bytes.Equal(got, v) {
		t.Fatalf("got %q, want %q", got, v)
	}
}

// The conversion reads the reference to turn copies into adds, so the wrong
// reference would give a delta that decodes to the wrong version.
func TestInplaceRejectsWrongReference(t *testing.T) {
	dir := t.TempDir()
	r := []byte("AAAAAAAABBBBBBBBBBBB")
	v := []byte("BBBBBBBBBBBBAAAAAAAA")
	wrong := write(t, dir, "wrong", []byte("AAAAAAAABBBBBBBBBBBC"))
	in := write(t, dir, "in.delta", standardDelta(t, r, v))
	out := filepath.Join(dir, "out.delta")
	err := run([]string{"inplace", wrong, in, out})
	if err == nil || !strings.Contains(err.Error(), "source file does not match delta") {
		t.Fatalf("inplace with the wrong reference: got error %v", err)
	}
	if _, err := os.Stat(out); !os.IsNotExist(err) {
		t.Fatal("inplace wrote an output delta despite the wrong reference")
	}
}

// A standard delta may hold a move, which has no in-place form.
func TestInplaceRejectsMove(t *testing.T) {
	dir := t.TempDir()
	r := []byte("ABC")
	v := []byte("ABCABC")
	placed := []delta.PlacedCommand{
		delta.PlacedCopy{Src: 0, DstOff: 0, Length: 3},
		delta.PlacedMove{Src: 0, DstOff: 3, Length: 3},
	}
	ref := write(t, dir, "ref", r)
	in := write(t, dir, "in.delta",
		delta.EncodeDeltaLarge(placed, false, len(v), delta.Crc64XZ(r), delta.Crc64XZ(v), false))
	out := filepath.Join(dir, "out.delta")
	err := run([]string{"inplace", ref, in, out})
	if err == nil || !strings.Contains(err.Error(), "MOVE") {
		t.Fatalf("inplace on a delta with a move: got error %v", err)
	}
	if _, err := os.Stat(out); !os.IsNotExist(err) {
		t.Fatal("inplace wrote an output delta despite the move")
	}
}
