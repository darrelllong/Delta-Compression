use delta::{
    apply_delta, apply_delta_inplace, apply_placed_to, crc64_xz, decode_delta, diff_correcting,
    diff_greedy, diff_onepass, encode_delta, encode_delta_large, is_inplace_delta, is_prime,
    make_inplace, next_prime, output_size, place_commands, unplace_commands,
    validate_placed_commands, Command, CyclePolicy, DeltaError, DiffOptions, PlacedCommand,
    DELTA_HEADER_SIZE_LARGE, DELTA_MAGIC_LARGE, TABLE_SIZE,
};
use std::fs;

type DiffFn = fn(&[u8], &[u8], &DiffOptions) -> Vec<Command>;

fn opts(p: usize) -> DiffOptions {
    DiffOptions {
        p,
        ..DiffOptions::default()
    }
}

fn roundtrip(algo_fn: DiffFn, r: &[u8], v: &[u8], p: usize) -> Vec<u8> {
    let cmds = algo_fn(r, v, &opts(p));
    let sz = output_size(&cmds);
    let placed = place_commands(cmds);
    let delta = encode_delta(&placed, false, sz, &crc64_xz(r), &crc64_xz(v)).unwrap();
    let (placed2, _, _, sc, dc) = decode_delta(&delta).unwrap();
    assert_eq!(sc, crc64_xz(r));
    assert_eq!(dc, crc64_xz(v));
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
    let delta = encode_delta(&ip, true, v.len(), &crc64_xz(r), &crc64_xz(v)).unwrap();
    let (ip2, _, vs, sc, dc) = decode_delta(&delta).unwrap();
    assert_eq!(sc, crc64_xz(r));
    assert_eq!(dc, crc64_xz(v));
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

// Standard differencing.

// The example of Section 2.1.1 of Ajtai et al. 2002.
#[test]
fn test_paper_example() {
    let r = b"ABCDEFGHIJKLMNOP";
    let v = b"QWIJKLMNOBCDEFGHZDEFGHIJKL";
    for (name, algo) in all_algos() {
        assert_eq!(
            apply_delta(r, &algo(r, v, &opts(2))),
            v,
            "failed for {}",
            name
        );
    }
}

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
            cmds.iter().all(|c| matches!(c, Command::Copy { .. })),
            "{}: identical strings should produce no adds",
            name
        );
    }
}

#[test]
fn test_completely_different() {
    let r: Vec<u8> = (0..=255u8).cycle().take(512).collect();
    let v: Vec<u8> = (0..=255u8).rev().cycle().take(512).collect();
    for (name, algo) in all_algos() {
        assert_eq!(
            apply_delta(&r, &algo(&r, &v, &opts(2))),
            v,
            "failed for {}",
            name
        );
    }
}

#[test]
fn test_empty_version() {
    for (name, algo) in all_algos() {
        let cmds = algo(b"hello", b"", &opts(2));
        assert!(cmds.is_empty(), "{}: should be empty", name);
        assert_eq!(apply_delta(b"hello", &cmds), b"", "failed for {}", name);
    }
}

#[test]
fn test_empty_reference() {
    let v = b"hello world";
    for (name, algo) in all_algos() {
        assert_eq!(
            apply_delta(b"", &algo(b"", v, &opts(2))),
            v,
            "failed for {}",
            name
        );
    }
}

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

// The binary format.

#[test]
fn test_binary_encoding_roundtrip() {
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
    let sh = [0u8; 8];
    let dh = [0xffu8; 8];
    let encoded = encode_delta(&placed, false, 491, &sh, &dh).unwrap();
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
    let sh = [1u8; 8];
    let dh = [2u8; 8];
    let standard = encode_delta(&placed, false, 15, &sh, &dh).unwrap();
    let inplace_enc = encode_delta(&placed, true, 15, &sh, &dh).unwrap();

    assert!(!is_inplace_delta(&standard));
    assert!(is_inplace_delta(&inplace_enc));

    let (d1, ip1, vs1, _, _) = decode_delta(&standard).unwrap();
    let (d2, ip2, vs2, _, _) = decode_delta(&inplace_enc).unwrap();
    assert!(!ip1);
    assert!(ip2);
    assert_eq!(vs1, vs2);
    assert_eq!(d1, d2);
}

#[test]
fn test_binary_encoding_magic_small() {
    let encoded = encode_delta(&[], false, 0, &[0u8; 8], &[0u8; 8]).unwrap();
    assert_eq!(&encoded[..4], b"DLT\x03");
}

#[test]
fn test_binary_encoding_wrong_magic_rejected() {
    let mut bad = encode_delta(&[], false, 0, &[0u8; 8], &[0u8; 8]).unwrap();
    bad[3] = 0x02; // DLT\x02 is not a format the decoder reads
    assert!(matches!(
        decode_delta(&bad),
        Err(DeltaError::InvalidFormat(_))
    ));
}

#[test]
fn test_decode_rejects_missing_end() {
    let encoded = encode_delta(&[], false, 0, &[0u8; 8], &[0u8; 8]).unwrap();
    assert!(matches!(
        decode_delta(&encoded[..encoded.len() - 1]),
        Err(DeltaError::InvalidFormat(msg)) if msg == "missing END command"
    ));
}

#[test]
fn test_decode_rejects_trailing_data() {
    let mut encoded = encode_delta(&[], false, 0, &[0u8; 8], &[0u8; 8]).unwrap();
    encoded.push(0x7f);
    assert!(matches!(
        decode_delta(&encoded),
        Err(DeltaError::InvalidFormat(msg)) if msg == "trailing data after END"
    ));
}

