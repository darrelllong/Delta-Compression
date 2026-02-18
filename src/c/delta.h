/*
 * delta.h — Differential compression (Ajtai, Burns, Fagin, Long — JACM 2002)
 *
 * Single-header API for the delta compression library.  Implements three
 * differencing algorithms (greedy, onepass, correcting), a unified binary
 * delta format, in-place reconstruction (Burns et al. — IEEE TKDE 2003),
 * and optional Tarjan-Sleator splay tree lookup.
 */

#ifndef DELTA_H
#define DELTA_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* ====================================================================
 * Constants (Section 2.1.3)
 * ==================================================================== */

#define DELTA_SEED_LEN    16
#define DELTA_TABLE_SIZE  1048573   /* largest prime < 2^20 */
#define DELTA_HASH_BASE   263ULL
#define DELTA_HASH_MOD    ((1ULL << 61) - 1)  /* Mersenne prime 2^61-1 */
#define DELTA_FLAG_INPLACE 0x01
#define DELTA_CMD_END      0
#define DELTA_CMD_COPY     1
#define DELTA_CMD_ADD      2
#define DELTA_HEADER_SIZE  9    /* magic(4) + flags(1) + version_size(4) */
#define DELTA_U32_SIZE     4
#define DELTA_COPY_PAYLOAD 12   /* src(4) + dst(4) + len(4) */
#define DELTA_ADD_HEADER   8    /* dst(4) + len(4) */
#define DELTA_BUF_CAP      256

static const uint8_t DELTA_MAGIC[4] = {'D', 'L', 'T', 0x01};

/* ── Checked allocation helpers ───────────────────────────────────── */

#include <stdio.h>
#include <stdlib.h>

static inline void *
delta_realloc(void *ptr, size_t size)
{
	void *p = realloc(ptr, size);
	if (!p && size > 0) {
		fprintf(stderr, "delta: out of memory (realloc %zu bytes)\n",
		        size);
		abort();
	}
	return p;
}

static inline void *
delta_malloc(size_t size)
{
	void *p = malloc(size);
	if (!p && size > 0) {
		fprintf(stderr, "delta: out of memory (malloc %zu bytes)\n",
		        size);
		abort();
	}
	return p;
}

static inline void *
delta_calloc(size_t count, size_t size)
{
	void *p = calloc(count, size);
	if (!p && count > 0 && size > 0) {
		fprintf(stderr,
		        "delta: out of memory (calloc %zu x %zu bytes)\n",
		        count, size);
		abort();
	}
	return p;
}

/* ====================================================================
 * Enums
 * ==================================================================== */

typedef enum { ALGO_GREEDY, ALGO_ONEPASS, ALGO_CORRECTING } delta_algorithm_t;
typedef enum { POLICY_LOCALMIN, POLICY_CONSTANT } delta_cycle_policy_t;

/* ====================================================================
 * Delta commands (Section 2.1.1)
 *
 * Algorithm output: copy from R or add literal bytes.
 * ==================================================================== */

typedef enum { CMD_COPY, CMD_ADD } delta_cmd_tag_t;

typedef struct {
	delta_cmd_tag_t tag;
	union {
		struct { size_t offset; size_t length; }          copy;
		struct { uint8_t *data;  size_t length; }         add;
	};
} delta_command_t;

/* Dynamic array of commands. */
typedef struct {
	delta_command_t *data;
	size_t len;
	size_t cap;
} delta_commands_t;

void   delta_commands_init(delta_commands_t *c);
void   delta_commands_push(delta_commands_t *c, delta_command_t cmd);
void   delta_commands_free(delta_commands_t *c);

/* ====================================================================
 * Placed commands — with explicit src/dst, ready for encoding
 * ==================================================================== */

typedef enum { PCMD_COPY, PCMD_ADD } delta_pcmd_tag_t;

typedef struct {
	delta_pcmd_tag_t tag;
	union {
		struct { size_t src; size_t dst; size_t length; }  copy;
		struct { size_t dst; uint8_t *data; size_t length; } add;
	};
} delta_placed_command_t;

typedef struct {
	delta_placed_command_t *data;
	size_t len;
	size_t cap;
} delta_placed_commands_t;

void   delta_placed_commands_init(delta_placed_commands_t *c);
void   delta_placed_commands_push(delta_placed_commands_t *c, delta_placed_command_t cmd);
void   delta_placed_commands_free(delta_placed_commands_t *c);

/* ====================================================================
 * Summary statistics
 * ==================================================================== */

typedef struct {
	size_t num_commands;
	size_t num_copies;
	size_t num_adds;
	size_t copy_bytes;
	size_t add_bytes;
	size_t total_output_bytes;
} delta_summary_t;

delta_summary_t delta_summary(const delta_commands_t *cmds);
delta_summary_t delta_placed_summary(const delta_placed_commands_t *cmds);

/* ====================================================================
 * Karp-Rabin rolling hash (Section 2.1.3)
 * ==================================================================== */

uint64_t delta_mod_mersenne(__uint128_t x);
uint64_t delta_fingerprint(const uint8_t *data, size_t offset, size_t p);
uint64_t delta_precompute_bp(size_t p);

typedef struct {
	uint64_t value;
	uint64_t bp;    /* HASH_BASE^{p-1} mod HASH_MOD */
	size_t   p;
} delta_rolling_hash_t;

void     delta_rh_init(delta_rolling_hash_t *rh, const uint8_t *data,
                       size_t offset, size_t p);
