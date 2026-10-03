// CRC-64/XZ: the ECMA-182 polynomial, reflected, with the register
// initialised to all ones and complemented at the end.
//
// The slicing-by-8 method (Kounavis and Berry, Intel, 2005): eight tables,
// table[k][b] being the CRC of byte b followed by k zero bytes, let eight
// bytes be folded into the register at once.

#include "internal.h"

#define CRC64_POLY 0xC96C5795D7870F42ULL // ECMA-182, bit-reversed

static uint64_t table[8][256];

static void
init_tables(void)
{
	for (unsigned i = 0; i < 256; i++) {
		uint64_t c = i;
		for (int bit = 0; bit < 8; bit++) {
			c = (c & 1) ? (c >> 1) ^ CRC64_POLY : c >> 1;
		}
		table[0][i] = c;
	}
	for (int k = 1; k < 8; k++) {
		for (unsigned i = 0; i < 256; i++) {
			uint64_t c = table[k - 1][i];
			table[k][i] = table[0][c & 0xFF] ^ (c >> 8);
		}
	}
}

void
delta_crc64_xz(const uint8_t *data, size_t len, uint8_t out[DELTA_CRC_SIZE])
{
	// Filled on first use, so the first call must not race with another.
	static bool ready;
	if (!ready) {
		init_tables();
		ready = true;
	}

	uint64_t crc = ~0ULL;
	for (; len >= 8; data += 8, len -= 8) {
		uint64_t word;
		memcpy(&word, data, 8); // to be read as a little-endian word
#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
		word = __builtin_bswap64(word);
#endif
		crc ^= word;
		crc = table[7][crc & 0xFF] ^ table[6][(crc >> 8) & 0xFF] ^
		      table[5][(crc >> 16) & 0xFF] ^ table[4][(crc >> 24) & 0xFF] ^
		      table[3][(crc >> 32) & 0xFF] ^ table[2][(crc >> 40) & 0xFF] ^
		      table[1][(crc >> 48) & 0xFF] ^ table[0][crc >> 56];
	}
	for (; len > 0; data++, len--) {
		crc = table[0][(crc ^ *data) & 0xFF] ^ (crc >> 8);
	}
	crc = ~crc;

	for (int i = 0; i < DELTA_CRC_SIZE; i++) {
		out[i] = (uint8_t)(crc >> (56 - 8 * i));
	}
}
