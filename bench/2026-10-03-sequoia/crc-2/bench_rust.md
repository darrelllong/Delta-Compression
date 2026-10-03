
### Encode (1 MiB, ~5% mutations)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| encode_greedy_1m             |      5.566 |    0.02183 |    30 |
| encode_onepass_1m            |      29.74 |     0.1167 |    30 |
| encode_correcting_1m         |      26.61 |    0.07636 |    30 |

### Decode / In-place (1 MiB)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| decode_1m                    |       1167 |      8.789 |    30 |
| inplace_1m                   |      393.6 |      1.198 |    30 |