#[test]
fn test_decode_rejects_copy_past_version_size() {
    let encoded = encode_delta(
        &[PlacedCommand::Copy {
            src: 0,
            dst: 1,
            length: 2,
        }],
        false,
        2,
        &[0u8; 8],
        &[0u8; 8],
    )
    .unwrap();
    assert!(matches!(
        decode_delta(&encoded),
        Err(DeltaError::InvalidFormat(msg)) if msg == "copy command exceeds version size"
    ));
}

#[test]
fn test_validate_placed_commands_rejects_source_overflow() {
    let err = validate_placed_commands(
        &[PlacedCommand::Copy {
            src: 1,
            dst: 0,
            length: 2,
        }],
        2,
        2,
        false,
    )
    .unwrap_err();
    assert!(matches!(
        err,
        DeltaError::InvalidFormat(msg) if msg == "copy source out of range"
    ));
}

#[test]
fn test_real_data_roundtrip() {
    let r = fs::read("../../../README.md").expect("read README");
    let v = fs::read("../../../HOWTO.md").expect("read HOWTO");
    for (name, algo) in all_algos() {
        let got = roundtrip(algo, &r, &v, 8);
        assert_eq!(got, v, "failed for {}", name);
    }
}

#[test]
fn test_large_copy_roundtrip() {
    let placed = vec![PlacedCommand::Copy {
        src: 100000,
        dst: 0,
        length: 50000,
    }];
    let sh = [3u8; 8];
    let dh = [4u8; 8];
    let encoded = encode_delta(&placed, false, 50000, &sh, &dh).unwrap();
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

#[test]
fn test_large_add_roundtrip() {
    let big_data: Vec<u8> = (0..=255u8).cycle().take(256 * 4).collect();
    let placed = vec![PlacedCommand::Add {
        dst: 0,
        data: big_data.clone(),
    }];
    let sh = [5u8; 8];
    let dh = [6u8; 8];
    let encoded = encode_delta(&placed, false, big_data.len(), &sh, &dh).unwrap();
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
        assert_eq!(
            apply_delta(&r, &algo(&r, &v, &opts(4))),
            v,
            "failed for {}",
            name
        );
    }
}

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
        assert_eq!(
            apply_delta(&r, &algo(&r, &v, &opts(4))),
            v,
            "failed for {}",
            name
        );
    }
}

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

// In-place basics.

#[test]
fn test_inplace_paper_example() {
    let r = b"ABCDEFGHIJKLMNOP";
    let v = b"QWIJKLMNOBCDEFGHZDEFGHIJKL";
    for (_, algo) in all_algos() {
        for (_, pol) in all_policies() {
            assert_eq!(inplace_roundtrip(algo, r, v, pol, 2), v,);
        }
    }
}

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

#[test]
fn test_inplace_version_larger() {
    let r: Vec<u8> = b"ABCDEFGH".iter().cycle().take(8 * 50).copied().collect();
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

#[test]
fn test_inplace_version_smaller() {
    let r: Vec<u8> = b"ABCDEFGHIJKLMNOP"
        .iter()
        .cycle()
        .take(16 * 100)
        .copied()
        .collect();
    let v: Vec<u8> = b"EFGHIJKL".iter().cycle().take(8 * 50).copied().collect();
    for (_, algo) in all_algos() {
        for (_, pol) in all_policies() {
            assert_eq!(inplace_roundtrip(algo, &r, &v, pol, 4), v);
        }
    }
}

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

#[test]
fn test_inplace_empty_version() {
    for (_, algo) in all_algos() {
        let cmds = algo(b"hello", b"", &opts(2));
        let (ip, _) = make_inplace(b"hello", &cmds, CyclePolicy::Localmin);
        assert_eq!(apply_delta_inplace(b"hello", &ip, 0), b"");
    }
}

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

#[test]
fn test_standard_not_detected_as_inplace() {
    let r: Vec<u8> = b"ABCDEFGH".iter().cycle().take(8 * 10).copied().collect();
    let v: Vec<u8> = b"EFGHABCD".iter().cycle().take(8 * 10).copied().collect();
    let cmds = diff_greedy(&r, &v, &opts(2));
    let placed = place_commands(cmds);
    let delta = encode_delta(&placed, false, v.len(), &crc64_xz(&r), &crc64_xz(&v)).unwrap();
    assert!(!is_inplace_delta(&delta));
}

#[test]
fn test_inplace_detected() {
    let r: Vec<u8> = b"ABCDEFGH".iter().cycle().take(8 * 10).copied().collect();
    let v: Vec<u8> = b"EFGHABCD".iter().cycle().take(8 * 10).copied().collect();
    let cmds = diff_greedy(&r, &v, &opts(2));
    let (ip, _) = make_inplace(&r, &cmds, CyclePolicy::Localmin);
    let delta = encode_delta(&ip, true, v.len(), &crc64_xz(&r), &crc64_xz(&v)).unwrap();
    assert!(is_inplace_delta(&delta));
}

// In-place: variable-length transpositions.

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

// Random permutation of all 8 blocks.
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

// All 8 blocks in reverse order.
#[test]
fn test_inplace_varlen_reverse() {
    let blocks = make_blocks();
    let r = blocks_ref(&blocks);
    let v: Vec<u8> = blocks
        .iter()
        .rev()
        .flat_map(|b| b.iter().copied())
        .collect();

    for (_, algo) in all_algos() {
        for (_, pol) in all_policies() {
            assert_eq!(inplace_roundtrip(algo, &r, &v, pol, 4), v);
        }
    }
}

// Permuted blocks interleaved with random junk.
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

// Drop some blocks, duplicate others.
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

// Version is 2x reference.
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
    let mut v: Vec<u8> = p1.iter().flat_map(|&i| blocks[i].iter().copied()).collect();
    let v2: Vec<u8> = p2.iter().flat_map(|&i| blocks[i].iter().copied()).collect();
    v.extend_from_slice(&v2);

    for (_, algo) in all_algos() {
        for (_, pol) in all_policies() {
            assert_eq!(inplace_roundtrip(algo, &r, &v, pol, 4), v);
        }
    }
}

