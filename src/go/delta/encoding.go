package delta

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"math"
)

// DecodeResult is the content of a delta file.
type DecodeResult struct {
	Commands    []PlacedCommand
	Inplace     bool    // the commands are ordered for application in place
	VersionSize int     // length of the reconstructed version, in bytes
	SrcCrc      [8]byte // CRC-64/XZ of the reference
	DstCrc      [8]byte // CRC-64/XZ of the version
}

const maxU32 = math.MaxUint32

func fitsU32(vals ...int) bool {
	for _, v := range vals {
		if v < 0 || v > maxU32 {
			return false
		}
	}
	return true
}

type field struct {
	name string
	val  int
}

// checkU32 returns an error naming the first field whose value does not
// fit in 32 bits.
func checkU32(fields ...field) error {
	for _, f := range fields {
		if !fitsU32(f.val) {
			return fmt.Errorf("%s exceeds 4 GiB (32-bit format limit)", f.name)
		}
	}
	return nil
}

func appendHeader(out []byte, magic string, inplace bool) []byte {
	out = append(out, magic...)
	if inplace {
		return append(out, DeltaFlagInplace)
	}
	return append(out, 0)
}

// appendFields appends a command type and its fields as 32-bit or 64-bit
// big-endian integers.
func appendFields(out []byte, cmd byte, wide bool, fields ...int) []byte {
	out = append(out, cmd)
	for _, f := range fields {
		if wide {
			out = binary.BigEndian.AppendUint64(out, uint64(f))
		} else {
			out = binary.BigEndian.AppendUint32(out, uint32(f))
		}
	}
	return out
}

// EncodeDelta serializes commands in the DLT\x03 format. It returns an error
// if a size or offset does not fit in 32 bits or if commands contains a
// PlacedMove, which the format cannot represent.
func EncodeDelta(commands []PlacedCommand, inplace bool, versionSize int,
	srcCrc, dstCrc [8]byte) ([]byte, error) {

	if err := checkU32(field{"version_size", versionSize}); err != nil {
		return nil, err
	}
	size := DeltaHeaderSize + 1
	for _, cmd := range commands {
		switch c := cmd.(type) {
		case PlacedCopy:
			err := checkU32(
				field{"copy src offset", c.Src},
				field{"copy dst offset", c.DstOff},
				field{"copy length", c.Length})
			if err != nil {
				return nil, err
			}
			size += 1 + DeltaCopyPayload
		case PlacedAdd:
			err := checkU32(
				field{"add dst offset", c.DstOff},
				field{"add length", len(c.Data)})
			if err != nil {
				return nil, err
			}
			size += 1 + DeltaAddHeader + len(c.Data)
		case PlacedMove:
			return nil, errors.New("move command requires DLT\\x04 format")
		}
	}

	out := make([]byte, 0, size)
	out = appendHeader(out, DeltaMagic, inplace)
	out = binary.BigEndian.AppendUint32(out, uint32(versionSize))
	out = append(out, srcCrc[:]...)
	out = append(out, dstCrc[:]...)
	for _, cmd := range commands {
		switch c := cmd.(type) {
		case PlacedCopy:
			out = appendFields(out, DeltaCmdCopy, false, c.Src, c.DstOff, c.Length)
		case PlacedAdd:
			out = appendFields(out, DeltaCmdAdd, false, c.DstOff, len(c.Data))
			out = append(out, c.Data...)
		}
	}
	return append(out, DeltaCmdEnd), nil
}

// EncodeDeltaLarge serializes commands in the DLT\x04 format. Each command
// takes its 32-bit form if all its fields fit and forceLarge is false, and
// its 64-bit form otherwise.
func EncodeDeltaLarge(commands []PlacedCommand, inplace bool, versionSize int,
	srcCrc, dstCrc [8]byte, forceLarge bool) []byte {

	size := DeltaHeaderSizeLarge + 1
	for _, cmd := range commands {
		switch c := cmd.(type) {
		case PlacedCopy, PlacedMove:
			size += 1 + DeltaBigCopyPayload
		case PlacedAdd:
			size += 1 + DeltaBigAddHeader + len(c.Data)
		}
	}

	out := make([]byte, 0, size)
	out = appendHeader(out, DeltaMagicLarge, inplace)
	out = binary.BigEndian.AppendUint64(out, uint64(versionSize))
	out = append(out, srcCrc[:]...)
	out = append(out, dstCrc[:]...)
	for _, cmd := range commands {
		switch c := cmd.(type) {
		case PlacedCopy:
			if wide := forceLarge || !fitsU32(c.Src, c.DstOff, c.Length); wide {
				out = appendFields(out, DeltaCmdBigCopy, true, c.Src, c.DstOff, c.Length)
			} else {
				out = appendFields(out, DeltaCmdCopy, false, c.Src, c.DstOff, c.Length)
			}
		case PlacedAdd:
			if wide := forceLarge || !fitsU32(c.DstOff, len(c.Data)); wide {
				out = appendFields(out, DeltaCmdBigAdd, true, c.DstOff, len(c.Data))
			} else {
				out = appendFields(out, DeltaCmdAdd, false, c.DstOff, len(c.Data))
			}
			out = append(out, c.Data...)
		case PlacedMove:
			if wide := forceLarge || !fitsU32(c.Src, c.DstOff, c.Length); wide {
				out = appendFields(out, DeltaCmdBigMove, true, c.Src, c.DstOff, c.Length)
			} else {
				out = appendFields(out, DeltaCmdMove, false, c.Src, c.DstOff, c.Length)
			}
		}
	}
	return append(out, DeltaCmdEnd)
}

