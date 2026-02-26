// hash.c — Karp-Rabin rolling hash and Miller-Rabin primality testing
//
// Polynomial fingerprints over the Mersenne prime 2^61-1 (Section 2.1.3).

#include "delta.h"

#include <stdlib.h>
#include <string.h>

// ── Mersenne prime arithmetic ──────────────────────────────────────────

uint64_t
delta_mod_mersenne(__uint128_t x)
{
	__uint128_t m = DELTA_HASH_MOD;
	__uint128_t r = (x >> 61) + (x & m);
	if (r >= m) { r -= m; }
	r = (r >> 61) + (r & m);
	if (r >= m) { r -= m; }
	return (uint64_t)r;
}

// ── Karp-Rabin fingerprint (Eq. 1, Section 2.1.3) ────────────────────

uint64_t
delta_fingerprint(const uint8_t *data, size_t offset, size_t p)
{
	uint64_t h = 0;
	size_t i;
	for (i = 0; i < p; i++) {
		h = delta_mod_mersenne((__uint128_t)h * DELTA_HASH_BASE +
		                       data[offset + i]);
	}
	return h;
}

// ── Precompute HASH_BASE^{p-1} mod HASH_MOD ───────────────────────────

uint64_t
delta_precompute_bp(size_t p)
{
	uint64_t result = 1;
	uint64_t base = DELTA_HASH_BASE;
	size_t exp;
	if (p == 0) { return 1; }
	exp = p - 1;
	while (exp > 0) {
		if (exp & 1) {
			result = delta_mod_mersenne((__uint128_t)result * base);
		}
		base = delta_mod_mersenne((__uint128_t)base * base);
		exp >>= 1;
	}
	return result;
}

// ── Rolling hash ───────────────────────────────────────────────────────

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

// ── Rolling hash advance helper ────────────────────────────────────────

uint64_t
delta_rh_advance(delta_rolling_hash_t *rh, int *valid, size_t *rh_pos,
                 const uint8_t *data, size_t target, size_t p)
{
	if (*valid && target == *rh_pos) {
		// already positioned
	} else if (*valid && target == *rh_pos + 1) {
		delta_rh_roll(rh, data[target - 1], data[target + p - 1]);
		*rh_pos = target;
	} else {
		delta_rh_init(rh, data, target, p);
		*valid = 1;
		*rh_pos = target;
	}
	return rh->value;
}

// ── Primality testing ──────────────────────────────────────────────────

static uint64_t
power_mod(uint64_t base, uint64_t exp, uint64_t modulus)
{
	__uint128_t m = modulus;
	__uint128_t result = 1;
	__uint128_t b;
	if (modulus == 1) { return 0; }
	b = (__uint128_t)base % m;
	while (exp > 0) {
		if (exp & 1) {
			result = result * b % m;
		}
		exp >>= 1;
		b = b * b % m;
	}
	return (uint64_t)result;
}

// Factor n into d * 2^r.
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

// Miller-Rabin witness test: returns true if a proves n composite.
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
		if (y == 1 && x != 1 && x != n - 1) {
			return true;
		}
		x = y;
	}
	return x != 1;
}

// Fixed witnesses for deterministic Miller-Rabin.
// Sufficient for all n < 3,317,044,064,679,887,385,961,981 (> 2^81).
// Jaeschke, Math. Comp. 61(204), 1993.
static const uint64_t MR_WITNESSES[] = {
	2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37
};

bool
delta_is_prime(size_t n)
{
	uint64_t n64 = (uint64_t)n;
	size_t i;
	if (n64 < 2 || (n64 != 2 && n64 % 2 == 0)) { return false; }
	if (n64 == 2 || n64 == 3) { return true; }
	for (i = 0; i < sizeof(MR_WITNESSES) / sizeof(MR_WITNESSES[0]); i++) {
		if (MR_WITNESSES[i] >= n64) { break; }
		if (witness(MR_WITNESSES[i], n64)) { return false; }
	}
	return true;
}

size_t
delta_next_prime(size_t n)
{
	size_t c;
	if (n <= 2) { return 2; }
	c = (n % 2 == 0) ? n + 1 : n;
	while (!delta_is_prime(c)) {
		c += 2;
	}
	return c;
}

