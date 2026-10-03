// The delta command: encode, decode, info and inplace.

#include "delta.h"

#include <errno.h>
#include <fcntl.h>
#include <getopt.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static _Noreturn void die(const char *fmt, ...)
	__attribute__((format(printf, 1, 2)));

static _Noreturn void
die(const char *fmt, ...)
{
	va_list ap;

	va_start(ap, fmt);
	vfprintf(stderr, fmt, ap);
	va_end(ap);
	fputc('\n', stderr);
	exit(1);
}

static _Noreturn void
usage(void)
{
	fprintf(stderr,
	    "Usage:\n"
	    "  delta encode <algorithm> <ref> <ver> <delta> [options]\n"
	    "  delta decode <ref> <delta> <output> [--ignore-hash]\n"
	    "  delta info <delta>\n"
	    "  delta inplace <ref> <delta_in> <delta_out> [--policy P]\n"
	    "\n"
	    "Algorithms: greedy, onepass, correcting\n"
	    "\n"
	    "Options:\n"
	    "  --seed-len N     Seed length (default %d)\n"
	    "  --table-size N   Hash table size floor (default %lu)\n"
	    "  --max-table N    Max hash table size, k/M/B suffix ok (default %lu)\n"
	    "  --inplace        Produce in-place delta\n"
	    "  --policy P       Cycle policy: localmin (default), constant\n"
	    "  --verbose        Print diagnostics\n"
	    "  --splay          Use splay tree instead of hash table\n",
	    DELTA_SEED_LEN, DELTA_TABLE_SIZE, DELTA_MAX_TABLE_SIZE);
	exit(1);
}

// A file mapped read-only.  data is NULL if the file is empty.
typedef struct {
	uint8_t *data;
	size_t   size;
} mapped_file_t;

static mapped_file_t
map_file(const char *path)
{
	mapped_file_t mf = { NULL, 0 };
	struct stat st;

	int fd = open(path, O_RDONLY);
	if (fd < 0) {
		die("Error opening %s: %s", path, strerror(errno));
	}
	if (fstat(fd, &st) < 0) {
		die("Error stat %s: %s", path, strerror(errno));
	}
	mf.size = (size_t)st.st_size;
	if (mf.size > 0) {
		mf.data = mmap(NULL, mf.size, PROT_READ, MAP_PRIVATE, fd, 0);
		if (mf.data == MAP_FAILED) {
			die("Error mmap %s: %s", path, strerror(errno));
		}
	}
	close(fd); // the mapping outlives the descriptor
	return mf;
}

static void
unmap_file(mapped_file_t *mf)
{
	if (mf->data) {
		munmap(mf->data, mf->size);
	}
	mf->data = NULL;
	mf->size = 0;
}

// read_file returns the contents of path, which the caller frees.
static uint8_t *
read_file(const char *path, size_t *len)
{
	FILE *f = fopen(path, "rb");
	if (!f) {
		die("Error reading %s: %s", path, strerror(errno));
	}
	if (fseek(f, 0, SEEK_END) != 0) {
		die("Error seeking %s: %s", path, strerror(errno));
	}
	long size = ftell(f);
	if (size < 0) {
		die("Error sizing %s: %s", path, strerror(errno));
	}
	if (fseek(f, 0, SEEK_SET) != 0) {
		die("Error seeking %s: %s", path, strerror(errno));
	}

	uint8_t *buf = malloc(size > 0 ? (size_t)size : 1);
	if (!buf) {
		die("Error reading %s: out of memory", path);
	}
	if (fread(buf, 1, (size_t)size, f) != (size_t)size) {
		die("Error reading %s", path);
	}
	fclose(f);
	*len = (size_t)size;
	return buf;
}

static void
write_file(const char *path, const uint8_t *data, size_t len)
{
	FILE *f = fopen(path, "wb");
	if (!f) {
		die("Error writing %s: %s", path, strerror(errno));
	}
	if (fwrite(data, 1, len, f) != len) {
		die("Error writing %s", path);
	}
	if (fclose(f) != 0) {
		die("Error writing %s: %s", path, strerror(errno));
	}
}

// seconds_now returns a monotonic time in seconds.
static double
seconds_now(void)
{
	struct timespec t;
	clock_gettime(CLOCK_MONOTONIC, &t);
	return (double)t.tv_sec + (double)t.tv_nsec / 1e9;
}

static void
print_hex(FILE *f, const uint8_t *bytes, size_t len)
{
	for (size_t i = 0; i < len; i++) {
		fprintf(f, "%02x", bytes[i]);
	}
}

