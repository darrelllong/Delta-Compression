//! The `delta` command: encode, decode, inspect, and convert delta files.

use std::fs::{self, File, OpenOptions};
use std::process;
use std::time::Instant;

use clap::{Args, Parser, Subcommand, ValueEnum};
use memmap2::MmapMut;

use delta::{
    apply_placed_inplace_to, apply_placed_to, crc64_xz, decode_delta, encode_delta_large,
    make_inplace, place_commands, placed_summary, unplace_commands, validate_placed_commands,
    Algorithm, CyclePolicy, DiffOptions, PlacedCommand, DELTA_CRC_SIZE,
};

#[derive(Parser)]
#[command(about = "Differential compression (Ajtai et al. 2002)")]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Compute delta encoding
    Encode(EncodeArgs),
    /// Reconstruct version from delta
    Decode(DecodeArgs),
    /// Show delta file statistics
    Info(InfoArgs),
    /// Convert standard delta to in-place delta
    Inplace(InplaceArgs),
}

#[derive(Args)]
struct EncodeArgs {
    /// Algorithm to use
    #[arg(value_enum)]
    algorithm: AlgorithmArg,

    /// Reference file
    reference: String,

    /// Version file
    version: String,

    /// Output delta file
    delta_file: String,

    /// Seed length (must be >= 1; default 16 balances collision rate and match quality)
    #[arg(long, default_value_t = delta::SEED_LEN, value_parser = parse_seed_len)]
    seed_len: usize,

    /// Hash table floor size
    #[arg(long, default_value_t = delta::TABLE_SIZE)]
    table_size: usize,

    /// Maximum hash table size; accepts k/M/B suffix (e.g. 512M, 2B)
    #[arg(long, default_value_t = delta::MAX_TABLE_SIZE, value_parser = parse_size_suffix)]
    max_table: usize,

    /// Produce in-place reconstructible delta
    #[arg(long)]
    inplace: bool,

    /// Force 64-bit (BIGCOPY/BIGADD/BIGMOVE) commands even for small files
    #[arg(long)]
    large: bool,

    /// Cycle-breaking policy for --inplace
    #[arg(long, value_enum, default_value_t = PolicyArg::Localmin)]
    policy: PolicyArg,

    /// Print diagnostic messages to stderr
    #[arg(long)]
    verbose: bool,

    /// Use splay tree instead of hash table
    #[arg(long)]
    splay: bool,
}

#[derive(Args)]
struct DecodeArgs {
    /// Reference file
    reference: String,

    /// Delta file
    delta_file: String,

    /// Output (reconstructed version) file
    output: String,

    /// Skip hash verification (for partial recovery)
    #[arg(long)]
    ignore_hash: bool,
}

#[derive(Args)]
struct InfoArgs {
    /// Delta file
    delta_file: String,
}

#[derive(Args)]
struct InplaceArgs {
    /// Reference file
    reference: String,

    /// Input (standard) delta file
    delta_in: String,

    /// Output (in-place) delta file
    delta_out: String,

    /// Cycle-breaking policy
    #[arg(long, value_enum, default_value_t = PolicyArg::Localmin)]
    policy: PolicyArg,

    /// Force 64-bit (BIGCOPY/BIGADD/BIGMOVE) commands even for small files
    #[arg(long)]
    large: bool,

    /// Print diagnostics (cycles broken, etc.)
    #[arg(long)]
    verbose: bool,
}

#[derive(Clone, Copy, ValueEnum)]
enum AlgorithmArg {
    Greedy,
    Onepass,
    Correcting,
}

impl AlgorithmArg {
    fn name(self) -> &'static str {
        match self {
            AlgorithmArg::Greedy => "greedy",
            AlgorithmArg::Onepass => "onepass",
            AlgorithmArg::Correcting => "correcting",
        }
    }
}

impl From<AlgorithmArg> for Algorithm {
    fn from(a: AlgorithmArg) -> Self {
        match a {
            AlgorithmArg::Greedy => Algorithm::Greedy,
            AlgorithmArg::Onepass => Algorithm::Onepass,
            AlgorithmArg::Correcting => Algorithm::Correcting,
        }
    }
}

