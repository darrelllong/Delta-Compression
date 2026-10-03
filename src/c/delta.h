// Differential compression: the greedy, one-pass and correcting 1.5-pass
// algorithms of Ajtai, Burns, Fagin, Long and Stockmeyer (JACM 49(3), 2002),
// and the in-place conversion of Burns, Long and Stockmeyer (IEEE TKDE
// 15(4), 2003).  Section and figure numbers in this library refer to the
// JACM paper unless they name the other one.
//
// R is the reference, V the version.  A diff turns (R, V) into commands; the
// commands are placed (given destinations in V), optionally reordered for
// in-place application, and encoded.
//
// Functions that return a structure return one the caller owns and releases
// with the matching _free function; it shares no storage with the arguments.
// A malformed delta is fatal: the library prints a message to stderr and
// exits with status 1.  So is exhausted memory, on which it aborts.

#ifndef DELTA_H
#define DELTA_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

// Defaults.  The seed length is that of Section 2.1.3 and the buffer that of
// Section 9.1.
#define DELTA_SEED_LEN       16           // bytes in a seed
#define DELTA_TABLE_SIZE     1048573UL    // largest prime below 2^20
#define DELTA_MAX_TABLE_SIZE 1073741827UL // smallest prime above 2^30
#define DELTA_BUF_CAP        256          // commands held for correction

// Karp-Rabin fingerprints are polynomials in DELTA_HASH_BASE modulo the
// Mersenne prime 2^61 - 1.
#define DELTA_HASH_BASE 263ULL
#define DELTA_HASH_MOD  ((1ULL << 61) - 1)

// Algorithms.

typedef enum {
	ALGO_GREEDY,     // Section 3: optimal if p <= 2; quadratic time.
	ALGO_ONEPASS,    // Section 4: linear time, space set by the table size.
	ALGO_CORRECTING  // Sections 7-8: 1.5 passes with checkpointing.
} delta_algorithm_t;

// Which copy to convert to an add when the in-place ordering has a cycle
// (Burns et al., Section 4.3).
typedef enum {
	POLICY_LOCALMIN, // The shortest copy on the cycle found.
	POLICY_CONSTANT  // The lowest-numbered copy not yet ordered.
} delta_cycle_policy_t;

// Commands.
//
// A command list reproduces V left to right: a copy takes length bytes from
// R at offset, an add supplies length literal bytes.  An add owns its data.

typedef enum { CMD_COPY, CMD_ADD } delta_cmd_tag_t;

typedef struct {
	delta_cmd_tag_t tag;
	union {
		struct { size_t offset; size_t length; } copy;
		struct { uint8_t *data; size_t length; } add;
	};
} delta_command_t;

typedef struct {
	delta_command_t *data;
	size_t len;
	size_t cap;
} delta_commands_t;

void delta_commands_init(delta_commands_t *c);

// delta_commands_push appends cmd.  The list takes over an add's data.
void delta_commands_push(delta_commands_t *c, delta_command_t cmd);
void delta_commands_free(delta_commands_t *c);

// A placed command names its destination in V, so placed commands may be
// applied in any order that respects their reads.  A move reads from V
// itself, from bytes already written; only the DLT\x04 format carries it.

typedef enum { PCMD_COPY, PCMD_ADD, PCMD_MOVE } delta_pcmd_tag_t;

typedef struct {
	delta_pcmd_tag_t tag;
	union {
		struct { size_t src; size_t dst; size_t length; }    copy;
		struct { size_t dst; uint8_t *data; size_t length; } add;
		struct { size_t src; size_t dst; size_t length; }    move;
	};
} delta_placed_command_t;

typedef struct {
	delta_placed_command_t *data;
	size_t len;
	size_t cap;
} delta_placed_commands_t;

void delta_placed_commands_init(delta_placed_commands_t *c);

// delta_placed_commands_push appends cmd.  The list takes over an add's data.
void delta_placed_commands_push(delta_placed_commands_t *c,
                                delta_placed_command_t cmd);
void delta_placed_commands_free(delta_placed_commands_t *c);

