
### Encode: Shakespeare (~5.4 MB, 5% mutations)

| Language | Algorithm  |   MiB/s    | CI width (95%) | Runs  |
|----------|------------|------------|------------|-------|
| Rust     | onepass    |      52.08 |      1.318 |   240 |
| Rust     | correcting |      58.66 |     0.5418 |   120 |
| C        | onepass    |      44.46 |     0.5101 |   211 |
| C        | correcting |       36.3 |     0.9065 |    34 |
| Cpp      | onepass    |      40.79 |     0.9494 |    31 |
| Cpp      | correcting |      48.08 |     0.8691 |    69 |
| Java     | onepass    |       25.8 |     0.7844 |    60 |
| Java     | correcting |      25.45 |     0.7985 |    30 |
| Go       | onepass    |      55.76 |      1.913 |   110 |
| Go       | correcting |      39.41 |     0.8778 |    30 |

