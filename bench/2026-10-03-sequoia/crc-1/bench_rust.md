
### Encode (1 MiB, ~5% mutations)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| encode_greedy_1m             |      5.582 |    0.00696 |    30 |
| encode_onepass_1m            |      29.84 |    0.02189 |    30 |
| encode_correcting_1m         |      26.63 |     0.1784 |    30 |

### Decode / In-place (1 MiB)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| decode_1m                    |       1171 |      2.884 |    30 |
| inplace_1m                   |      393.5 |      1.383 |    30 |

