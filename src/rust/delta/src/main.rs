use std::fs::{self, File, OpenOptions};
use std::process;
use std::time::Instant;

use clap::{Parser, Subcommand, ValueEnum};
use memmap2::MmapMut;

use delta::{
    Algorithm, CyclePolicy, DiffOptions,
    apply_placed_inplace_to, apply_placed_to,
    decode_delta, encode_delta,
    make_inplace, place_commands, unplace_commands,
    placed_summary, shake128_16,
};

/// Format a byte slice as a lowercase hex string.
fn hex_str(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{:02x}", b)).collect()
}

/// Parse a size with optional k/M/B suffix (decimal: k=1000, M=1_000_000, B=1_000_000_000).
fn parse_size_suffix(s: &str) -> Result<usize, String> {
    let s = s.trim();
    let (num_str, mult) = match s.as_bytes().last() {
        Some(b'k') | Some(b'K') => (&s[..s.len() - 1], 1_000usize),
        Some(b'M') | Some(b'm') => (&s[..s.len() - 1], 1_000_000usize),
        Some(b'B') | Some(b'b') => (&s[..s.len() - 1], 1_000_000_000usize),
        _ => (s, 1usize),
    };
    let n: usize = num_str
        .parse()
        .map_err(|_| format!("invalid number: '{}'", num_str))?;
    n.checked_mul(mult)
        .ok_or_else(|| format!("'{}' overflows usize", s))
}

// ── mmap helpers ─────────────────────────────────────────────────────────

/// Create a file of `size` bytes and memory-map it for read-write.
/// Returns `None` for size 0 (creates an empty file).
fn mmap_create(path: &str, size: usize) -> std::io::Result<(File, Option<MmapMut>)> {
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(true)
        .open(path)?;
    if size > 0 {
        file.set_len(size as u64)?;
        // SAFETY: We have exclusive access to this newly-created file.
        let mmap = unsafe { MmapMut::map_mut(&file)? };
        Ok((file, Some(mmap)))
    } else {
        Ok((file, None))
    }
}

// ── CLI types ────────────────────────────────────────────────────────────

#[derive(Clone, Copy, ValueEnum)]
enum AlgorithmArg {
    Greedy,
    Onepass,
    Correcting,
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

impl From<PolicyArg> for CyclePolicy {
    fn from(p: PolicyArg) -> Self {
        match p {
            PolicyArg::Localmin => CyclePolicy::Localmin,
            PolicyArg::Constant => CyclePolicy::Constant,
        }
    }
}

#[derive(Parser)]
#[command(about = "Differential compression (Ajtai et al. 2002)")]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Compute delta encoding
    Encode {
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
        #[arg(long, default_value_t = delta::SEED_LEN,
              value_parser = |s: &str| s.parse::<usize>()
                  .map_err(|e| e.to_string())
                  .and_then(|n| if n >= 1 { Ok(n) }
                            else { Err("--seed-len must be >= 1".to_string()) }))]
        seed_len: usize,

        /// Hash table floor size
        #[arg(long, default_value_t = delta::TABLE_SIZE)]
        table_size: usize,

        /// Maximum hash table size; accepts k/M/B suffix (e.g. 512M, 2B)
        #[arg(long, default_value = "1073741827", value_parser = parse_size_suffix)]
        max_table: usize,

        /// Produce in-place reconstructible delta
        #[arg(long)]
        inplace: bool,

        /// Cycle-breaking policy for --inplace
        #[arg(long, value_enum, default_value_t = PolicyArg::Localmin)]
        policy: PolicyArg,

        /// Print diagnostic messages to stderr
        #[arg(long)]
        verbose: bool,

        /// Use splay tree instead of hash table
        #[arg(long)]
        splay: bool,
    },

    /// Reconstruct version from delta
    Decode {
        /// Reference file
        reference: String,

        /// Delta file
        delta_file: String,

        /// Output (reconstructed version) file
        output: String,

        /// Skip hash verification (for partial recovery)
        #[arg(long)]
        ignore_hash: bool,
    },

    /// Show delta file statistics
    Info {
        /// Delta file
        delta_file: String,
    },

    /// Convert standard delta to in-place delta
    Inplace {
        /// Reference file
        reference: String,

        /// Input (standard) delta file
        delta_in: String,

        /// Output (in-place) delta file
        delta_out: String,

        /// Cycle-breaking policy
        #[arg(long, value_enum, default_value_t = PolicyArg::Localmin)]
        policy: PolicyArg,

        /// Print diagnostics (cycles broken, etc.)
        #[arg(long)]
        verbose: bool,
    },
}

// ── main ─────────────────────────────────────────────────────────────────

