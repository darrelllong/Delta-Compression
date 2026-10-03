
### Encode (1 MiB, ~5% mutations)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| encode_greedy_1m             |       3.13 |   0.007135 |    30 |
| encode_onepass_1m            |      29.68 |    0.02522 |    30 |
| encode_correcting_1m         |      25.62 |    0.05426 |    30 |

### Decode / In-place (1 MiB)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| decode_1m                    |       1630 |      3.335 |    30 |
| inplace_1m                   |      371.6 |      1.587 |    30 |

