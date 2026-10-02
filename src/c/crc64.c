// CRC-64/XZ: the ECMA-182 polynomial, reflected, with the register
// initialised to all ones and complemented at the end.

#include "internal.h"

#define CRC64_POLY 0xC96C5795D7870F42ULL // ECMA-182, bit-reversed

void
delta_crc64_xz(const uint8_t *data, size_t len, uint8_t out[DELTA_CRC_SIZE])
{
	// Filled on first use, so the first call must not race with another.
	static uint64_t table[256];
	static bool ready;

	if (!ready) {
		for (unsigned i = 0; i < 256; i++) {
			uint64_t c = i;
			for (int bit = 0; bit < 8; bit++) {
				c = (c & 1) ? (c >> 1) ^ CRC64_POLY : c >> 1;
			}
			table[i] = c;
		}
		ready = true;
	}

	uint64_t crc = ~0ULL;
	for (size_t i = 0; i < len; i++) {
		crc = table[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
	}
	crc = ~crc;

	for (int i = 0; i < DELTA_CRC_SIZE; i++) {
		out[i] = (uint8_t)(crc >> (56 - 8 * i));
	}
}
