
### Encode (1 MiB, ~5% mutations)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| encode_greedy_1m             |      3.033 |    0.02725 |    30 |
| encode_onepass_1m            |       14.7 |     0.3438 |    30 |
| encode_correcting_1m         |       14.5 |    0.03339 |    30 |

### Decode / In-place (1 MiB)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| decode_1m                    |      608.8 |      50.82 |    30 |
| inplace_1m                   |      224.7 |      2.137 |    30 |

