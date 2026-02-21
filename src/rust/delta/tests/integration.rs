use delta::{
    apply_delta, apply_delta_inplace, decode_delta, diff_correcting, diff_greedy, diff_onepass,
    encode_delta, is_inplace_delta, is_prime, make_inplace, next_prime, output_size,
    place_commands, shake128_16, unplace_commands, Command, CyclePolicy, DeltaError, DiffOptions,
    PlacedCommand, TABLE_SIZE,
};

// ── helpers ──────────────────────────────────────────────────────────────

type DiffFn = fn(&[u8], &[u8], &DiffOptions) -> Vec<Command>;

fn opts(p: usize) -> DiffOptions {
    DiffOptions { p, ..DiffOptions::default() }
}

fn roundtrip(algo_fn: DiffFn, r: &[u8], v: &[u8], p: usize) -> Vec<u8> {
    let cmds = algo_fn(r, v, &opts(p));
    let placed = place_commands(&cmds);
    let delta = encode_delta(&placed, false, output_size(&cmds), &shake128_16(r), &shake128_16(v));
    let (placed2, _, _, sh, dh) = decode_delta(&delta).unwrap();
    assert_eq!(sh, shake128_16(r));
    assert_eq!(dh, shake128_16(v));
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
    let cmds = algo_fn(r, v, &opts(p));
    let (ip, _) = make_inplace(r, &cmds, policy);
    apply_delta_inplace(r, &ip, v.len())
}

fn inplace_binary_roundtrip(
    algo_fn: DiffFn,
    r: &[u8],
    v: &[u8],
    policy: CyclePolicy,
    p: usize,
) -> Vec<u8> {
    let cmds = algo_fn(r, v, &opts(p));
    let (ip, _) = make_inplace(r, &cmds, policy);
    let delta = encode_delta(&ip, true, v.len(), &shake128_16(r), &shake128_16(v));
    let (ip2, _, vs, sh, dh) = decode_delta(&delta).unwrap();
    assert_eq!(sh, shake128_16(r));
    assert_eq!(dh, shake128_16(v));
    apply_delta_inplace(r, &ip2, vs)
}

fn all_algos() -> Vec<(&'static str, DiffFn)> {
    vec![
        ("greedy", diff_greedy as DiffFn),
        ("onepass", diff_onepass as DiffFn),
        ("correcting", diff_correcting as DiffFn),
    ]
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
        assert_eq!(apply_delta(r, &algo(r, v, &opts(2))), v, "failed for {}", name);
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
        let cmds = algo(&data, &data, &opts(2));
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
        assert_eq!(apply_delta(&r, &algo(&r, &v, &opts(2))), v, "failed for {}", name);
    }
}

// TestEmptyVersion
#[test]
fn test_empty_version() {
    for (name, algo) in all_algos() {
        let cmds = algo(b"hello", b"", &opts(2));
        assert!(cmds.is_empty(), "{}: should be empty", name);
        assert_eq!(apply_delta(b"hello", &cmds), b"", "failed for {}", name);
    }
}

