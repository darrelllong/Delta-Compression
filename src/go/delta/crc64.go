package delta

import (
	"encoding/binary"
	"hash/crc64"
)

// crc64.ECMA is the reflected ECMA-182 polynomial that CRC-64/XZ uses, and
// crc64.Checksum applies the same all-ones initial value and final
// inversion.
var crc64Table = crc64.MakeTable(crc64.ECMA)

// Crc64XZ returns the CRC-64/XZ checksum of data, big-endian.
// The checksum of "123456789" is 995dc9bbdf1939fa.
func Crc64XZ(data []byte) [8]byte {
	var out [8]byte
	binary.BigEndian.PutUint64(out[:], crc64.Checksum(data, crc64Table))
	return out
}
