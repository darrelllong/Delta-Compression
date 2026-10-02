// Algorithm dispatch and the statistics the algorithms print when verbose.

#include "internal.h"

delta_commands_t
delta_diff(delta_algorithm_t algo,
           const uint8_t *r, size_t r_len,
           const uint8_t *v, size_t v_len,
           const delta_diff_options_t *opts)
{
	switch (algo) {
	case ALGO_GREEDY:
		return delta_diff_greedy(r, r_len, v, v_len, opts);
	case ALGO_ONEPASS:
		return delta_diff_onepass(r, r_len, v, v_len, opts);
	case ALGO_CORRECTING:
		return delta_diff_correcting(r, r_len, v, v_len, opts);
	}
	fprintf(stderr, "delta_diff: unknown algorithm %d\n", (int)algo);
	delta_commands_t empty;
	delta_commands_init(&empty);
	return empty;
}

static int
cmp_size(const void *a, const void *b)
{
	size_t x = *(const size_t *)a, y = *(const size_t *)b;
	return (x > y) - (x < y);
}

int
delta_cmp_dst_index(const void *a, const void *b)
{
	const dst_index_t *x = a, *y = b;
	if (x->dst != y->dst) {
		return x->dst < y->dst ? -1 : 1;
	}
	return (x->idx > y->idx) - (x->idx < y->idx);
}

void
delta_print_command_stats(const delta_commands_t *cmds)
{
	delta_summary_t s = delta_summary(cmds);
	double copy_pct = s.total_output_bytes > 0
	    ? (double)s.copy_bytes / s.total_output_bytes * 100.0 : 0.0;

	fprintf(stderr,
	        "  result: %zu copies (%zu bytes), %zu adds (%zu bytes)\n"
	        "  result: copy coverage %.1f%%, output %zu bytes\n",
	        s.num_copies, s.copy_bytes, s.num_adds, s.add_bytes,
	        copy_pct, s.total_output_bytes);
	if (s.num_copies == 0) {
		return;
	}

	size_t *lens = delta_malloc(s.num_copies * sizeof(*lens));
	size_t n = 0;
	for (size_t i = 0; i < cmds->len; i++) {
		if (cmds->data[i].tag == CMD_COPY) {
			lens[n++] = cmds->data[i].copy.length;
		}
	}
	qsort(lens, n, sizeof(*lens), cmp_size);
	fprintf(stderr,
	        "  copies: %zu regions, min=%zu max=%zu "
	        "mean=%.1f median=%zu bytes\n",
	        n, lens[0], lens[n - 1], (double)s.copy_bytes / n, lens[n / 2]);
	free(lens);
}
