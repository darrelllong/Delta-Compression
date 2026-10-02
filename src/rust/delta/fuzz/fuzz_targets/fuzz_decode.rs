// Feeds arbitrary bytes to decode_delta, which must return an error for
// malformed input and never panic.
//
//   cargo fuzz run fuzz_decode -- -max_total_time=300
//   cargo fuzz run fuzz_decode corpus/fuzz_decode -- -max_total_time=300 -jobs=4

#![no_main]
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let _ = delta::decode_delta(data);
});
