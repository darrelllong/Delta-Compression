
### Encode (1 MiB, ~5% mutations)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| encode_greedy_1m             |      12.42 |     0.1797 |   270 |
| encode_onepass_1m            |      73.17 |     0.9886 |   120 |
| encode_correcting_1m         |      37.14 |      1.795 |   302 |

### Decode / In-place (1 MiB)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| decode_1m                    |       1051 |      15.07 |    38 |
| inplace_1m                   |      529.1 |      6.177 |    36 |

