
### Encode (1 MiB, ~5% mutations)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| encode_greedy_1m             |      5.616 |   0.006972 |    30 |
| encode_onepass_1m            |      29.13 |     0.1103 |    30 |
| encode_correcting_1m         |      26.35 |    0.03711 |    30 |

### Decode / In-place (1 MiB)

| Operation                    |   MiB/s    | CI width (95%) | Runs  |
|------------------------------|------------|------------|-------|
| decode_1m                    |       1187 |      2.226 |    30 |
| inplace_1m                   |      315.5 |     0.9464 |    30 |