typedef struct {
	size_t num_commands;
	size_t num_copies;         // Moves count as copies.
	size_t num_adds;
	size_t copy_bytes;
	size_t add_bytes;
	size_t total_output_bytes; // copy_bytes + add_bytes
} delta_summary_t;

delta_summary_t delta_summary(const delta_commands_t *cmds);
delta_summary_t delta_placed_summary(const delta_placed_commands_t *cmds);

// Fingerprints (Section 2.1.3).

// The functions a scan calls once for each byte are defined here, so that
// every caller compiles them inline.

// delta_mod_mersenne reduces x modulo DELTA_HASH_MOD.  Since 2^61 = 1
// (mod 2^61 - 1), the high bits of x fold onto the low ones; two folds bring
// any 128-bit x into range.
static inline uint64_t
delta_mod_mersenne(__uint128_t x)
{
	__uint128_t m = DELTA_HASH_MOD;
	__uint128_t r = (x >> 61) + (x & m);
	if (r >= m) {
		r -= m;
	}
	r = (r >> 61) + (r & m);
	if (r >= m) {
		r -= m;
	}
	return (uint64_t)r;
}

// delta_fingerprint is the fingerprint of the p bytes at data + offset: the
// sum of b[i] BASE^(p-1-i) over the bytes b[0..p) (Eq. 1), evaluated by
// Horner's rule.
static inline uint64_t
delta_fingerprint(const uint8_t *data, size_t offset, size_t p)
{
	uint64_t h = 0;
	for (size_t i = 0; i < p; i++) {
		h = delta_mod_mersenne((__uint128_t)h * DELTA_HASH_BASE +
		                       data[offset + i]);
	}
	return h;
}

// delta_precompute_bp is DELTA_HASH_BASE^(p-1) mod DELTA_HASH_MOD, the
// weight of the byte that leaves a p-byte window.
uint64_t delta_precompute_bp(size_t p);

typedef struct {
	uint64_t value; // Fingerprint of the current window.
	uint64_t bp;    // delta_precompute_bp(p)
	size_t   p;
} delta_rolling_hash_t;

void delta_rh_init(delta_rolling_hash_t *rh, const uint8_t *data,
                   size_t offset, size_t p);

// delta_rh_roll slides the window one byte: old_byte leaves, new_byte enters.
static inline void
delta_rh_roll(delta_rolling_hash_t *rh, uint8_t old_byte, uint8_t new_byte)
{
	uint64_t sub = delta_mod_mersenne((__uint128_t)old_byte * rh->bp);
	uint64_t v = rh->value >= sub ? rh->value - sub
	                              : DELTA_HASH_MOD - (sub - rh->value);
	rh->value = delta_mod_mersenne((__uint128_t)v * DELTA_HASH_BASE +
	                               new_byte);
}

// delta_rh_advance returns the fingerprint of the window at target.  *valid
// and *rh_pos are the caller's record of whether rh holds a window and where;
// start with *valid zero.  A step of one byte forward rolls; any other move
// computes the fingerprint afresh.
static inline uint64_t
delta_rh_advance(delta_rolling_hash_t *rh, int *valid, size_t *rh_pos,
                 const uint8_t *data, size_t target, size_t p)
{
	if (!*valid) {
		delta_rh_init(rh, data, target, p);
		*valid = 1;
	} else if (target == *rh_pos + 1) {
		delta_rh_roll(rh, data[target - 1], data[target + p - 1]);
	} else if (target != *rh_pos) {
		rh->value = delta_fingerprint(data, target, p);
	}
	*rh_pos = target;
	return rh->value;
}

// delta_is_prime is a Miller-Rabin test, deterministic for every size_t.
bool   delta_is_prime(size_t n);

// delta_next_prime is the smallest prime not less than n.
size_t delta_next_prime(size_t n);

// Splay tree (Sleator and Tarjan, JACM 32(3), 1985), keyed by fingerprint.
//
// Each node holds value_size bytes of value, copied in and out by memcpy.
// Pointers to values stay valid until the tree is cleared.

typedef struct delta_splay_node {
	uint64_t key;
	struct delta_splay_node *left;
	struct delta_splay_node *right;
	char value[];
} delta_splay_node_t;

