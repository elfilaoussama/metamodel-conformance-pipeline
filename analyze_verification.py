#!/usr/bin/env python3
"""
Analyze verification CSV results and produce statistical diagrams.

Usage:
    python analyze_verification.py <csv-file> [--output-dir plots]

Expected CSV columns:
    Repository, Result, Constraint, Line, Description

The script auto-detects column names (case-insensitive) and generates
a set of PNG charts + a console summary.
"""

import sys
import argparse
from pathlib import Path

import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import seaborn as sns
import numpy as np

sns.set_theme(style="whitegrid")
plt.rcParams.update({"figure.max_open_warning": 0})


# ── helpers ──────────────────────────────────────────────────────────

def _coerce_numeric(col: pd.Series) -> pd.Series:
    """Try to convert a column to numeric, coercing errors to NaN."""
    return pd.to_numeric(col, errors="coerce")


def _shorten(text: str, width: int = 50) -> str:
    """Truncate long strings for display."""
    if not isinstance(text, str):
        return str(text)
    return text if len(text) <= width else text[: width - 3] + "..."


# ── column detection ──────────────────────────────────────────────────

_COLUMN_ALIASES = {
    "repository": ("repository", "repo", "repo_name", "name", "url"),
    "result": ("result", "status", "outcome", "verdict"),
    "constraint": ("constraint", "invariant", "invariant_name", "invariantname",
                   "rule", "check"),
    "line": ("line", "line_number", "lineno", "linenumber"),
    "description": ("description", "desc", "message", "detail", "violation"),
}

def _detect_column(df: pd.DataFrame, role: str) -> str | None:
    for alias in _COLUMN_ALIASES[role]:
        for col in df.columns:
            if col.strip().lower() == alias.lower():
                return col
    return None


# ── charting ──────────────────────────────────────────────────────────

def plot_result_distribution(df: pd.DataFrame, col: str, output: Path):
    """Pie + bar chart for SAT / UNSAT / ERROR distribution."""
    counts = df[col].value_counts()
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(10, 4.5))

    colors = {"SAT": "#2ecc71", "UNSAT": "#e74c3c", "ERROR": "#f39c12"}
    palette = [colors.get(k, "#95a5a6") for k in counts.index]

    wedges, texts, autotexts = ax1.pie(
        counts.values, labels=None, autopct="%1.1f%%",
        startangle=90, colors=palette, wedgeprops={"edgecolor": "white", "linewidth": 1.2},
    )
    ax1.legend(
        wedges, [f"{k}  ({v})" for k, v in counts.items()],
        title="Result", loc="center left", bbox_to_anchor=(1, 0, 0.5, 1),
    )
    ax1.set_title("Result Distribution", fontweight="bold")

    bars = ax2.bar(counts.index, counts.values, color=palette, edgecolor="white", linewidth=1.2)
    ax2.bar_label(bars, fmt="%d", padding=2, fontweight="bold")
    ax2.set_ylabel("Count")
    ax2.set_title("Result Counts", fontweight="bold")
    sns.despine(ax=ax2)

    fig.tight_layout()
    fig.savefig(output / "01_result_distribution.png", dpi=150, bbox_inches="tight")
    plt.close(fig)


def plot_top_constraints(df: pd.DataFrame, col: str, output: Path, top_n: int = 15):
    """Horizontal bar of most frequently violated constraints."""
    non_empty = df[df[col].notna() & (df[col].astype(str).str.strip() != "")]
    counts = non_empty[col].value_counts().head(top_n)
    if counts.empty:
        return

    labels = [_shorten(l, 45) for l in counts.index]

    fig, ax = plt.subplots(figsize=(10, max(4, len(counts) * 0.35)))
    bars = ax.barh(labels[::-1], counts.values[::-1],
                   color=sns.color_palette("viridis", len(counts)), edgecolor="white")
    ax.bar_label(bars, fmt="%d", padding=2)
    ax.set_xlabel("Violation count")
    ax.set_title(f"Top {min(top_n, len(counts))} Most Violated Constraints", fontweight="bold")
    sns.despine(ax=ax, left=True)
    fig.tight_layout()
    fig.savefig(output / "02_top_constraints.png", dpi=150, bbox_inches="tight")
    plt.close(fig)


