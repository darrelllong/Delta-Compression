package delta

import "fmt"

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

// EncodeDelta serializes placed commands to the unified binary delta format.
func EncodeDelta(commands []PlacedCommand, inplace bool, versionSize int,
	srcCrc, dstCrc [8]byte) []byte {

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

	return out[:pos]
}

// DecodeDelta parses the unified binary delta format.
func DecodeDelta(data []byte) (DecodeResult, error) {
	if len(data) < DeltaHeaderSize {
		return DecodeResult{}, fmt.Errorf("not a delta file")
	}
	for i := 0; i < 4; i++ {
		if data[i] != DeltaMagic[i] {
			return DecodeResult{}, fmt.Errorf("not a delta file")
		}
	}

	inplace := (data[4] & DeltaFlagInplace) != 0
	versionSize := getU32BE(data, 5)
	if versionSize < 0 {
		return DecodeResult{}, fmt.Errorf("invalid version size")
	}
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
			if pos+DeltaCopyPayload > len(data) {
				return DecodeResult{}, fmt.Errorf("unexpected EOF")
			}
			src := getU32BE(data, pos)
			pos += DeltaU32Size
			dst := getU32BE(data, pos)
			pos += DeltaU32Size
			length := getU32BE(data, pos)
			pos += DeltaU32Size
			if err := validatePlacedRange(dst, length, versionSize, "copy"); err != nil {
				return DecodeResult{}, err
			}
			if src < 0 {
				return DecodeResult{}, fmt.Errorf("copy src out of range")
			}
			commands = append(commands, PlacedCopy{Src: src, DstOff: dst, Length: length})
		case DeltaCmdAdd:
			if pos+DeltaAddHeader > len(data) {
				return DecodeResult{}, fmt.Errorf("unexpected EOF")
			}
			dst := getU32BE(data, pos)
			pos += DeltaU32Size
			length := getU32BE(data, pos)
			pos += DeltaU32Size
			if pos+length > len(data) {
				return DecodeResult{}, fmt.Errorf("unexpected EOF")
			}
			if err := validatePlacedRange(dst, length, versionSize, "add"); err != nil {
				return DecodeResult{}, err
			}
			payload := make([]byte, length)
			copy(payload, data[pos:pos+length])
			pos += length
			commands = append(commands, PlacedAdd{DstOff: dst, Data: payload})
		default:
			return DecodeResult{}, fmt.Errorf("unknown command type: %d", t)
		}
	}
	if !sawEnd {
		return DecodeResult{}, fmt.Errorf("missing END command")
	}
	if pos != len(data) {
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

// IsInplaceDelta reports whether data is an in-place delta file.
func IsInplaceDelta(data []byte) bool {
	if len(data) < 5 {
		return false
	}
	for i := 0; i < 4; i++ {
		if data[i] != DeltaMagic[i] {
			return false
		}
	}
	return (data[4] & DeltaFlagInplace) != 0
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

func validatePlacedRange(dst, length, versionSize int, kind string) error {
	if dst < 0 || length < 0 {
		return fmt.Errorf("%s command out of range", kind)
	}
	if dst > versionSize || length > versionSize-dst {
		return fmt.Errorf("%s command exceeds version size", kind)
	}
	return nil
}
