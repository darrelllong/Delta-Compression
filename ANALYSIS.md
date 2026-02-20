# ANALYSIS — Algorithms, Design Decisions, and Performance

Explains why the implementation is structured as it is: the algorithmic
background, key design choices, and measured performance data.

## Relationship to edit distance and common substrings

Differential compression emerged as an application of the
string-to-string correction problem (Wagner and Fischer 1974), which
asks for the minimum-cost sequence of edits transforming one string into
another.  Levenshtein distance (Levenshtein 1966) is the simplest
instance: single-character insertions, deletions, and substitutions,
computed by O(mn) dynamic programming.

Early differencing algorithms solved string-to-string correction by
computing the longest common subsequence (LCS) of strings R and V, then
treating all characters outside the LCS as data to be added explicitly
(Ajtai et al. 2002, Section 1.1).  This formulation assumes a one-to-one
correspondence between matching substrings and requires that they appear
in the same order in both strings.  Tichy (1984) generalized to the
"string-to-string correction problem with block move," which permits
variable-length substrings to be copied multiple times and out of
sequence.  Traditional algorithms for this problem — the greedy
algorithm of Reichenberger (1991) and the dynamic programming approach
of Miller and Myers (1985) — run in O(mn) or O(n^2) time.

The algorithms implemented here (Ajtai et al. 2002) solve the
string-to-string correction problem with block move using Karp-Rabin
fingerprinting (Karp and Rabin 1987) to discover variable-length common
substrings between R and V in linear time.  A single substring in R may
be copied to multiple locations in V, and matches need not preserve
order.  The onepass and correcting algorithms run in O(n) time with O(1)
space — compared to O(mn) for edit-distance dynamic programming.  For
a 1 MB file with a 1 KB change, Levenshtein requires ~10^12 operations;
onepass finds the change in a single linear scan.

## Checkpointing (correcting algorithm)

The correcting algorithm uses checkpointing (Ajtai et al. 2002,
Section 8) to select which seeds enter the hash table.

Two parameters govern the hash table:

- **|C|** = auto-sized table capacity (`next_prime(max(table_size, 2 *
  num_seeds / p))`).  Each entry is ~24 bytes.  `--table-size` sets
  the floor.
- **|F|** ≈ 2|R| (auto-computed): the footprint modulus.  Set to
  `next_prime(2 * num_seeds)` for good distribution.

The checkpoint stride is `m = ⌈|F|/|C|⌉`.  A seed is a **checkpoint
seed** if its footprint `f = fingerprint mod |F|` satisfies `f ≡ k
(mod m)` (Section 8.1, Eq. 3), where `k` is a biased checkpoint class
chosen from V (p. 348).  Only checkpoint seeds are stored in or
looked up from the hash table; all others are skipped.  This gives
~|C|/2 occupied slots (~50% load factor) regardless of |R| (Section 8.1,
p. 347: L · |C|/|F| ≈ |C|/2, hence |F| ≈ 2L).

The checkpoint stride `m` equals the average spacing between checkpoint
seeds.  Matching substrings shorter than ~m bytes may be missed because
none of their seeds pass the checkpoint test.  Longer matches are found
reliably: backward extension (Section 5.1) discovers the true start of
the match even when it falls between checkpoint positions (Section 8.2,
p. 349).

With auto-sizing, `m ≈ p` (the seed length), so checkpoint granularity
roughly matches seed granularity.  When the reference is small enough
that |F| ≤ |C|, the stride is m=1 and every seed is a checkpoint —
equivalent to direct indexing with no filtering overhead.

The footprint modulus |F| is chosen as a prime using the Miller-Rabin
probabilistic primality test (Rabin 1980) with 100 random witnesses
drawn uniformly from [2, n-2).  Each witness independently has at most
a 1/4 probability of being a "liar" (falsely certifying a composite as
prime), so 100 rounds give Pr[false positive] ≤ 4^{-100} < 10^{-60}.
Using random witnesses avoids the brittleness of fixed witness sets,
which can be fooled by adversarially chosen composites — fixed witnesses
are, to put it mildly, weak sauce.  Carmichael numbers (561, 1105,
1729, ...) pass the Fermat test for all bases coprime to n, but
Miller-Rabin with random witnesses catches them reliably.

## Splay tree: design and tradeoffs

