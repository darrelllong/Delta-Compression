
### Encode (1 MiB, ~5% mutations)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| encode_greedy_1m             |      5.599 |   0.008361 |    30 |
| encode_onepass_1m            |      29.02 |    0.01982 |    30 |
| encode_correcting_1m         |      26.31 |    0.07709 |    30 |

### Decode / In-place (1 MiB)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| decode_1m                    |       1187 |      2.157 |    30 |
| inplace_1m                   |      315.7 |     0.9876 |    30 |

