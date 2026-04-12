// fuzz_decode — feed arbitrary bytes to decode_delta().
//
// Invariant: decode_delta() must never panic regardless of input.
// Returning Err is the expected rejection path for malformed data.
//
// Run:
//   cargo fuzz run fuzz_decode -- -max_total_time=300
//   cargo fuzz run fuzz_decode corpus/fuzz_decode -- -max_total_time=300 -jobs=4

#![no_main]
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let _ = delta::decode_delta(data);
});
