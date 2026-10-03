
### Encode (1 MiB, ~5% mutations)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| encode_greedy_1m             |      12.62 |    0.06085 |   101 |
| encode_onepass_1m            |      75.79 |     0.6771 |    30 |
| encode_correcting_1m         |      40.65 |     0.3841 |    70 |

### Decode / In-place (1 MiB)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| decode_1m                    |       1079 |      15.87 |    31 |
| inplace_1m                   |      535.1 |       5.93 |    30 |