def plot_violations_per_repo(df: pd.DataFrame, repo_col: str, result_col: str,
                              output: Path, top_n: int = 20):
    """Bar chart: repositories with the most UNSAT entries."""
    unsat = df[df[result_col].astype(str).str.upper().isin(["UNSAT", "FALSE", "VIOLATION"])]
    counts = unsat[repo_col].value_counts().head(top_n)
    if counts.empty:
        return

    labels = [_shorten(l, 35) for l in counts.index]

    fig, ax = plt.subplots(figsize=(10, max(4, len(counts) * 0.35)))
    bars = ax.barh(labels[::-1], counts.values[::-1],
                   color=sns.color_palette("rocket_r", len(counts)), edgecolor="white")
    ax.bar_label(bars, fmt="%d", padding=2)
    ax.set_xlabel("Violation count")
    ax.set_title(f"Top {min(top_n, len(counts))} Repositories by Violations", fontweight="bold")
    sns.despine(ax=ax, left=True)
    fig.tight_layout()
    fig.savefig(output / "03_violations_per_repo.png", dpi=150, bbox_inches="tight")
    plt.close(fig)


def plot_constraint_cooccurrence(df: pd.DataFrame, constraint_col: str,
                                   result_col: str, output: Path):
    """Heatmap: co-occurrence matrix of constraints within the same repository."""
    unsat = df[df[result_col].astype(str).str.upper().isin(["UNSAT", "FALSE", "VIOLATION"])]
    if unsat.empty:
        return

    repo_groups = unsat.groupby(_detect_column(unsat, "repository"))[constraint_col] \
        .apply(lambda x: [c for c in x if pd.notna(c) and str(c).strip()])
    repo_groups = repo_groups[repo_groups.map(len) > 0]

    all_constraints = sorted(set(c for group in repo_groups for c in group))
    if len(all_constraints) < 2:
        return
    if len(all_constraints) > 30:
        top = unsat[constraint_col].value_counts().head(30).index
        all_constraints = sorted(top)
        repo_groups = repo_groups.map(lambda g: [c for c in g if c in all_constraints])

    n = len(all_constraints)
    matrix = np.zeros((n, n), dtype=int)
    for group in repo_groups:
        for i, ci in enumerate(group):
            for cj in group[i:]:
                idx_i = all_constraints.index(ci)
                idx_j = all_constraints.index(cj)
                matrix[idx_i][idx_j] += 1
                if idx_i != idx_j:
                    matrix[idx_j][idx_i] += 1

    mask = np.triu(np.ones_like(matrix, dtype=bool), k=1)
    labels = [_shorten(c, 20) for c in all_constraints]

    fig, ax = plt.subplots(figsize=(max(7, n * 0.55), max(6, n * 0.5)))
    sns.heatmap(matrix, mask=mask, annot=n <= 20, fmt="d", cmap="YlOrRd",
                xticklabels=labels, yticklabels=labels, ax=ax, linewidths=0.5,
                cbar_kws={"shrink": 0.75, "label": "Co-occurrences"})
    ax.set_title("Constraint Co-occurrence Matrix", fontweight="bold")
    fig.tight_layout()
    fig.savefig(output / "04_constraint_cooccurrence.png", dpi=150, bbox_inches="tight")
    plt.close(fig)


