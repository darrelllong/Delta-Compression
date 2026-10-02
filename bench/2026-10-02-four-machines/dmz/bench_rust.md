
### Encode (1 MiB, ~5% mutations)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| encode_greedy_1m             |      2.916 |    0.06623 |    30 |
| encode_onepass_1m            |      14.73 |     0.2993 |    30 |
| encode_correcting_1m         |      14.48 |    0.04351 |    30 |

### Decode / In-place (1 MiB)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| decode_1m                    |      626.9 |      5.165 |    30 |
| inplace_1m                   |      223.4 |      13.87 |    30 |

