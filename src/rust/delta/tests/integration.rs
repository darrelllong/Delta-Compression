use delta::{
    apply_delta, apply_delta_inplace, decode_delta, diff_correcting, diff_greedy, diff_onepass,
    encode_delta, is_inplace_delta, is_prime, make_inplace, next_prime, output_size,
    place_commands, Command, CyclePolicy, PlacedCommand, TABLE_SIZE,
};

// ── helpers ──────────────────────────────────────────────────────────────

type DiffFn = fn(&[u8], &[u8], usize, usize, bool, bool, usize) -> Vec<Command>;

fn roundtrip(algo_fn: DiffFn, r: &[u8], v: &[u8], p: usize) -> Vec<u8> {
    let cmds = algo_fn(r, v, p, TABLE_SIZE, false, false, 0);
    let placed = place_commands(&cmds);
    let delta = encode_delta(&placed, false, output_size(&cmds));
    let (placed2, _, _) = decode_delta(&delta).unwrap();
    // Apply standard: read from r, write sequentially
    let mut out = vec![0u8; v.len()];
    delta::apply_placed_to(r, &placed2, &mut out);
    out
}

fn inplace_roundtrip(
    algo_fn: DiffFn,
    r: &[u8],
    v: &[u8],
    policy: CyclePolicy,
    p: usize,
) -> Vec<u8> {
    let cmds = algo_fn(r, v, p, TABLE_SIZE, false, false, 0);
    let ip = make_inplace(r, &cmds, policy);
    apply_delta_inplace(r, &ip, v.len())
}

fn inplace_binary_roundtrip(
    algo_fn: DiffFn,
    r: &[u8],
    v: &[u8],
    policy: CyclePolicy,
    p: usize,
) -> Vec<u8> {
    let cmds = algo_fn(r, v, p, TABLE_SIZE, false, false, 0);
    let ip = make_inplace(r, &cmds, policy);
    let delta = encode_delta(&ip, true, v.len());
    let (ip2, _, vs) = decode_delta(&delta).unwrap();
    apply_delta_inplace(r, &ip2, vs)
}

fn all_algos() -> Vec<(&'static str, DiffFn)> {
    vec![
        ("greedy", diff_greedy as DiffFn),
        ("onepass", diff_onepass as DiffFn),
        ("correcting", correcting_wrapper as DiffFn),
    ]
}

// Wrapper to match DiffFn signature (correcting has extra buf_cap param)
fn correcting_wrapper(r: &[u8], v: &[u8], p: usize, q: usize, _verbose: bool, use_splay: bool, min_copy: usize) -> Vec<Command> {
    diff_correcting(r, v, p, q, 256, false, use_splay, min_copy)
}

fn all_policies() -> Vec<(&'static str, CyclePolicy)> {
    vec![
        ("constant", CyclePolicy::Constant),
        ("localmin", CyclePolicy::Localmin),
    ]
}

// ── standard differencing ────────────────────────────────────────────────

// TestPaperExample — Section 2.1.1 of Ajtai et al. 2002
#[test]
fn test_paper_example() {
    let r = b"ABCDEFGHIJKLMNOP";
    let v = b"QWIJKLMNOBCDEFGHZDEFGHIJKL";
    for (name, algo) in all_algos() {
        assert_eq!(apply_delta(r, &algo(r, v, 2, TABLE_SIZE, false, false, 0)), v, "failed for {}", name);
    }
}

// TestIdentical
#[test]
fn test_identical() {
    let data: Vec<u8> = b"The quick brown fox jumps over the lazy dog."
        .iter()
        .cycle()
        .take(44 * 10)
        .copied()
        .collect();
    for (name, algo) in all_algos() {
        let cmds = algo(&data, &data, 2, TABLE_SIZE, false, false, 0);
        assert_eq!(apply_delta(&data, &cmds), data, "failed for {}", name);
        assert!(
            cmds.iter()
                .all(|c| matches!(c, Command::Copy { .. })),
            "{}: identical strings should produce no adds", name
        );
    }
}

// TestCompletelyDifferent
#[test]
fn test_completely_different() {
    let r: Vec<u8> = (0..=255u8).cycle().take(512).collect();
    let v: Vec<u8> = (0..=255u8).rev().cycle().take(512).collect();
    for (name, algo) in all_algos() {
        assert_eq!(apply_delta(&r, &algo(&r, &v, 2, TABLE_SIZE, false, false, 0)), v, "failed for {}", name);
    }
}