static void
print_crc(const char *label, const uint8_t crc[DELTA_CRC_SIZE],
          const char *suffix)
{
	printf("%s", label);
	print_hex(stdout, crc, DELTA_CRC_SIZE);
	printf("%s\n", suffix);
}

// parse_size reads a decimal count with an optional decimal multiplier:
// k (thousand), M (million) or B (billion).
static size_t
parse_size(const char *s)
{
	char *end;
	unsigned long long mult = 1;
	unsigned long long n = strtoull(s, &end, 10);

	if (end == s) {
		die("Invalid size: %s", s);
	}
	if (*end != '\0') {
		switch (*end) {
		case 'k': case 'K': mult = 1000ULL; break;
		case 'm': case 'M': mult = 1000000ULL; break;
		case 'b': case 'B': mult = 1000000000ULL; break;
		default:
			die("Invalid size suffix: %s", s);
		}
		if (end[1] != '\0') {
			die("Invalid size suffix: %s", s);
		}
	}
	if (n > SIZE_MAX / mult) {
		die("Size too large: %s", s);
	}
	return (size_t)(n * mult);
}

static delta_cycle_policy_t
parse_policy(const char *s)
{
	if (strcmp(s, "localmin") == 0) {
		return POLICY_LOCALMIN;
	}
	if (strcmp(s, "constant") == 0) {
		return POLICY_CONSTANT;
	}
	die("Unknown policy: %s", s);
}

static delta_algorithm_t
parse_algorithm(const char *s)
{
	if (strcmp(s, "greedy") == 0) {
		return ALGO_GREEDY;
	}
	if (strcmp(s, "onepass") == 0) {
		return ALGO_ONEPASS;
	}
	if (strcmp(s, "correcting") == 0) {
		return ALGO_CORRECTING;
	}
	die("Unknown algorithm: %s", s);
}

// delta encode <algorithm> <ref> <ver> <delta> [options]
static void
cmd_encode(int argc, char **argv)
{
	if (argc < 6) {
		usage();
	}
	const char *algo_name = argv[2];
	const char *ref_path = argv[3];
	const char *ver_path = argv[4];
	const char *delta_path = argv[5];
	delta_algorithm_t algo = parse_algorithm(algo_name);

	delta_diff_options_t opts = DELTA_DIFF_OPTIONS_DEFAULT;
	delta_cycle_policy_t policy = POLICY_LOCALMIN;
	const char *policy_name = "localmin";
	bool inplace = false, splay = false, force_large = false;

	static const struct option long_opts[] = {
		{"seed-len",   required_argument, NULL, 's'},
		{"table-size", required_argument, NULL, 't'},
		{"max-table",  required_argument, NULL, 'x'},
		{"inplace",    no_argument,       NULL, 'i'},
		{"large",      no_argument,       NULL, 'L'},
		{"policy",     required_argument, NULL, 'p'},
		{"verbose",    no_argument,       NULL, 'v'},
		{"splay",      no_argument,       NULL, 'y'},
		{NULL, 0, NULL, 0}
	};
	int opt;
	optind = 6; // past the positional arguments
	while ((opt = getopt_long(argc, argv, "", long_opts, NULL)) != -1) {
		switch (opt) {
		case 's': opts.p = parse_size(optarg); break;
		case 't': opts.q = parse_size(optarg); break;
		case 'x': opts.max_table = parse_size(optarg); break;
		case 'i': inplace = true; break;
		case 'L': force_large = true; break;
		case 'p':
			policy_name = optarg;
			policy = parse_policy(optarg);
			break;
		case 'v':
			opts.flags = delta_flag_set(opts.flags, DELTA_OPT_VERBOSE);
			break;
		case 'y':
			splay = true;
			opts.flags = delta_flag_set(opts.flags, DELTA_OPT_SPLAY);
			break;
		default:
			usage();
		}
	}
	if (opts.p == 0) {
		die("error: --seed-len must be >= 1");
	}
	if (opts.q == 0) {
		die("error: --table-size must be >= 1");
	}

	mapped_file_t r = map_file(ref_path);
	mapped_file_t v = map_file(ver_path);
	uint8_t src_crc[DELTA_CRC_SIZE], dst_crc[DELTA_CRC_SIZE];
	delta_crc64_xz(r.data, r.size, src_crc);
	delta_crc64_xz(v.data, v.size, dst_crc);

	double t0 = seconds_now();
	delta_commands_t cmds = delta_diff(algo, r.data, r.size,
	                                   v.data, v.size, &opts);
	delta_placed_commands_t placed = inplace
	    ? delta_make_inplace(r.data, r.size, &cmds, policy)
	    : delta_place_commands(&cmds);
	double elapsed = seconds_now() - t0;

	delta_buffer_t delta = delta_encode_large(&placed, inplace, v.size,
	                                          src_crc, dst_crc, force_large);
	write_file(delta_path, delta.data, delta.len);

	delta_summary_t stats = delta_placed_summary(&placed);
	printf("Algorithm:    %s%s", algo_name, splay ? " [splay]" : "");
	if (inplace) {
		printf(" + in-place (%s)", policy_name);
	}
	printf("\n");
	printf("Reference:    %s (%zu bytes)\n", ref_path, r.size);
	printf("Version:      %s (%zu bytes)\n", ver_path, v.size);
	printf("Delta:        %s (%zu bytes)\n", delta_path, delta.len);
	printf("Compression:  %.4f (delta/version)\n",
	       v.size == 0 ? 0.0 : (double)delta.len / v.size);
	printf("Commands:     %zu copies, %zu adds\n",
	       stats.num_copies, stats.num_adds);
	printf("Copy bytes:   %zu\n", stats.copy_bytes);
	printf("Add bytes:    %zu\n", stats.add_bytes);
	print_crc("Src CRC:      ", src_crc, "");
	print_crc("Dst CRC:      ", dst_crc, "");
	printf("Time:         %.3fs\n", elapsed);

	delta_buffer_free(&delta);
	delta_placed_commands_free(&placed);
	delta_commands_free(&cmds);
	unmap_file(&r);
	unmap_file(&v);
}