// IsInplaceDelta reports whether data begins with the header of an in-place
// delta.
func IsInplaceDelta(data []byte) bool {
	return len(data) >= 5 &&
		(bytes.HasPrefix(data, []byte(DeltaMagic)) || bytes.HasPrefix(data, []byte(DeltaMagicLarge))) &&
		data[4]&DeltaFlagInplace != 0
}

var (
	errNotDelta = errors.New("not a delta file")
	errEOF      = errors.New("unexpected EOF")
)

// cmdNames gives each command type the name used in error messages.
var cmdNames = [...]string{
	DeltaCmdCopy:    "copy",
	DeltaCmdAdd:     "add",
	DeltaCmdBigCopy: "bigcopy",
	DeltaCmdBigAdd:  "bigadd",
	DeltaCmdMove:    "move",
	DeltaCmdBigMove: "bigmove",
}

// The fields of a command, in file order. An add has no src.
var fieldNames = [...]string{"src", "dst", "length"}

// A decoder reads the fields of a delta file.
type decoder struct {
	data        []byte // unread input
	large       bool   // the file is DLT\x04
	versionSize int
}

// uint reads a big-endian integer of 4 or 8 bytes, which must be available.
// It returns an error if the value does not fit in an int.
func (d *decoder) uint(width int) (int, error) {
	var u uint64
	if width == DeltaU32Size {
		u = uint64(binary.BigEndian.Uint32(d.data))
	} else {
		u = binary.BigEndian.Uint64(d.data)
	}
	d.data = d.data[width:]
	if u > math.MaxInt {
		return 0, fmt.Errorf("delta field %d overflows int on this platform", u)
	}
	return int(u), nil
}

// command reads the command of type t, which is not END. It checks that
// the command writes within the version, but not what a copy reads.
func (d *decoder) command(t byte) (PlacedCommand, error) {
	if int(t) >= len(cmdNames) {
		return nil, fmt.Errorf("unknown command type: %d", t)
	}
	name := cmdNames[t]
	if !d.large && t > DeltaCmdAdd {
		return nil, fmt.Errorf("command type %d requires DLT\\x04 format", t)
	}
	isAdd := t == DeltaCmdAdd || t == DeltaCmdBigAdd
	isMove := t == DeltaCmdMove || t == DeltaCmdBigMove
	width := DeltaU32Size
	if t == DeltaCmdBigCopy || t == DeltaCmdBigAdd || t == DeltaCmdBigMove {
		width = DeltaU64Size
	}

	var val [len(fieldNames)]int
	first := 0
	if isAdd {
		first = 1
	}
	if len(d.data) < (len(val)-first)*width {
		return nil, errEOF
	}
	for i := first; i < len(val); i++ {
		var err error
		if val[i], err = d.uint(width); err != nil {
			return nil, fmt.Errorf("%s %s: %w", name, fieldNames[i], err)
		}
	}
	src, dst, length := val[0], val[1], val[2]

	if isAdd && length > len(d.data) {
		return nil, errEOF
	}
	if dst > d.versionSize || length > d.versionSize-dst {
		return nil, fmt.Errorf("%s command exceeds version size", name)
	}
	switch {
	case isAdd:
		data := bytes.Clone(d.data[:length])
		d.data = d.data[length:]
		return PlacedAdd{DstOff: dst, Data: data}, nil
	case isMove:
		if src > dst-length {
			return nil, fmt.Errorf("%s src+length > dst: encoder ordering constraint violated", name)
		}
		return PlacedMove{Src: src, DstOff: dst, Length: length}, nil
	default:
		return PlacedCopy{Src: src, DstOff: dst, Length: length}, nil
	}
}

// DecodeDelta parses a delta file in either format. It checks that the
// file is well formed and that every command writes within the version;
// ValidatePlacedCommands checks the commands against a reference.
func DecodeDelta(data []byte) (DecodeResult, error) {
	var res DecodeResult
	d := decoder{data: data}
	headerSize, sizeWidth := DeltaHeaderSize, DeltaU32Size
	switch {
	case bytes.HasPrefix(data, []byte(DeltaMagic)):
	case bytes.HasPrefix(data, []byte(DeltaMagicLarge)):
		d.large = true
		headerSize, sizeWidth = DeltaHeaderSizeLarge, DeltaU64Size
	default:
		return res, errNotDelta
	}
	if len(data) < headerSize {
		return res, errNotDelta
	}

	res.Inplace = data[4]&DeltaFlagInplace != 0
	d.data = data[5:]
	var err error
	if d.versionSize, err = d.uint(sizeWidth); err != nil {
		return DecodeResult{}, fmt.Errorf("version_size: %w", err)
	}
	res.VersionSize = d.versionSize
	d.data = d.data[copy(res.SrcCrc[:], d.data):]
	d.data = d.data[copy(res.DstCrc[:], d.data):]

	for {
		if len(d.data) == 0 {
			return DecodeResult{}, errors.New("missing END command")
		}
		t := d.data[0]
		d.data = d.data[1:]
		if t == DeltaCmdEnd {
			break
		}
		cmd, err := d.command(t)
		if err != nil {
			return DecodeResult{}, err
		}
		res.Commands = append(res.Commands, cmd)
	}
	if len(d.data) != 0 {
		return DecodeResult{}, errors.New("trailing data after END")
	}
	return res, nil
}