// TestEmptyVersion
#[test]
fn test_empty_version() {
    for (name, algo) in all_algos() {
        let cmds = algo(b"hello", b"", 2, TABLE_SIZE, false, false, 0);
        assert!(cmds.is_empty(), "{}: should be empty", name);
        assert_eq!(apply_delta(b"hello", &cmds), b"", "failed for {}", name);
    }
}

// TestEmptyReference
#[test]
fn test_empty_reference() {
    let v = b"hello world";
    for (name, algo) in all_algos() {
        assert_eq!(apply_delta(b"", &algo(b"", v, 2, TABLE_SIZE, false, false, 0)), v, "failed for {}", name);
    }
}

// TestBinaryRoundTrip
#[test]
fn test_binary_roundtrip() {
    let r: Vec<u8> = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        .iter()
        .cycle()
        .take(26 * 100)
        .copied()
        .collect();
    let v: Vec<u8> = b"0123EFGHIJKLMNOPQRS456ABCDEFGHIJKL789"
        .iter()
        .cycle()
        .take(37 * 100)
        .copied()
        .collect();
    for (name, algo) in all_algos() {
        assert_eq!(roundtrip(algo, &r, &v, 4), v, "failed for {}", name);
    }
}

// TestBinaryEncoding — unified format encode/decode
#[test]
fn test_binary_encoding_roundtrip() {
    // Build placed commands manually and verify encode→decode roundtrip
    let placed = vec![
        PlacedCommand::Add {
            dst: 0,
            data: vec![100, 101, 102],
        },
        PlacedCommand::Copy {
            src: 888,
            dst: 3,
            length: 488,
        },
    ];
    let encoded = encode_delta(&placed, false, 491);
    let (decoded, is_ip, vs) = decode_delta(&encoded).unwrap();
    assert!(!is_ip);
    assert_eq!(vs, 491);
    assert_eq!(decoded, placed);
}

#[test]
fn test_binary_encoding_inplace_flag() {
    let placed = vec![PlacedCommand::Copy {
        src: 0,
        dst: 10,
        length: 5,
    }];
    let standard = encode_delta(&placed, false, 15);
    let inplace = encode_delta(&placed, true, 15);

    assert!(!is_inplace_delta(&standard));
    assert!(is_inplace_delta(&inplace));

    // Both decode to the same commands
    let (d1, ip1, vs1) = decode_delta(&standard).unwrap();
    let (d2, ip2, vs2) = decode_delta(&inplace).unwrap();
    assert!(!ip1);
    assert!(ip2);
    assert_eq!(vs1, vs2);
    assert_eq!(d1, d2);
}

// TestLargeCopy
#[test]
fn test_large_copy_roundtrip() {
    let placed = vec![PlacedCommand::Copy {
        src: 100000,
        dst: 0,
        length: 50000,
    }];
    let encoded = encode_delta(&placed, false, 50000);
    let (decoded, _, _) = decode_delta(&encoded).unwrap();
    assert_eq!(decoded.len(), 1);
    match &decoded[0] {
        PlacedCommand::Copy { src, dst, length } => {
            assert_eq!(*src, 100000);
            assert_eq!(*dst, 0);
            assert_eq!(*length, 50000);
        }
        _ => panic!("expected Copy"),
    }
}

// TestLargeAdd
#[test]
fn test_large_add_roundtrip() {
    let big_data: Vec<u8> = (0..=255u8).cycle().take(256 * 4).collect();
    let placed = vec![PlacedCommand::Add {
        dst: 0,
        data: big_data.clone(),
    }];
    let encoded = encode_delta(&placed, false, big_data.len());
    let (decoded, _, _) = decode_delta(&encoded).unwrap();
    assert_eq!(decoded.len(), 1);
    match &decoded[0] {
        PlacedCommand::Add { dst, data } => {
            assert_eq!(*dst, 0);
            assert_eq!(data, &big_data);
        }
        _ => panic!("expected Add"),
    }
}

// TestBackwardExtension
#[test]
fn test_backward_extension() {
    let block: Vec<u8> = b"ABCDEFGHIJKLMNOP"
        .iter()
        .cycle()
        .take(16 * 20)
        .copied()
        .collect();
    let mut r = b"____".to_vec();
    r.extend_from_slice(&block);
    r.extend_from_slice(b"____");
    let mut v = b"**".to_vec();
    v.extend_from_slice(&block);
    v.extend_from_slice(b"**");
    for (name, algo) in all_algos() {
        assert_eq!(apply_delta(&r, &algo(&r, &v, 4, TABLE_SIZE, false, false, 0)), v, "failed for {}", name);
    }
}