def plot_result_by_constraint(df: pd.DataFrame, constraint_col: str,
                               result_col: str, output: Path, top_n: int = 15):
    """Stacked bar: SAT / UNSAT breakdown per constraint."""
    non_empty = df[df[constraint_col].notna() & (df[constraint_col].astype(str).str.strip() != "")]
    top = non_empty[constraint_col].value_counts().head(top_n).index
    subset = non_empty[non_empty[constraint_col].isin(top)]
    ctab = pd.crosstab(subset[constraint_col], subset[result_col].apply(
        lambda x: str(x).upper() if pd.notna(x) else "UNKNOWN"))

    sat_cols = [c for c in ["SAT", "TRUE", "PASS"] if c in ctab.columns]
    unsat_cols = [c for c in ["UNSAT", "FALSE", "FAIL", "VIOLATION"] if c in ctab.columns]

    labels = [_shorten(c, 40) for c in ctab.index]

    fig, ax = plt.subplots(figsize=(10, max(4, len(ctab) * 0.4)))

    y_pos = np.arange(len(ctab))
    bar_height = 0.6

    if sat_cols:
        sat_vals = ctab[sat_cols].sum(axis=1).values
        ax.barh(y_pos, sat_vals, bar_height, label="SAT", color="#2ecc71", edgecolor="white")
    else:
        sat_vals = np.zeros(len(ctab))

    if unsat_cols:
        unsat_vals = ctab[unsat_cols].sum(axis=1).values
        ax.barh(y_pos, unsat_vals, bar_height, left=sat_vals,
                label="UNSAT", color="#e74c3c", edgecolor="white")

    ax.set_yticks(y_pos)
    ax.set_yticklabels(labels[::-1])
    ax.set_xlabel("Count")
    ax.set_title("Result Breakdown per Constraint", fontweight="bold")
    ax.legend()
    sns.despine(ax=ax, left=True)
    fig.tight_layout()
    fig.savefig(output / "05_result_by_constraint.png", dpi=150, bbox_inches="tight")
    plt.close(fig)