// Version is much smaller, just two blocks.
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

// Split each block in half, shuffle all 16 halves.
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

// 20 random trials.
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

#[test]
fn test_localmin_picks_smallest() {
    let blocks = make_blocks();
    let r = blocks_ref(&blocks);
    let v: Vec<u8> = blocks
        .iter()
        .rev()
        .flat_map(|b| b.iter().copied())
        .collect();

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

// Checkpointing: correcting with various table sizes.

#[test]
fn test_correcting_checkpointing_tiny_table() {
    // q is a floor: R has 305 seeds, so the table has 41 slots, the first
    // prime at or above 2 * 305 / 16, and one seed in 15 is a checkpoint.
    let r = b"ABCDEFGHIJKLMNOP".repeat(20); // 320 bytes
    let mut v = r[..160].to_vec();
    v.extend_from_slice(b"XXXXYYYY");
    v.extend_from_slice(&r[160..]);
    let cmds = diff_correcting(
        &r,
        &v,
        &DiffOptions {
            p: 16,
            q: 7,
            ..DiffOptions::default()
        },
    );
    let recovered = apply_delta(&r, &cmds);
    assert_eq!(recovered, v);
}

#[test]
fn test_correcting_checkpointing_various_sizes() {
    // R has 1985 seeds, so every q below 2 * 1985 / 16 = 248 gives the same
    // table of 251 slots; the last two give larger ones.
    let r: Vec<u8> = (0..=255u8).cycle().take(2000).collect();
    let mut v = r[..500].to_vec();
    v.extend_from_slice(&[0xFFu8; 50]);
    v.extend_from_slice(&r[500..]);
    for q in [7, 31, 101, 1009, TABLE_SIZE] {
        let cmds = diff_correcting(
            &r,
            &v,
            &DiffOptions {
                p: 16,
                q,
                ..DiffOptions::default()
            },
        );
        let recovered = apply_delta(&r, &cmds);
        assert_eq!(recovered, v, "failed with q={}", q);
    }
}

#[test]
fn test_next_prime_is_prime() {
    assert!(is_prime(TABLE_SIZE), "TABLE_SIZE should be prime");
    assert!(is_prime(next_prime(1048574)));
    assert_eq!(next_prime(1048573), 1048573);
}

// With no lookback buffer every command is final at once, and a match that
// extends backward can reclaim nothing.
#[test]
fn test_correcting_without_lookback() {
    let r: Vec<u8> = (0..2000u32).map(|i| (i * 7 + i / 13) as u8).collect();
    let mut v = r[500..1500].to_vec();
    v.extend_from_slice(&r[..700]);
    v[300] ^= 0xFF;
    for buf_cap in [0, 1, 2] {
        let opts = DiffOptions {
            p: 4,
            buf_cap,
            ..DiffOptions::default()
        };
        assert_eq!(apply_delta(&r, &diff_correcting(&r, &v, &opts)), v);
    }
}

// The `delta inplace` subcommand converts a standard delta without the
// version: decode, unplace_commands, make_inplace, encode.  These tests
// check that the result is the same as that of `delta encode --inplace`.

// Encodes a standard delta and converts it as the subcommand does.
fn via_inplace_subcommand(
    algo_fn: DiffFn,
    r: &[u8],
    v: &[u8],
    policy: CyclePolicy,
    p: usize,
) -> Vec<u8> {
    let cmds = algo_fn(r, v, &opts(p));
    let placed = place_commands(cmds);
    let sc = crc64_xz(r);
    let dc = crc64_xz(v);
    let standard = encode_delta(&placed, false, v.len(), &sc, &dc).unwrap();
    let (placed2, is_ip, version_size, src_crc, dst_crc) = decode_delta(&standard).unwrap();
    assert!(!is_ip, "standard delta should not be flagged as inplace");
    let cmds2 = unplace_commands(placed2);
    let (ip, _) = make_inplace(r, &cmds2, policy);
    encode_delta(&ip, true, version_size, &src_crc, &dst_crc).unwrap()
}

#[test]
fn test_inplace_subcommand_roundtrip() {
    // Encode standard, convert, decode and apply: the result is V.
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
                let (cmds, _, _, sc, dc) = decode_delta(&ip_delta).unwrap();
                assert_eq!(sc, crc64_xz(r));
                assert_eq!(dc, crc64_xz(v));
                let recovered = apply_delta_inplace(r, &cmds, v.len());
                assert_eq!(
                    recovered, *v,
                    "subcommand roundtrip failed for r={:?} v={:?}",
                    r, v
                );
            }
        }
    }
}

#[test]
fn test_inplace_subcommand_idempotent() {
    // The subcommand copies a delta that is already in-place.  It knows one
    // by the flag that decode_delta returns, which is all that is checked
    // here.
    let r = b"ABCDEFGHIJ";
    let v = b"JIHGFEDCBA";
    for (_, algo_fn) in all_algos() {
        for (_, pol) in all_policies() {
            let cmds = algo_fn(r, v, &opts(2));
            let (ip, _) = make_inplace(r, &cmds, pol);
            let sc = crc64_xz(r);
            let dc = crc64_xz(v);
            let ip_delta = encode_delta(&ip, true, v.len(), &sc, &dc).unwrap();

            let (_, is_ip, _, sc2, dc2) = decode_delta(&ip_delta).unwrap();
            assert!(is_ip, "inplace delta should be detected as inplace");
            assert_eq!(sc2, sc);
            assert_eq!(dc2, dc);
        }
    }
}