void     delta_rh_roll(delta_rolling_hash_t *rh, uint8_t old_byte,
                       uint8_t new_byte);
uint64_t delta_rh_advance(delta_rolling_hash_t *rh, int *valid,
                          size_t *rh_pos, const uint8_t *data,
                          size_t target, size_t p);

/* ====================================================================
 * Primality (for hash table auto-sizing)
 * ==================================================================== */

bool     delta_is_prime(size_t n);
size_t   delta_next_prime(size_t n);

/* ====================================================================
 * Splay tree (Sleator & Tarjan, JACM 1985)
 *
 * Keyed on uint64_t, with fixed-size values stored inline via memcpy.
 * ==================================================================== */

typedef struct delta_splay_node {
	uint64_t key;
	struct delta_splay_node *left;
	struct delta_splay_node *right;
	/* value follows in flexible array member */
	char value[];
} delta_splay_node_t;

typedef struct {
	delta_splay_node_t *root;
	size_t size;
	size_t value_size;
	void (*value_free)(void *value);  /* optional destructor for values */
} delta_splay_t;

void     delta_splay_init(delta_splay_t *t, size_t value_size);
void    *delta_splay_find(delta_splay_t *t, uint64_t key);
void    *delta_splay_insert_or_get(delta_splay_t *t, uint64_t key,
                                    const void *value);
void     delta_splay_insert(delta_splay_t *t, uint64_t key,
                             const void *value);
void     delta_splay_clear(delta_splay_t *t);
void     delta_splay_free(delta_splay_t *t);

/* ====================================================================
 * Option flags — bitset for on/off options (cf. Sleator & Tarjan set.h)
 * ==================================================================== */

typedef uint64_t delta_flags_t;

typedef enum {
	DELTA_OPT_VERBOSE = 0,
	DELTA_OPT_SPLAY   = 1,
	DELTA_OPT_INPLACE = 2
} delta_opt_flag_t;

static inline bool
delta_flag_get(delta_flags_t s, delta_opt_flag_t f)
{
	return (s >> f) & 1;
}

static inline delta_flags_t
delta_flag_set(delta_flags_t s, delta_opt_flag_t f)
{
	return s | (1ULL << f);
}

static inline delta_flags_t
delta_flag_clear(delta_flags_t s, delta_opt_flag_t f)
{
	return s & ~(1ULL << f);
}

/* ====================================================================
 * Diff options — replaces positional parameter lists
 * ==================================================================== */

typedef struct {
	size_t        p;
	size_t        q;
	size_t        buf_cap;
	delta_flags_t flags;
	size_t        min_copy;
} delta_diff_options_t;

#define DELTA_DIFF_OPTIONS_DEFAULT \
	{ DELTA_SEED_LEN, DELTA_TABLE_SIZE, DELTA_BUF_CAP, 0, 0 }

/* ====================================================================
 * Differencing algorithms
 * ==================================================================== */

void delta_print_command_stats(const delta_commands_t *cmds);

delta_commands_t delta_diff_greedy(
	const uint8_t *r, size_t r_len,
	const uint8_t *v, size_t v_len,
	const delta_diff_options_t *opts);

delta_commands_t delta_diff_onepass(
	const uint8_t *r, size_t r_len,
	const uint8_t *v, size_t v_len,
	const delta_diff_options_t *opts);

delta_commands_t delta_diff_correcting(
	const uint8_t *r, size_t r_len,
	const uint8_t *v, size_t v_len,
	const delta_diff_options_t *opts);

delta_commands_t delta_diff(
	delta_algorithm_t algo,
	const uint8_t *r, size_t r_len,
	const uint8_t *v, size_t v_len,
	const delta_diff_options_t *opts);

/* ====================================================================
 * Binary delta format encode/decode
 *
 * Format: DLT\x01 + flags:u8 + version_size:u32be + commands + END(0)
 * ==================================================================== */

typedef struct {
	uint8_t *data;
	size_t   len;
} delta_buffer_t;

delta_buffer_t delta_encode(const delta_placed_commands_t *cmds,
                            bool inplace, size_t version_size);
void           delta_buffer_free(delta_buffer_t *buf);

typedef struct {
	delta_placed_commands_t commands;
	bool   inplace;
	size_t version_size;
} delta_decode_result_t;

delta_decode_result_t delta_decode(const uint8_t *data, size_t len);
void                  delta_decode_result_free(delta_decode_result_t *dr);

/* ====================================================================
 * Command placement and application
 * ==================================================================== */

size_t delta_output_size(const delta_commands_t *cmds);

delta_placed_commands_t delta_place_commands(const delta_commands_t *cmds);

delta_commands_t delta_unplace_commands(const delta_placed_commands_t *placed);

delta_buffer_t delta_apply_placed(const uint8_t *r,
                                   const delta_placed_commands_t *cmds,
                                   size_t version_size);

void           delta_apply_placed_inplace(const delta_placed_commands_t *cmds,
                                          uint8_t *buf);

delta_buffer_t delta_apply_delta_inplace(const uint8_t *r, size_t r_len,
                                          const delta_placed_commands_t *cmds,
                                          size_t version_size);

/* ====================================================================
 * In-place conversion (Burns, Long, Stockmeyer — IEEE TKDE 2003)
 * ==================================================================== */

delta_placed_commands_t delta_make_inplace(
	const uint8_t *r, size_t r_len,
	const delta_commands_t *cmds,
	delta_cycle_policy_t policy);

#endif /* DELTA_H */
