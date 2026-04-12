// fuzz_roundtrip — encode→decode→apply cycle must always reconstruct exactly.
//
// Splits the fuzz input into (reference, version), encodes with greedy,
// decodes, applies to reference, and asserts the output equals version.
//
// Input layout: [split_byte | reference... | version...]
//   split = 1 + (split_byte * (size-1)) / 256
//
// Inputs are capped at 4 KiB; greedy is O(|ref|×|ver|) so larger inputs
// would dominate fuzzer cycle time without proportional bug coverage.
//
// Run:
//   cargo fuzz run fuzz_roundtrip -- -max_total_time=300

#![no_main]
use libfuzzer_sys::fuzz_target;
use delta::{
    apply_placed_to, crc64_xz, decode_delta, diff_greedy, encode_delta_large, place_commands,
    DiffOptions,
};

fuzz_target!(|data: &[u8]| {
    if data.len() < 2 || data.len() > 4096 {
        return;
    }

    let split = (1 + (data[0] as usize * (data.len() - 1)) / 256).min(data.len());
    let ref_data = &data[1..split];
    let ver_data = &data[split..];

    // Encode
    let cmds = diff_greedy(ref_data, ver_data, &DiffOptions::default());
    let placed = place_commands(cmds);
    let src_crc = crc64_xz(ref_data);
    let dst_crc = crc64_xz(ver_data);
    let encoded = encode_delta_large(&placed, false, ver_data.len(), &src_crc, &dst_crc, false);

    // Decode — must not fail on our own output
    let (placed2, _is_ip, vsize, sc2, dc2) = decode_delta(&encoded)
        .unwrap_or_else(|e| panic!("decode failed on valid encoder output: {e}"));

    assert_eq!(sc2, src_crc, "src_crc did not round-trip");
    assert_eq!(dc2, dst_crc, "dst_crc did not round-trip");
    assert_eq!(vsize, ver_data.len(), "version_size did not round-trip");

    // Apply
    let mut out = vec![0u8; vsize];
    apply_placed_to(ref_data, &placed2, &mut out);
    assert_eq!(out, ver_data, "reconstructed output differs from version");
});