#[test]
fn test_inplace_subcommand_equiv_direct() {
    // Converting a standard delta and encoding in place directly give the
    // same bytes, since both call make_inplace with the same reference and
    // commands.
    let cases: &[(&[u8], &[u8])] = &[
        (b"ABCDEF", b"FEDCBA"),
        (b"AAABBBCCC", b"CCCBBBAAA"),
        (b"the quick brown fox", b"the quick brown cat"),
        (b"ABCDEFGHIJKLMNOP", b"PONMLKJIHGFEDCBA"),
    ];
    for (r, v) in cases {
        for (_, algo_fn) in all_algos() {
            for (_, pol) in all_policies() {
                let sc = crc64_xz(r);
                let dc = crc64_xz(v);
                let cmds = algo_fn(r, v, &opts(2));
                let (ip_direct, _) = make_inplace(r, &cmds, pol);
                let direct_bytes = encode_delta(&ip_direct, true, v.len(), &sc, &dc).unwrap();

                let subcommand_bytes = via_inplace_subcommand(algo_fn, r, v, pol, 2);

                assert_eq!(
                    direct_bytes, subcommand_bytes,
                    "direct vs subcommand path differ for r={:?} v={:?}",
                    r, v
                );
            }
        }
    }
}

// crc64_xz.

#[test]
fn test_crc64_output_length() {
    assert_eq!(crc64_xz(b"").len(), 8);
    assert_eq!(crc64_xz(b"hello").len(), 8);
}

#[test]
fn test_crc64_deterministic() {
    assert_eq!(crc64_xz(b"test"), crc64_xz(b"test"));
}

#[test]
fn test_crc64_differs_on_different_input() {
    assert_ne!(crc64_xz(b"hello"), crc64_xz(b"world"));
}

#[test]
fn test_crc64_empty() {
    // CRC-64/XZ of empty input is all-zeros.
    assert_eq!(crc64_xz(b""), [0u8; 8]);
}

#[test]
fn test_crc64_check_value() {
    // Standard check value: CRC-64/XZ of b"123456789" = 0x995DC9BBDF1939FA.
    let expected: [u8; 8] = [0x99, 0x5D, 0xC9, 0xBB, 0xDF, 0x19, 0x39, 0xFA];
    assert_eq!(crc64_xz(b"123456789"), expected);
}

// Edge cases and boundaries.

#[test]
fn test_single_byte() {
    for (_, algo) in all_algos() {
        assert_eq!(
            apply_delta(b"\x41", &algo(b"\x41", b"\x41", &opts(1))),
            b"\x41"
        );
        assert_eq!(
            apply_delta(b"\x41", &algo(b"\x41", b"\x42", &opts(1))),
            b"\x42"
        );
        assert_eq!(apply_delta(b"\x41", &algo(b"\x41", b"", &opts(1))), b"");
        assert_eq!(apply_delta(b"", &algo(b"", b"\x41", &opts(1))), b"\x41");
    }
}

#[test]
fn test_boundary_byte_mutations() {
    let n = 64usize;
    let r: Vec<u8> = (0..n).map(|i| (i & 0xFF) as u8).collect();
    let mut v_first = r.clone();
    v_first[0] ^= 0xFF;
    let mut v_last = r.clone();
    v_last[n - 1] ^= 0xFF;
    let mut v_app = r.clone();
    v_app.push(0x5A);
    let v_drop: Vec<u8> = r[..n - 1].to_vec();
    for (_, algo) in all_algos() {
        assert_eq!(roundtrip(algo, &r, &v_first, 4), v_first);
        assert_eq!(roundtrip(algo, &r, &v_last, 4), v_last);
        assert_eq!(roundtrip(algo, &r, &v_app, 4), v_app);
        assert_eq!(roundtrip(algo, &r, &v_drop, 4), v_drop);
    }
}

// With |R| < p there is no seed in R, so the delta is a single add.
#[test]
fn test_ref_shorter_than_seed() {
    let p = 8usize;
    let v: Vec<u8> = vec![0x10, 0x11, 0x12, 0x13, 0xAA, 0xBB, 0xCC, 0xDD];
    for r_len in 0..p {
        let r: Vec<u8> = (1..=r_len).map(|i| (i & 0xFF) as u8).collect();
        for (_, algo) in all_algos() {
            assert_eq!(
                apply_delta(&r, &algo(&r, &v, &opts(p))),
                v,
                "r_len={}",
                r_len
            );
        }
    }
}

// Sizes around 0, p, and powers of two, where a loop bound that is off by
// one would show.
#[test]
fn test_size_sweep() {
    let p = 4usize;
    let sizes: &[usize] = &[
        0, 1, 2, 3, 4, 5, 7, 8, 9, 63, 64, 65, 127, 128, 129, 255, 256, 257, 511, 512, 513,
    ];
    let big_ref: Vec<u8> = (0..600u16).map(|i| ((i * 3) & 0xFF) as u8).collect();

    for &v_len in sizes {
        let v_prefix: Vec<u8> = big_ref[..v_len].to_vec();
        let v_new: Vec<u8> = (0..v_len).map(|i| ((i * 7 + 1) & 0xFF) as u8).collect();
        for (_, algo) in all_algos() {
            assert_eq!(
                roundtrip(algo, &big_ref, &v_prefix, p),
                v_prefix,
                "vLen={} prefix",
                v_len
            );
            assert_eq!(
                roundtrip(algo, &big_ref, &v_new, p),
                v_new,
                "vLen={} new",
                v_len
            );
        }
    }
    let fixed_ver: Vec<u8> = big_ref[..64].to_vec();
    for &r_len in sizes {
        let r: Vec<u8> = big_ref[..r_len].to_vec();
        for (_, algo) in all_algos() {
            assert_eq!(
                roundtrip(algo, &r, &fixed_ver, p),
                fixed_ver,
                "rLen={}",
                r_len
            );
        }
    }
}

