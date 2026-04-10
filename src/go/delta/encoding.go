package delta

import (
	"fmt"
	"math"
)

// Unified binary delta format (Section 2.1.1).
//
// Header: magic(4) + flags(1) + version_size(u32 BE) + src_crc(8) + dst_crc(8)
// Commands:
//   END:  type=0
//   COPY: type=1, src:u32, dst:u32, len:u32
//   ADD:  type=2, dst:u32, len:u32, data

// DecodeResult holds the parsed contents of a delta file.
type DecodeResult struct {
	Commands    []PlacedCommand // Placed commands to execute during apply.
	Inplace     bool            // True if the delta uses the in-place format.
	VersionSize int             // Byte length of the reconstructed version.
	SrcCrc      [8]byte         // CRC-64/XZ of the reference (8 bytes big-endian).
	DstCrc      [8]byte         // CRC-64/XZ of the version (8 bytes big-endian).
}

const maxU32 = 1<<32 - 1

func checkU32(val int, field string) error {
	if val < 0 || val > maxU32 {
		return fmt.Errorf("%s exceeds 4 GiB (32-bit format limit)", field)
	}
	return nil
}

// EncodeDelta serializes placed commands to the unified binary delta format.
func EncodeDelta(commands []PlacedCommand, inplace bool, versionSize int,
	srcCrc, dstCrc [8]byte) ([]byte, error) {

	if err := checkU32(versionSize, "version_size"); err != nil {
		return nil, err
	}
	for _, cmd := range commands {
		switch c := cmd.(type) {
		case PlacedCopy:
			if err := checkU32(c.Src, "copy src offset"); err != nil {
				return nil, err
			}
			if err := checkU32(c.DstOff, "copy dst offset"); err != nil {
				return nil, err
			}
			if err := checkU32(c.Length, "copy length"); err != nil {
				return nil, err
			}
		case PlacedAdd:
			if err := checkU32(c.DstOff, "add dst offset"); err != nil {
				return nil, err
			}
			if err := checkU32(len(c.Data), "add length"); err != nil {
				return nil, err
			}
		}
	}

	// Estimate size: header + commands + END(1).
	est := DeltaHeaderSize + 1
	for _, cmd := range commands {
		switch cmd.(type) {
		case PlacedCopy:
			est += 1 + DeltaCopyPayload
		case PlacedAdd:
			est += 1 + DeltaAddHeader + len(cmd.(PlacedAdd).Data)
		}
	}
	out := make([]byte, est)
	pos := 0

	// Header.
	copy(out[pos:], DeltaMagic)
	pos += 4
	if inplace {
		out[pos] = DeltaFlagInplace
	}
	pos++
	putU32BE(out, pos, versionSize)
	pos += DeltaU32Size
	copy(out[pos:], srcCrc[:])
	pos += DeltaCrcSize
	copy(out[pos:], dstCrc[:])
	pos += DeltaCrcSize

	for _, cmd := range commands {
		switch c := cmd.(type) {
		case PlacedCopy:
			out[pos] = DeltaCmdCopy
			pos++
			putU32BE(out, pos, c.Src)
			pos += DeltaU32Size
			putU32BE(out, pos, c.DstOff)
			pos += DeltaU32Size
			putU32BE(out, pos, c.Length)
			pos += DeltaU32Size
		case PlacedAdd:
			out[pos] = DeltaCmdAdd
			pos++
			putU32BE(out, pos, c.DstOff)
			pos += DeltaU32Size
			putU32BE(out, pos, len(c.Data))
			pos += DeltaU32Size
			copy(out[pos:], c.Data)
			pos += len(c.Data)
		}
	}

	out[pos] = DeltaCmdEnd
	pos++

	return out[:pos], nil
}