The splay tree option (`--splay`) replaces the hash table with a
Tarjan-Sleator self-adjusting binary search tree (Sleator and Tarjan 1985).
Every access splays the accessed node to the root via zig/zig-zig/zig-zag
rotations, giving amortized O(log n) per operation.

**Why it helps for onepass:** onepass inserts a seed from R and then
looks it up shortly after when it scans the corresponding V region.
The splay tree exploits this temporal locality: a recently-inserted seed
is near the root, so the lookup is cheap.  On 871 MB kernel tarballs
this gives a ~17% speedup over the hash table (0.5s vs 0.6s).

**Why it hurts for correcting:** correcting's R pass inserts millions
of checkpoint seeds in random order before any V lookups begin.  The
build phase has no locality benefit, and O(log n) per insertion is
slower than O(1) hash table insertion.  Lookups during the V pass also
lack the recent-access advantage.  On kernel tarballs, correcting+splay
is 3.5× slower (49s vs 14s).

**Why splay improves correcting ratio:** the hash table indexes seeds by
`f / m` (where `f = fp % |F|`), so two seeds with the same `f / m`
collide and only the first is retained.  The splay tree keys on the full
64-bit fingerprint, making collisions negligible: every checkpoint-passing
R seed gets its own node.  Splay stores more fingerprints and finds more
matches, yielding slightly better compression at the cost of O(log n)
lookups.  On the 22 MB literary corpus, correcting+hash gives 22.94%
and correcting+splay gives 22.39%.

**Practical access cost:** the O(log n) characterization is a worst-case
amortized bound.  A fingerprint appearing k times in R is splayed to the
root k times during the build phase, so the most common fingerprints are
near the root by the time the V scan begins.  For a Zipfian frequency
distribution — which natural language and source code both follow closely
— the weighted average access cost is O(log H) where H is the entropy of
the distribution, substantially less than O(log n).  On kernel tarballs
the effect is visible: common boilerplate dominates the fingerprint
distribution, limiting the practical slowdown to 3.5× rather than what
the asymptotic ratio would suggest.

## In-place conversion: CRWI graph and cycle breaking

In-place reconstruction writes the version V directly into the buffer
holding the reference R, without a separate output buffer.  This requires
that a copy command reading from R[src..src+len] execute before any copy
command that overwrites that region.

### The CRWI digraph

The dependency relation is captured by the Copy-Read/Write-Intersection
(CRWI) digraph (Burns, Long, and Stockmeyer 2003): edge i → j means
copy i reads a region that copy j will write, so i must execute before j.
If this graph is acyclic, a topological sort gives a safe execution order.
If it contains a cycle, the commands in the cycle cannot all be copies —
at least one must be converted to a literal add (materializing the source
data before it is overwritten).

The CRWI graph is built in O(n log n + E): copies are sorted by write
start, and for each copy's read interval a binary search finds the exact
range of overlapping writes in O(log n), exploiting the fact that write
intervals are non-overlapping (each output byte is written exactly once).

### Cycle breaking: Kahn + Tarjan + amortized DFS

A naïve approach — remove vertices one-by-one until the graph is acyclic
— can convert far more copies than necessary if it ignores the global
structure.  The correct algorithm combines three ideas:

1. **Global Kahn topological sort** processes all zero-in-degree copies
   first, in order of increasing length (to minimize the total size of
   any forced adds).  When a copy is processed, its out-edges are removed
   and successors whose in-degree drops to zero are added to the queue.
   This preserves the **cascade effect**: converting one copy to an add
   globally decrements in-degrees across SCC boundaries, potentially
   freeing other copies for free.

2. **Tarjan SCC decomposition** identifies the strongly connected
   components of the CRWI graph before Kahn begins.  SCCs are processed
   in topological order (sources first); non-trivial SCCs (size > 1)
   are the only ones that contain cycles and need cycle-breaking attention.

