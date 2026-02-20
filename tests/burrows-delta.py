#!/usr/bin/env python3
"""
burrows-delta.py — Burrows' Delta stylometric analysis on the Shakespeare corpora

Implements Argamon's (2008) z-score formulation of Burrows' Delta, the standard
stylometric distance metric used in authorship attribution.  Lower Delta means
more similar writing style.

Algorithm:
  1. Tokenize each corpus into lowercase alphabetic words.
  2. Find the top-N most frequent words across all corpora combined.
  3. For each corpus, compute per-word relative frequency (per 1000 words).
  4. Compute z-scores: (corpus_freq - mean_freq) / population_std for each word,
     where mean and std are taken across all corpora.
  5. Linear Delta(A, B)  = mean |z_A(w) - z_B(w)|   across top-N words.
     Cosine Delta(A, B)  = sqrt(sum (z_A(w) - z_B(w))^2)  (Euclidean on z-vecs).

Calibration: Delta == 0 means identical style; values < 1.0 are considered
"close" in the literature; values > 1.5 are "distant."

Corpora (whitespace-normalized, from Project Gutenberg / Internet Archive):
  shakespeare  5.4 MB   PG #100
  marlowe      915 KB   PG #779, 901, 1094, 1496, 1589, 18781, 20288
  bacon        2.1 MB   PG #56463, 5500, 45988, 2434, 3290, 46964
  marysidney   421 KB   IA + PG #21789
  devere        74 KB   Internet Archive, Looney ed. 1921

Usage:
  python3 tests/burrows-delta.py
  python3 tests/burrows-delta.py --data-dir /path/to/dir --top-n 100 200
  python3 tests/burrows-delta.py --show-words --top-n 100
"""

import argparse
import math
import re
import sys
from collections import Counter
from pathlib import Path

# ── Configuration ─────────────────────────────────────────────────────────────

DEFAULT_DATA_DIR = "/tmp/delta-gutenberg"

CORPORA = {
    "shakespeare": "shakespeare-norm.txt",
    "marlowe":     "marlowe-norm.txt",
    "bacon":       "bacon-norm.txt",
    "marysidney":  "marysidney-norm.txt",
    "devere":      "devere-norm.txt",
}

# Display labels for output
LABELS = {
    "shakespeare": "Shakespeare",
    "marlowe":     "Marlowe",
    "bacon":       "Bacon",
    "marysidney":  "Mary Sidney",
    "devere":      "de Vere",
}

DEFAULT_N_VALUES = [50, 100, 200, 500]

# Tokenization artifacts to exclude from top-N words.
# re.findall('[a-zA-Z]+') splits "king's" → ["king", "s"], making bare "s"
# the ~10th most common token.  It is not a stylistic word choice.
EXCLUDE = {"s"}

# ── Tokenization ──────────────────────────────────────────────────────────────

def tokenize(path: Path) -> list[str]:
    """Return list of lowercase alphabetic tokens from a text file."""
    text = path.read_text(encoding="utf-8-sig", errors="replace")
    tokens = re.findall(r"[a-zA-Z]+", text.lower())
    return [t for t in tokens if t not in EXCLUDE]

# ── Core algorithm ────────────────────────────────────────────────────────────

def top_n_words(combined: Counter, n: int) -> list[str]:
    """Return the n most frequent words from the combined counter."""
    return [w for w, _ in combined.most_common() if w not in EXCLUDE][:n]

def relative_freqs(counter: Counter, total: int, words: list[str]) -> dict[str, float]:
    """Return {word: frequency per 1000 words} for the given word list."""
    return {w: counter[w] / total * 1000 for w in words}

def compute_zscores(
    freqs_by_corpus: dict[str, dict[str, float]],
    words: list[str],
) -> dict[str, dict[str, float]]:
    """
    Compute z-scores using population std (Burrows 2002 convention).
    z(corpus, word) = (freq - mean_freq) / std_freq
    If std == 0 (word has identical frequency everywhere), z = 0.
    """
    names = list(freqs_by_corpus)
    n = len(names)
    zscores = {name: {} for name in names}
    for w in words:
        vals = [freqs_by_corpus[name][w] for name in names]
        mean = sum(vals) / n
        variance = sum((v - mean) ** 2 for v in vals) / n   # population variance
        std = math.sqrt(variance) if variance > 0 else 0.0
        for name, v in zip(names, vals):
            zscores[name][w] = (v - mean) / std if std > 0 else 0.0
    return zscores

def linear_delta(
    zs_a: dict[str, float],
    zs_b: dict[str, float],
    words: list[str],
) -> float:
    """Argamon linear Delta: mean |z_A(w) - z_B(w)|."""
    return sum(abs(zs_a[w] - zs_b[w]) for w in words) / len(words)

def cosine_delta(
    zs_a: dict[str, float],
    zs_b: dict[str, float],
    words: list[str],
) -> float:
    """Euclidean distance of z-score vectors (Argamon cosine Delta)."""
    return math.sqrt(sum((zs_a[w] - zs_b[w]) ** 2 for w in words))

# ── Output formatting ─────────────────────────────────────────────────────────