// TestTransposition
#[test]
fn test_transposition() {
    let x: Vec<u8> = b"FIRST_BLOCK_DATA_"
        .iter()
        .cycle()
        .take(17 * 10)
        .copied()
        .collect();
    let y: Vec<u8> = b"SECOND_BLOCK_DATA"
        .iter()
        .cycle()
        .take(17 * 10)
        .copied()
        .collect();
    let mut r = x.clone();
    r.extend_from_slice(&y);
    let mut v = y;
    v.extend_from_slice(&x);
    for (name, algo) in all_algos() {
        assert_eq!(apply_delta(&r, &algo(&r, &v, 4, TABLE_SIZE, false, false, 0)), v, "failed for {}", name);
    }
}

// TestScatteredModifications
#[test]
fn test_scattered_modifications() {
    use rand::rngs::StdRng;
    use rand::{Rng, SeedableRng};
    let mut rng = StdRng::seed_from_u64(42);
    let r: Vec<u8> = (0..2000).map(|_| rng.gen()).collect();
    let mut v = r.clone();
    for _ in 0..100 {
        let idx = rng.gen_range(0..v.len());
        v[idx] = rng.gen();
    }
    for (name, algo) in all_algos() {
        assert_eq!(roundtrip(algo, &r, &v, 4), v, "failed for {}", name);
    }
}

// ── in-place basics ──────────────────────────────────────────────────────

// TestInPlacePaperExample
#[test]
fn test_inplace_paper_example() {
    let r = b"ABCDEFGHIJKLMNOP";
    let v = b"QWIJKLMNOBCDEFGHZDEFGHIJKL";
    for (_, algo) in all_algos() {
        for (_, pol) in all_policies() {
            assert_eq!(
                inplace_roundtrip(algo, r, v, pol, 2),
                v,
            );
        }
    }
}

// TestInPlaceBinaryRoundTrip
#[test]
fn test_inplace_binary_roundtrip() {
    let r: Vec<u8> = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        .iter()
        .cycle()
        .take(26 * 100)
        .copied()
        .collect();
    let v: Vec<u8> = b"0123EFGHIJKLMNOPQRS456ABCDEFGHIJKL789"
        .iter()
        .cycle()
        .take(37 * 100)
        .copied()
        .collect();
    for (_, algo) in all_algos() {
        for (_, pol) in all_policies() {
            assert_eq!(inplace_binary_roundtrip(algo, &r, &v, pol, 4), v);
        }
    }
}

// TestInPlaceSimpleTransposition
#[test]
fn test_inplace_simple_transposition() {
    let x: Vec<u8> = b"FIRST_BLOCK_DATA_"
        .iter()
        .cycle()
        .take(17 * 20)
        .copied()
        .collect();
    let y: Vec<u8> = b"SECOND_BLOCK_DATA"
        .iter()
        .cycle()
        .take(17 * 20)
        .copied()
        .collect();
    let mut r = x.clone();
    r.extend_from_slice(&y);
    let mut v = y;
    v.extend_from_slice(&x);
    for (_, algo) in all_algos() {
        for (_, pol) in all_policies() {
            assert_eq!(inplace_roundtrip(algo, &r, &v, pol, 4), v);
        }
    }
}

// TestInPlaceVersionLarger
#[test]
fn test_inplace_version_larger() {
    let r: Vec<u8> = b"ABCDEFGH"
        .iter()
        .cycle()
        .take(8 * 50)
        .copied()
        .collect();
    let mut v: Vec<u8> = b"XXABCDEFGH"
        .iter()
        .cycle()
        .take(10 * 50)
        .copied()
        .collect();
    let extra: Vec<u8> = b"YYABCDEFGH"
        .iter()
        .cycle()
        .take(10 * 50)
        .copied()
        .collect();
    v.extend_from_slice(&extra);
    for (_, algo) in all_algos() {
        for (_, pol) in all_policies() {
            assert_eq!(inplace_roundtrip(algo, &r, &v, pol, 4), v);
        }
    }
}

// TestInPlaceVersionSmaller
#[test]
fn test_inplace_version_smaller() {
    let r: Vec<u8> = b"ABCDEFGHIJKLMNOP"
        .iter()
        .cycle()
        .take(16 * 100)
        .copied()
        .collect();
    let v: Vec<u8> = b"EFGHIJKL"
        .iter()
        .cycle()
        .take(8 * 50)
        .copied()
        .collect();
    for (_, algo) in all_algos() {
        for (_, pol) in all_policies() {
            assert_eq!(inplace_roundtrip(algo, &r, &v, pol, 4), v);
        }
    }
}