// version_size around the powers of two at which a byte of the field
// carries or its high bit turns on, which a sign-extending decoder would
// get wrong.
#[test]
fn test_encoding_version_size_boundaries() {
    let zh = [0u8; 8];
    for sz in [
        0,
        1,
        127,
        128,
        255,
        256,
        257,
        32767,
        32768,
        32769,
        65535,
        65536,
        65537,
        8388607,
        8388608,
        8388609,
        16777215,
        16777216,
        16777217usize,
    ] {
        let encoded = encode_delta(&[], false, sz, &zh, &zh).unwrap();
        let (decoded, _, vs, _, _) = decode_delta(&encoded).unwrap();
        assert_eq!(vs, sz, "version_size={}", sz);
        assert!(decoded.is_empty());
    }
}

// The same for the fields of COPY and ADD.
#[test]
fn test_encoding_command_field_boundaries() {
    let zh = [0u8; 8];
    let offsets = [0usize, 1, 127, 128, 255, 256, 257, 65535, 65536, 65537];

    // src
    for src in offsets {
        let placed = vec![PlacedCommand::Copy {
            src,
            dst: 0,
            length: 1,
        }];
        let (dec, _, _, _, _) =
            decode_delta(&encode_delta(&placed, false, 1, &zh, &zh).unwrap()).unwrap();
        match &dec[0] {
            PlacedCommand::Copy {
                src: s,
                dst: d,
                length: l,
            } => {
                assert_eq!(*s, src);
                assert_eq!(*d, 0);
                assert_eq!(*l, 1);
            }
            _ => panic!("expected Copy"),
        }
    }
    // dst
    for dst in offsets {
        let placed = vec![PlacedCommand::Copy {
            src: 0,
            dst,
            length: 1,
        }];
        let (dec, _, _, _, _) =
            decode_delta(&encode_delta(&placed, false, dst + 1, &zh, &zh).unwrap()).unwrap();
        match &dec[0] {
            PlacedCommand::Copy { dst: d, .. } => assert_eq!(*d, dst),
            _ => panic!("expected Copy"),
        }
    }
    // length
    for len in [1usize, 127, 128, 255, 256, 257, 65535, 65536] {
        let placed = vec![PlacedCommand::Copy {
            src: 0,
            dst: 0,
            length: len,
        }];
        let (dec, _, _, _, _) =
            decode_delta(&encode_delta(&placed, false, len, &zh, &zh).unwrap()).unwrap();
        match &dec[0] {
            PlacedCommand::Copy { length: l, .. } => assert_eq!(*l, len),
            _ => panic!("expected Copy"),
        }
    }
    // add dst
    for dst in offsets {
        let placed = vec![PlacedCommand::Add {
            dst,
            data: vec![0xFF],
        }];
        let (dec, _, _, _, _) =
            decode_delta(&encode_delta(&placed, false, dst + 1, &zh, &zh).unwrap()).unwrap();
        match &dec[0] {
            PlacedCommand::Add { dst: d, data } => {
                assert_eq!(*d, dst);
                assert_eq!(data, &[0xFF]);
            }
            _ => panic!("expected Add"),
        }
    }
}

// |V| = |R| + 1: the version ends one byte past the reference.
#[test]
fn test_inplace_version_one_larger_tight() {
    for n in [1, 2, 3, 4, 7, 8, 15, 16, 17, 31, 32, 63, 64usize] {
        let r: Vec<u8> = (0..n).map(|i| (i & 0xFF) as u8).collect();
        let mut v = r.clone();
        v.push(0x5A);
        for (_, algo) in all_algos() {
            for (_, pol) in all_policies() {
                assert_eq!(inplace_roundtrip(algo, &r, &v, pol, 2), v, "n={}", n);
            }
        }
    }
}

// |V| = |R| - 1: the version ends one byte short of the reference.
#[test]
fn test_inplace_version_one_smaller_tight() {
    for n in [2, 3, 4, 5, 8, 9, 15, 16, 17, 31, 32, 65usize] {
        let r: Vec<u8> = (0..n).map(|i| (i & 0xFF) as u8).collect();
        let v: Vec<u8> = r[..n - 1].to_vec();
        for (_, algo) in all_algos() {
            for (_, pol) in all_policies() {
                assert_eq!(inplace_roundtrip(algo, &r, &v, pol, 2), v, "n={}", n);
            }
        }
    }
}

// |V| = |R| with the halves exchanged: copies of both halves, where the
// algorithm finds them, form a cycle.
#[test]
fn test_inplace_version_same_size_tight() {
    for n in [2, 4, 8, 16, 32, 64, 128, 256usize] {
        let r: Vec<u8> = (0..n).map(|i| (i & 0xFF) as u8).collect();
        let half = n / 2;
        let mut v = vec![0u8; n];
        v[..half].copy_from_slice(&r[half..]);
        v[half..].copy_from_slice(&r[..half]);
        for (_, algo) in all_algos() {
            for (_, pol) in all_policies() {
                assert_eq!(inplace_roundtrip(algo, &r, &v, pol, 2), v, "n={}", n);
            }
        }
    }
}

