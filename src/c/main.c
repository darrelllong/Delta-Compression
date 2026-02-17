/*
 * main.c — CLI for differential compression (Ajtai et al. 2002)
 *
 * Usage:
 *   delta encode <algorithm> <reference> <version> <delta_file> [options]
 *   delta decode <reference> <delta_file> <output>
 *   delta info <delta_file>
 */

#include "delta.h"

#include <errno.h>
#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* POSIX mmap */
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

/* ── mmap wrapper ──────────────────────────────────────────────────── */

typedef struct {
	uint8_t *data;
	size_t   size;
	int      fd;
} mapped_file_t;

static mapped_file_t
map_file(const char *path)
{
	mapped_file_t mf = {NULL, 0, -1};
	struct stat st;

	mf.fd = open(path, O_RDONLY);
	if (mf.fd < 0) {
		fprintf(stderr, "Error opening %s: %s\n", path, strerror(errno));
		exit(1);
	}
	if (fstat(mf.fd, &st) < 0) {
		fprintf(stderr, "Error stat %s: %s\n", path, strerror(errno));
		exit(1);
	}
	mf.size = (size_t)st.st_size;
	if (mf.size > 0) {
		mf.data = mmap(NULL, mf.size, PROT_READ, MAP_PRIVATE, mf.fd, 0);
		if (mf.data == MAP_FAILED) {
			fprintf(stderr, "Error mmap %s: %s\n",
			        path, strerror(errno));
			exit(1);
		}
	}
	return mf;
}

static void
unmap_file(mapped_file_t *mf)
{
	if (mf->data && mf->data != MAP_FAILED)
		munmap(mf->data, mf->size);
	if (mf->fd >= 0)
		close(mf->fd);
	mf->data = NULL;
	mf->size = 0;
	mf->fd = -1;
}

/* ── File read into malloc'd buffer ────────────────────────────────── */

static uint8_t *
read_file(const char *path, size_t *out_len)
{
	FILE *f = fopen(path, "rb");
	uint8_t *buf;
	size_t len;
	if (!f) {
		fprintf(stderr, "Error reading %s: %s\n", path, strerror(errno));
		exit(1);
	}
	fseek(f, 0, SEEK_END);
	len = (size_t)ftell(f);
	fseek(f, 0, SEEK_SET);
	buf = malloc(len);
	if (fread(buf, 1, len, f) != len) {
		fprintf(stderr, "Error reading %s\n", path);
		exit(1);
	}
	fclose(f);
	*out_len = len;
	return buf;
}

static void
write_file(const char *path, const uint8_t *data, size_t len)
{
	FILE *f = fopen(path, "wb");
	if (!f) {
		fprintf(stderr, "Error writing %s: %s\n", path, strerror(errno));
		exit(1);
	}
	if (fwrite(data, 1, len, f) != len) {
		fprintf(stderr, "Error writing %s\n", path);
		exit(1);
	}
	fclose(f);
}

/* ── Elapsed time helper ───────────────────────────────────────────── */

static double
elapsed_sec(struct timespec *t0, struct timespec *t1)
{
	return (double)(t1->tv_sec - t0->tv_sec)
	     + (double)(t1->tv_nsec - t0->tv_nsec) / 1e9;
}

/* ── Usage ─────────────────────────────────────────────────────────── */

static void
usage(void)
{
	fprintf(stderr,
	    "Usage:\n"
	    "  delta encode <algorithm> <ref> <ver> <delta> [options]\n"
	    "  delta decode <ref> <delta> <output>\n"
	    "  delta info <delta>\n"
	    "  delta inplace <ref> <delta_in> <delta_out> [--policy P]\n"
	    "\n"
	    "Algorithms: greedy, onepass, correcting\n"
	    "\n"
	    "Options:\n"
	    "  --seed-len N     Seed length (default %d)\n"
	    "  --table-size N   Hash table size floor (default %d)\n"
	    "  --inplace        Produce in-place delta\n"
	    "  --policy P       Cycle policy: localmin (default), constant\n"
	    "  --verbose        Print diagnostics\n"
	    "  --splay          Use splay tree instead of hash table\n"
	    "  --min-copy N     Minimum copy length (0 = use seed length)\n",
	    DELTA_SEED_LEN, DELTA_TABLE_SIZE);
	exit(1);
}

/* ── Main ──────────────────────────────────────────────────────────── */