// EncodeDeltaLarge serializes placed commands to the DLT\x04 binary delta format.
//
// Per-command size selection: COPY/BIGCOPY, ADD/BIGADD, MOVE/BIGMOVE chosen
// based on whether all fields fit in u32. This function is infallible: on
// 64-bit platforms int ≤ 63 bits, which always fits in u64.
func EncodeDeltaLarge(commands []PlacedCommand, inplace bool, versionSize int,
	srcCrc, dstCrc [8]byte) []byte {

	// Worst-case estimate: V4 header + BIGCOPY/BIGMOVE per cmd + END.
	est := DeltaHeaderSizeLarge + 1
	for _, cmd := range commands {
		switch c := cmd.(type) {
		case PlacedCopy:
			est += 1 + DeltaBigCopyPayload
		case PlacedAdd:
			est += 1 + DeltaBigAddHeader + len(c.Data)
		case PlacedMove:
			est += 1 + DeltaBigCopyPayload
		}
	}
	out := make([]byte, est)
	pos := 0

	// V4 header: magic(4) + flags(1) + version_size(u64 BE) + crcs(16).
	copy(out[pos:], DeltaMagicLarge)
	pos += 4
	if inplace {
		out[pos] = DeltaFlagInplace
	}
	pos++
	putU64BE(out, pos, versionSize)
	pos += DeltaU64Size
	copy(out[pos:], srcCrc[:])
	pos += DeltaCrcSize
	copy(out[pos:], dstCrc[:])
	pos += DeltaCrcSize

	for _, cmd := range commands {
		switch c := cmd.(type) {
		case PlacedCopy:
			if c.Src <= maxU32 && c.DstOff <= maxU32 && c.Length <= maxU32 {
				out[pos] = DeltaCmdCopy; pos++
				putU32BE(out, pos, c.Src); pos += DeltaU32Size
				putU32BE(out, pos, c.DstOff); pos += DeltaU32Size
				putU32BE(out, pos, c.Length); pos += DeltaU32Size
			} else {
				out[pos] = DeltaCmdBigCopy; pos++
				putU64BE(out, pos, c.Src); pos += DeltaU64Size
				putU64BE(out, pos, c.DstOff); pos += DeltaU64Size
				putU64BE(out, pos, c.Length); pos += DeltaU64Size
			}
		case PlacedAdd:
			if c.DstOff <= maxU32 && len(c.Data) <= maxU32 {
				out[pos] = DeltaCmdAdd; pos++
				putU32BE(out, pos, c.DstOff); pos += DeltaU32Size
				putU32BE(out, pos, len(c.Data)); pos += DeltaU32Size
			} else {
				out[pos] = DeltaCmdBigAdd; pos++
				putU64BE(out, pos, c.DstOff); pos += DeltaU64Size
				putU64BE(out, pos, len(c.Data)); pos += DeltaU64Size
			}
			copy(out[pos:], c.Data)
			pos += len(c.Data)
		case PlacedMove:
			if c.Src <= maxU32 && c.DstOff <= maxU32 && c.Length <= maxU32 {
				out[pos] = DeltaCmdMove; pos++
				putU32BE(out, pos, c.Src); pos += DeltaU32Size
				putU32BE(out, pos, c.DstOff); pos += DeltaU32Size
				putU32BE(out, pos, c.Length); pos += DeltaU32Size
			} else {
				out[pos] = DeltaCmdBigMove; pos++
				putU64BE(out, pos, c.Src); pos += DeltaU64Size
				putU64BE(out, pos, c.DstOff); pos += DeltaU64Size
				putU64BE(out, pos, c.Length); pos += DeltaU64Size
			}
		}
	}

	out[pos] = DeltaCmdEnd
	pos++
	return out[:pos]
}

// DecodeDelta parses a binary delta (DLT\x03 or DLT\x04).
func DecodeDelta(data []byte) (DecodeResult, error) {
	if len(data) < 4 {
		return DecodeResult{}, fmt.Errorf("not a delta file")
	}
	switch string(data[:4]) {
	case DeltaMagic:
		return decodeDeltaSmall(data)
	case DeltaMagicLarge:
		return decodeDeltaLarge(data)
	default:
		return DecodeResult{}, fmt.Errorf("not a delta file")
	}
}