3. **Per-SCC amortized DFS** finds a cycle within one SCC, selects the
   minimum-length copy in that cycle as the victim, and converts it to
   an add.  Three amortizations ensure O(|SCC| + E_SCC) total work per
   SCC, not O(|SCC|) per stall:

   - **scc_id filter (O(1) per neighbor):** Instead of setting and
     clearing a `member[]` bitmap for each stall — O(|SCC|) per call —
     the DFS checks `scc_id[w] != sid || removed[w]` in O(1).
     The global `scc_id[]` array is precomputed by Tarjan and never
     modified; the scc_id filter isolates one SCC without any per-call
     sweep.

   - **color=2 persistence:** DFS colors vertices gray (1) when on the
     path and black (2) when fully explored.  Black vertices persist
     across calls within the same SCC.  This is monotone-correct:
     removing a vertex can only reduce edges, never introduce new cycles,
     so a vertex with no reachable cycle remains cycle-free after any
     removal.  Total DFS work per SCC is O(|SCC| + E_SCC) amortized
     across all stalls, not O(|SCC|) per stall.

   - **scan_start:** the outer DFS loop resumes from where the last call
     left off, accumulating O(|SCC|) total outer-loop work per SCC
     instead of O(|SCC|) per stall.

   On cycle found, only the gray (color=1) vertices on the cycle path
   are reset to 0; black (color=2) vertices are untouched.  An
   `scc_active[id]` counter tracks live members, giving O(1) SCC
   exhaustion checks in the global Kahn loop.

### Why per-SCC local Kahn is wrong

An earlier approach used Tarjan SCC plus a *local* Kahn sort within each
SCC, computing in-degrees only among within-SCC edges.  This loses the
**global cascade effect**: when a victim is converted, the global in-degree
decrement can free copies in other SCCs at no additional conversion cost.
Local Kahn misses this, producing 56% more conversions (16,048 vs 10,265
at 16 MB 100% permutation) and significantly worse compression ratios
(0.4036 vs 0.2569).

### Complexity

CRWI graph build: O(n log n + E).
Kahn + Tarjan + amortized DFS: O(n log n + E) (Kahn heap is O(n log n);
Tarjan and amortized DFS are O(n + E)).
Total: O(n log n + E).

---

## Performance benchmarks

### Kernel tarball benchmark (linux-5.1 → linux-5.1.1, 871 MB)

All five implementations, same input pair, default flags.  All produce
byte-identical delta files.

**onepass** (delta: 5.1 MB, ratio: 0.58%)

| Implementation | Time |
|----------------|-----:|
| Rust | 0.6s |
| C | 0.7s |
| C++ | 0.8s |
| Java | 2.2s |
| Python | 69s |

**correcting** (delta: 7.0 MB, ratio: 0.81%)

| Implementation | Time |
|----------------|-----:|
| Rust | 5.3s |
| Java | 7.4s |
| C | 13.6s |
| C++ | 13.6s |
| Python | 354s |

Rust leads on both algorithms.  For correcting, Java outperforms C and
C++ by nearly 2×; for onepass the JVM overhead shows and Java lands
between C and Python.  C and C++ are within measurement noise of each
other on correcting; C edges C++ slightly on onepass.  Python is
60–120× slower than Rust.

**Rust, default vs `--splay` (onepass)**

| Algorithm | Flags | Time | Delta | Copies | Median copy |
|-----------|-------|-----:|------:|-------:|------------:|
| onepass | (default) | 0.6s | 5.1 MB | 205,030 | 89 B |
| onepass | `--splay` | 0.5s | 5.1 MB | 205,030 | 89 B |
| correcting | (default) | 5.3s | 7.0 MB | 243,546 | 91 B |

The copy-length distribution is heavy-tailed: median is 89–91 bytes
(barely above the 16-byte seed length), but the mean is 3,500–4,200
bytes and the maximum reaches 14 MB.  Most copies are short, but most
*bytes* come from long copies.

### Cross-version kernel benchmark (linux-5.1.x, C++)

All six ordered pairs of linux-5.1.1, 5.1.2, and 5.1.3 (~831 MB each),
encoded with the C++ implementation (default flags):

| Ref → Ver | onepass Ratio | onepass Time | correcting Ratio | correcting Time |
|-----------|-------------:|------------:|-----------------:|----------------:|
| 5.1.1 → 5.1.2 | 0.54% | 3.8s | 0.86% | 13.8s |
| 5.1.1 → 5.1.3 | 0.55% | 2.1s | 0.81% | 13.9s |
| 5.1.2 → 5.1.1 | 0.53% | 0.8s | 0.81% | 13.9s |
| 5.1.2 → 5.1.3 | 0.47% | 0.7s | 1.02% | 14.0s |
| 5.1.3 → 5.1.1 | 0.54% | 0.8s | 0.78% | 14.0s |
| 5.1.3 → 5.1.2 | 0.47% | 0.7s | 0.85% | 14.0s |

