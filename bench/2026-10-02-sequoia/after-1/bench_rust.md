
### Encode (1 MiB, ~5% mutations)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| encode_greedy_1m             |      5.587 |   0.007851 |    30 |
| encode_onepass_1m            |      29.77 |    0.01587 |    30 |
| encode_correcting_1m         |      26.72 |    0.03753 |    30 |

### Decode / In-place (1 MiB)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| decode_1m                    |       1185 |      2.624 |    30 |
| inplace_1m                   |      394.3 |      1.382 |    30 |

