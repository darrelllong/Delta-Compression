// Karp-Rabin fingerprints (Section 2.1.3) and the primality test used to
// size hash tables.  The fingerprint functions called once for each byte are
// in delta.h.

#include "internal.h"

uint64_t
delta_precompute_bp(size_t p)
{
	if (p == 0) {
		return 1;
	}
	uint64_t result = 1;
	uint64_t base = DELTA_HASH_BASE;
	for (size_t exp = p - 1; exp > 0; exp >>= 1) {
		if (exp & 1) {
			result = delta_mod_mersenne((__uint128_t)result * base);
		}
		base = delta_mod_mersenne((__uint128_t)base * base);
	}
	return result;
}

void
delta_rh_init(delta_rolling_hash_t *rh, const uint8_t *data,
              size_t offset, size_t p)
{
	rh->bp = delta_precompute_bp(p);
	rh->p = p;
	rh->value = delta_fingerprint(data, offset, p);
}

static uint64_t
mul_mod(uint64_t a, uint64_t b, uint64_t n)
{
	return (uint64_t)((__uint128_t)a * b % n);
}

static uint64_t
pow_mod(uint64_t base, uint64_t exp, uint64_t n)
{
	uint64_t result = 1;
	base %= n;
	for (; exp > 0; exp >>= 1) {
		if (exp & 1) {
			result = mul_mod(result, base, n);
		}
		base = mul_mod(base, base, n);
	}
	return result;
}

// is_witness reports whether a proves the odd number n = d 2^s + 1 composite.
static bool
is_witness(uint64_t a, uint64_t n, uint64_t d, unsigned s)
{
	uint64_t x = pow_mod(a, d, n);
	for (unsigned i = 0; i < s; i++) {
		uint64_t y = mul_mod(x, x, n);
		if (y == 1 && x != 1 && x != n - 1) {
			return true;
		}
		x = y;
	}
	return x != 1;
}

// The first twelve primes are witnesses enough for every n below 3.18e23,
// which covers 64 bits (Sorenson and Webster, Math. Comp. 86(304), 2017).
bool
delta_is_prime(size_t n)
{
	static const uint64_t witnesses[] = {
		2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37
	};

	if (n < 2) {
		return false;
	}
	if (n < 4) {
		return true;
	}
	if (n % 2 == 0) {
		return false;
	}

	uint64_t d = n - 1;
	unsigned s = 0;
	while (d % 2 == 0) {
		d /= 2;
		s++;
	}
	for (size_t i = 0; i < sizeof(witnesses) / sizeof(witnesses[0]); i++) {
		if (witnesses[i] >= n) {
			break;
		}
		if (is_witness(witnesses[i], n, d, s)) {
			return false;
		}
	}
	return true;
}

size_t
delta_next_prime(size_t n)
{
	if (n <= 2) {
		return 2;
	}
	size_t c = n | 1;
	while (!delta_is_prime(c)) {
		c += 2;
	}
	return c;
}