#[derive(Clone, Copy, ValueEnum)]
enum PolicyArg {
    Localmin,
    Constant,
}

impl PolicyArg {
    fn name(self) -> &'static str {
        match self {
            PolicyArg::Localmin => "localmin",
            PolicyArg::Constant => "constant",
        }
    }
}

impl From<PolicyArg> for CyclePolicy {
    fn from(p: PolicyArg) -> Self {
        match p {
            PolicyArg::Localmin => CyclePolicy::Localmin,
            PolicyArg::Constant => CyclePolicy::Constant,
        }
    }
}

fn parse_seed_len(s: &str) -> Result<usize, String> {
    match s.parse::<usize>() {
        Ok(0) => Err("--seed-len must be >= 1".to_string()),
        Ok(n) => Ok(n),
        Err(e) => Err(e.to_string()),
    }
}

/// Parses a count with an optional decimal suffix: k, M (10^6) or B (10^9),
/// in either case.
fn parse_size_suffix(s: &str) -> Result<usize, String> {
    let s = s.trim();
    let (digits, mult) = match s.as_bytes().last() {
        Some(b'k' | b'K') => (&s[..s.len() - 1], 1_000),
        Some(b'm' | b'M') => (&s[..s.len() - 1], 1_000_000),
        Some(b'b' | b'B') => (&s[..s.len() - 1], 1_000_000_000),
        _ => (s, 1),
    };
    let n: usize = digits
        .parse()
        .map_err(|_| format!("invalid number: '{}'", digits))?;
    n.checked_mul(mult)
        .ok_or_else(|| format!("'{}' overflows usize", s))
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{:02x}", b)).collect()
}

fn read_file(path: &str) -> Result<Vec<u8>, String> {
    fs::read(path).map_err(|e| format!("Error reading {}: {}", path, e))
}

fn write_file(path: &str, bytes: &[u8]) -> Result<(), String> {
    fs::write(path, bytes).map_err(|e| format!("Error writing {}: {}", path, e))
}

/// The message for a reference whose CRC is not the one the delta records.
fn source_mismatch(expected: &[u8], got: &[u8]) -> String {
    format!(
        "error: source file does not match delta: expected {}, got {}",
        hex(expected),
        hex(got)
    )
}

/// A decoded delta file.
struct Delta {
    commands: Vec<PlacedCommand>,
    inplace: bool,
    version_size: usize,
    src_crc: [u8; DELTA_CRC_SIZE],
    dst_crc: [u8; DELTA_CRC_SIZE],
}

impl Delta {
    fn decode(bytes: &[u8]) -> Result<Self, String> {
        let (commands, inplace, version_size, src_crc, dst_crc) =
            decode_delta(bytes).map_err(|e| format!("Error decoding delta: {}", e))?;
        Ok(Delta {
            commands,
            inplace,
            version_size,
            src_crc,
            dst_crc,
        })
    }

    fn format(&self) -> &'static str {
        if self.inplace {
            "in-place"
        } else {
            "standard"
        }
    }
}

/// Creates the file `path` with `size` bytes and maps it for writing.  A
/// file of no bytes cannot be mapped, so the map is `None` if `size` is 0.
fn mmap_create(path: &str, size: usize) -> std::io::Result<(File, Option<MmapMut>)> {
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(true)
        .open(path)?;
    if size == 0 {
        return Ok((file, None));
    }
    file.set_len(size as u64)?;
    // SAFETY: the file was just created or truncated by this process, and
    // nothing else is expected to modify it while it is mapped.
    let map = unsafe { MmapMut::map_mut(&file)? };
    Ok((file, Some(map)))
}