// delta decode <ref> <delta> <output> [--ignore-hash]
static void
cmd_decode(int argc, char **argv)
{
	if (argc < 5) {
		usage();
	}
	const char *ref_path = argv[2];
	const char *delta_path = argv[3];
	const char *out_path = argv[4];

	bool ignore_hash = false;
	for (int a = 5; a < argc; a++) {
		if (strcmp(argv[a], "--ignore-hash") != 0) {
			die("error: unknown decode option: %s", argv[a]);
		}
		ignore_hash = true;
	}

	mapped_file_t r = map_file(ref_path);
	size_t delta_len;
	uint8_t *delta = read_file(delta_path, &delta_len);
	delta_decode_result_t dr = delta_decode(delta, delta_len);

	uint8_t r_crc[DELTA_CRC_SIZE];
	delta_crc64_xz(r.data, r.size, r_crc);
	if (memcmp(r_crc, dr.src_crc, DELTA_CRC_SIZE) != 0) {
		if (!ignore_hash) {
			fprintf(stderr, "source file does not match delta: expected ");
			print_hex(stderr, dr.src_crc, DELTA_CRC_SIZE);
			fprintf(stderr, ", got ");
			print_hex(stderr, r_crc, DELTA_CRC_SIZE);
			fprintf(stderr, "\n");
			exit(1);
		}
		fprintf(stderr,
		        "warning: skipping source CRC check (--ignore-hash)\n");
	}
	delta_validate_placed_commands(&dr.commands, r.size,
	                               dr.version_size, dr.inplace);

	double t0 = seconds_now();
	delta_buffer_t out = dr.inplace
	    ? delta_apply_delta_inplace(r.data, r.size, &dr.commands,
	                                dr.version_size)
	    : delta_apply_placed(r.data, &dr.commands, dr.version_size);
	double elapsed = seconds_now() - t0;

	// A version that fails its check is not written, unless --ignore-hash
	// is given.
	uint8_t out_crc[DELTA_CRC_SIZE];
	delta_crc64_xz(out.data, out.len, out_crc);
	if (memcmp(out_crc, dr.dst_crc, DELTA_CRC_SIZE) != 0) {
		if (!ignore_hash) {
			die("output integrity check failed");
		}
		fprintf(stderr,
		        "warning: skipping output CRC check (--ignore-hash)\n");
	}
	write_file(out_path, out.data, out.len);

	printf("Format:       %s\n", dr.inplace ? "in-place" : "standard");
	printf("Reference:    %s (%zu bytes)\n", ref_path, r.size);
	printf("Delta:        %s (%zu bytes)\n", delta_path, delta_len);
	printf("Output:       %s (%zu bytes)\n", out_path, dr.version_size);
	if (!ignore_hash) {
		print_crc("Src CRC:      ", dr.src_crc, "  OK");
		print_crc("Dst CRC:      ", dr.dst_crc, "  OK");
	}
	printf("Time:         %.3fs\n", elapsed);

	delta_buffer_free(&out);
	delta_decode_result_free(&dr);
	free(delta);
	unmap_file(&r);
}

