#!/usr/bin/env python3

from __future__ import annotations

import argparse
import os
import pathlib
import re
from typing import Dict, List, Tuple

os.environ.setdefault("MPLCONFIGDIR", "/tmp/mplconfig")
os.environ.setdefault("XDG_CACHE_HOME", "/tmp")

try:
    import matplotlib
except ModuleNotFoundError as exc:
    raise SystemExit(
        "matplotlib is not installed for this Python interpreter. "
        "On this machine, use /opt/homebrew/bin/python3."
    ) from exc

matplotlib.use("Agg")
import matplotlib.pyplot as plt


PER_LANGUAGE_ROW = re.compile(r"^\s{2}([A-Za-z+]+)\s+([0-9.]+)s\s+([0-9.]+)s\s*$")
KERNEL_ROW = re.compile(r"^(5\.1\.[0-9]|5\.1\.[0-9]→5\.1\.[0-9])\s+([0-9,]+)\s+([0-9,]+)\s+([.0-9]+)%\s+([0-9.]+)s\s*$")

KERNEL_SECTIONS = {
    "=== From base: 5.1.0 → 5.1.{1..7} ===": "base",
    "=== Successive: 5.1.n → 5.1.n+1 ===": "successive",
    "=== From 5.1.1: divergence 5.1.1 → 5.1.{2..7} ===": "divergence",
}

KERNEL_X = {
    "base": ["5.1.1", "5.1.2", "5.1.3", "5.1.4", "5.1.5", "5.1.6", "5.1.7"],
    "successive": ["5.1.1", "5.1.2", "5.1.3", "5.1.4", "5.1.5", "5.1.6", "5.1.7"],
    "divergence": ["5.1.2", "5.1.3", "5.1.4", "5.1.5", "5.1.6", "5.1.7"],
}

COLORS = {
    "onepass": "#1f77b4",
    "correcting": "#d62728",
    "base": "#1f77b4",
    "successive": "#2ca02c",
    "divergence": "#ff7f0e",
}


def parse_per_language(path: pathlib.Path) -> List[Tuple[str, float, float]]:
    rows: List[Tuple[str, float, float]] = []
    for line in path.read_text().splitlines():
        match = PER_LANGUAGE_ROW.match(line)
        if not match:
            continue
        name, onepass, correcting = match.groups()
        rows.append((name, float(onepass), float(correcting)))
    if not rows:
        raise ValueError(f"no per-language rows found in {path}")
    return rows


def parse_kernel(path: pathlib.Path) -> Dict[str, Dict[str, List[Tuple[str, float, float]]]]:
    data: Dict[str, Dict[str, List[Tuple[str, float, float]]]] = {
        "base": {"onepass": [], "correcting": []},
        "successive": {"onepass": [], "correcting": []},
        "divergence": {"onepass": [], "correcting": []},
    }
    section: str | None = None
    algo: str | None = None

    for line in path.read_text().splitlines():
        if line in KERNEL_SECTIONS:
            section = KERNEL_SECTIONS[line]
            algo = None
            continue
        if line == "--- onepass ---":
            algo = "onepass"
            continue
        if line == "--- correcting ---":
            algo = "correcting"
            continue

        match = KERNEL_ROW.match(line)
        if not match or section is None or algo is None:
            continue

        label, _tar_size, _delta_size, ratio, seconds = match.groups()
        x_label = label.split("→")[-1]
        data[section][algo].append((x_label, float(ratio), float(seconds)))

    for section_name, section_data in data.items():
        for algo_name, rows in section_data.items():
            if not rows:
                raise ValueError(f"missing {algo_name} rows for {section_name} in {path}")
    return data


def style() -> None:
    plt.style.use("seaborn-v0_8-whitegrid")
    plt.rcParams.update(
        {
            "figure.figsize": (11, 6),
            "axes.spines.top": False,
            "axes.spines.right": False,
            "axes.titleweight": "bold",
            "axes.labelweight": "bold",
            "font.size": 11,
            "axes.facecolor": "#f8f8f8",
            "figure.facecolor": "white",
            "grid.alpha": 0.25,
        }
    )


def plot_per_language(rows: List[Tuple[str, float, float]], out_path: pathlib.Path) -> None:
    names = [row[0] for row in rows]
    onepass = [row[1] for row in rows]
    correcting = [row[2] for row in rows]
    xs = list(range(len(names)))
    width = 0.38

    fig, ax = plt.subplots()
    ax.bar([x - width / 2 for x in xs], onepass, width=width, color=COLORS["onepass"], label="onepass")
    ax.bar([x + width / 2 for x in xs], correcting, width=width, color=COLORS["correcting"], label="correcting")

    ax.set_title("Kernel Benchmark by Implementation")
    ax.set_ylabel("Seconds (log scale)")
    ax.set_xticks(xs, names)
    ax.set_yscale("log")
    ax.legend(frameon=False)

    for x, value in zip([x - width / 2 for x in xs], onepass):
        ax.text(x, value * 1.08, f"{value:.1f}", ha="center", va="bottom", fontsize=8, rotation=90)
    for x, value in zip([x + width / 2 for x in xs], correcting):
        ax.text(x, value * 1.08, f"{value:.1f}", ha="center", va="bottom", fontsize=8, rotation=90)

    fig.tight_layout()
    fig.savefig(out_path, dpi=180, bbox_inches="tight")
    plt.close(fig)


def plot_kernel_metric(
    data: Dict[str, Dict[str, List[Tuple[str, float, float]]]],
    metric_index: int,
    ylabel: str,
    title: str,
    out_path: pathlib.Path,
) -> None:
    fig, axes = plt.subplots(1, 2, figsize=(13, 5), sharex=False)

    for axis, algo in zip(axes, ["onepass", "correcting"]):
        for section in ["base", "successive", "divergence"]:
            rows = data[section][algo]
            x_labels = [row[0] for row in rows]
            y_values = [row[metric_index] for row in rows]
            axis.plot(
                x_labels,
                y_values,
                marker="o",
                linewidth=2.2,
                color=COLORS[section],
                label=section,
            )
        axis.set_title(algo)
        axis.set_xlabel("Target kernel version")
        axis.set_ylabel(ylabel)
        axis.legend(frameon=False)

    fig.suptitle(title, fontweight="bold")
    fig.tight_layout()
    fig.savefig(out_path, dpi=180, bbox_inches="tight")
    plt.close(fig)


def main() -> None:
    parser = argparse.ArgumentParser(description="Plot benchmark outputs into PNG files.")
    parser.add_argument("--kernel", required=True, type=pathlib.Path, help="Path to kernel-delta-test output")
    parser.add_argument(
        "--per-language", required=True, type=pathlib.Path, help="Path to per-language-benchmark output"
    )
    parser.add_argument("--out-dir", required=True, type=pathlib.Path, help="Output directory for PNG plots")
    args = parser.parse_args()

    style()
    args.out_dir.mkdir(parents=True, exist_ok=True)

    per_language = parse_per_language(args.per_language)
    kernel = parse_kernel(args.kernel)

    plot_per_language(per_language, args.out_dir / "benchmark_per_language.png")
    plot_kernel_metric(
        kernel,
        metric_index=1,
        ylabel="Delta ratio (%)",
        title="Extended Kernel Benchmark: Compression Ratio",
        out_path=args.out_dir / "benchmark_kernel_ratios.png",
    )
    plot_kernel_metric(
        kernel,
        metric_index=2,
        ylabel="Encode time (s)",
        title="Extended Kernel Benchmark: Encode Time",
        out_path=args.out_dir / "benchmark_kernel_times.png",
    )


if __name__ == "__main__":
    main()