typedef struct {
	delta_splay_node_t *root;
	size_t size;                     // Number of keys.
	size_t value_size;
	void (*value_free)(void *value); // If set, called on each value at clear.
} delta_splay_t;

void  delta_splay_init(delta_splay_t *t, size_t value_size);

// delta_splay_find returns the value stored under key, or NULL.
void *delta_splay_find(delta_splay_t *t, uint64_t key);

// delta_splay_insert_or_get returns the value stored under key, first
// storing *value if the key is absent.
void *delta_splay_insert_or_get(delta_splay_t *t, uint64_t key,
                                const void *value);

// delta_splay_insert stores *value under key, replacing any earlier value.
void  delta_splay_insert(delta_splay_t *t, uint64_t key, const void *value);

// delta_splay_clear empties the tree, which may then be used again.
// delta_splay_free does the same.
void  delta_splay_clear(delta_splay_t *t);
void  delta_splay_free(delta_splay_t *t);

// Differencing.

typedef uint64_t delta_flags_t;

// Bit numbers in delta_flags_t.
typedef enum {
	DELTA_OPT_VERBOSE = 0, // Report table sizes and match statistics on stderr.
	DELTA_OPT_SPLAY   = 1, // Look fingerprints up in a splay tree.
	DELTA_OPT_INPLACE = 2  // Not read by the library; for the caller's use.
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

typedef struct {
	size_t p;         // Seed length: the shortest match found.  At least 1.
	size_t q;         // Least number of hash table slots; tables grow with |R|.
	                  // The greedy algorithm does not read it.
	size_t buf_cap;   // Commands the correcting algorithm can still revise;
	                  // 0 is taken as 1.
	size_t max_table; // Bound on the slots of the correcting table, before
	                  // rounding up to a prime; 0 means DELTA_MAX_TABLE_SIZE.
	delta_flags_t flags;
} delta_diff_options_t;

#define DELTA_DIFF_OPTIONS_DEFAULT \
	{ DELTA_SEED_LEN, DELTA_TABLE_SIZE, DELTA_BUF_CAP, DELTA_MAX_TABLE_SIZE, 0 }

// Each differencing function returns commands that rebuild v from r.  opts
// must not be NULL.
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

// delta_diff runs the algorithm named.
delta_commands_t delta_diff(
	delta_algorithm_t algo,
	const uint8_t *r, size_t r_len,
	const uint8_t *v, size_t v_len,
	const delta_diff_options_t *opts);

// delta_print_command_stats writes copy and add totals, and the distribution
// of copy lengths, to stderr.
void delta_print_command_stats(const delta_commands_t *cmds);

// Binary format.
//
//   DLT\x03: magic, flags (1), version size (u32), CRC of R (8), CRC of V (8)
//   DLT\x04: the same with a u64 version size
//
// then commands, each a type byte and big-endian fields:
//
//   0 END
//   1 COPY     src dst len    (u32)        3 BIGCOPY  (u64)
//   2 ADD      dst len data   (u32)        4 BIGADD   (u64)
//   5 MOVE     src dst len    (u32)        6 BIGMOVE  (u64)
//
// DLT\x03 has only END, COPY and ADD.

#define DELTA_FLAG_INPLACE 0x01 // header flag: commands are in in-place order

#define DELTA_CMD_END     0
#define DELTA_CMD_COPY    1
#define DELTA_CMD_ADD     2
#define DELTA_CMD_BIGCOPY 3
#define DELTA_CMD_BIGADD  4
#define DELTA_CMD_MOVE    5
#define DELTA_CMD_BIGMOVE 6

#define DELTA_CRC_SIZE          8
#define DELTA_HEADER_SIZE       25
#define DELTA_HEADER_SIZE_LARGE 29

// delta_crc64_xz writes the CRC-64/XZ of data to out, most significant byte
// first.  The CRC of "123456789" is 995dc9bbdf1939fa.  The first call fills
// the tables and must not race with another.
void delta_crc64_xz(const uint8_t *data, size_t len,
                    uint8_t out[DELTA_CRC_SIZE]);

typedef struct {
	uint8_t *data;
	size_t   len;
} delta_buffer_t;

void delta_buffer_init(delta_buffer_t *buf);
void delta_buffer_free(delta_buffer_t *buf);

// delta_encode writes DLT\x03.  It exits if a field does not fit in 32 bits
// or if there is a move.
delta_buffer_t delta_encode(const delta_placed_commands_t *cmds,
                            bool inplace, size_t version_size,
                            const uint8_t src_crc[DELTA_CRC_SIZE],
                            const uint8_t dst_crc[DELTA_CRC_SIZE]);

// delta_encode_large writes DLT\x04.  Each command takes the 32-bit form if
// its fields fit and force_large is false, and the 64-bit form otherwise.
delta_buffer_t delta_encode_large(const delta_placed_commands_t *cmds,
                                  bool inplace, size_t version_size,
                                  const uint8_t src_crc[DELTA_CRC_SIZE],
                                  const uint8_t dst_crc[DELTA_CRC_SIZE],
                                  bool force_large);

typedef struct {
	delta_placed_commands_t commands;
	bool    inplace;
	size_t  version_size;
	uint8_t src_crc[DELTA_CRC_SIZE]; // CRC-64/XZ of R
	uint8_t dst_crc[DELTA_CRC_SIZE]; // CRC-64/XZ of V
} delta_decode_result_t;

// delta_decode reads either format.  It checks that every command writes
// within version_size; what a copy or move reads is for
// delta_validate_placed_commands, which knows the size of R.
delta_decode_result_t delta_decode(const uint8_t *data, size_t len);
void                  delta_decode_result_init(delta_decode_result_t *dr);
void                  delta_decode_result_free(delta_decode_result_t *dr);

// Placement and application.

// delta_output_size is the length of the version the commands produce.
size_t delta_output_size(const delta_commands_t *cmds);

// delta_place_commands gives each command the destination that follows from
// its position in the list.
delta_placed_commands_t delta_place_commands(const delta_commands_t *cmds);

// delta_unplace_commands is the inverse: the commands in order of
// destination.  It exits if there is a move.
delta_commands_t delta_unplace_commands(const delta_placed_commands_t *placed);

// delta_validate_placed_commands exits unless every command reads and writes
// within bounds.  A copy of an in-place delta reads the working buffer, which
// is as long as the longer of R and V.
void delta_validate_placed_commands(const delta_placed_commands_t *cmds,
                                    size_t reference_size,
                                    size_t version_size,
                                    bool inplace);

// delta_apply_placed builds V in a new buffer.
delta_buffer_t delta_apply_placed(const uint8_t *r,
                                  const delta_placed_commands_t *cmds,
                                  size_t version_size);

// delta_apply_placed_inplace runs in-place commands on buf, which holds R and
// has room for the longer of R and V.
void delta_apply_placed_inplace(const delta_placed_commands_t *cmds,
                                uint8_t *buf);

// delta_apply_delta_inplace copies R to a new buffer and runs the in-place
// commands there.
delta_buffer_t delta_apply_delta_inplace(const uint8_t *r, size_t r_len,
                                         const delta_placed_commands_t *cmds,
                                         size_t version_size);

// delta_make_inplace orders the commands so that they can be applied to the
// buffer holding R: no copy reads a byte that an earlier command wrote.
// Copies that cannot be so ordered become adds of the bytes they would have
// read from r.  r_len is not used.
delta_placed_commands_t delta_make_inplace(
	const uint8_t *r, size_t r_len,
	const delta_commands_t *cmds,
	delta_cycle_policy_t policy);

// What delta_make_inplace_stats did.
typedef struct {
	size_t num_copies;       // Copies in the result.
	size_t num_adds;         // Adds in the result, converted copies included.
	size_t edges;            // Edges in the digraph of conflicts.
	size_t cycles_broken;    // Each by converting one copy to an add.
	size_t copies_converted; // One for each cycle broken.
	size_t bytes_converted;  // Total length of the copies converted.
} delta_inplace_stats_t;

// delta_make_inplace_stats is delta_make_inplace, and fills in *stats unless
// it is NULL.
delta_placed_commands_t delta_make_inplace_stats(
	const uint8_t *r, size_t r_len,
	const delta_commands_t *cmds,
	delta_cycle_policy_t policy,
	delta_inplace_stats_t *stats);

#endif // DELTA_H
