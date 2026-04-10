/*
 * test_overflow.c — verify that delta_encode exits non-zero when field values
 * exceed UINT32_MAX.  Each subcommand triggers one specific overflow check.
 *
 * Usage: ./test_overflow <case>
 *   version_size | copy_src | copy_dst | copy_len | add_dst | add_len
 *
 * Expected exit: 1 (check_u32 calls exit(1) on overflow).
 */
#include "delta.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define OVERFLOW ((size_t)UINT32_MAX + 1)

static void
do_encode(const delta_placed_commands_t *cmds, size_t version_size)
{
    uint8_t z[DELTA_CRC_SIZE] = {0};
    delta_buffer_t buf = delta_encode(cmds, false, version_size, z, z);
    free(buf.data);   /* should not reach here if check_u32 fires */
}

int main(int argc, char **argv)
{
    if (argc < 2) {
        fprintf(stderr, "usage: test_overflow <case>\n");
        return 2;
    }

    delta_placed_commands_t cmds;
    delta_placed_commands_init(&cmds);

    if (strcmp(argv[1], "version_size") == 0) {
        do_encode(&cmds, OVERFLOW);

    } else if (strcmp(argv[1], "copy_src") == 0) {
        delta_placed_command_t cmd = { .tag = PCMD_COPY,
            .copy = { .src = OVERFLOW, .dst = 0, .length = 1 } };
        delta_placed_commands_push(&cmds, cmd);
        do_encode(&cmds, 1);

    } else if (strcmp(argv[1], "copy_dst") == 0) {
        delta_placed_command_t cmd = { .tag = PCMD_COPY,
            .copy = { .src = 0, .dst = OVERFLOW, .length = 1 } };
        delta_placed_commands_push(&cmds, cmd);
        do_encode(&cmds, 1);

    } else if (strcmp(argv[1], "copy_len") == 0) {
        delta_placed_command_t cmd = { .tag = PCMD_COPY,
            .copy = { .src = 0, .dst = 0, .length = OVERFLOW } };
        delta_placed_commands_push(&cmds, cmd);
        do_encode(&cmds, 1);

    } else if (strcmp(argv[1], "add_dst") == 0) {
        uint8_t byte = 0;
        delta_placed_command_t cmd = { .tag = PCMD_ADD,
            .add = { .dst = OVERFLOW, .data = &byte, .length = 1 } };
        delta_placed_commands_push(&cmds, cmd);
        do_encode(&cmds, 1);

    } else if (strcmp(argv[1], "add_len") == 0) {
        uint8_t byte = 0;
        delta_placed_command_t cmd = { .tag = PCMD_ADD,
            .add = { .dst = 0, .data = &byte, .length = OVERFLOW } };
        delta_placed_commands_push(&cmds, cmd);
        do_encode(&cmds, 1);

    } else {
        fprintf(stderr, "unknown case: %s\n", argv[1]);
        delta_placed_commands_free(&cmds);
        return 2;
    }

    /* If we reach here the overflow check did NOT fire — test failure. */
    delta_placed_commands_free(&cmds);
    fprintf(stderr, "FAIL: check_u32 did not exit for case '%s'\n", argv[1]);
    return 0;   /* returning 0 means the shell check_fails will FAIL the test */
}
