use std::fs::{self, File, OpenOptions};
use std::process;
use std::time::Instant;

use clap::{Parser, Subcommand, ValueEnum};
use memmap2::{Mmap, MmapMut};

use delta::{
    Algorithm, CyclePolicy,
    apply_placed_inplace_to, apply_placed_to,
    decode_delta, encode_delta,
    make_inplace, place_commands,
    placed_summary,
};

// ── mmap helpers ─────────────────────────────────────────────────────────

/// Memory-map a file for reading.  Returns `None` for empty files.
fn mmap_open(path: &str) -> std::io::Result<(File, Option<Mmap>)> {
    let file = File::open(path)?;
    let mmap = if file.metadata()?.len() > 0 {
        // SAFETY: We do not modify the file while the mapping is active.
        Some(unsafe { Mmap::map(&file)? })
    } else {
        None
    };
    Ok((file, mmap))
}

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

        /// Seed length
        #[arg(long, default_value_t = delta::SEED_LEN)]
        seed_len: usize,

        /// Hash table size
        #[arg(long, default_value_t = delta::TABLE_SIZE)]
        table_size: usize,

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
    },

    /// Show delta file statistics
    Info {
        /// Delta file
        delta_file: String,
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
            inplace,
            policy,
            verbose,
            splay,
        } => {
            let (_rf, r_mmap) = mmap_open(&reference).unwrap_or_else(|e| {
                eprintln!("Error reading {}: {}", reference, e);
                process::exit(1);
            });
            let r: &[u8] = r_mmap.as_deref().unwrap_or(&[]);

            let (_vf, v_mmap) = mmap_open(&version).unwrap_or_else(|e| {
                eprintln!("Error reading {}: {}", version, e);
                process::exit(1);
            });
            let v: &[u8] = v_mmap.as_deref().unwrap_or(&[]);

            let algo: Algorithm = algorithm.into();
            let t0 = Instant::now();
            let commands = delta::diff(algo, r, v, seed_len, table_size, verbose, splay);

            let pol: CyclePolicy = policy.into();
            let placed = if inplace {
                make_inplace(r, &commands, pol)
            } else {
                place_commands(&commands)
            };
            let elapsed = t0.elapsed();

            let delta_bytes = encode_delta(&placed, inplace, v.len());
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
            println!("Copy bytes:   {}", stats.copy_bytes);
            println!("Add bytes:    {}", stats.add_bytes);
            println!("Time:         {:.3}s", elapsed.as_secs_f64());
        }

        Commands::Decode {
            reference,
            delta_file,
            output,
        } => {
            let (_rf, r_mmap) = mmap_open(&reference).unwrap_or_else(|e| {
                eprintln!("Error reading {}: {}", reference, e);
                process::exit(1);
            });
            let r: &[u8] = r_mmap.as_deref().unwrap_or(&[]);

            let delta_bytes = fs::read(&delta_file).unwrap_or_else(|e| {
                eprintln!("Error reading {}: {}", delta_file, e);
                process::exit(1);
            });

            let t0 = Instant::now();
            let (placed, is_ip, version_size) = decode_delta(&delta_bytes).unwrap_or_else(|e| {
                eprintln!("Error decoding delta: {}", e);
                process::exit(1);
            });

            if is_ip {
                let buf_size = r.len().max(version_size);
                let (out_file, out_mmap) =
                    mmap_create(&output, buf_size).unwrap_or_else(|e| {
                        eprintln!("Error creating {}: {}", output, e);
                        process::exit(1);
                    });
                if let Some(mut mm) = out_mmap {
                    mm[..r.len()].copy_from_slice(r);
                    apply_placed_inplace_to(&placed, &mut mm);
                    mm.flush().unwrap_or_else(|e| {
                        eprintln!("Error flushing {}: {}", output, e);
                        process::exit(1);
                    });
                    drop(mm);
                }
                out_file
                    .set_len(version_size as u64)
                    .unwrap_or_else(|e| {
                        eprintln!("Error truncating {}: {}", output, e);
                        process::exit(1);
                    });
            } else {
                let (_out_file, out_mmap) =
                    mmap_create(&output, version_size).unwrap_or_else(|e| {
                        eprintln!("Error creating {}: {}", output, e);
                        process::exit(1);
                    });
                if let Some(mut mm) = out_mmap {
                    apply_placed_to(r, &placed, &mut mm);
                    mm.flush().unwrap_or_else(|e| {
                        eprintln!("Error flushing {}: {}", output, e);
                        process::exit(1);
                    });
                }
            }
            let elapsed = t0.elapsed();

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

            let (placed, is_ip, version_size) =
                decode_delta(&delta_bytes).unwrap_or_else(|e| {
                    eprintln!("Error decoding delta: {}", e);
                    process::exit(1);
                });

            let stats = placed_summary(&placed);
            let fmt = if is_ip { "in-place" } else { "standard" };
            println!("Delta file:   {} ({} bytes)", delta_file, delta_bytes.len());
            println!("Format:       {}", fmt);
            println!("Version size: {} bytes", version_size);
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
    }
}