Onepass is 4–20× faster than correcting and achieves better ratios on
every pair.  The 5.1.1 → 5.1.2 onepass time (3.8s) is elevated by a
cold mmap cache on the first run; subsequent pairs drop to 0.7–0.8s.
Correcting times are nearly uniform (~14s) because encoding is dominated
by the build phase over the 831 MB reference.

### Splay tree: correcting compression ratio

The correcting+splay cross-version kernel results (same six pairs, ~831 MB):

| Ref → Ver | Ratio (hash) | Ratio (splay) | Time (hash) | Time (splay) |
|-----------|-------------:|--------------:|------------:|-------------:|
| 5.1.1 → 5.1.2 | 0.86% | 0.85% | 13.8s | 48.9s |
| 5.1.1 → 5.1.3 | 0.81% | 0.80% | 13.9s | 49.5s |
| 5.1.2 → 5.1.1 | 0.81% | 0.80% | 13.9s | 48.9s |
| 5.1.2 → 5.1.3 | 1.02% | 1.00% | 14.0s | 48.6s |
| 5.1.3 → 5.1.1 | 0.78% | 0.78% | 14.0s | 49.2s |
| 5.1.3 → 5.1.2 | 0.85% | 0.83% | 14.0s | 48.7s |

Splay wins on ratio by a small but consistent margin (~0.01–0.02 pp) on
every pair, at 3.5× the time.

### Transposition benchmark

Synthetic test: R and V contain the same blocks in different orders.
R is written in identity order; V is written in a permuted order where
the specified percentage of blocks have been displaced from their
original positions.  Generated by `tests/gen_transpositions.py`;
run with `tests/transposition-benchmark.sh`.

**16 MB — 32,000 blocks × 512 B mean (greedy, onepass, correcting)**

| Algorithm | Perm% | Ratio | Copies | Adds | Time |
|-----------|------:|------:|-------:|-----:|-----:|
| greedy | 0% | 0.0000 | 1 | 0 | 2.403s |
| greedy | 25% | 0.0112 | 14,062 | 0 | 2.377s |
| greedy | 50% | 0.0191 | 24,064 | 0 | 2.478s |
| greedy | 75% | 0.0238 | 30,015 | 0 | 2.509s |
| greedy | 100% | 0.0254 | 31,998 | 0 | 2.736s |
| onepass | 0% | 0.0000 | 1 | 0 | 0.009s |
| onepass | 25% | 0.2580 | 6,064 | 6,063 | 0.206s |
| onepass | 50% | 0.5115 | 8,068 | 8,066 | 0.387s |
| onepass | 75% | 0.7588 | 6,026 | 6,025 | 0.561s |
| onepass | 100% | 0.9921 | 268 | 268 | 0.883s |
| correcting | 0% | 0.0000 | 1 | 0 | 0.133s |
| correcting | 25% | 0.0112 | 14,062 | 0 | 0.137s |
| correcting | 50% | 0.0191 | 24,064 | 0 | 0.139s |
| correcting | 75% | 0.0238 | 30,015 | 0 | 0.144s |
| correcting | 100% | 0.0254 | 31,998 | 0 | 0.147s |

At 16 MB with 512 B blocks (~497 seeds per block), correcting matches
greedy exactly at every permutation level: zero adds, identical copy
counts and ratios.  Each block has enough seeds that at least one
passes the checkpoint filter with overwhelming probability, so no
blocks are missed.  onepass degrades severely — by 100% permutation
its ratio is 0.9921, nearly the full file size as adds.

**1 GB — 8,000,000 blocks × 128 B mean (onepass, correcting)**

| Algorithm | Perm% | Ratio | Copies | Adds | Time |
|-----------|------:|------:|-------:|-----:|-----:|
| onepass | 0% | 0.0000 | 1 | 0 | 0.513s |
| onepass | 25% | 0.4344 | 1,910,225 | 1,910,224 | 21.840s |
| onepass | 50% | 0.6720 | 1,860,789 | 1,860,788 | 33.549s |
| onepass | 75% | 0.8066 | 1,387,053 | 1,387,052 | 39.911s |
| onepass | 100% | 0.8847 | 936,854 | 936,852 | 43.270s |
| correcting | 0% | 0.0000 | 1 | 0 | 9.508s |
| correcting | 25% | 0.0689 | 4,998,726 | 58,453 | 11.494s |
| correcting | 50% | 0.0954 | 6,822,473 | 93,991 | 12.432s |
| correcting | 75% | 0.1052 | 7,491,093 | 108,998 | 12.756s |
| correcting | 100% | 0.1090 | 7,736,399 | 115,630 | 13.066s |

