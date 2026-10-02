
### Encode (1 MiB, ~5% mutations)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| encode_greedy_1m             |        3.1 |   0.009072 |    30 |
| encode_onepass_1m            |      29.38 |     0.0783 |    30 |
| encode_correcting_1m         |      25.63 |    0.06219 |    30 |

### Decode / In-place (1 MiB)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| decode_1m                    |       1635 |      4.429 |    30 |
| inplace_1m                   |      373.5 |      1.349 |    30 |