// A version of one byte, which is a copy if the byte is in R and an add
// if it is not.
#[test]
fn test_inplace_version_one_byte_min() {
    let r: Vec<u8> = (0u8..64).collect();
    let v_copy = vec![r[32]]; // in R, so a copy
    let v_add = vec![0xABu8]; // 0xAB = 171 > 63, not in R, so an add
    for (_, algo) in all_algos() {
        for (_, pol) in all_policies() {
            assert_eq!(inplace_roundtrip(algo, &r, &v_copy, pol, 2), v_copy);
            assert_eq!(inplace_roundtrip(algo, &r, &v_add, pol, 2), v_add);
        }
    }
}

// p = 1, 2, |R|, for which R is a single seed, and |R| + 1, for which R
// has none.
#[test]
fn test_seed_length_boundaries() {
    let r = b"ABCDEFGHIJKLMNOP";
    let v = b"QWIJKLMNOBCDEFGHZDEFGHIJKL";
    for p in [1, 2, r.len(), r.len() + 1] {
        for (_, algo) in all_algos() {
            assert_eq!(apply_delta(r, &algo(r, v, &opts(p))), v, "p={}", p);
        }
    }
    // p > |R| with V shorter and longer than p.
    for (_, algo) in all_algos() {
        assert_eq!(apply_delta(r, &algo(r, b"QW", &opts(r.len() + 1))), b"QW");
        assert_eq!(
            apply_delta(
                r,
                &algo(
                    r,
                    b"QWIJKLMNOBCDEFGHZDEFGHIJKLMNOPQRSTUVWXYZ",
                    &opts(r.len() + 1)
                )
            ),
            b"QWIJKLMNOBCDEFGHZDEFGHIJKLMNOPQRSTUVWXYZ"
        );
    }
}

#[test]
fn test_encode_delta_rejects_version_size_overflow() {
    let z = [0u8; 8];
    let result = encode_delta(&[], false, (u32::MAX as usize) + 1, &z, &z);
    assert!(
        matches!(result, Err(DeltaError::InvalidFormat(_))),
        "expected Err, got {:?}",
        result
    );
}

#[test]
fn test_encode_delta_rejects_copy_src_overflow() {
    let z = [0u8; 8];
    let cmd = PlacedCommand::Copy {
        src: (u32::MAX as usize) + 1,
        dst: 0,
        length: 1,
    };
    let result = encode_delta(&[cmd], false, 1, &z, &z);
    assert!(
        matches!(result, Err(DeltaError::InvalidFormat(_))),
        "expected Err, got {:?}",
        result
    );
}

#[test]
fn test_encode_delta_rejects_copy_dst_overflow() {
    let z = [0u8; 8];
    let cmd = PlacedCommand::Copy {
        src: 0,
        dst: (u32::MAX as usize) + 1,
        length: 1,
    };
    let result = encode_delta(&[cmd], false, 1, &z, &z);
    assert!(
        matches!(result, Err(DeltaError::InvalidFormat(_))),
        "expected Err, got {:?}",
        result
    );
}

#[test]
fn test_encode_delta_rejects_copy_length_overflow() {
    let z = [0u8; 8];
    let cmd = PlacedCommand::Copy {
        src: 0,
        dst: 0,
        length: (u32::MAX as usize) + 1,
    };
    let result = encode_delta(&[cmd], false, 1, &z, &z);
    assert!(
        matches!(result, Err(DeltaError::InvalidFormat(_))),
        "expected Err, got {:?}",
        result
    );
}

#[test]
fn test_encode_delta_rejects_add_dst_overflow() {
    let z = [0u8; 8];
    let cmd = PlacedCommand::Add {
        dst: (u32::MAX as usize) + 1,
        data: vec![0u8],
    };
    let result = encode_delta(&[cmd], false, 1, &z, &z);
    assert!(
        matches!(result, Err(DeltaError::InvalidFormat(_))),
        "expected Err, got {:?}",
        result
    );
}

// DLT\x04: the header.

#[test]
fn test_large_header_magic() {
    let z = [0u8; 8];
    let bytes = encode_delta_large(&[], false, 0, &z, &z, false);
    assert_eq!(&bytes[..4], DELTA_MAGIC_LARGE, "DLT\\x04 magic");
    assert_eq!(bytes.len(), DELTA_HEADER_SIZE_LARGE + 1); // header + END byte
}

#[test]
fn test_large_header_version_size_u64() {
    let z = [0u8; 8];
    let vs: usize = (u32::MAX as usize) + 1; // 2^32; doesn't fit in u32
    let bytes = encode_delta_large(&[], false, vs, &z, &z, false);
    let decoded = u64::from_be_bytes([
        bytes[5], bytes[6], bytes[7], bytes[8], bytes[9], bytes[10], bytes[11], bytes[12],
    ]) as usize;
    assert_eq!(decoded, vs);
}

#[test]
fn test_large_header_crcs_preserved() {
    let src_crc = [1u8, 2, 3, 4, 5, 6, 7, 8];
    let dst_crc = [9u8, 10, 11, 12, 13, 14, 15, 16];
    let bytes = encode_delta_large(&[], false, 0, &src_crc, &dst_crc, false);
    assert_eq!(&bytes[13..21], &src_crc);
    assert_eq!(&bytes[21..29], &dst_crc);
}

#[test]
fn test_large_inplace_flag() {
    let z = [0u8; 8];
    let standard = encode_delta_large(&[], false, 0, &z, &z, false);
    let inplace = encode_delta_large(&[], true, 0, &z, &z, false);
    assert_eq!(standard[4], 0x00);
    assert_eq!(inplace[4], 0x01);
}