int
main(int argc, char **argv)
{
	if (argc < 2) usage();

	if (strcmp(argv[1], "encode") == 0) {
		/* encode <algo> <ref> <ver> <delta> [options] */
		if (argc < 6) usage();

		const char *algo_str = argv[2];
		const char *ref_path = argv[3];
		const char *ver_path = argv[4];
		const char *delta_path = argv[5];

		delta_algorithm_t algo;
		if (strcmp(algo_str, "greedy") == 0)
			algo = ALGO_GREEDY;
		else if (strcmp(algo_str, "onepass") == 0)
			algo = ALGO_ONEPASS;
		else if (strcmp(algo_str, "correcting") == 0)
			algo = ALGO_CORRECTING;
		else {
			fprintf(stderr, "Unknown algorithm: %s\n", algo_str);
			return 1;
		}

		size_t seed_len = DELTA_SEED_LEN;
		size_t table_size = DELTA_TABLE_SIZE;
		bool inplace = false;
		delta_cycle_policy_t policy = POLICY_LOCALMIN;
		bool verbose_flag = false;
		bool splay = false;
		size_t min_copy_val = 0;
		const char *policy_str = "localmin";

		/* Parse options from argv[6..] */
		static struct option long_opts[] = {
			{"seed-len",   required_argument, NULL, 's'},
			{"table-size", required_argument, NULL, 't'},
			{"inplace",    no_argument,       NULL, 'i'},
			{"policy",     required_argument, NULL, 'p'},
			{"verbose",    no_argument,       NULL, 'v'},
			{"splay",      no_argument,       NULL, 'y'},
			{"min-copy",   required_argument, NULL, 'm'},
			{NULL, 0, NULL, 0}
		};

		optind = 6;  /* start parsing after positional args */
		int opt;
		while ((opt = getopt_long(argc, argv, "", long_opts, NULL)) != -1) {
			switch (opt) {
			case 's': seed_len = (size_t)atol(optarg); break;
			case 't': table_size = (size_t)atol(optarg); break;
			case 'i': inplace = true; break;
			case 'p':
				policy_str = optarg;
				if (strcmp(optarg, "constant") == 0)
					policy = POLICY_CONSTANT;
				break;
			case 'v': verbose_flag = true; break;
			case 'y': splay = true; break;
			case 'm': min_copy_val = (size_t)atol(optarg); break;
			default: usage();
			}
		}

		mapped_file_t r_file = map_file(ref_path);
		mapped_file_t v_file = map_file(ver_path);

		struct timespec t0, t1;
		clock_gettime(CLOCK_MONOTONIC, &t0);

		delta_diff_options_t diff_opts = DELTA_DIFF_OPTIONS_DEFAULT;
		diff_opts.p = seed_len;
		diff_opts.q = table_size;
		diff_opts.verbose = verbose_flag;
		diff_opts.use_splay = splay;
		diff_opts.min_copy = min_copy_val;

		delta_commands_t cmds = delta_diff(
			algo, r_file.data, r_file.size,
			v_file.data, v_file.size, &diff_opts);

		delta_placed_commands_t placed;
		if (inplace)
			placed = delta_make_inplace(r_file.data, r_file.size,
			                            &cmds, policy);
		else
			placed = delta_place_commands(&cmds);

		clock_gettime(CLOCK_MONOTONIC, &t1);
		double elapsed = elapsed_sec(&t0, &t1);

		delta_buffer_t delta_buf = delta_encode(&placed, inplace,
		                                        v_file.size);
		write_file(delta_path, delta_buf.data, delta_buf.len);

		delta_summary_t stats = delta_placed_summary(&placed);
		double ratio = v_file.size == 0 ? 0.0
		    : (double)delta_buf.len / v_file.size;

		if (inplace)
			printf("Algorithm:    %s%s + in-place (%s)\n",
			       algo_str, splay ? " [splay]" : "", policy_str);
		else
			printf("Algorithm:    %s%s\n",
			       algo_str, splay ? " [splay]" : "");
		printf("Reference:    %s (%zu bytes)\n", ref_path, r_file.size);
		printf("Version:      %s (%zu bytes)\n", ver_path, v_file.size);
		printf("Delta:        %s (%zu bytes)\n", delta_path, delta_buf.len);
		printf("Compression:  %.4f (delta/version)\n", ratio);
		printf("Commands:     %zu copies, %zu adds\n",
		       stats.num_copies, stats.num_adds);
		printf("Copy bytes:   %zu\n", stats.copy_bytes);
		printf("Add bytes:    %zu\n", stats.add_bytes);
		printf("Time:         %.3fs\n", elapsed);

		free(delta_buf.data);
		delta_placed_commands_free(&placed);
		delta_commands_free(&cmds);
		unmap_file(&r_file);
		unmap_file(&v_file);

	} else if (strcmp(argv[1], "decode") == 0) {
		if (argc < 5) usage();

		const char *ref_path = argv[2];
		const char *delta_path = argv[3];
		const char *out_path = argv[4];

		mapped_file_t r_file = map_file(ref_path);
		size_t delta_len;
		uint8_t *delta_data = read_file(delta_path, &delta_len);

		struct timespec t0, t1;
		clock_gettime(CLOCK_MONOTONIC, &t0);

		delta_decode_result_t dr = delta_decode(delta_data, delta_len);

		if (dr.inplace) {
			uint8_t *result = delta_apply_delta_inplace(
				r_file.data, r_file.size,
				&dr.commands, dr.version_size);
			write_file(out_path, result, dr.version_size);
			free(result);
		} else {
			uint8_t *out = calloc(dr.version_size, 1);
			delta_apply_placed(r_file.data, &dr.commands, out);
			write_file(out_path, out, dr.version_size);
			free(out);
		}

		clock_gettime(CLOCK_MONOTONIC, &t1);
		double elapsed = elapsed_sec(&t0, &t1);

		printf("Format:       %s\n", dr.inplace ? "in-place" : "standard");
		printf("Reference:    %s (%zu bytes)\n", ref_path, r_file.size);
		printf("Delta:        %s (%zu bytes)\n", delta_path, delta_len);
		printf("Output:       %s (%zu bytes)\n", out_path, dr.version_size);
		printf("Time:         %.3fs\n", elapsed);

		delta_placed_commands_free(&dr.commands);
		free(delta_data);
		unmap_file(&r_file);

	} else if (strcmp(argv[1], "info") == 0) {
		if (argc < 3) usage();

		const char *delta_path = argv[2];
		size_t delta_len;
		uint8_t *delta_data = read_file(delta_path, &delta_len);

		delta_decode_result_t dr = delta_decode(delta_data, delta_len);
		delta_summary_t stats = delta_placed_summary(&dr.commands);

		printf("Delta file:   %s (%zu bytes)\n", delta_path, delta_len);
		printf("Format:       %s\n", dr.inplace ? "in-place" : "standard");
		printf("Version size: %zu bytes\n", dr.version_size);
		printf("Commands:     %zu\n", stats.num_commands);
		printf("  Copies:     %zu (%zu bytes)\n",
		       stats.num_copies, stats.copy_bytes);
		printf("  Adds:       %zu (%zu bytes)\n",
		       stats.num_adds, stats.add_bytes);
		printf("Output size:  %zu bytes\n", stats.total_output_bytes);

		delta_placed_commands_free(&dr.commands);
		free(delta_data);

	} else if (strcmp(argv[1], "inplace") == 0) {
		if (argc < 5) usage();

		const char *ref_path = argv[2];
		const char *delta_in_path = argv[3];
		const char *delta_out_path = argv[4];

		delta_cycle_policy_t policy = POLICY_LOCALMIN;
		const char *policy_str = "localmin";

		/* Parse optional --policy */
		{
			int a;
			for (a = 5; a < argc; a++) {
				if (strcmp(argv[a], "--policy") == 0 &&
				    a + 1 < argc) {
					policy_str = argv[++a];
					if (strcmp(policy_str, "constant") == 0)
						policy = POLICY_CONSTANT;
				}
			}
		}

		mapped_file_t r_file = map_file(ref_path);
		size_t delta_len;
		uint8_t *delta_data = read_file(delta_in_path, &delta_len);

		delta_decode_result_t dr = delta_decode(delta_data, delta_len);

		if (dr.inplace) {
			write_file(delta_out_path, delta_data, delta_len);
			printf("Delta is already in-place format; "
			       "copied unchanged.\n");
			delta_placed_commands_free(&dr.commands);
			free(delta_data);
			unmap_file(&r_file);
			return 0;
		}

		struct timespec t0, t1;
		clock_gettime(CLOCK_MONOTONIC, &t0);

		delta_commands_t cmds = delta_unplace_commands(&dr.commands);
		delta_placed_commands_t ip_placed = delta_make_inplace(
			r_file.data, r_file.size, &cmds, policy);

		clock_gettime(CLOCK_MONOTONIC, &t1);
		double elapsed = elapsed_sec(&t0, &t1);

		delta_buffer_t ip_buf = delta_encode(&ip_placed, true,
		                                     dr.version_size);
		write_file(delta_out_path, ip_buf.data, ip_buf.len);

		delta_summary_t stats = delta_placed_summary(&ip_placed);
		printf("Reference:    %s (%zu bytes)\n",
		       ref_path, r_file.size);
		printf("Input delta:  %s (%zu bytes)\n",
		       delta_in_path, delta_len);
		printf("Output delta: %s (%zu bytes)\n",
		       delta_out_path, ip_buf.len);
		printf("Format:       in-place (%s)\n", policy_str);
		printf("Commands:     %zu copies, %zu adds\n",
		       stats.num_copies, stats.num_adds);
		printf("Copy bytes:   %zu\n", stats.copy_bytes);
		printf("Add bytes:    %zu\n", stats.add_bytes);
		printf("Time:         %.3fs\n", elapsed);

		free(ip_buf.data);
		delta_placed_commands_free(&ip_placed);
		delta_commands_free(&cmds);
		delta_placed_commands_free(&dr.commands);
		free(delta_data);
		unmap_file(&r_file);

	} else {
		usage();
	}

	return 0;
}
