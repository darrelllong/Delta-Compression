
### Encode (1 MiB, ~5% mutations)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| encode_greedy_1m             |      3.109 |   0.006879 |    30 |
| encode_onepass_1m            |      29.25 |    0.01961 |    30 |
| encode_correcting_1m         |      25.62 |    0.04495 |    30 |

### Decode / In-place (1 MiB)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| decode_1m                    |       1628 |      4.661 |    30 |
| inplace_1m                   |      371.9 |      1.491 |    30 |

