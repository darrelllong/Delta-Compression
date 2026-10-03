
### Encode (1 MiB, ~5% mutations)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| encode_greedy_1m             |      5.592 |   0.006687 |    30 |
| encode_onepass_1m            |       29.8 |    0.01808 |    30 |
| encode_correcting_1m         |      26.64 |    0.07379 |    30 |

### Decode / In-place (1 MiB)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| decode_1m                    |       1184 |      7.858 |    30 |
| inplace_1m                   |      393.8 |          3 |    30 |