At 1 GB with 128 B blocks (~113 seeds per block), correcting diverges
from greedy.  The checkpoint filter now misses a measurable fraction of
blocks — 58K–116K adds per permutation level — because with only ~7
checkpoint seeds per block (113 seeds / stride m≈16), some blocks have
no seed that passes the checkpoint test for the chosen class k.
correcting is still 6–8× better than onepass at 25–100% permutation and
runs in near-constant time (~10–13 s) regardless of permutation level,
while onepass time grows with permutation as it emits more adds.

**Inplace vs normal — 16 MB, onepass and correcting**

"Cycles" counts copy commands converted to literal adds to break CRWI
dependency cycles.

| Algorithm | Perm% | Ratio-N | Ratio-IP | Adds-N | Adds-IP | Time-N | Time-IP | Cycles |
|-----------|------:|--------:|---------:|-------:|--------:|-------:|--------:|-------:|
| onepass | 0% | 0.0000 | 0.0000 | 0 | 0 | 0.038s | 0.008s | 0 |
| onepass | 25% | 0.2580 | 0.2580 | 6,063 | 6,063 | 0.203s | 0.170s | 0 |
| onepass | 50% | 0.5115 | 0.5115 | 8,066 | 8,066 | 0.376s | 0.325s | 0 |
| onepass | 75% | 0.7588 | 0.7588 | 6,025 | 6,025 | 0.504s | 0.482s | 0 |
| onepass | 100% | 0.9921 | 0.9921 | 268 | 268 | 0.815s | 0.794s | 0 |
| correcting | 0% | 0.0000 | 0.0000 | 0 | 0 | 0.126s | 0.123s | 0 |
| correcting | 25% | 0.0112 | 0.1520 | 0 | 4,847 | 0.123s | 0.151s | 4,847 |
| correcting | 50% | 0.0191 | 0.2409 | 0 | 8,569 | 0.131s | 0.157s | 8,569 |
| correcting | 75% | 0.0238 | 0.2529 | 0 | 9,841 | 0.131s | 0.198s | 9,841 |
| correcting | 100% | 0.0254 | 0.2569 | 0 | 10,265 | 0.135s | 0.215s | 10,265 |

onepass has zero inplace overhead: it already emits adds for displaced
blocks, so the remaining copies don't create cycles in the CRWI graph.

correcting's inplace ratio is higher than standard because it encodes
all transpositions as copies in standard mode, but those copies create
CRWI cycles (copy A reads the region copy B will write; copy B reads the
region copy A will write).  "Cycles broken" counts copy-to-add
conversions: each time the topological sort stalls, the cycle finder
locates one cycle and converts the minimum-length copy to a literal add.
Because a single converted node can participate in multiple cycles, the
number of conversions may be less than the number of distinct cycles.
The count equals Adds-IP exactly, since correcting's standard output has
zero adds.  At 25% permutation 4,847 copies are converted, raising the
ratio from 0.0112 to 0.1520 (~14×).

**Apply-phase performance — 16 MB, onepass and correcting**

Decoding a standard delta and decoding an in-place delta should cost
roughly the same: both walk the command list and emit bytes.  The data
below confirms this.  For correcting, apply time is 5–7 ms at all
permutation levels and is format-transparent.  The design goal holds:
all the cost lives in CRWI construction and cycle-breaking at encode time,
done once.  Apply is essentially free.

| Algorithm | Perm% | Apply-N | Apply-IP |
|-----------|------:|--------:|---------:|
| correcting | 0% | 0.005s | 0.004s |
| correcting | 25% | 0.005s | 0.006s |
| correcting | 50% | 0.006s | 0.006s |
| correcting | 75% | 0.006s | 0.006s |
| correcting | 100% | 0.006s | 0.007s |

onepass apply times are similar for small deltas (low permutation) but
grow with delta size at high permutation: at 100% perm the onepass
standard delta is ~15.9 MB of literal data (ratio 0.9921), requiring
proportionally more I/O.  onepass apply-N and apply-IP remain within
2 ms of each other at every level.