// decodeDeltaSmall parses DLT\x03 format (u32 fields; no MOVE or big variants).
func decodeDeltaSmall(data []byte) (DecodeResult, error) {
	if len(data) < DeltaHeaderSize {
		return DecodeResult{}, fmt.Errorf("not a delta file")
	}
	inplace := (data[4] & DeltaFlagInplace) != 0
	versionSize := getU32BE(data, 5)
	crcOff := 9
	var srcCrc, dstCrc [8]byte
	copy(srcCrc[:], data[crcOff:crcOff+DeltaCrcSize])
	copy(dstCrc[:], data[crcOff+DeltaCrcSize:crcOff+2*DeltaCrcSize])
	pos := DeltaHeaderSize

	var commands []PlacedCommand
	sawEnd := false
	for pos < len(data) {
		t := int(data[pos] & 0xFF)
		pos++
		if t == DeltaCmdEnd {
			sawEnd = true
			break
		}
		switch t {
		case DeltaCmdCopy:
			cmd, newPos, err := parseCopy(data, pos, versionSize)
			if err != nil {
				return DecodeResult{}, err
			}
			pos = newPos
			commands = append(commands, cmd)
		case DeltaCmdAdd:
			cmd, newPos, err := parseAdd(data, pos, versionSize)
			if err != nil {
				return DecodeResult{}, err
			}
			pos = newPos
			commands = append(commands, cmd)
		case DeltaCmdBigCopy, DeltaCmdBigAdd, DeltaCmdMove, DeltaCmdBigMove:
			return DecodeResult{}, fmt.Errorf("command type %d requires DLT\\x04 format", t)
		default:
			return DecodeResult{}, fmt.Errorf("unknown command type: %d", t)
		}
	}
	return finishDecode(commands, sawEnd, pos, len(data), inplace, versionSize, srcCrc, dstCrc)
}