#[test]
fn test_large_decode_roundtrip_header() {
    let src_crc = [0xABu8; 8];
    let dst_crc = [0xCDu8; 8];
    let bytes = encode_delta_large(&[], false, 42, &src_crc, &dst_crc, false);
    let (cmds, ip, vs, sc, dc) = decode_delta(&bytes).unwrap();
    assert!(cmds.is_empty());
    assert!(!ip);
    assert_eq!(vs, 42);
    assert_eq!(sc, src_crc);
    assert_eq!(dc, dst_crc);
}

// DLT\x04: COPY with fields that fit in u32.

#[test]
fn test_large_copy_small_roundtrip() {
    let z = [0u8; 8];
    let cmd = PlacedCommand::Copy {
        src: 0,
        dst: 0,
        length: 5,
    };
    let bytes = encode_delta_large(std::slice::from_ref(&cmd), false, 5, &z, &z, false);
    let (cmds, _, _, _, _) = decode_delta(&bytes).unwrap();
    assert_eq!(cmds, vec![cmd]);
}

#[test]
fn test_large_copy_u32_max_boundary() {
    let z = [0u8; 8];
    let max = u32::MAX as usize;
    let cmd = PlacedCommand::Copy {
        src: max,
        dst: 0,
        length: 0,
    };
    // u32::MAX still fits a COPY.  The length is 0 so that the destination
    // stays within a version of no bytes.
    let bytes = encode_delta_large(std::slice::from_ref(&cmd), false, 0, &z, &z, false);
    let (cmds, _, _, _, _) = decode_delta(&bytes).unwrap();
    assert_eq!(cmds, vec![cmd]);
}

// DLT\x04: ADD with fields that fit in u32.

#[test]
fn test_large_add_small_roundtrip() {
    let z = [0u8; 8];
    let data = b"hello".to_vec();
    let cmd = PlacedCommand::Add {
        dst: 0,
        data: data.clone(),
    };
    let bytes = encode_delta_large(std::slice::from_ref(&cmd), false, data.len(), &z, &z, false);
    let (cmds, _, _, _, _) = decode_delta(&bytes).unwrap();
    assert_eq!(cmds, vec![cmd]);
}

#[test]
fn test_large_add_payload_intact() {
    let z = [0u8; 8];
    let payload: Vec<u8> = (0u8..=255).collect();
    let len = payload.len();
    let cmd = PlacedCommand::Add {
        dst: 0,
        data: payload.clone(),
    };
    let bytes = encode_delta_large(&[cmd], false, len, &z, &z, false);
    let (cmds, _, _, _, _) = decode_delta(&bytes).unwrap();
    if let PlacedCommand::Add { data, .. } = &cmds[0] {
        assert_eq!(data, &payload);
    } else {
        panic!("expected Add, got {:?}", cmds[0]);
    }
}

// DLT\x04: MOVE.

#[test]
fn test_large_move_roundtrip() {
    let z = [0u8; 8];
    let cmd = PlacedCommand::Move {
        src: 0,
        dst: 5,
        length: 5,
    };
    let bytes = encode_delta_large(std::slice::from_ref(&cmd), false, 10, &z, &z, false);
    let (cmds, _, _, _, _) = decode_delta(&bytes).unwrap();
    assert_eq!(cmds, vec![cmd]);
}

#[test]
fn test_large_move_rejected_on_small() {
    let z = [0u8; 8];
    let cmd = PlacedCommand::Move {
        src: 0,
        dst: 5,
        length: 5,
    };
    let result = encode_delta(&[cmd], false, 10, &z, &z);
    assert!(matches!(result, Err(DeltaError::InvalidFormat(_))));
}

#[test]
fn test_small_rejects_large_command_bytes_with_diagnostic() {
    // A BIGCOPY tag in a DLT\x03 stream: the decoder must say "requires
    // DLT\x04", not "unknown command type".
    let mut bad = encode_delta(&[], false, 0, &[0u8; 8], &[0u8; 8]).unwrap();
    bad.pop(); // remove END
    bad.push(3); // DELTA_CMD_BIGCOPY
    bad.push(0); // END
    match decode_delta(&bad) {
        Err(DeltaError::InvalidFormat(msg)) => {
            assert!(
                msg.contains("requires DLT"),
                "expected DLT\\x04 hint, got: {}",
                msg
            );
        }
        other => panic!("expected InvalidFormat, got {:?}", other),
    }
}

#[test]
fn test_large_move_apply_standard() {
    // The MOVE reads what the ADD wrote.
    let z = [0u8; 8];
    let cmds = vec![
        PlacedCommand::Add {
            dst: 0,
            data: b"hello".to_vec(),
        },
        PlacedCommand::Move {
            src: 0,
            dst: 5,
            length: 5,
        },
    ];
    let bytes = encode_delta_large(&cmds, false, 10, &z, &z, false);
    let (decoded, _, vs, _, _) = decode_delta(&bytes).unwrap();
    let mut out = vec![0u8; vs];
    apply_placed_to(&[], &decoded, &mut out);
    assert_eq!(&out, b"hellohello");
}

#[test]
fn test_large_move_apply_inplace() {
    // The MOVE reads what the ADD wrote.
    let cmds = vec![
        PlacedCommand::Add {
            dst: 0,
            data: b"hello".to_vec(),
        },
        PlacedCommand::Move {
            src: 0,
            dst: 5,
            length: 5,
        },
    ];
    let result = delta::apply_delta_inplace(&[], &cmds, 10);
    assert_eq!(&result, b"hellohello");
}

#[test]
fn test_large_validate_move_src_past_dst_rejected() {
    let cmd = PlacedCommand::Move {
        src: 5,
        dst: 3,
        length: 3,
    }; // src+len=8 > dst=3
    let result = validate_placed_commands(&[cmd], 100, 100, false);
    assert!(matches!(result, Err(DeltaError::InvalidFormat(_))));
}