**Inplace scaling — correcting, 16 → 256 MB (512 B mean blocks)**

| Size | Perm% | Ratio-N | Ratio-IP | Adds-IP | Time-N | Time-IP |
|-----:|------:|--------:|---------:|--------:|-------:|--------:|
| 16 MB | 0% | 0.0000 | 0.0000 | 0 | 0.116s | 0.113s |
| 16 MB | 25% | 0.0112 | 0.1520 | 4,847 | 0.115s | 0.136s |
| 16 MB | 50% | 0.0191 | 0.2409 | 8,569 | 0.124s | 0.153s |
| 16 MB | 75% | 0.0238 | 0.2529 | 9,841 | 0.124s | 0.182s |
| 16 MB | 100% | 0.0254 | 0.2569 | 10,265 | 0.124s | 0.200s |
| 32 MB | 0% | 0.0000 | 0.0000 | 0 | 0.308s | 0.247s |
| 32 MB | 25% | 0.0111 | 0.1584 | 10,138 | 0.329s | 0.323s |
| 32 MB | 50% | 0.0191 | 0.2413 | 17,238 | 0.343s | 0.379s |
| 32 MB | 75% | 0.0238 | 0.2491 | 19,411 | 0.339s | 0.478s |
| 32 MB | 100% | 0.0254 | 0.2573 | 20,585 | 0.349s | 0.538s |
| 64 MB | 0% | 0.0000 | 0.0000 | 0 | 0.654s | 0.523s |
| 64 MB | 25% | 0.0111 | 0.1531 | 19,275 | 0.678s | 0.730s |
| 64 MB | 50% | 0.0190 | 0.2368 | 34,053 | 0.673s | 0.887s |
| 64 MB | 75% | 0.0238 | 0.2542 | 39,652 | 0.692s | 1.202s |
| 64 MB | 100% | 0.0254 | 0.2576 | 41,191 | 0.702s | 1.426s |
| 128 MB | 0% | 0.0000 | 0.0000 | 0 | 1.339s | 1.074s |
| 128 MB | 25% | 0.0111 | 0.1576 | 39,388 | 1.424s | 1.991s |
| 128 MB | 50% | 0.0190 | 0.2421 | 69,158 | 1.456s | 2.289s |
| 128 MB | 75% | 0.0238 | 0.2555 | 79,538 | 1.468s | 3.074s |
| 128 MB | 100% | 0.0254 | 0.2582 | 82,561 | 1.476s | 3.527s |
| 256 MB | 0% | 0.0000 | 0.0000 | 0 | 2.641s | 2.170s |
| 256 MB | 25% | 0.0111 | 0.1594 | 79,232 | 2.761s | 4.370s |
| 256 MB | 50% | 0.0190 | 0.2430 | 138,716 | 2.953s | 5.353s |
| 256 MB | 75% | 0.0238 | 0.2544 | 158,392 | 2.947s | 7.923s |
| 256 MB | 100% | 0.0254 | 0.2579 | 164,877 | 3.011s | 9.738s |

The CRWI graph build is O(n log n + E): the binary-search sweep exploits
non-overlapping write intervals for exact overlap detection.
Standard-mode correcting time scales ~2× per doubling (linear in n).
Inplace time at 100% permutation scales ~2.6× per doubling
(0.200 → 0.538 → 1.426 → 3.527 → 9.738 s across 16 → 32 → 64 → 128 → 256 MB),
reflecting the O(n log n + E) total complexity of the Tarjan + global
Kahn + amortized DFS cycle-breaking algorithm.  At 256 MB with 512K
blocks and 164K conversions, the total encode time is under 10 seconds.

### Effect of `--max-table` on correcting ratio (1 GB, 128 B blocks)

Delta ratio as a function of `--max-table` cap, across all five
permutation levels.  Each cell is the correcting ratio for that
(permutation, table size) pair.