// decodeDeltaLarge parses DLT\x04 format (u32+u64 fields, MOVE/BIGMOVE).
func decodeDeltaLarge(data []byte) (DecodeResult, error) {
	if len(data) < DeltaHeaderSizeLarge {
		return DecodeResult{}, fmt.Errorf("not a delta file")
	}
	inplace := (data[4] & DeltaFlagInplace) != 0
	versionSize, err := getU64BE(data, 5)
	if err != nil {
		return DecodeResult{}, fmt.Errorf("version_size: %w", err)
	}
	crcOff := 13 // 4 + 1 + 8
	var srcCrc, dstCrc [8]byte
	copy(srcCrc[:], data[crcOff:crcOff+DeltaCrcSize])
	copy(dstCrc[:], data[crcOff+DeltaCrcSize:crcOff+2*DeltaCrcSize])
	pos := DeltaHeaderSizeLarge

	var commands []PlacedCommand
	sawEnd := false
	for pos < len(data) {
		t := int(data[pos] & 0xFF)
		pos++
		if t == DeltaCmdEnd {
			sawEnd = true
			break
		}
		switch t {
		case DeltaCmdCopy:
			cmd, newPos, err := parseCopy(data, pos, versionSize)
			if err != nil {
				return DecodeResult{}, err
			}
			pos = newPos
			commands = append(commands, cmd)
		case DeltaCmdAdd:
			cmd, newPos, err := parseAdd(data, pos, versionSize)
			if err != nil {
				return DecodeResult{}, err
			}
			pos = newPos
			commands = append(commands, cmd)
		case DeltaCmdBigCopy:
			if pos+DeltaBigCopyPayload > len(data) {
				return DecodeResult{}, fmt.Errorf("unexpected EOF")
			}
			src, err := getU64BE(data, pos); pos += DeltaU64Size
			if err != nil {
				return DecodeResult{}, fmt.Errorf("bigcopy src: %w", err)
			}
			dst, err := getU64BE(data, pos); pos += DeltaU64Size
			if err != nil {
				return DecodeResult{}, fmt.Errorf("bigcopy dst: %w", err)
			}
			length, err := getU64BE(data, pos); pos += DeltaU64Size
			if err != nil {
				return DecodeResult{}, fmt.Errorf("bigcopy length: %w", err)
			}
			if err := validatePlacedRange(dst, length, versionSize, "bigcopy"); err != nil {
				return DecodeResult{}, err
			}
			commands = append(commands, PlacedCopy{Src: src, DstOff: dst, Length: length})
		case DeltaCmdBigAdd:
			if pos+DeltaBigAddHeader > len(data) {
				return DecodeResult{}, fmt.Errorf("unexpected EOF")
			}
			dst, err := getU64BE(data, pos); pos += DeltaU64Size
			if err != nil {
				return DecodeResult{}, fmt.Errorf("bigadd dst: %w", err)
			}
			length, err := getU64BE(data, pos); pos += DeltaU64Size
			if err != nil {
				return DecodeResult{}, fmt.Errorf("bigadd length: %w", err)
			}
			if pos+length > len(data) {
				return DecodeResult{}, fmt.Errorf("unexpected EOF")
			}
			if err := validatePlacedRange(dst, length, versionSize, "bigadd"); err != nil {
				return DecodeResult{}, err
			}
			payload := make([]byte, length)
			copy(payload, data[pos:pos+length])
			pos += length
			commands = append(commands, PlacedAdd{DstOff: dst, Data: payload})
		case DeltaCmdMove:
			if pos+DeltaCopyPayload > len(data) {
				return DecodeResult{}, fmt.Errorf("unexpected EOF")
			}
			src := getU32BE(data, pos); pos += DeltaU32Size
			dst := getU32BE(data, pos); pos += DeltaU32Size
			length := getU32BE(data, pos); pos += DeltaU32Size
			if err := validatePlacedRange(dst, length, versionSize, "move"); err != nil {
				return DecodeResult{}, err
			}
			if src+length > dst {
				return DecodeResult{}, fmt.Errorf("move src+length > dst: encoder ordering constraint violated")
			}
			commands = append(commands, PlacedMove{Src: src, DstOff: dst, Length: length})
		case DeltaCmdBigMove:
			if pos+DeltaBigCopyPayload > len(data) {
				return DecodeResult{}, fmt.Errorf("unexpected EOF")
			}
			src, err := getU64BE(data, pos); pos += DeltaU64Size
			if err != nil {
				return DecodeResult{}, fmt.Errorf("bigmove src: %w", err)
			}
			dst, err := getU64BE(data, pos); pos += DeltaU64Size
			if err != nil {
				return DecodeResult{}, fmt.Errorf("bigmove dst: %w", err)
			}
			length, err := getU64BE(data, pos); pos += DeltaU64Size
			if err != nil {
				return DecodeResult{}, fmt.Errorf("bigmove length: %w", err)
			}
			if err := validatePlacedRange(dst, length, versionSize, "bigmove"); err != nil {
				return DecodeResult{}, err
			}
			if src+length > dst {
				return DecodeResult{}, fmt.Errorf("bigmove src+length > dst: encoder ordering constraint violated")
			}
			commands = append(commands, PlacedMove{Src: src, DstOff: dst, Length: length})
		default:
			return DecodeResult{}, fmt.Errorf("unknown command type: %d", t)
		}
	}
	return finishDecode(commands, sawEnd, pos, len(data), inplace, versionSize, srcCrc, dstCrc)
}

func finishDecode(commands []PlacedCommand, sawEnd bool, pos, dataLen int,
	inplace bool, versionSize int, srcCrc, dstCrc [8]byte) (DecodeResult, error) {
	if !sawEnd {
		return DecodeResult{}, fmt.Errorf("missing END command")
	}
	if pos != dataLen {
		return DecodeResult{}, fmt.Errorf("trailing data after END")
	}
	return DecodeResult{
		Commands:    commands,
		Inplace:     inplace,
		VersionSize: versionSize,
		SrcCrc:      srcCrc,
		DstCrc:      dstCrc,
	}, nil
}