def plot_weekly_trend(df: pd.DataFrame, repo_col: str, output: Path):
    """Dummy timeline: repos processed per bucket (uses row order as proxy)."""
    total = len(df)
    if total < 5:
        return
    bucket_size = max(1, total // 20)
    buckets = [df.iloc[i: i + bucket_size] for i in range(0, total, bucket_size)]
    x = range(len(buckets))
    unsat_counts = []
    sat_counts = []

    result_col = _detect_column(df, "result")
    for bucket in buckets:
        if result_col:
            unsat = bucket[result_col].astype(str).str.upper().isin(["UNSAT", "FALSE", "VIOLATION"]).sum()
        else:
            unsat = 0
        sat = len(bucket) - unsat
        unsat_counts.append(unsat)
        sat_counts.append(sat)

    fig, ax = plt.subplots(figsize=(10, 4))
    ax.fill_between(x, sat_counts, alpha=0.5, color="#2ecc71", label="SAT")
    ax.fill_between(x, [s + u for s, u in zip(sat_counts, unsat_counts)],
                    sat_counts, alpha=0.5, color="#e74c3c", label="UNSAT")
    ax.plot(x, [s + u for s, u in zip(sat_counts, unsat_counts)],
            color="#2c3e50", linewidth=1.2, marker=".", markersize=4)
    ax.set_xlabel("Batch (sequential)")
    ax.set_ylabel("Entries per batch")
    ax.set_title("Result Trend Across Dataset", fontweight="bold")
    ax.legend()
    sns.despine(ax=ax)
    fig.tight_layout()
    fig.savefig(output / "06_trend.png", dpi=150, bbox_inches="tight")
    plt.close(fig)


# ── main ──────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Analyze verification CSV results and produce statistical diagrams.",
    )
    parser.add_argument("csv", type=str, help="Path to the verification CSV file")
    parser.add_argument("--output-dir", "-o", type=str, default="plots",
                        help="Directory to save PNG charts (default: plots/)")
    args = parser.parse_args()

    csv_path = Path(args.csv)
    if not csv_path.is_file():
        print(f"Error: file not found: {csv_path}", file=sys.stderr)
        sys.exit(1)

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    # ── load ──────────────────────────────────────────────────────────
    try:
        df = pd.read_csv(csv_path, dtype=str, keep_default_na=False)
    except Exception as e:
        print(f"Error reading CSV: {e}", file=sys.stderr)
        sys.exit(1)

    df.columns = [c.strip() for c in df.columns]
    df.replace("", pd.NA, inplace=True)
    print(f"Loaded {len(df)} rows, {len(df.columns)} columns: {list(df.columns)}\n")

    # ── detect columns ────────────────────────────────────────────────
    repo_col = _detect_column(df, "repository")
    result_col = _detect_column(df, "result")
    constraint_col = _detect_column(df, "constraint")
    line_col = _detect_column(df, "line")
    desc_col = _detect_column(df, "description")

    print("Detected columns:")
    print(f"  Repository  -> {repo_col}")
    print(f"  Result      -> {result_col}")
    print(f"  Constraint  -> {constraint_col}")
    print(f"  Line        -> {line_col}")
    print(f"  Description -> {desc_col}")

    if result_col:
        # normalize result strings
        df[result_col] = df[result_col].astype(str).str.upper().str.strip()

    # ── console summary ───────────────────────────────────────────────
    print("\n" + "=" * 55)
    print("SUMMARY STATISTICS")
    print("=" * 55)

    if result_col:
        counts = df[result_col].value_counts()
        print(f"\nResult distribution:")
        for k, v in counts.items():
            print(f"  {k:12s}  {v:5d}  ({v / len(df) * 100:5.1f}%)")

    if repo_col:
        unique_repos = df[repo_col].nunique()
        print(f"\nUnique repositories: {unique_repos}")
        if result_col:
            repo_summary = df.groupby(repo_col)[result_col].first().value_counts()
            print("Repository-level results:")
            for k, v in repo_summary.items():
                print(f"  {k:12s}  {v:5d}")

    if constraint_col:
        non_empty = df[constraint_col].notna() & (df[constraint_col].astype(str).str.strip() != "")
        print(f"\nEntries with named constraint: {non_empty.sum()}")
        if non_empty.any():
            print("Most frequent constraints:")
            for k, v in df.loc[non_empty, constraint_col].value_counts().head(10).items():
                print(f"  {_shorten(str(k), 45):48s}  {v:5d}")

    if line_col:
        numeric = _coerce_numeric(df[line_col])
        valid = numeric.notna()
        if valid.any():
            print(f"\nLine references: {valid.sum()} entries, "
                  f"range {numeric.min():.0f}–{numeric.max():.0f}")

    if desc_col:
        non_empty_desc = df[desc_col].notna() & (df[desc_col].astype(str).str.strip() != "")
        print(f"\nEntries with description: {non_empty_desc.sum()}")

    print("=" * 55)

    # ── generate plots ────────────────────────────────────────────────
    print(f"\nGenerating plots -> {output_dir}/")

    if result_col:
        plot_result_distribution(df, result_col, output_dir)
        print("  [1/6] Result distribution")

    if constraint_col and result_col:
        plot_top_constraints(df, constraint_col, output_dir)
        print("  [2/6] Top violated constraints")

    if repo_col and result_col:
        plot_violations_per_repo(df, repo_col, result_col, output_dir)
        print("  [3/6] Violations per repository")

    if constraint_col and result_col and repo_col:
        plot_constraint_cooccurrence(df, constraint_col, result_col, output_dir)
        print("  [4/6] Constraint co-occurrence matrix")

    if constraint_col and result_col:
        plot_result_by_constraint(df, constraint_col, result_col, output_dir)
        print("  [5/6] Result breakdown per constraint")

    if repo_col:
        plot_weekly_trend(df, repo_col, output_dir)
        print("  [6/6] Result trend")

    count = len(list(output_dir.glob('*.png')))
    print(f"\nDone - {count} charts saved to {output_dir}/")


if __name__ == "__main__":
    main()
