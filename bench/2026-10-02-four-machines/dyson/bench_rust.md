
### Encode (1 MiB, ~5% mutations)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| encode_greedy_1m             |      12.41 |     0.2303 |    30 |
| encode_onepass_1m            |      72.31 |      1.769 |   270 |
| encode_correcting_1m         |      38.47 |     0.9613 |    30 |

### Decode / In-place (1 MiB)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| decode_1m                    |       1017 |      78.63 |    31 |
| inplace_1m                   |      495.9 |      45.86 |    30 |

