package delta

import (
	"errors"
	"sort"
)

// OutputSize returns the length of the version that commands produce.
func OutputSize(commands []Command) int {
	size := 0
	for _, cmd := range commands {
		switch c := cmd.(type) {
		case CopyCmd:
			size += c.Length
		case AddCmd:
			size += len(c.Data)
		}
	}
	return size
}

// PlaceCommands gives each command the destination at which it writes when
// the commands are applied in order. The adds share their data with
// commands.
func PlaceCommands(commands []Command) []PlacedCommand {
	placed := make([]PlacedCommand, 0, len(commands))
	dst := 0
	for _, cmd := range commands {
		switch c := cmd.(type) {
		case CopyCmd:
			placed = append(placed, PlacedCopy{Src: c.Offset, DstOff: dst, Length: c.Length})
			dst += c.Length
		case AddCmd:
			placed = append(placed, PlacedAdd{DstOff: dst, Data: c.Data})
			dst += len(c.Data)
		}
	}
	return placed
}

// HasMove reports whether commands contains a PlacedMove.
func HasMove(commands []PlacedCommand) bool {
	for _, cmd := range commands {
		if _, ok := cmd.(PlacedMove); ok {
			return true
		}
	}
	return false
}

// UnplaceCommands is the inverse of PlaceCommands: it returns the commands
// in order of destination, without their destinations. It panics if placed
// contains a PlacedMove, which has no unplaced form; see HasMove.
func UnplaceCommands(placed []PlacedCommand) []Command {
	sorted := make([]PlacedCommand, len(placed))
	copy(sorted, placed)
	sort.Slice(sorted, func(i, j int) bool {
		return sorted[i].Dst() < sorted[j].Dst()
	})
	commands := make([]Command, len(sorted))
	for i, cmd := range sorted {
		switch c := cmd.(type) {
		case PlacedCopy:
			commands[i] = CopyCmd{Offset: c.Src, Length: c.Length}
		case PlacedAdd:
			commands[i] = AddCmd{Data: c.Data}
		case PlacedMove:
			panic("PlacedMove has no algorithm-level equivalent; DLT\\x04-only")
		}
	}
	return commands
}

// ApplyDelta returns the version that commands build from the reference r.
func ApplyDelta(r []byte, commands []Command) []byte {
	out := make([]byte, 0, OutputSize(commands))
	for _, cmd := range commands {
		switch c := cmd.(type) {
		case CopyCmd:
			out = append(out, r[c.Offset:c.Offset+c.Length]...)
		case AddCmd:
			out = append(out, c.Data...)
		}
	}
	return out
}

// ApplyPlacedTo applies commands, reading from the reference r and writing
// to out, which must be large enough. It returns the offset just past the
// highest byte written. The commands must be valid; see
// ValidatePlacedCommands.
func ApplyPlacedTo(r []byte, commands []PlacedCommand, out []byte) int {
	written := 0
	for _, cmd := range commands {
		end := 0
		switch c := cmd.(type) {
		case PlacedCopy:
			end = c.DstOff + copy(out[c.DstOff:], r[c.Src:c.Src+c.Length])
		case PlacedAdd:
			end = c.DstOff + copy(out[c.DstOff:], c.Data)
		case PlacedMove:
			end = c.DstOff + copy(out[c.DstOff:], out[c.Src:c.Src+c.Length])
		}
		written = max(written, end)
	}
	return written
}

// ApplyPlacedInplaceTo applies the commands of an in-place delta to buf,
// which holds the reference on entry and the version on return and must be
// as long as the larger of the two. A copy's source and destination may
// overlap.
func ApplyPlacedInplaceTo(commands []PlacedCommand, buf []byte) {
	for _, cmd := range commands {
		switch c := cmd.(type) {
		case PlacedCopy:
			copy(buf[c.DstOff:], buf[c.Src:c.Src+c.Length])
		case PlacedAdd:
			copy(buf[c.DstOff:], c.Data)
		case PlacedMove:
			copy(buf[c.DstOff:], buf[c.Src:c.Src+c.Length])
		}
	}
}

// ApplyDeltaInplace returns the version that the in-place commands build
// from the reference r. It leaves r unchanged.
func ApplyDeltaInplace(r []byte, commands []PlacedCommand, versionSize int) []byte {
	buf := make([]byte, max(len(r), versionSize))
	copy(buf, r)
	ApplyPlacedInplaceTo(commands, buf)
	return buf[:versionSize]
}

// ValidatePlacedCommands returns an error unless commands can be applied
// safely to a reference of referenceSize bytes to produce a version of
// versionSize bytes: every write must lie within the version and every read
// within its source. For an in-place delta the source of a copy is the
// working buffer, which is as long as the larger of the reference and the
// version. A move must read only output before its destination.
func ValidatePlacedCommands(commands []PlacedCommand, referenceSize, versionSize int, inplace bool) error {
	if referenceSize < 0 || versionSize < 0 {
		return errors.New("negative buffer size")
	}
	sourceSize := referenceSize
	if inplace {
		sourceSize = max(referenceSize, versionSize)
	}
	// within reports whether length bytes at start lie within limit bytes,
	// without overflowing.
	within := func(start, length, limit int) bool {
		return start >= 0 && length >= 0 && start <= limit && length <= limit-start
	}
	for _, cmd := range commands {
		switch c := cmd.(type) {
		case PlacedCopy:
			if !within(c.DstOff, c.Length, versionSize) {
				return errors.New("copy destination out of range")
			}
			if !within(c.Src, c.Length, sourceSize) {
				return errors.New("copy source out of range")
			}
		case PlacedAdd:
			if !within(c.DstOff, len(c.Data), versionSize) {
				return errors.New("add destination out of range")
			}
		case PlacedMove:
			if !within(c.DstOff, c.Length, versionSize) {
				return errors.New("move destination out of range")
			}
			if !within(c.Src, c.Length, versionSize) {
				return errors.New("move source out of range")
			}
			if c.Src+c.Length > c.DstOff {
				return errors.New("move src+length > dst: encoder ordering constraint violated")
			}
		}
	}
	return nil
}

// PlacedSummaryOf returns counts of commands and the bytes they produce.
func PlacedSummaryOf(commands []PlacedCommand) PlacedSummary {
	s := PlacedSummary{NumCommands: len(commands)}
	for _, cmd := range commands {
		switch c := cmd.(type) {
		case PlacedCopy:
			s.NumCopies++
			s.CopyBytes += int64(c.Length)
		case PlacedAdd:
			s.NumAdds++
			s.AddBytes += int64(len(c.Data))
		case PlacedMove:
			s.NumCopies++
			s.CopyBytes += int64(c.Length)
		}
	}
	s.TotalOutputBytes = s.CopyBytes + s.AddBytes
	return s
}