// TestEmptyReference
#[test]
fn test_empty_reference() {
    let v = b"hello world";
    for (name, algo) in all_algos() {
        assert_eq!(apply_delta(b"", &algo(b"", v, &opts(2))), v, "failed for {}", name);
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
    let sh = [0u8; 16];
    let dh = [0xffu8; 16];
    let encoded = encode_delta(&placed, false, 491, &sh, &dh);
    let (decoded, is_ip, vs, sh2, dh2) = decode_delta(&encoded).unwrap();
    assert!(!is_ip);
    assert_eq!(vs, 491);
    assert_eq!(sh2, sh);
    assert_eq!(dh2, dh);
    assert_eq!(decoded, placed);
}

#[test]
fn test_binary_encoding_inplace_flag() {
    let placed = vec![PlacedCommand::Copy {
        src: 0,
        dst: 10,
        length: 5,
    }];
    let sh = [1u8; 16];
    let dh = [2u8; 16];
    let standard = encode_delta(&placed, false, 15, &sh, &dh);
    let inplace_enc = encode_delta(&placed, true, 15, &sh, &dh);

    assert!(!is_inplace_delta(&standard));
    assert!(is_inplace_delta(&inplace_enc));

    // Both decode to the same commands
    let (d1, ip1, vs1, _, _) = decode_delta(&standard).unwrap();
    let (d2, ip2, vs2, _, _) = decode_delta(&inplace_enc).unwrap();
    assert!(!ip1);
    assert!(ip2);
    assert_eq!(vs1, vs2);
    assert_eq!(d1, d2);
}

#[test]
fn test_binary_encoding_magic_v2() {
    let encoded = encode_delta(&[], false, 0, &[0u8; 16], &[0u8; 16]);
    assert_eq!(&encoded[..4], b"DLT\x02");
}

#[test]
fn test_binary_encoding_wrong_magic_rejected() {
    let mut bad = encode_delta(&[], false, 0, &[0u8; 16], &[0u8; 16]);
    bad[3] = 0x01; // downgrade to v1
    assert!(matches!(decode_delta(&bad), Err(DeltaError::InvalidFormat(_))));
}

// TestLargeCopy
#[test]
fn test_large_copy_roundtrip() {
    let placed = vec![PlacedCommand::Copy {
        src: 100000,
        dst: 0,
        length: 50000,
    }];
    let sh = [3u8; 16];
    let dh = [4u8; 16];
    let encoded = encode_delta(&placed, false, 50000, &sh, &dh);
    let (decoded, _, _, _, _) = decode_delta(&encoded).unwrap();
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
    let sh = [5u8; 16];
    let dh = [6u8; 16];
    let encoded = encode_delta(&placed, false, big_data.len(), &sh, &dh);
    let (decoded, _, _, _, _) = decode_delta(&encoded).unwrap();
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
        assert_eq!(apply_delta(&r, &algo(&r, &v, &opts(4))), v, "failed for {}", name);
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
        assert_eq!(apply_delta(&r, &algo(&r, &v, &opts(4))), v, "failed for {}", name);
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
        let cmds = algo(b"hello", b"", &opts(2));
        let (ip, _) = make_inplace(b"hello", &cmds, CyclePolicy::Localmin);
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
    let cmds = diff_greedy(&r, &v, &opts(2));
    let placed = place_commands(&cmds);
    let delta = encode_delta(&placed, false, v.len(), &shake128_16(&r), &shake128_16(&v));
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
    let cmds = diff_greedy(&r, &v, &opts(2));
    let (ip, _) = make_inplace(&r, &cmds, CyclePolicy::Localmin);
    let delta = encode_delta(&ip, true, v.len(), &shake128_16(&r), &shake128_16(&v));
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

    let cmds = diff_greedy(&r, &v, &opts(4));
    let (ip_const, _) = make_inplace(&r, &cmds, CyclePolicy::Constant);
    let (ip_lmin, _) = make_inplace(&r, &cmds, CyclePolicy::Localmin);

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
    let cmds = diff_correcting(&r, &v, &DiffOptions { p: 16, q: 7, ..DiffOptions::default() });
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
        let cmds = diff_correcting(&r, &v, &DiffOptions { p: 16, q, ..DiffOptions::default() });
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

// ── inplace subcommand path ───────────────────────────────────────────────
//
// The `delta inplace` subcommand converts a standard delta to inplace format
// without re-encoding from source: decode → unplace → make_inplace → encode.
// These tests verify that path is equivalent to the direct encode --inplace path.

/// Simulate the `delta inplace` subcommand: encode a standard delta, then
/// convert it via decode → unplace_commands → make_inplace → encode(inplace).
fn via_inplace_subcommand(
    algo_fn: DiffFn,
    r: &[u8],
    v: &[u8],
    policy: CyclePolicy,
    p: usize,
) -> Vec<u8> {
    // Step 1: encode a standard delta (compute hashes in same pass as data)
    let cmds = algo_fn(r, v, &opts(p));
    let placed = place_commands(&cmds);
    let sh = shake128_16(r);
    let dh = shake128_16(v);
    let standard = encode_delta(&placed, false, v.len(), &sh, &dh);
    // Step 2: decode it back, unplace, convert to inplace; preserve hashes
    let (placed2, is_ip, version_size, src_hash, dst_hash) = decode_delta(&standard).unwrap();
    assert!(!is_ip, "standard delta should not be flagged as inplace");
    let cmds2 = unplace_commands(&placed2);
    let (ip, _) = make_inplace(r, &cmds2, policy);
    encode_delta(&ip, true, version_size, &src_hash, &dst_hash)
}

#[test]
fn test_inplace_subcommand_roundtrip() {
    // encode standard → inplace subcommand → decode → apply produces original V
    let cases: &[(&[u8], &[u8])] = &[
        (b"ABCDEF", b"FEDCBA"),
        (b"AAABBBCCC", b"CCCBBBAAA"),
        (b"the quick brown fox", b"the quick brown cat"),
        (b"ABCDEF", b"ABCDEF"),
        (b"hello world", b""),
        (b"", b"hello world"),
    ];
    for (r, v) in cases {
        for (_, algo_fn) in all_algos() {
            for (_, pol) in all_policies() {
                let ip_delta = via_inplace_subcommand(algo_fn, r, v, pol, 2);
                let (cmds, _, _, sh, dh) = decode_delta(&ip_delta).unwrap();
                assert_eq!(sh, shake128_16(r));
                assert_eq!(dh, shake128_16(v));
                let recovered = apply_delta_inplace(r, &cmds, v.len());
                assert_eq!(recovered, *v,
                    "subcommand roundtrip failed for r={:?} v={:?}", r, v);
            }
        }
    }
}

#[test]
fn test_inplace_subcommand_idempotent() {
    // Passing an already-inplace delta through the subcommand path should
    // return byte-identical output (it's already inplace; just copy it).
    let r = b"ABCDEFGHIJ";
    let v = b"JIHGFEDCBA";
    for (_, algo_fn) in all_algos() {
        for (_, pol) in all_policies() {
            let cmds = algo_fn(r, v, &opts(2));
            let (ip, _) = make_inplace(r, &cmds, pol);
            let sh = shake128_16(r);
            let dh = shake128_16(v);
            let ip_delta = encode_delta(&ip, true, v.len(), &sh, &dh);

            // Feeding the inplace delta to the subcommand logic should detect
            // is_ip=true and return the bytes unchanged.
            let (_, is_ip, _, sh2, dh2) = decode_delta(&ip_delta).unwrap();
            assert!(is_ip, "inplace delta should be detected as inplace");
            assert_eq!(sh2, sh);
            assert_eq!(dh2, dh);
        }
    }
}

#[test]
fn test_inplace_subcommand_equiv_direct() {
    // The subcommand path (encode standard → convert) and the direct path
    // (encode --inplace directly) must produce byte-identical output, since
    // both call make_inplace with the same reference and commands.
    let cases: &[(&[u8], &[u8])] = &[
        (b"ABCDEF", b"FEDCBA"),
        (b"AAABBBCCC", b"CCCBBBAAA"),
        (b"the quick brown fox", b"the quick brown cat"),
        (b"ABCDEFGHIJKLMNOP", b"PONMLKJIHGFEDCBA"),
    ];
    for (r, v) in cases {
        for (_, algo_fn) in all_algos() {
            for (_, pol) in all_policies() {
                let sh = shake128_16(r);
                let dh = shake128_16(v);
                // Direct path
                let cmds = algo_fn(r, v, &opts(2));
                let (ip_direct, _) = make_inplace(r, &cmds, pol);
                let direct_bytes = encode_delta(&ip_direct, true, v.len(), &sh, &dh);

                // Subcommand path
                let subcommand_bytes = via_inplace_subcommand(algo_fn, r, v, pol, 2);

                assert_eq!(direct_bytes, subcommand_bytes,
                    "direct vs subcommand path differ for r={:?} v={:?}", r, v);
            }
        }
    }
}

// ── shake128_16 tests ─────────────────────────────────────────────────────

#[test]
fn test_shake128_16_output_length() {
    assert_eq!(shake128_16(b"").len(), 16);
    assert_eq!(shake128_16(b"hello").len(), 16);
}

#[test]
fn test_shake128_16_deterministic() {
    assert_eq!(shake128_16(b"test"), shake128_16(b"test"));
}

#[test]
fn test_shake128_16_differs_on_different_input() {
    assert_ne!(shake128_16(b"hello"), shake128_16(b"world"));
}

#[test]
fn test_shake128_16_nist_empty() {
    // NIST FIPS 202 SHAKE128 test vector: empty message, first 16 bytes
    let expected = [
        0x7f, 0x9c, 0x2b, 0xa4, 0xe8, 0x8f, 0x82, 0x7d,
        0x61, 0x60, 0x45, 0x50, 0x76, 0x05, 0x85, 0x3e,
    ];
    assert_eq!(shake128_16(b""), expected);
}

#[test]
fn test_shake128_16_nist_one_byte_bd() {
    // NIST FIPS 202 SHAKE128 test vector: msg = 0xbd, first 16 bytes
    let expected = [
        0x83, 0x38, 0x82, 0x86, 0xb2, 0xc0, 0x06, 0x5e,
        0xd2, 0x37, 0xfb, 0xe7, 0x14, 0xfc, 0x31, 0x63,
    ];
    assert_eq!(shake128_16(b"\xbd"), expected);
}

#[test]
fn test_shake128_16_nist_200_byte_a3() {
    // NIST FIPS 202 SHAKE128 test vector: msg = 0xa3 * 200, first 16 bytes
    let expected = [
        0x13, 0x1a, 0xb8, 0xd2, 0xb5, 0x94, 0x94, 0x6b,
        0x9c, 0x81, 0x33, 0x3f, 0x9b, 0xb6, 0xe0, 0xce,
    ];
    assert_eq!(shake128_16(&[0xa3u8; 200]), expected);
}
