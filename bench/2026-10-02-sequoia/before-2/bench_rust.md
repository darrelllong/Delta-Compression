
### Encode (1 MiB, ~5% mutations)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| encode_greedy_1m             |      5.602 |   0.006997 |    30 |
| encode_onepass_1m            |      29.02 |    0.02144 |    30 |
| encode_correcting_1m         |      26.36 |    0.05708 |    30 |

### Decode / In-place (1 MiB)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| decode_1m                    |       1183 |      17.87 |    30 |
| inplace_1m                   |      315.7 |      1.033 |    30 |

