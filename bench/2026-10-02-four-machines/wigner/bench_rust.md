
### Encode (1 MiB, ~5% mutations)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| encode_greedy_1m             |      8.842 |    0.08766 |    30 |
| encode_onepass_1m            |      66.19 |      1.018 |    30 |
| encode_correcting_1m         |      30.01 |     0.1852 |    30 |

### Decode / In-place (1 MiB)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| decode_1m                    |      769.9 |       2.12 |    30 |
| inplace_1m                   |      384.2 |      1.442 |    30 |

