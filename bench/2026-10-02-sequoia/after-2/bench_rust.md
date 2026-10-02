
### Encode (1 MiB, ~5% mutations)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| encode_greedy_1m             |      5.583 |    0.01276 |    30 |
| encode_onepass_1m            |      29.75 |    0.01837 |    30 |
| encode_correcting_1m         |      26.66 |    0.05826 |    30 |

### Decode / In-place (1 MiB)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| decode_1m                    |       1181 |      18.38 |    30 |
| inplace_1m                   |      393.1 |      1.264 |    30 |