// IsInplaceDelta reports whether data is an in-place delta (DLT\x03 or DLT\x04).
func IsInplaceDelta(data []byte) bool {
	if len(data) < 5 {
		return false
	}
	magic := string(data[:4])
	return (magic == DeltaMagic || magic == DeltaMagicLarge) &&
		(data[4]&DeltaFlagInplace) != 0
}

// putU32BE writes value as a 32-bit unsigned integer in big-endian byte order.
func putU32BE(buf []byte, off, value int) {
	buf[off] = byte(value >> 24)
	buf[off+1] = byte(value >> 16)
	buf[off+2] = byte(value >> 8)
	buf[off+3] = byte(value)
}

// getU32BE reads a 32-bit unsigned integer in big-endian byte order.
func getU32BE(buf []byte, off int) int {
	return int(buf[off])<<24 | int(buf[off+1])<<16 | int(buf[off+2])<<8 | int(buf[off+3])
}

// putU64BE writes value as a 64-bit unsigned integer in big-endian byte order.
func putU64BE(buf []byte, off, value int) {
	buf[off]   = byte(value >> 56)
	buf[off+1] = byte(value >> 48)
	buf[off+2] = byte(value >> 40)
	buf[off+3] = byte(value >> 32)
	buf[off+4] = byte(value >> 24)
	buf[off+5] = byte(value >> 16)
	buf[off+6] = byte(value >> 8)
	buf[off+7] = byte(value)
}

// getU64BE reads a 64-bit unsigned integer in big-endian byte order.
// Returns an error if the value exceeds math.MaxInt (truncation on 32-bit platforms).
func getU64BE(buf []byte, off int) (int, error) {
	v := uint64(buf[off])<<56 | uint64(buf[off+1])<<48 | uint64(buf[off+2])<<40 | uint64(buf[off+3])<<32 |
		uint64(buf[off+4])<<24 | uint64(buf[off+5])<<16 | uint64(buf[off+6])<<8 | uint64(buf[off+7])
	if v > math.MaxInt {
		return 0, fmt.Errorf("delta field %d overflows int on this platform", v)
	}
	return int(v), nil
}

// parseCopy reads a u32 COPY command starting at pos and returns the command
// and the updated position. Both decoders share this helper.
func parseCopy(data []byte, pos, versionSize int) (PlacedCopy, int, error) {
	if pos+DeltaCopyPayload > len(data) {
		return PlacedCopy{}, pos, fmt.Errorf("unexpected EOF")
	}
	src := getU32BE(data, pos); pos += DeltaU32Size
	dst := getU32BE(data, pos); pos += DeltaU32Size
	length := getU32BE(data, pos); pos += DeltaU32Size
	if err := validatePlacedRange(dst, length, versionSize, "copy"); err != nil {
		return PlacedCopy{}, pos, err
	}
	return PlacedCopy{Src: src, DstOff: dst, Length: length}, pos, nil
}

// parseAdd reads a u32 ADD command starting at pos and returns the command
// and the updated position. Both decoders share this helper.
func parseAdd(data []byte, pos, versionSize int) (PlacedAdd, int, error) {
	if pos+DeltaAddHeader > len(data) {
		return PlacedAdd{}, pos, fmt.Errorf("unexpected EOF")
	}
	dst := getU32BE(data, pos); pos += DeltaU32Size
	length := getU32BE(data, pos); pos += DeltaU32Size
	if pos+length > len(data) {
		return PlacedAdd{}, pos, fmt.Errorf("unexpected EOF")
	}
	if err := validatePlacedRange(dst, length, versionSize, "add"); err != nil {
		return PlacedAdd{}, pos, err
	}
	payload := make([]byte, length)
	copy(payload, data[pos:pos+length])
	return PlacedAdd{DstOff: dst, Data: payload}, pos + length, nil
}

func validatePlacedRange(dst, length, versionSize int, kind string) error {
	if dst < 0 || length < 0 {
		return fmt.Errorf("%s command out of range", kind)
	}
	if dst > versionSize || length > versionSize-dst {
		return fmt.Errorf("%s command exceeds version size", kind)
	}
	return nil
}