fn encode(args: EncodeArgs) -> Result<(), String> {
    let r = read_file(&args.reference)?;
    let src_crc = crc64_xz(&r);
    let v = read_file(&args.version)?;
    let dst_crc = crc64_xz(&v);

    let opts = DiffOptions {
        p: args.seed_len,
        q: args.table_size,
        max_table: args.max_table,
        verbose: args.verbose,
        use_splay: args.splay,
        ..DiffOptions::default()
    };
    let t0 = Instant::now();
    let commands = delta::diff(args.algorithm.into(), &r, &v, &opts);
    let (placed, cycles_broken) = if args.inplace {
        let (placed, stats) = make_inplace(&r, &commands, args.policy.into());
        (placed, stats.cycles_broken)
    } else {
        (place_commands(commands), 0)
    };
    let elapsed = t0.elapsed();

    let delta_bytes = encode_delta_large(
        &placed,
        args.inplace,
        v.len(),
        &src_crc,
        &dst_crc,
        args.large,
    );
    write_file(&args.delta_file, &delta_bytes)?;

    let stats = placed_summary(&placed);
    let ratio = if v.is_empty() {
        0.0
    } else {
        delta_bytes.len() as f64 / v.len() as f64
    };
    let algorithm = args.algorithm.name();
    let splay = if args.splay { " [splay]" } else { "" };
    if args.inplace {
        let policy = args.policy.name();
        println!("Algorithm:    {algorithm}{splay} + in-place ({policy})");
    } else {
        println!("Algorithm:    {algorithm}{splay}");
    }
    println!("Reference:    {} ({} bytes)", args.reference, r.len());
    println!("Version:      {} ({} bytes)", args.version, v.len());
    println!(
        "Delta:        {} ({} bytes)",
        args.delta_file,
        delta_bytes.len()
    );
    println!("Compression:  {:.4} (delta/version)", ratio);
    println!(
        "Commands:     {} copies, {} adds",
        stats.num_copies, stats.num_adds
    );
    if args.inplace {
        println!("Cycles broken: {}", cycles_broken);
    }
    println!("Copy bytes:   {}", stats.copy_bytes);
    println!("Add bytes:    {}", stats.add_bytes);
    if args.verbose {
        println!("Src CRC:      {}", hex(&src_crc));
        println!("Dst CRC:      {}", hex(&dst_crc));
    }
    println!("Time:         {:.3}s", elapsed.as_secs_f64());
    Ok(())
}

fn decode(args: DecodeArgs) -> Result<(), String> {
    let r = read_file(&args.reference)?;
    let r_crc = crc64_xz(&r);
    let delta_bytes = read_file(&args.delta_file)?;
    let output = &args.output;

    let t0 = Instant::now();
    let delta = Delta::decode(&delta_bytes)?;
    if r_crc != delta.src_crc {
        if !args.ignore_hash {
            return Err(source_mismatch(&delta.src_crc, &r_crc));
        }
        eprintln!("warning: skipping source CRC check (--ignore-hash)");
    }
    let version_size = delta.version_size;
    validate_placed_commands(&delta.commands, r.len(), version_size, delta.inplace)
        .map_err(|e| format!("Error validating delta: {}", e))?;

    // An in-place delta is applied to a file that starts as a copy of the
    // reference, is as large as the larger of reference and version while
    // the commands run, and is cut to the size of the version afterward.
    let work_size = if delta.inplace {
        r.len().max(version_size)
    } else {
        version_size
    };
    let (file, map) =
        mmap_create(output, work_size).map_err(|e| format!("Error creating {}: {}", output, e))?;
    let (out_crc, elapsed) = match map {
        Some(mut out) => {
            if delta.inplace {
                out[..r.len()].copy_from_slice(&r);
                apply_placed_inplace_to(&delta.commands, &mut out);
            } else {
                apply_placed_to(&r, &delta.commands, &mut out);
            }
            out.flush()
                .map_err(|e| format!("Error flushing {}: {}", output, e))?;
            let elapsed = t0.elapsed();
            (crc64_xz(&out[..version_size]), elapsed)
        }
        None => (crc64_xz(&[]), t0.elapsed()),
    };
    if delta.inplace {
        file.set_len(version_size as u64)
            .map_err(|e| format!("Error truncating {}: {}", output, e))?;
    }

    if out_crc != delta.dst_crc {
        if !args.ignore_hash {
            return Err("error: output integrity check failed".to_string());
        }
        eprintln!("warning: skipping output CRC check (--ignore-hash)");
    }

    println!("Format:       {}", delta.format());
    println!("Reference:    {} ({} bytes)", args.reference, r.len());
    println!(
        "Delta:        {} ({} bytes)",
        args.delta_file,
        delta_bytes.len()
    );
    println!("Output:       {} ({} bytes)", output, version_size);
    println!("Time:         {:.3}s", elapsed.as_secs_f64());
    Ok(())
}

