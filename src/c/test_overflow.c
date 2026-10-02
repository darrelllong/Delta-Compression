// test_overflow <case> hands delta_encode one value that does not fit in 32
// bits; delta_encode must print a message and exit with status 1.  The case
// names the value: version_size, copy_src, copy_dst, copy_len, add_dst or
// add_len.  Status 0 means delta_encode returned, which is the failure;
// status 2 is a usage error.

#include "delta.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define TOO_BIG ((size_t)UINT32_MAX + 1)

int
main(int argc, char **argv)
{
	if (argc != 2) {
		fprintf(stderr, "usage: test_overflow <case>\n");
		return 2;
	}
	const char *name = argv[1];

	// Nothing reads the add's data: the lengths are checked first.
	static uint8_t byte;
	size_t version_size = 1;
	delta_placed_command_t cmd;
	bool have_cmd = true;

	if (strcmp(name, "version_size") == 0) {
		version_size = TOO_BIG;
		have_cmd = false;
	} else if (strcmp(name, "copy_src") == 0) {
		cmd = (delta_placed_command_t){ .tag = PCMD_COPY,
		    .copy = { .src = TOO_BIG, .dst = 0, .length = 1 } };
	} else if (strcmp(name, "copy_dst") == 0) {
		cmd = (delta_placed_command_t){ .tag = PCMD_COPY,
		    .copy = { .src = 0, .dst = TOO_BIG, .length = 1 } };
	} else if (strcmp(name, "copy_len") == 0) {
		cmd = (delta_placed_command_t){ .tag = PCMD_COPY,
		    .copy = { .src = 0, .dst = 0, .length = TOO_BIG } };
	} else if (strcmp(name, "add_dst") == 0) {
		cmd = (delta_placed_command_t){ .tag = PCMD_ADD,
		    .add = { .dst = TOO_BIG, .data = &byte, .length = 1 } };
	} else if (strcmp(name, "add_len") == 0) {
		cmd = (delta_placed_command_t){ .tag = PCMD_ADD,
		    .add = { .dst = 0, .data = &byte, .length = TOO_BIG } };
	} else {
		fprintf(stderr, "unknown case: %s\n", name);
		return 2;
	}

	delta_placed_commands_t cmds;
	delta_placed_commands_init(&cmds);
	if (have_cmd) {
		delta_placed_commands_push(&cmds, cmd);
	}
	const uint8_t crc[DELTA_CRC_SIZE] = {0};
	delta_encode(&cmds, false, version_size, crc, crc);

	fprintf(stderr, "FAIL: delta_encode accepted %s\n", name);
	return 0;
}
