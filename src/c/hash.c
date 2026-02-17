/*
 * hash.c — Karp-Rabin rolling hash and Miller-Rabin primality testing
 *
 * Polynomial fingerprints over the Mersenne prime 2^61-1 (Section 2.1.3).
 */

#include "delta.h"

#include <stdlib.h>
#include <string.h>
#include <time.h>

/* ── Mersenne prime arithmetic ─────────────────────────────────────── */

uint64_t
delta_mod_mersenne(__uint128_t x)
{
	__uint128_t m = DELTA_HASH_MOD;
	__uint128_t r = (x >> 61) + (x & m);
	if (r >= m) r -= m;
	r = (r >> 61) + (r & m);
	if (r >= m) r -= m;
	return (uint64_t)r;
}

/* ── Karp-Rabin fingerprint (Eq. 1, Section 2.1.3) ────────────────── */

uint64_t
delta_fingerprint(const uint8_t *data, size_t offset, size_t p)
{
	uint64_t h = 0;
	size_t i;
	for (i = 0; i < p; i++)
		h = delta_mod_mersenne((__uint128_t)h * DELTA_HASH_BASE +
		                       data[offset + i]);
	return h;
}

/* ── Precompute HASH_BASE^{p-1} mod HASH_MOD ──────────────────────── */

uint64_t
delta_precompute_bp(size_t p)
{
	uint64_t result = 1;
	uint64_t base = DELTA_HASH_BASE;
	size_t exp;
	if (p == 0) return 1;
	exp = p - 1;
	while (exp > 0) {
		if (exp & 1)
			result = delta_mod_mersenne((__uint128_t)result * base);
		base = delta_mod_mersenne((__uint128_t)base * base);
		exp >>= 1;
	}
	return result;
}

/* ── Rolling hash ──────────────────────────────────────────────────── */

void
delta_rh_init(delta_rolling_hash_t *rh, const uint8_t *data,
              size_t offset, size_t p)
{
	rh->bp = delta_precompute_bp(p);
	rh->p = p;
	rh->value = delta_fingerprint(data, offset, p);
}

void
delta_rh_roll(delta_rolling_hash_t *rh, uint8_t old_byte, uint8_t new_byte)
{
	uint64_t sub = delta_mod_mersenne((__uint128_t)old_byte * rh->bp);
	uint64_t v = (rh->value >= sub) ? (rh->value - sub)
	                                : (DELTA_HASH_MOD - (sub - rh->value));
	rh->value = delta_mod_mersenne((__uint128_t)v * DELTA_HASH_BASE +
	                               new_byte);
}

/* ── Primality testing ─────────────────────────────────────────────── */

static uint64_t
power_mod(uint64_t base, uint64_t exp, uint64_t modulus)
{
	__uint128_t m = modulus;
	__uint128_t result = 1;
	__uint128_t b;
	if (modulus == 1) return 0;
	b = (__uint128_t)base % m;
	while (exp > 0) {
		if (exp & 1)
			result = result * b % m;
		exp >>= 1;
		b = b * b % m;
	}
	return (uint64_t)result;
}

/* Factor n into d * 2^r. */
static void
factor_pow2(uint64_t n, uint64_t *d, uint32_t *r)
{
	*r = 0;
	while (n % 2 == 0) {
		n /= 2;
		(*r)++;
	}
	*d = n;
}

/* Miller-Rabin witness test: returns true if a proves n composite. */
static bool
witness(uint64_t a, uint64_t n)
{
	uint64_t d;
	uint32_t r, i;
	uint64_t x, y;
	factor_pow2(n - 1, &d, &r);
	x = power_mod(a, d, n);
	for (i = 0; i < r; i++) {
		y = power_mod(x, 2, n);
		if (y == 1 && x != 1 && x != n - 1)
			return true;
		x = y;
	}
	return x != 1;
}

/* Simple xorshift64 PRNG for witness generation. */
static uint64_t
xorshift64(uint64_t *state)
{
	uint64_t s = *state;
	s ^= s << 13;
	s ^= s >> 7;
	s ^= s << 17;
	*state = s;
	return s;
}

bool
delta_is_prime(size_t n)
{
	uint64_t n64 = (uint64_t)n;
	uint64_t rng_state;
	uint32_t i;
	if (n64 < 2 || (n64 != 2 && n64 % 2 == 0)) return false;
	if (n64 == 2 || n64 == 3) return true;

	rng_state = n64 ^ 0xdeadbeefcafebabeULL ^ (uint64_t)time(NULL);
	for (i = 0; i < 100; i++) {
		uint64_t a = xorshift64(&rng_state) % (n64 - 3) + 2;
		if (witness(a, n64)) return false;
	}
	return true;
}

size_t
delta_next_prime(size_t n)
{
	size_t c;
	if (n <= 2) return 2;
	c = (n % 2 == 0) ? n + 1 : n;
	while (!delta_is_prime(c))
		c += 2;
	return c;
}
