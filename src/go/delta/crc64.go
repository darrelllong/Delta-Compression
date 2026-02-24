package delta

import "sync"

// CRC-64/XZ (ECMA-182 reflected).
//
// Reflected poly: 0xC96C5795D7870F42, Init = XorOut = 0xFFFFFFFFFFFFFFFF.
// Check value: Crc64XZ([]byte("123456789")) = [8]byte big-endian for 0x995DC9BBDF1939FA.

const crc64Poly = uint64(0xC96C5795D7870F42)

var (
	crc64Once  sync.Once
	crc64Table [256]uint64
)

func initCrc64Table() {
	for i := range crc64Table {
		c := uint64(i)
		for j := 0; j < 8; j++ {
			if c&1 != 0 {
				c = (c >> 1) ^ crc64Poly
			} else {
				c >>= 1
			}
		}
		crc64Table[i] = c
	}
}

// Crc64XZ computes the CRC-64/XZ digest of data and returns it as 8 bytes big-endian.
func Crc64XZ(data []byte) [8]byte {
	crc64Once.Do(initCrc64Table)
	crc := uint64(0xFFFFFFFFFFFFFFFF)
	for _, b := range data {
		crc = crc64Table[byte(crc)^b] ^ (crc >> 8)
	}
	crc ^= 0xFFFFFFFFFFFFFFFF
	var out [8]byte
	for i := 0; i < 8; i++ {
		out[i] = byte(crc >> (56 - 8*i))
	}
	return out
}