// TestInPlaceIdentical
#[test]
fn test_inplace_identical() {
    let data: Vec<u8> = b"The quick brown fox jumps over the lazy dog."
        .iter()
        .cycle()
        .take(44 * 10)
        .copied()
        .collect();
    for (_, algo) in all_algos() {
        for (_, pol) in all_policies() {
            assert_eq!(inplace_roundtrip(algo, &data, &data, pol, 2), data);
        }
    }
}

// TestInPlaceEmptyVersion
#[test]
fn test_inplace_empty_version() {
    for (_, algo) in all_algos() {
        let cmds = algo(b"hello", b"", 2, TABLE_SIZE, false, false, 0);
        let ip = make_inplace(b"hello", &cmds, CyclePolicy::Localmin);
        assert_eq!(apply_delta_inplace(b"hello", &ip, 0), b"");
    }
}

// TestInPlaceScattered
#[test]
fn test_inplace_scattered() {
    use rand::rngs::StdRng;
    use rand::{Rng, SeedableRng};
    let mut rng = StdRng::seed_from_u64(99);
    let r: Vec<u8> = (0..2000).map(|_| rng.gen()).collect();
    let mut v = r.clone();
    for _ in 0..100 {
        let idx = rng.gen_range(0..v.len());
        v[idx] = rng.gen();
    }
    for (_, algo) in all_algos() {
        for (_, pol) in all_policies() {
            assert_eq!(inplace_binary_roundtrip(algo, &r, &v, pol, 4), v);
        }
    }
}

// TestInPlaceFormatDetection
#[test]
fn test_standard_not_detected_as_inplace() {
    let r: Vec<u8> = b"ABCDEFGH"
        .iter()
        .cycle()
        .take(8 * 10)
        .copied()
        .collect();
    let v: Vec<u8> = b"EFGHABCD"
        .iter()
        .cycle()
        .take(8 * 10)
        .copied()
        .collect();
    let cmds = diff_greedy(&r, &v, 2, TABLE_SIZE, false, false, 0);
    let placed = place_commands(&cmds);
    let delta = encode_delta(&placed, false, v.len());
    assert!(!is_inplace_delta(&delta));
}

#[test]
fn test_inplace_detected() {
    let r: Vec<u8> = b"ABCDEFGH"
        .iter()
        .cycle()
        .take(8 * 10)
        .copied()
        .collect();
    let v: Vec<u8> = b"EFGHABCD"
        .iter()
        .cycle()
        .take(8 * 10)
        .copied()
        .collect();
    let cmds = diff_greedy(&r, &v, 2, TABLE_SIZE, false, false, 0);
    let ip = make_inplace(&r, &cmds, CyclePolicy::Localmin);
    let delta = encode_delta(&ip, true, v.len());
    assert!(is_inplace_delta(&delta));
}

// ── in-place: variable-length transpositions ─────────────────────────────

fn make_blocks() -> Vec<Vec<u8>> {
    let sizes = [200, 500, 1234, 3000, 800, 4999, 1500, 2750];
    sizes
        .iter()
        .enumerate()
        .map(|(i, &sz)| {
            (0..sz)
                .map(|j| ((i as u16 * 37 + j as u16) & 0xFF) as u8)
                .collect()
        })
        .collect()
}

fn blocks_ref(blocks: &[Vec<u8>]) -> Vec<u8> {
    blocks.iter().flat_map(|b| b.iter().copied()).collect()
}

// TestInPlaceVarlenPermutation — random permutation of all 8 blocks
#[test]
fn test_inplace_varlen_permutation() {
    let blocks = make_blocks();
    let r = blocks_ref(&blocks);

    use rand::rngs::StdRng;
    use rand::seq::SliceRandom;
    use rand::SeedableRng;
    let mut rng = StdRng::seed_from_u64(2003);
    let mut perm: Vec<usize> = (0..8).collect();
    perm.shuffle(&mut rng);
    let v: Vec<u8> = perm
        .iter()
        .flat_map(|&i| blocks[i].iter().copied())
        .collect();

    for (_, algo) in all_algos() {
        for (_, pol) in all_policies() {
            assert_eq!(inplace_roundtrip(algo, &r, &v, pol, 4), v);
        }
    }
}

