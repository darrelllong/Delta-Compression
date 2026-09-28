
### Encode (1 MiB, ~5% mutations)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| encode_greedy_1m             |      3.168 |   0.008224 |    30 |
| encode_onepass_1m            |      30.06 |    0.08313 |   164 |
| encode_correcting_1m         |      26.41 |    0.04793 |    37 |

### Decode / In-place (1 MiB)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| decode_1m                    |       1638 |      2.381 |    60 |
| inplace_1m                   |      308.3 |     0.7248 |    30 |