fn info(args: InfoArgs) -> Result<(), String> {
    let delta_bytes = read_file(&args.delta_file)?;
    let delta = Delta::decode(&delta_bytes)?;
    let stats = placed_summary(&delta.commands);
    println!(
        "Delta file:   {} ({} bytes)",
        args.delta_file,
        delta_bytes.len()
    );
    println!("Format:       {}", delta.format());
    println!("Version size: {} bytes", delta.version_size);
    println!("Src CRC:      {}", hex(&delta.src_crc));
    println!("Dst CRC:      {}", hex(&delta.dst_crc));
    println!("Commands:     {}", stats.num_commands);
    println!(
        "  Copies:     {} ({} bytes)",
        stats.num_copies, stats.copy_bytes
    );
    println!(
        "  Adds:       {} ({} bytes)",
        stats.num_adds, stats.add_bytes
    );
    println!("Output size:  {} bytes", stats.total_output_bytes);
    Ok(())
}

fn inplace(args: InplaceArgs) -> Result<(), String> {
    let r = read_file(&args.reference)?;
    let delta_bytes = read_file(&args.delta_in)?;
    let delta = Delta::decode(&delta_bytes)?;
    if delta.inplace {
        write_file(&args.delta_out, &delta_bytes)?;
        println!("Delta is already in-place format; copied unchanged.");
        return Ok(());
    }
    // Converting a copy to an add reads R, so R must be the right file.
    let r_crc = crc64_xz(&r);
    if r_crc != delta.src_crc {
        return Err(source_mismatch(&delta.src_crc, &r_crc));
    }
    validate_placed_commands(&delta.commands, r.len(), delta.version_size, false)
        .map_err(|e| format!("Error validating delta: {}", e))?;
    // A move reads the output, not R, and unplace_commands panics on one.
    if delta
        .commands
        .iter()
        .any(|cmd| matches!(cmd, PlacedCommand::Move { .. }))
    {
        return Err(
            "error: delta contains a move command, which cannot be converted to in-place"
                .to_string(),
        );
    }

    let t0 = Instant::now();
    let commands = unplace_commands(delta.commands);
    let (placed, converted) = make_inplace(&r, &commands, args.policy.into());
    let elapsed = t0.elapsed();

    // The CRCs describe the reference and the version, which the conversion
    // does not change.
    let out_bytes = encode_delta_large(
        &placed,
        true,
        delta.version_size,
        &delta.src_crc,
        &delta.dst_crc,
        args.large,
    );
    write_file(&args.delta_out, &out_bytes)?;

    if args.verbose {
        eprintln!(
            "inplace: {} copies, {} CRWI edges, {} cycles broken",
            converted.num_copies + converted.copies_converted,
            converted.edges,
            converted.cycles_broken,
        );
        if converted.copies_converted > 0 {
            eprintln!(
                "  converted {} copies -> adds ({} bytes materialized)",
                converted.copies_converted, converted.bytes_converted,
            );
        }
    }

    let stats = placed_summary(&placed);
    println!("Reference:    {} ({} bytes)", args.reference, r.len());
    println!(
        "Input delta:  {} ({} bytes)",
        args.delta_in,
        delta_bytes.len()
    );
    println!(
        "Output delta: {} ({} bytes)",
        args.delta_out,
        out_bytes.len()
    );
    println!("Format:       in-place ({})", args.policy.name());
    println!(
        "Commands:     {} copies, {} adds",
        stats.num_copies, stats.num_adds
    );
    println!("Copy bytes:   {}", stats.copy_bytes);
    println!("Add bytes:    {}", stats.add_bytes);
    println!("Time:         {:.3}s", elapsed.as_secs_f64());
    Ok(())
}

fn main() {
    let result = match Cli::parse().command {
        Commands::Encode(args) => encode(args),
        Commands::Decode(args) => decode(args),
        Commands::Info(args) => info(args),
        Commands::Inplace(args) => inplace(args),
    };
    if let Err(message) = result {
        eprintln!("{}", message);
        process::exit(1);
    }
}