#[test]
fn test_large_validate_move_out_of_range() {
    let cmd = PlacedCommand::Move {
        src: 0,
        dst: 98,
        length: 5,
    }; // dst+len=103 > version_size=100
    let result = validate_placed_commands(&[cmd], 100, 100, false);
    assert!(matches!(result, Err(DeltaError::InvalidFormat(_))));
}

// DLT\x04: BIG commands, with offsets of 2^32.

#[test]
fn test_large_bigcopy_roundtrip() {
    let z = [0u8; 8];
    // A src of 2^32 forces BIGCOPY.  With length 0 the copy stays within
    // any version.
    let big = (u32::MAX as usize) + 1;
    let cmd = PlacedCommand::Copy {
        src: big,
        dst: 0,
        length: 0,
    };
    let bytes = encode_delta_large(std::slice::from_ref(&cmd), false, 0, &z, &z, false);
    assert_eq!(bytes[DELTA_HEADER_SIZE_LARGE], 3); // DELTA_CMD_BIGCOPY = 3
    let (cmds, _, _, _, _) = decode_delta(&bytes).unwrap();
    assert_eq!(cmds, vec![cmd]);
}

#[test]
fn test_large_bigadd_encoder_path() {
    // A dst of 2^32 forces BIGADD without a 4 GiB literal.
    let z = [0u8; 8];
    let big_dst = (u32::MAX as usize) + 1;
    let data = b"hello".to_vec();
    let cmd = PlacedCommand::Add {
        dst: big_dst,
        data: data.clone(),
    };
    let version_size = big_dst + data.len();
    let bytes = encode_delta_large(
        std::slice::from_ref(&cmd),
        false,
        version_size,
        &z,
        &z,
        false,
    );
    assert_eq!(
        bytes[DELTA_HEADER_SIZE_LARGE], 4,
        "expected DELTA_CMD_BIGADD=4"
    );
    let (cmds, _, _, _, _) = decode_delta(&bytes).unwrap();
    assert_eq!(cmds, vec![cmd]);
}

#[test]
fn test_large_bigmove_roundtrip() {
    let z = [0u8; 8];
    // A dst of 2^32 forces BIGMOVE.  With length 0 the move stays within
    // a version of that size.
    let big = (u32::MAX as usize) + 1;
    let cmd = PlacedCommand::Move {
        src: 0,
        dst: big,
        length: 0,
    };
    let bytes = encode_delta_large(std::slice::from_ref(&cmd), false, big, &z, &z, false);
    assert_eq!(bytes[DELTA_HEADER_SIZE_LARGE], 6); // DELTA_CMD_BIGMOVE = 6
    let (cmds, _, _, _, _) = decode_delta(&bytes).unwrap();
    assert_eq!(cmds, vec![cmd]);
}

// DLT\x04: the in-place flag.

#[test]
fn test_large_is_inplace_detected() {
    let z = [0u8; 8];
    let standard = encode_delta_large(&[], false, 0, &z, &z, false);
    let inplace = encode_delta_large(&[], true, 0, &z, &z, false);
    assert!(!is_inplace_delta(&standard));
    assert!(is_inplace_delta(&inplace));
}

// DLT\x04: each algorithm, encoded, decoded and applied.

#[test]
fn test_large_algo_roundtrip_greedy() {
    let r = b"The quick brown fox jumps over the lazy dog.";
    let v = b"The slow brown fox jumps over the eager dog.";
    let z = crc64_xz(r);
    let cmds = diff_greedy(r, v, &DiffOptions::default());
    let placed = place_commands(cmds);
    let bytes = encode_delta_large(&placed, false, v.len(), &z, &crc64_xz(v), false);
    let (decoded, _, vs, _, _) = decode_delta(&bytes).unwrap();
    let mut out = vec![0u8; vs];
    apply_placed_to(r, &decoded, &mut out);
    assert_eq!(&out, v);
}

#[test]
fn test_large_algo_roundtrip_onepass() {
    let r = b"ABCDEFGHIJKLMNOP";
    let v = b"QWIJKLMNOBCDEFGHZDEFGHIJKL";
    let z_r = crc64_xz(r);
    let z_v = crc64_xz(v);
    let placed = place_commands(diff_onepass(
        r,
        v,
        &DiffOptions {
            p: 2,
            ..DiffOptions::default()
        },
    ));
    let bytes = encode_delta_large(&placed, false, v.len(), &z_r, &z_v, false);
    let (decoded, _, vs, sc, dc) = decode_delta(&bytes).unwrap();
    assert_eq!(sc, z_r);
    assert_eq!(dc, z_v);
    let mut out = vec![0u8; vs];
    apply_placed_to(r, &decoded, &mut out);
    assert_eq!(&out, v);
}

#[test]
fn test_large_algo_roundtrip_correcting() {
    let r: Vec<u8> = (0u8..=127).cycle().take(512).collect();
    let mut v = r.clone();
    v[100..150].copy_from_slice(&r[200..250]); // a block of R repeated out of place
    let z_r = crc64_xz(&r);
    let z_v = crc64_xz(&v);
    let placed = place_commands(diff_correcting(&r, &v, &DiffOptions::default()));
    let bytes = encode_delta_large(&placed, false, v.len(), &z_r, &z_v, false);
    let (decoded, _, vs, _, _) = decode_delta(&bytes).unwrap();
    let mut out = vec![0u8; vs];
    apply_placed_to(&r, &decoded, &mut out);
    assert_eq!(out, v);
}