// delta info <delta>
static void
cmd_info(int argc, char **argv)
{
	if (argc < 3) {
		usage();
	}
	const char *delta_path = argv[2];
	size_t delta_len;
	uint8_t *delta = read_file(delta_path, &delta_len);
	delta_decode_result_t dr = delta_decode(delta, delta_len);
	delta_summary_t stats = delta_placed_summary(&dr.commands);

	printf("Delta file:   %s (%zu bytes)\n", delta_path, delta_len);
	printf("Format:       %s\n", dr.inplace ? "in-place" : "standard");
	printf("Version size: %zu bytes\n", dr.version_size);
	print_crc("Src CRC:      ", dr.src_crc, "");
	print_crc("Dst CRC:      ", dr.dst_crc, "");
	printf("Commands:     %zu\n", stats.num_commands);
	printf("  Copies:     %zu (%zu bytes)\n",
	       stats.num_copies, stats.copy_bytes);
	printf("  Adds:       %zu (%zu bytes)\n",
	       stats.num_adds, stats.add_bytes);
	printf("Output size:  %zu bytes\n", stats.total_output_bytes);

	delta_decode_result_free(&dr);
	free(delta);
}

// delta inplace <ref> <delta_in> <delta_out> [--policy P] [--large]
static void
cmd_inplace(int argc, char **argv)
{
	if (argc < 5) {
		usage();
	}
	const char *ref_path = argv[2];
	const char *in_path = argv[3];
	const char *out_path = argv[4];

	delta_cycle_policy_t policy = POLICY_LOCALMIN;
	const char *policy_name = "localmin";
	bool force_large = false;
	for (int a = 5; a < argc; a++) {
		if (strcmp(argv[a], "--policy") == 0) {
			if (a + 1 >= argc) {
				die("error: --policy: missing value");
			}
			policy_name = argv[++a];
			policy = parse_policy(policy_name);
		} else if (strcmp(argv[a], "--large") == 0) {
			force_large = true;
		} else {
			die("error: unknown inplace option: %s", argv[a]);
		}
	}

	mapped_file_t r = map_file(ref_path);
	size_t in_len;
	uint8_t *in = read_file(in_path, &in_len);
	delta_decode_result_t dr = delta_decode(in, in_len);
	delta_validate_placed_commands(&dr.commands, r.size,
	                               dr.version_size, dr.inplace);

	if (dr.inplace) {
		write_file(out_path, in, in_len);
		printf("Delta is already in-place format; copied unchanged.\n");
	} else {
		double t0 = seconds_now();
		delta_commands_t cmds = delta_unplace_commands(&dr.commands);
		delta_placed_commands_t placed =
		    delta_make_inplace(r.data, r.size, &cmds, policy);
		double elapsed = seconds_now() - t0;

		delta_buffer_t out = delta_encode_large(&placed, true,
		    dr.version_size, dr.src_crc, dr.dst_crc, force_large);
		write_file(out_path, out.data, out.len);

		delta_summary_t stats = delta_placed_summary(&placed);
		printf("Reference:    %s (%zu bytes)\n", ref_path, r.size);
		printf("Input delta:  %s (%zu bytes)\n", in_path, in_len);
		printf("Output delta: %s (%zu bytes)\n", out_path, out.len);
		printf("Format:       in-place (%s)\n", policy_name);
		printf("Commands:     %zu copies, %zu adds\n",
		       stats.num_copies, stats.num_adds);
		printf("Copy bytes:   %zu\n", stats.copy_bytes);
		printf("Add bytes:    %zu\n", stats.add_bytes);
		printf("Time:         %.3fs\n", elapsed);

		delta_buffer_free(&out);
		delta_placed_commands_free(&placed);
		delta_commands_free(&cmds);
	}

	delta_decode_result_free(&dr);
	free(in);
	unmap_file(&r);
}

int
main(int argc, char **argv)
{
	if (argc < 2) {
		usage();
	}
	const char *cmd = argv[1];
	if (strcmp(cmd, "encode") == 0) {
		cmd_encode(argc, argv);
	} else if (strcmp(cmd, "decode") == 0) {
		cmd_decode(argc, argv);
	} else if (strcmp(cmd, "info") == 0) {
		cmd_info(argc, argv);
	} else if (strcmp(cmd, "inplace") == 0) {
		cmd_inplace(argc, argv);
	} else {
		usage();
	}
	return 0;
}
