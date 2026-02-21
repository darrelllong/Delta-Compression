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
	if (r >= m) { r -= m; }
	r = (r >> 61) + (r & m);
	if (r >= m) { r -= m; }
	return (uint64_t)r;
}

/* ── Karp-Rabin fingerprint (Eq. 1, Section 2.1.3) ────────────────── */

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

/* ── Precompute HASH_BASE^{p-1} mod HASH_MOD ──────────────────────── */

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

/* ── Rolling hash advance helper ───────────────────────────────────── */

uint64_t
delta_rh_advance(delta_rolling_hash_t *rh, int *valid, size_t *rh_pos,
                 const uint8_t *data, size_t target, size_t p)
{
	if (*valid && target == *rh_pos) {
		/* already positioned */
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

/* ── Primality testing ─────────────────────────────────────────────── */

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
		if (y == 1 && x != 1 && x != n - 1) {
			return true;
		}
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
	if (n64 < 2 || (n64 != 2 && n64 % 2 == 0)) { return false; }
	if (n64 == 2 || n64 == 3) { return true; }

	rng_state = n64 ^ 0xdeadbeefcafebabeULL ^ (uint64_t)time(NULL);
	for (i = 0; i < 100; i++) {
		uint64_t a = xorshift64(&rng_state) % (n64 - 3) + 2;
		if (witness(a, n64)) { return false; }
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

/* ── SHAKE128 (FIPS 202 XOF, 16-byte output) ───────────────────────── *
 *
 * Keccak-p[1600, 24] permutation + SHAKE128 sponge.
 * Rate = 168 bytes, domain suffix = 0x1F.
 */

/* 24 round constants for the iota step (FIPS 202 Table 5). */
static const uint64_t keccak_rc[24] = {
	0x0000000000000001ULL, 0x0000000000008082ULL,
	0x800000000000808AULL, 0x8000000080008000ULL,
	0x000000000000808BULL, 0x0000000080000001ULL,
	0x8000000080008081ULL, 0x8000000000008009ULL,
	0x000000000000008AULL, 0x0000000000000088ULL,
	0x0000000080008009ULL, 0x000000008000000AULL,
	0x000000008000808BULL, 0x800000000000008BULL,
	0x8000000000008089ULL, 0x8000000000008003ULL,
	0x8000000000008002ULL, 0x8000000000000080ULL,
	0x000000000000800AULL, 0x800000008000000AULL,
	0x8000000080008081ULL, 0x8000000000008080ULL,
	0x0000000080000001ULL, 0x8000000080008008ULL,
};

/* Rotation offsets for the rho step (FIPS 202 Table 2).
 * Indexed as state[x + 5*y]. */
static const int keccak_rho[25] = {
	 0,  1, 62, 28, 27,
	36, 44,  6, 55, 20,
	 3, 10, 43, 25, 39,
	41, 45, 15, 21,  8,
	18,  2, 61, 56, 14,
};

static inline uint64_t
rotl64(uint64_t x, int n)
{
	return n == 0 ? x : (x << n) | (x >> (64 - n));
}

static void
keccak_f1600(uint64_t A[25])
{
	int rnd, x, y;
	for (rnd = 0; rnd < 24; rnd++) {
		/* theta */
		uint64_t C[5], D[5], B[25];
		for (x = 0; x < 5; x++)
			C[x] = A[x] ^ A[x+5] ^ A[x+10] ^ A[x+15] ^ A[x+20];
		for (x = 0; x < 5; x++)
			D[x] = C[(x+4)%5] ^ rotl64(C[(x+1)%5], 1);
		for (x = 0; x < 25; x++)
			A[x] ^= D[x % 5];
		/* rho + pi (combined; FIPS 202 Appendix B optimisation)
		 * Destination B[x+5*y] receives A[src] rotated by rho[src],
		 * where src = (x+3y)%5 + 5x is the inverse-pi source lane. */
		for (y = 0; y < 5; y++)
			for (x = 0; x < 5; x++) {
				int src = (x + 3*y) % 5 + 5*x;
				B[x + 5*y] = rotl64(A[src], keccak_rho[src]);
			}
		/* chi */
		for (y = 0; y < 5; y++)
			for (x = 0; x < 5; x++)
				A[x + 5*y] = B[x + 5*y]
				           ^ ((~B[(x+1)%5 + 5*y]) & B[(x+2)%5 + 5*y]);
		/* iota */
		A[0] ^= keccak_rc[rnd];
	}
}

#define SHAKE128_RATE 168

static void
xor_into_state(uint64_t state[25], const uint8_t *data, size_t len)
{
	size_t i;
	for (i = 0; i < len; i++)
		state[i / 8] ^= (uint64_t)data[i] << (8 * (i % 8));
}

void
delta_shake128_16(const uint8_t *data, size_t len, uint8_t out[DELTA_HASH_SIZE])
{
	uint64_t state[25];
	uint8_t last[SHAKE128_RATE];
	size_t pos, rem, i;

	memset(state, 0, sizeof(state));

	/* Absorb full rate-sized blocks. */
	for (pos = 0; pos + SHAKE128_RATE <= len; pos += SHAKE128_RATE) {
		xor_into_state(state, data + pos, SHAKE128_RATE);
		keccak_f1600(state);
	}

	/* Final block with SHAKE128 multi-rate padding (FIPS 202 Sec. 6.2). */
	rem = len - pos;
	memset(last, 0, SHAKE128_RATE);
	if (rem > 0) { memcpy(last, data + pos, rem); }
	last[rem]              ^= 0x1F;
	last[SHAKE128_RATE - 1] ^= 0x80;
	xor_into_state(state, last, SHAKE128_RATE);
	keccak_f1600(state);

	/* Squeeze 16 bytes (little-endian lanes). */
	for (i = 0; i < DELTA_HASH_SIZE; i++)
		out[i] = (uint8_t)(state[i / 8] >> (8 * (i % 8)));
}

/* ── Streaming SHAKE128 ─────────────────────────────────────────────── */

void
delta_shake128_init(delta_shake128_ctx_t *ctx)
{
	memset(ctx, 0, sizeof(*ctx));
}

void
delta_shake128_update(delta_shake128_ctx_t *ctx, const uint8_t *data, size_t len)
{
	size_t i;
	for (i = 0; i < len; i++) {
		ctx->buf[ctx->buflen++] = data[i];
		if (ctx->buflen == SHAKE128_RATE) {
			xor_into_state(ctx->state, ctx->buf, SHAKE128_RATE);
			keccak_f1600(ctx->state);
			ctx->buflen = 0;
		}
	}
}

void
delta_shake128_final(delta_shake128_ctx_t *ctx, uint8_t out[DELTA_HASH_SIZE])
{
	size_t i;
	/* Pad remaining buffer (FIPS 202 Sec. 6.2). */
	memset(ctx->buf + ctx->buflen, 0, SHAKE128_RATE - ctx->buflen);
	ctx->buf[ctx->buflen]       ^= 0x1F;
	ctx->buf[SHAKE128_RATE - 1] ^= 0x80;
	xor_into_state(ctx->state, ctx->buf, SHAKE128_RATE);
	keccak_f1600(ctx->state);
	/* Squeeze exactly DELTA_HASH_SIZE (16) bytes from the first rate block.
	 * 16 < SHAKE128_RATE (168), so one permutation is always sufficient.
	 * To support d > SHAKE128_RATE output bytes, loop: extract up to
	 * SHAKE128_RATE bytes, call keccak_f1600 again, repeat. */
	for (i = 0; i < DELTA_HASH_SIZE; i++)
		out[i] = (uint8_t)(ctx->state[i / 8] >> (8 * (i % 8)));
}