| Max table | 0% | 25% | 50% | 75% | 100% |
|----------:|---:|----:|----:|----:|-----:|
| 1M | 0.0000 | 0.8844 | 0.9422 | 0.9550 | 0.9589 |
| 2M | 0.0000 | 0.7933 | 0.8898 | 0.9129 | 0.9198 |
| 4M | 0.0000 | 0.6596 | 0.7989 | 0.8358 | 0.8474 |
| 8M | 0.0000 | 0.4946 | 0.6569 | 0.7065 | 0.7230 |
| 16M | 0.0000 | 0.3262 | 0.4683 | 0.5188 | 0.5368 |
| 32M | 0.0000 | 0.1853 | 0.2744 | 0.3098 | 0.3230 |
| 64M | 0.0000 | 0.0988 | 0.1425 | 0.1601 | 0.1668 |
| 128M | 0.0000 | 0.0689 | 0.0954 | 0.1052 | 0.1090 |
| 256M+ | 0.0000 | 0.0689 | 0.0954 | 0.1052 | 0.1090 |

Ratios are stable at 128M entries and above — that is the natural table
size for this dataset (8M blocks of 128 B with p=16 seeds per block).
The "knee" of the curve lies around 32–64M entries; below that the ratio
climbs steeply as the checkpoint stride grows coarser and the filter
misses an increasing fraction of blocks.

At 1M entries the algorithm operates in the same regime as small-table
configurations in the original paper (Ajtai et al. 2002, Section 8):
checkpointing is so coarse that most blocks are missed and the ratio
approaches 1 for highly-permuted inputs.  A 128M-entry table uses
roughly 3 GB of RAM (~24 bytes per entry).

---

## Just for fun: the Shakespeare authorship question

Delta compression makes a reasonable (if tongue-in-cheek) stylometric probe.
If author A ghostwrote the works attributed to author B, their texts should
share long, structured runs of vocabulary, phrasing, and syntactic idiom —
exactly what the correcting algorithm is designed to find.  Short common-word
matches (median ≤ 17 bytes, i.e. "of the", "in the") are noise; long matches
(mean >> 100 bytes) are signal.

We downloaded the complete works from Project Gutenberg and ran correcting
in both directions (Shakespeare as reference, candidate as version; and vice
versa).  All sizes are bytes of raw UTF-8 plain text.

| Corpus | Size | Source |
|--------|-----:|--------|
| Shakespeare complete works | 5,638,525 B | PG #100 |
| Marlowe: 7 major works | 1,016,834 B | PG #779, 901, 1094, 1496, 1589, 18781, 20288 |
| Bacon: 6 major works | 2,240,861 B | PG #56463, 5500, 45988, 2434, 3290, 46964 |
| Mary Sidney: Discourse + Antonius (clean) | 185,647 B | PG #21789 |
| Mary Sidney: Psalms 44–150 + above (OCR) | 482,766 B | IA + PG #21789 |
| de Vere: ~24 poems (OCR) | 90,753 B | Internet Archive, Looney ed. 1921 |

### Results (correcting algorithm, Shakespeare as reference)

Raw text (as downloaded from Project Gutenberg / Internet Archive):

| Candidate | Corpus | Delta size | Ratio | Coverage | Copies | Mean copy |
|-----------|--------|----------:|------:|---------:|-------:|----------:|
| Marlowe | 1.0 MB clean | 884,620 B | 87.0% | 15.9% | 1,324 | 121.8 B |
| Mary Sidney | 186 KB clean | 166,529 B | 89.7% | 11.4% | 91 | 232.1 B |
| Mary Sidney | 483 KB mixed | 463,678 B | 96.0% | 4.4% | 88 | 239.0 B |
| Bacon | 2.2 MB clean | 2,134,603 B | 95.3% | 7.3% | 2,597 | 62.9 B |
| de Vere | 91 KB OCR | 90,772 B | 100.0% | 0.0% | 0 | — |

OCR-scanned texts (de Vere 1921, Mary Sidney Psalms 1960) contain runs of
extra spaces and mid-word hyphenation from the typesetting, which break the
16-byte exact-match requirement and suppress copy counts artificially.
Collapsing all whitespace runs to a single space (`tr -s '[:space:]'`) gives
a fairer comparison:

| Candidate | Corpus | Ratio | Coverage | Copies | Mean copy |
|-----------|--------|------:|---------:|-------:|----------:|
| Marlowe | 915 KB | 86.1% | 17.4% | 1,460 | 108.8 B |
| Mary Sidney | 421 KB | 95.8% | 5.4% | 218 | 103.9 B |
| Bacon | 2.09 MB | 95.1% | 7.7% | 2,627 | 60.9 B |
| de Vere | 74 KB | 97.8% | 5.6% | 114 | 36.1 B |

