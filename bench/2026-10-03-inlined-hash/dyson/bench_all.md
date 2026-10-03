
### Encode: Shakespeare (~5.4 MB, 5% mutations)

| Language | Algorithm  |   MiB/s    | CI width (95%) | Runs  |
|----------|------------|------------|------------|-------|
| Rust     | onepass    |      64.03 |       0.49 |   103 |
| Rust     | correcting |      70.81 |     0.7474 |    69 |
| C        | onepass    |      67.75 |     0.8607 |    70 |
| C        | correcting |      61.07 |      1.044 |    60 |
| Cpp      | onepass    |      46.84 |     0.3158 |    60 |
| Cpp      | correcting |      55.93 |     0.5489 |    60 |
| Java     | onepass    |      28.67 |      0.601 |    90 |
| Java     | correcting |      28.67 |      1.265 |    64 |
| Go       | onepass    |      58.14 |      1.725 |    60 |
| Go       | correcting |      40.59 |     0.5762 |    30 |