// TestInPlaceVarlenReverse — all 8 blocks in reverse order
#[test]
fn test_inplace_varlen_reverse() {
    let blocks = make_blocks();
    let r = blocks_ref(&blocks);
    let v: Vec<u8> = blocks.iter().rev().flat_map(|b| b.iter().copied()).collect();

    for (_, algo) in all_algos() {
        for (_, pol) in all_policies() {
            assert_eq!(inplace_roundtrip(algo, &r, &v, pol, 4), v);
        }
    }
}

// TestInPlaceVarlenJunk — permuted blocks interleaved with random junk
#[test]
fn test_inplace_varlen_junk() {
    let blocks = make_blocks();
    let r = blocks_ref(&blocks);

    use rand::rngs::StdRng;
    use rand::seq::SliceRandom;
    use rand::{Rng, SeedableRng};
    let mut rng = StdRng::seed_from_u64(20030);
    let junk: Vec<u8> = (0..300).map(|_| rng.gen()).collect();
    let mut perm: Vec<usize> = (0..8).collect();
    perm.shuffle(&mut rng);
    let mut pieces: Vec<u8> = Vec::new();
    for &i in &perm {
        pieces.extend_from_slice(&blocks[i]);
        let junk_len = rng.gen_range(50..=300);
        pieces.extend_from_slice(&junk[..junk_len.min(junk.len())]);
    }
    let v = pieces;

    for (_, algo) in all_algos() {
        for (_, pol) in all_policies() {
            assert_eq!(inplace_roundtrip(algo, &r, &v, pol, 4), v);
        }
    }
}

// TestInPlaceVarlenDropDup — drop some blocks, duplicate others
#[test]
fn test_inplace_varlen_drop_dup() {
    let blocks = make_blocks();
    let r = blocks_ref(&blocks);
    let mut v = Vec::new();
    v.extend_from_slice(&blocks[3]);
    v.extend_from_slice(&blocks[0]);
    v.extend_from_slice(&blocks[0]);
    v.extend_from_slice(&blocks[5]);
    v.extend_from_slice(&blocks[3]);

    for (_, algo) in all_algos() {
        for (_, pol) in all_policies() {
            assert_eq!(inplace_roundtrip(algo, &r, &v, pol, 4), v);
        }
    }
}

// TestInPlaceVarlenDoubleSized — version is 2x reference
#[test]
fn test_inplace_varlen_double_sized() {
    let blocks = make_blocks();
    let r = blocks_ref(&blocks);

    use rand::rngs::StdRng;
    use rand::seq::SliceRandom;
    use rand::SeedableRng;
    let mut rng = StdRng::seed_from_u64(7001);
    let mut p1: Vec<usize> = (0..8).collect();
    p1.shuffle(&mut rng);
    let mut p2: Vec<usize> = (0..8).collect();
    p2.shuffle(&mut rng);
    let mut v: Vec<u8> = p1
        .iter()
        .flat_map(|&i| blocks[i].iter().copied())
        .collect();
    let v2: Vec<u8> = p2
        .iter()
        .flat_map(|&i| blocks[i].iter().copied())
        .collect();
    v.extend_from_slice(&v2);

    for (_, algo) in all_algos() {
        for (_, pol) in all_policies() {
            assert_eq!(inplace_roundtrip(algo, &r, &v, pol, 4), v);
        }
    }
}

// TestInPlaceVarlenSubset — version is much smaller, just two blocks
#[test]
fn test_inplace_varlen_subset() {
    let blocks = make_blocks();
    let r = blocks_ref(&blocks);
    let mut v = Vec::new();
    v.extend_from_slice(&blocks[6]);
    v.extend_from_slice(&blocks[2]);

    for (_, algo) in all_algos() {
        for (_, pol) in all_policies() {
            assert_eq!(inplace_roundtrip(algo, &r, &v, pol, 4), v);
        }
    }
}

// TestInPlaceVarlenHalfBlockScramble — split each block in half, shuffle all 16 halves
#[test]
fn test_inplace_varlen_half_block_scramble() {
    let blocks = make_blocks();
    let r = blocks_ref(&blocks);
    let mut halves: Vec<Vec<u8>> = Vec::new();
    for b in &blocks {
        let mid = b.len() / 2;
        halves.push(b[..mid].to_vec());
        halves.push(b[mid..].to_vec());
    }

    use rand::rngs::StdRng;
    use rand::seq::SliceRandom;
    use rand::SeedableRng;
    let mut rng = StdRng::seed_from_u64(5555);
    let mut perm: Vec<usize> = (0..halves.len()).collect();
    perm.shuffle(&mut rng);
    let v: Vec<u8> = perm
        .iter()
        .flat_map(|&i| halves[i].iter().copied())
        .collect();

    for (_, algo) in all_algos() {
        for (_, pol) in all_policies() {
            assert_eq!(
                inplace_roundtrip(algo, &r, &v, pol, 4),
                v,
                "inplace roundtrip failed"
            );
            assert_eq!(
                inplace_binary_roundtrip(algo, &r, &v, pol, 4),
                v,
                "inplace binary roundtrip failed"
            );
        }
    }
}