Normalization has little effect on already-clean texts (Marlowe: 15.9→17.4%,
Bacon: 7.3→7.7%) but dramatically unblocks OCR texts: de Vere goes from 0
to 114 copies and from 100% to 97.8% ratio, and Mary Sidney's combined corpus
improves from 88 to 218 copies.  Both effects confirm OCR noise was the
primary obstacle, not the absence of shared content.

That said, the signal once unblocked is weak.  De Vere's 114 copies average
only 36 bytes each — the threshold is 16 bytes, so these are short function
phrases ("and the", "of his"), not literary kinship.  Mary Sidney's 218 copies
average 104 bytes each, a longer signal, but still covering only 5.4% of her
text.  Marlowe's 1,460 copies at 109 bytes mean and 17.4% coverage remain the
clear high-water mark.

None achieves meaningful compression.  Successive Linux kernel point releases,
which genuinely share 99%+ of their content, compress to 0.5–1.0%; all
Shakespeare-vs-candidate ratios lie above 86%, indicating almost no shared
structure beyond common Elizabethan English.

**Marlowe** leads by every metric: highest coverage, most copies, lowest
ratio.  Shared genre (blank verse drama) produces the best result, but 83%
of his text still requires raw adds.  Shared genre is not shared authorship.

**Mary Sidney** is the most structurally interesting result: the longest mean
copy (104–232 B depending on corpus and normalization), reflecting shared
classical sources (Petrarch, Garnier, Mornay) that Shakespeare also drew on.
Her authenticated prose corpus is small (~186 KB clean), and the bulk of her
surviving work (Psalms 44–150) is available only as OCR.

**Bacon** has the largest corpus (2.2 MB) yet the weakest signal per byte:
shortest mean copy, coverage barely above de Vere's.  Natural philosophy and
moral essays share only function-word sequences with blank verse drama.

**de Vere** remains last.  After normalization his coverage rises to 5.6%,
but with a mean copy of only 36 bytes it is indistinguishable from noise.
His authenticated corpus (~24 poems) is the smallest of any candidate; the
Oxfordian theory is essentially unfalsifiable by this method.

The compression oracle says: Shakespeare wrote Shakespeare.

---

## References

- M. Ajtai, R. Burns, R. Fagin, D.D.E. Long, and L. Stockmeyer.
  Compactly encoding unstructured inputs with differential compression.
  *Journal of the ACM*, 49(3):318-367, May 2002.

- R.C. Burns, D.D.E. Long, and L. Stockmeyer.
  In-place reconstruction of version differences.
  *IEEE Transactions on Knowledge and Data Engineering*, 15(4):973-984,
  Jul/Aug 2003.

- A.B. Kahn.
  Topological sorting of large networks.
  *Communications of the ACM*, 5(11):558-562, November 1962.

- R.E. Tarjan.
  Depth-first search and linear graph algorithms.
  *SIAM Journal on Computing*, 1(2):146-160, June 1972.

- R.M. Karp and M.O. Rabin.
  Efficient randomized pattern-matching algorithms.
  *IBM Journal of Research and Development*, 31(2):249-260, March 1987.

- V.I. Levenshtein.
  Binary codes capable of correcting deletions, insertions, and reversals.
  *Soviet Physics Doklady*, 10(8):707-710, 1966.

- W. Miller and E.W. Myers.
  A file comparison program.
  *Software — Practice and Experience*, 15(11):1025-1040, 1985.

- M.O. Rabin.
  Probabilistic algorithm for testing primality.
  *Journal of Number Theory*, 12(1):128-138, February 1980.

- D.D. Sleator and R.E. Tarjan.
  Self-adjusting binary search trees.
  *Journal of the ACM*, 32(3):652-686, July 1985.

- A. Reichenberger.
  Delta storage for arbitrary non-text files.
  *Proceedings of the 3rd International Workshop on Software Configuration
  Management*, pages 144-152, 1991.

- W.F. Tichy.
  The string-to-string correction problem with block moves.
  *ACM Transactions on Computer Systems*, 2(4):309-321, November 1984.

- R.A. Wagner and M.J. Fischer.
  The string-to-string correction problem.
  *Journal of the ACM*, 21(1):168-173, January 1974.