fn main() {
    let cli = Cli::parse();

    match cli.command {
        Commands::Encode {
            algorithm,
            reference,
            version,
            delta_file,
            seed_len,
            table_size,
            max_table,
            inplace,
            policy,
            verbose,
            splay,
        } => {
            // Read files and compute SHAKE128 hashes in a single sequential
            // pass each, then use the loaded bytes for the diff algorithm.
            let r_bytes = fs::read(&reference).unwrap_or_else(|e| {
                eprintln!("Error reading {}: {}", reference, e);
                process::exit(1);
            });
            let src_hash = shake128_16(&r_bytes);
            let r: &[u8] = &r_bytes;

            let v_bytes = fs::read(&version).unwrap_or_else(|e| {
                eprintln!("Error reading {}: {}", version, e);
                process::exit(1);
            });
            let dst_hash = shake128_16(&v_bytes);
            let v: &[u8] = &v_bytes;

            let algo: Algorithm = algorithm.into();
            let t0 = Instant::now();
            let opts = DiffOptions {
                p: seed_len,
                q: table_size,
                max_table,
                verbose,
                use_splay: splay,
                ..DiffOptions::default()
            };
            let commands = delta::diff(algo, r, v, &opts);

            let pol: CyclePolicy = policy.into();
            let mut cycles_broken = 0usize;
            let placed = if inplace {
                let (p, stats) = make_inplace(r, &commands, pol);
                cycles_broken = stats.cycles_broken;
                p
            } else {
                place_commands(&commands)
            };
            let elapsed = t0.elapsed();

            let delta_bytes = encode_delta(&placed, inplace, v.len(), &src_hash, &dst_hash);
            fs::write(&delta_file, &delta_bytes).unwrap_or_else(|e| {
                eprintln!("Error writing {}: {}", delta_file, e);
                process::exit(1);
            });

            let stats = placed_summary(&placed);
            let ratio = if v.is_empty() {
                0.0
            } else {
                delta_bytes.len() as f64 / v.len() as f64
            };
            let algo_name = format!("{:?}", algo).to_lowercase();
            let splay_tag = if splay { " [splay]" } else { "" };
            if inplace {
                let pol_name = format!("{:?}", pol).to_lowercase();
                println!("Algorithm:    {}{} + in-place ({})", algo_name, splay_tag, pol_name);
            } else {
                println!("Algorithm:    {}{}", algo_name, splay_tag);
            }
            println!("Reference:    {} ({} bytes)", reference, r.len());
            println!("Version:      {} ({} bytes)", version, v.len());
            println!("Delta:        {} ({} bytes)", delta_file, delta_bytes.len());
            println!("Compression:  {:.4} (delta/version)", ratio);
            println!(
                "Commands:     {} copies, {} adds",
                stats.num_copies, stats.num_adds
            );
            if inplace {
                println!("Cycles broken: {}", cycles_broken);
            }
            println!("Copy bytes:   {}", stats.copy_bytes);
            println!("Add bytes:    {}", stats.add_bytes);
            if verbose {
                println!("Src hash:     {}", hex_str(&src_hash));
                println!("Dst hash:     {}", hex_str(&dst_hash));
            }
            println!("Time:         {:.3}s", elapsed.as_secs_f64());
        }

        Commands::Decode {
            reference,
            delta_file,
            output,
            ignore_hash,
        } => {
            // Read reference and compute its hash in one sequential pass.
            let r_bytes = fs::read(&reference).unwrap_or_else(|e| {
                eprintln!("Error reading {}: {}", reference, e);
                process::exit(1);
            });
            let r_hash_actual = shake128_16(&r_bytes);
            let r: &[u8] = &r_bytes;

            let delta_bytes = fs::read(&delta_file).unwrap_or_else(|e| {
                eprintln!("Error reading {}: {}", delta_file, e);
                process::exit(1);
            });

            let t0 = Instant::now();
            let (placed, is_ip, version_size, src_hash, dst_hash) =
                decode_delta(&delta_bytes).unwrap_or_else(|e| {
                    eprintln!("Error decoding delta: {}", e);
                    process::exit(1);
                });

            // Pre-check: verify reference matches what was recorded at encode time.
            if r_hash_actual != src_hash {
                if !ignore_hash {
                    eprintln!(
                        "error: source file does not match delta: expected {}, got {}",
                        hex_str(&src_hash),
                        hex_str(&r_hash_actual)
                    );
                    process::exit(1);
                }
                eprintln!("warning: skipping source hash check (--ignore-hash)");
            }

            let out_bytes: Vec<u8> = if is_ip {
                let buf_size = r.len().max(version_size);
                let (out_file, out_mmap) =
                    mmap_create(&output, buf_size).unwrap_or_else(|e| {
                        eprintln!("Error creating {}: {}", output, e);
                        process::exit(1);
                    });
                if let Some(mut mm) = out_mmap {
                    mm[..r.len()].copy_from_slice(r);
                    apply_placed_inplace_to(&placed, &mut mm);
                    let result = mm[..version_size].to_vec();
                    mm.flush().unwrap_or_else(|e| {
                        eprintln!("Error flushing {}: {}", output, e);
                        process::exit(1);
                    });
                    drop(mm);
                    out_file.set_len(version_size as u64).unwrap_or_else(|e| {
                        eprintln!("Error truncating {}: {}", output, e);
                        process::exit(1);
                    });
                    result
                } else {
                    Vec::new()
                }
            } else {
                let (_out_file, out_mmap) =
                    mmap_create(&output, version_size).unwrap_or_else(|e| {
                        eprintln!("Error creating {}: {}", output, e);
                        process::exit(1);
                    });
                if let Some(mut mm) = out_mmap {
                    apply_placed_to(r, &placed, &mut mm);
                    let result = mm.to_vec();
                    mm.flush().unwrap_or_else(|e| {
                        eprintln!("Error flushing {}: {}", output, e);
                        process::exit(1);
                    });
                    result
                } else {
                    Vec::new()
                }
            };
            let elapsed = t0.elapsed();

            // Post-check: verify reconstructed output matches recorded dest hash.
            let out_hash_actual = shake128_16(&out_bytes);
            if out_hash_actual != dst_hash {
                if !ignore_hash {
                    eprintln!("error: output integrity check failed");
                    process::exit(1);
                }
                eprintln!("warning: skipping output integrity check (--ignore-hash)");
            }

            let fmt = if is_ip { "in-place" } else { "standard" };
            println!("Format:       {}", fmt);
            println!("Reference:    {} ({} bytes)", reference, r.len());
            println!("Delta:        {} ({} bytes)", delta_file, delta_bytes.len());
            println!("Output:       {} ({} bytes)", output, version_size);
            println!("Time:         {:.3}s", elapsed.as_secs_f64());
        }

        Commands::Info { delta_file } => {
            let delta_bytes = fs::read(&delta_file).unwrap_or_else(|e| {
                eprintln!("Error reading {}: {}", delta_file, e);
                process::exit(1);
            });

            let (placed, is_ip, version_size, src_hash, dst_hash) =
                decode_delta(&delta_bytes).unwrap_or_else(|e| {
                    eprintln!("Error decoding delta: {}", e);
                    process::exit(1);
                });

            let stats = placed_summary(&placed);
            let fmt = if is_ip { "in-place" } else { "standard" };
            println!("Delta file:   {} ({} bytes)", delta_file, delta_bytes.len());
            println!("Format:       {}", fmt);
            println!("Version size: {} bytes", version_size);
            println!("Src hash:     {}", hex_str(&src_hash));
            println!("Dst hash:     {}", hex_str(&dst_hash));
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
        }

        Commands::Inplace {
            reference,
            delta_in,
            delta_out,
            policy,
            verbose,
        } => {
            // Read reference and compute hash in one sequential pass.
            let r_bytes = fs::read(&reference).unwrap_or_else(|e| {
                eprintln!("Error reading {}: {}", reference, e);
                process::exit(1);
            });
            let r: &[u8] = &r_bytes;

            let delta_bytes = fs::read(&delta_in).unwrap_or_else(|e| {
                eprintln!("Error reading {}: {}", delta_in, e);
                process::exit(1);
            });

            let (placed, is_ip, version_size, src_hash, dst_hash) =
                decode_delta(&delta_bytes).unwrap_or_else(|e| {
                    eprintln!("Error decoding delta: {}", e);
                    process::exit(1);
                });

            if is_ip {
                fs::write(&delta_out, &delta_bytes).unwrap_or_else(|e| {
                    eprintln!("Error writing {}: {}", delta_out, e);
                    process::exit(1);
                });
                println!("Delta is already in-place format; copied unchanged.");
                return;
            }

            let t0 = Instant::now();
            let pol: CyclePolicy = policy.into();
            let commands = unplace_commands(&placed);
            let (ip_placed, ip_stats) = make_inplace(r, &commands, pol);
            let elapsed = t0.elapsed();

            // Preserve the original src_hash and dst_hash from the input delta.
            let ip_delta = encode_delta(&ip_placed, true, version_size, &src_hash, &dst_hash);
            fs::write(&delta_out, &ip_delta).unwrap_or_else(|e| {
                eprintln!("Error writing {}: {}", delta_out, e);
                process::exit(1);
            });

            if verbose {
                eprintln!(
                    "inplace: {} copies, {} CRWI edges, {} cycles broken",
                    ip_stats.num_copies + ip_stats.copies_converted,
                    ip_stats.edges,
                    ip_stats.cycles_broken,
                );
                if ip_stats.copies_converted > 0 {
                    eprintln!(
                        "  converted {} copies -> adds ({} bytes materialized)",
                        ip_stats.copies_converted,
                        ip_stats.bytes_converted,
                    );
                }
            }

            let stats = placed_summary(&ip_placed);
            let pol_name = format!("{:?}", pol).to_lowercase();
            println!("Reference:    {} ({} bytes)", reference, r.len());
            println!(
                "Input delta:  {} ({} bytes)",
                delta_in,
                delta_bytes.len()
            );
            println!(
                "Output delta: {} ({} bytes)",
                delta_out,
                ip_delta.len()
            );
            println!("Format:       in-place ({})", pol_name);
            println!(
                "Commands:     {} copies, {} adds",
                stats.num_copies, stats.num_adds
            );
            println!("Copy bytes:   {}", stats.copy_bytes);
            println!("Add bytes:    {}", stats.add_bytes);
            println!("Time:         {:.3}s", elapsed.as_secs_f64());
        }
    }
}