// TestInPlaceVarlenRandomTrials — 20 random trials
#[test]
fn test_inplace_varlen_random_trials() {
    let blocks = make_blocks();
    let r = blocks_ref(&blocks);

    use rand::rngs::StdRng;
    use rand::seq::SliceRandom;
    use rand::{Rng, SeedableRng};
    let mut rng = StdRng::seed_from_u64(9999);

    let mut trials: Vec<(Vec<usize>, Vec<u8>)> = Vec::new();
    for _ in 0..20 {
        let k = rng.gen_range(3..=8);
        let mut indices: Vec<usize> = (0..8).collect();
        indices.shuffle(&mut rng);
        indices.truncate(k);
        indices.shuffle(&mut rng);
        let v: Vec<u8> = indices
            .iter()
            .flat_map(|&i| blocks[i].iter().copied())
            .collect();
        trials.push((indices, v));
    }

    for (_, algo) in all_algos() {
        for (_, pol) in all_policies() {
            for (chosen, v) in &trials {
                assert_eq!(
                    inplace_roundtrip(algo, &r, v, pol, 4),
                    *v,
                    "failed on {:?}",
                    chosen
                );
            }
        }
    }
}

// TestLocalminPicksSmallest
#[test]
fn test_localmin_picks_smallest() {
    let blocks = make_blocks();
    let r = blocks_ref(&blocks);
    let v: Vec<u8> = blocks.iter().rev().flat_map(|b| b.iter().copied()).collect();

    let cmds = diff_greedy(&r, &v, 4, TABLE_SIZE, false, false, 0);
    let ip_const = make_inplace(&r, &cmds, CyclePolicy::Constant);
    let ip_lmin = make_inplace(&r, &cmds, CyclePolicy::Localmin);

    let add_const: usize = ip_const
        .iter()
        .filter_map(|c| match c {
            PlacedCommand::Add { data, .. } => Some(data.len()),
            _ => None,
        })
        .sum();
    let add_lmin: usize = ip_lmin
        .iter()
        .filter_map(|c| match c {
            PlacedCommand::Add { data, .. } => Some(data.len()),
            _ => None,
        })
        .sum();
    assert!(
        add_lmin <= add_const,
        "localmin ({}) should be <= constant ({})",
        add_lmin,
        add_const
    );
}

// ── checkpointing: correcting with various table sizes ──────────────────

#[test]
fn test_correcting_checkpointing_tiny_table() {
    // With a tiny table (q=7), checkpointing still produces correct output.
    let r = b"ABCDEFGHIJKLMNOP".repeat(20); // 320 bytes
    let mut v = r[..160].to_vec();
    v.extend_from_slice(b"XXXXYYYY");
    v.extend_from_slice(&r[160..]);
    let cmds = diff_correcting(&r, &v, 16, 7, 256, false, false, 0);
    let recovered = apply_delta(&r, &cmds);
    assert_eq!(recovered, v);
}

#[test]
fn test_correcting_checkpointing_various_sizes() {
    // Correcting produces correct output across a range of table sizes.
    let r: Vec<u8> = (0..=255u8).cycle().take(2000).collect();
    let mut v = r[..500].to_vec();
    v.extend_from_slice(&[0xFFu8; 50]);
    v.extend_from_slice(&r[500..]);
    for q in [7, 31, 101, 1009, TABLE_SIZE] {
        let cmds = diff_correcting(&r, &v, 16, q, 256, false, false, 0);
        let recovered = apply_delta(&r, &cmds);
        assert_eq!(recovered, v, "failed with q={}", q);
    }
}

#[test]
fn test_next_prime_is_prime() {
    // Verify that next_prime always returns a prime, and that the TABLE_SIZE
    // constant is itself prime.
    assert!(is_prime(TABLE_SIZE), "TABLE_SIZE should be prime");
    assert!(is_prime(next_prime(1048574)));
    assert_eq!(next_prime(1048573), 1048573);
}
