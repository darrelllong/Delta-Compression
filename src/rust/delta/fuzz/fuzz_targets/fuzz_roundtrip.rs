// Encodes a version against a reference with the greedy algorithm, decodes
// the delta, applies it, and requires the version back.
//
// The input is a split byte, the reference, and the version: the reference
// ends at offset 1 + split_byte * (len - 1) / 256.  Inputs over 4 KiB are
// skipped because greedy takes time proportional to |R| |V|.
//
//   cargo fuzz run fuzz_roundtrip -- -max_total_time=300

#![no_main]
use delta::{
    apply_placed_to, crc64_xz, decode_delta, diff_greedy, encode_delta_large, place_commands,
    DiffOptions,
};
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    if data.len() < 2 || data.len() > 4096 {
        return;
    }

    let split = (1 + (data[0] as usize * (data.len() - 1)) / 256).min(data.len());
    let reference = &data[1..split];
    let version = &data[split..];

    let placed = place_commands(diff_greedy(reference, version, &DiffOptions::default()));
    let src_crc = crc64_xz(reference);
    let dst_crc = crc64_xz(version);
    let encoded = encode_delta_large(&placed, false, version.len(), &src_crc, &dst_crc, false);

    let (decoded, _, version_size, src_crc2, dst_crc2) = decode_delta(&encoded)
        .unwrap_or_else(|e| panic!("decode failed on valid encoder output: {e}"));
    assert_eq!(src_crc2, src_crc, "src_crc did not round-trip");
    assert_eq!(dst_crc2, dst_crc, "dst_crc did not round-trip");
    assert_eq!(version_size, version.len(), "version_size did not round-trip");

    let mut out = vec![0u8; version_size];
    apply_placed_to(reference, &decoded, &mut out);
    assert_eq!(out, version, "reconstructed output differs from version");
});
