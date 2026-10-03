
### Encode (1 MiB, ~5% mutations)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| encode_greedy_1m             |       3.03 |    0.03309 |    30 |
| encode_onepass_1m            |      14.69 |     0.3759 |    30 |
| encode_correcting_1m         |      14.51 |    0.02836 |    30 |

### Decode / In-place (1 MiB)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| decode_1m                    |      613.7 |      41.68 |    30 |
| inplace_1m                   |      225.9 |      1.194 |    30 |