def print_matrix(title: str, names: list[str], labels: dict[str, str],
                 matrix: dict[tuple[str, str], float]) -> None:
    """Print a labelled distance matrix."""
    col_w = max(len(labels[n]) for n in names)
    row_w = col_w

    print(f"  {title}:")
    header = " " * (row_w + 2) + "  ".join(f"{labels[n]:>{col_w}}" for n in names)
    print(f"  {header}")
    for a in names:
        row = f"  {labels[a]:>{row_w}}"
        for b in names:
            row += f"  {matrix[(a, b)]:>{col_w}.4f}"
        print(row)
    print()

def print_ranking(names: list[str], labels: dict[str, str],
                  ref: str, matrix: dict[tuple[str, str], float]) -> None:
    """Print candidates ranked by distance from ref."""
    others = [(n, matrix[(ref, n)]) for n in names if n != ref]
    others.sort(key=lambda x: x[1])
    print(f"  Nearest to {labels[ref]} (linear Delta, ascending):")
    for rank, (name, d) in enumerate(others, 1):
        flag = "  ← closest" if rank == 1 else ""
        print(f"    {rank}. {labels[name]:<14}  {d:.4f}{flag}")
    print()

# ── Main ──────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--data-dir", default=DEFAULT_DATA_DIR,
                        help=f"Directory containing corpus files (default: {DEFAULT_DATA_DIR})")
    parser.add_argument("--top-n", nargs="+", type=int, default=DEFAULT_N_VALUES,
                        metavar="N", help="Top-N word list sizes to run (default: 50 100 200 500)")
    parser.add_argument("--show-words", action="store_true",
                        help="Print the top-N word list for each N")
    args = parser.parse_args()

    data_dir = Path(args.data_dir)

    # ── Load corpora ──────────────────────────────────────────────────────────

    print("Loading corpora...")
    tokens: dict[str, list[str]] = {}
    counters: dict[str, Counter] = {}
    totals: dict[str, int] = {}

    for key, fname in CORPORA.items():
        path = data_dir / fname
        if not path.exists():
            print(f"  WARNING: {path} not found — skipping {LABELS[key]}", file=sys.stderr)
            continue
        tokens[key] = tokenize(path)
        counters[key] = Counter(tokens[key])
        totals[key] = len(tokens[key])
        print(f"  {LABELS[key]:<14} {totals[key]:>10,} words")

    if not tokens:
        print("No corpora found. Check --data-dir.", file=sys.stderr)
        sys.exit(1)

    names = list(tokens)

    # Combined counter for top-N selection (all corpora equally)
    combined: Counter = Counter()
    for c in counters.values():
        combined.update(c)

    print()

    # ── Run for each N ────────────────────────────────────────────────────────

    rank_table: dict[int, list[tuple[str, float]]] = {}   # n → sorted (name, delta)

    for n in sorted(args.top_n):
        words = top_n_words(combined, n)

        if args.show_words:
            print(f"Top {n} words: {words}")
            print()

        # Zero-frequency check for small corpora
        for key in names:
            zeros = sum(1 for w in words if counters[key][w] == 0)
            if zeros > 0 and key == "devere":
                pass   # reported at end of section

        freqs = {key: relative_freqs(counters[key], totals[key], words)
                 for key in names}
        zscores = compute_zscores(freqs, words)

        lin: dict[tuple[str, str], float] = {}
        cos: dict[tuple[str, str], float] = {}
        for a in names:
            for b in names:
                lin[(a, b)] = linear_delta(zscores[a], zscores[b], words)
                cos[(a, b)] = cosine_delta(zscores[a], zscores[b], words)

        print(f"=== Burrows' Delta  (top {n} words, population std) ===")
        print()

        # Zero-frequency note for de Vere
        if "devere" in names:
            zeros = sum(1 for w in words if counters["devere"][w] == 0)
            if zeros:
                print(f"  Note: {zeros}/{n} top words have zero frequency in de Vere "
                      f"({totals['devere']:,} words — small corpus, noisier estimates).")
                print()

        print_matrix("Linear Delta (mean |Δz|, lower = more similar)", names, LABELS, lin)
        print_matrix("Cosine Delta (||Δz||₂)", names, LABELS, cos)

        if "shakespeare" in names:
            print_ranking(names, LABELS, "shakespeare", lin)

        # Store ranking for summary
        others = [(k, lin[("shakespeare", k)]) for k in names if k != "shakespeare"]
        others.sort(key=lambda x: x[1])
        rank_table[n] = others

    # ── Rank stability summary ────────────────────────────────────────────────

    ns = sorted(args.top_n)
    print("=== Rank stability across N ===")
    print()
    cands = [k for k in names if k != "shakespeare"]
    col = max(len(LABELS[c]) for c in cands)
    header = f"  {'Candidate':<{col}}" + "".join(f"  N={n:>4}" for n in ns)
    print(header)
    print(f"  {'-'*col}" + "".join(f"  ------" for _ in ns))
    for c in cands:
        row = f"  {LABELS[c]:<{col}}"
        for n in ns:
            ranked = [k for k, _ in rank_table[n]]
            rank = ranked.index(c) + 1 if c in ranked else "-"
            delta = next((d for k, d in rank_table[n] if k == c), None)
            cell = f"{rank}({delta:.3f})" if delta is not None else "  -"
            row += f"  {cell:>6}"
        print(row)
    print()
    print("  (rank 1 = stylistically closest to Shakespeare)")

if __name__ == "__main__":
    main()
