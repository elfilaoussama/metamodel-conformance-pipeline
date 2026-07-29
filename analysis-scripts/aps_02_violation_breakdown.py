"""
APS_02_violation_breakdown.py
Analysis Pipeline Script 02: Per-construct violation breakdown from Java
verification-report.csv files. Aggregates violation counts by invariant and
produces stacked/grouped bar charts.
"""
import csv, json, os, sys
from pathlib import Path
from collections import Counter, defaultdict
import matplotlib.pyplot as plt
import numpy as np

OUTPUT_DIR = Path(__file__).resolve().parent
JAVA_DIR = OUTPUT_DIR.parent / "analysis-output" / "java"
FIG_DIR = OUTPUT_DIR / "figures"
FIG_DIR.mkdir(exist_ok=True)

plt.rcParams.update({
    "font.family": "serif", "font.size": 9,
    "axes.titlesize": 10, "axes.labelsize": 9,
    "figure.dpi": 150, "savefig.dpi": 300,
    "savefig.bbox": "tight"
})

TRUNCATE = {
    "AbstractionPolicy",
    "InterfacePolicy",
    "OverridePolicy",
    "InheritedConflictPolicy",
    "LocalNamespaces",
    "ExclusiveDeclarationOwnership",
    "IdentifierIntegrity",
    "AcyclicGeneralization",
    "GeneralizationKindPolicy",
    "InheritedMethodView",
    "InheritedAttributeView",
    "LocalInheritedSeparation",
    "ImplementationBindingPolicy",
    "DirectInstancePolicy",
    "ParameterPositionPolicy",
    "ParameterContiguity",
}


def collect_all_violations():
    per_repo = []
    global_counter = Counter()
    seen = set()

    for repo_dir in sorted(JAVA_DIR.iterdir()):
        if not repo_dir.is_dir():
            continue
        vf = repo_dir / "verification" / "verification-report.csv"
        if not vf.exists():
            continue
        rname = repo_dir.name
        if rname in seen:
            continue
        seen.add(rname)

        counts = Counter()
        with open(vf, newline="", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                inv = (row.get("InvariantName") or "").strip()
                if not inv:
                    continue
                counts[inv] += 1
                global_counter[inv] += 1

        per_repo.append({"repo": rname, "violations": dict(counts)})

    return per_repo, global_counter


def main():
    per_repo, global_counts = collect_all_violations()

    if not global_counts:
        print("No verification data found.")
        return

    top_n = 12
    top_invariants = [inv for inv, _ in global_counts.most_common(top_n)]

    # ---- FIGURE 1: Global violation distribution ----
    fig, ax = plt.subplots(figsize=(8, 4.5))
    names = top_invariants
    vals  = [global_counts[n] for n in names]
    colours = plt.cm.RdYlGn_r(np.linspace(0.15, 0.85, len(names)))
    bars = ax.barh(range(len(names)), vals, color=colours, edgecolor="white")
    ax.set_yticks(range(len(names)))
    ax.set_yticklabels(names, fontsize=8)
    ax.invert_yaxis()
    ax.set_xlabel("Total violations across all repos")
    ax.set_title(f"Violation Count by Invariant (Java, n={len(per_repo)} repos)")
    for i, (bar, v) in enumerate(zip(bars, vals)):
        ax.text(bar.get_width() + max(vals)*0.01, bar.get_y() + bar.get_height()/2,
                f"{v:,}", va="center", fontsize=7)
    fig.tight_layout()
    fig.savefig(FIG_DIR / "fig_04_violation_by_invariant.pdf")
    fig.savefig(FIG_DIR / "fig_04_violation_by_invariant.png")
    plt.close(fig)

    # ---- FIGURE 2: Per-repo heatmap data (top 30 repos by total violations) ----
    per_repo.sort(key=lambda x: sum(x["violations"].values()), reverse=True)
    top_repos = per_repo[:30]
    top_inv = top_invariants[:8]

    heatmap_data = []
    repo_labels = []
    for r in top_repos:
        short = r["repo"]
        if "__" in short:
            parts = short.split("__", 1)
            short = parts[1][:35] if len(parts) > 1 else short[:35]
        else:
            short = short[:35]
        repo_labels.append(short)
        row = [r["violations"].get(inv, 0) for inv in top_inv]
        heatmap_data.append(row)

    heatmap_data = np.array(heatmap_data, dtype=float)
    # log-scale for better visibility
    heatmap_data_log = np.log1p(heatmap_data)

    fig, ax = plt.subplots(figsize=(9, 7))
    im = ax.imshow(heatmap_data_log, aspect="auto", cmap="YlOrRd")
    ax.set_xticks(range(len(top_inv)))
    ax.set_xticklabels(top_inv, rotation=45, ha="right", fontsize=8)
    ax.set_yticks(range(len(repo_labels)))
    ax.set_yticklabels(repo_labels, fontsize=6.5)
    ax.set_title(f"Violation Heatmap (log scale) — Top 30 Java repos")
    cbar = fig.colorbar(im, ax=ax, shrink=0.78)
    cbar.set_label("log(1 + violations)", fontsize=8)
    fig.tight_layout()
    fig.savefig(FIG_DIR / "fig_05_violation_heatmap.pdf")
    fig.savefig(FIG_DIR / "fig_05_violation_heatmap.png")
    plt.close(fig)

    # ---- FIGURE 3: Repository-level violation intensity ----
    repo_totals = [sum(r["violations"].values()) for r in per_repo]
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.8))

    # Density plot
    ax = axes[0]
    log_vals = np.log1p(repo_totals)
    ax.hist(log_vals, bins=30, color="#5e9cd3", edgecolor="white", alpha=0.85)
    ax.set_xlabel("log(1 + total violations)")
    ax.set_ylabel("Repository count")
    ax.set_title("Repo-level violation intensity")

    # Cumulative distribution
    ax = axes[1]
    sorted_vals = np.sort(repo_totals)
    cumulative = np.arange(1, len(sorted_vals) + 1) / len(sorted_vals) * 100
    ax.plot(sorted_vals, cumulative, color="#d94f4f", linewidth=1.5)
    ax.set_xlabel("Total violations per repo")
    ax.set_ylabel("Cumulative % of repos")
    ax.set_title("Cumulative violation distribution")
    ax.axhline(50, color="gray", linestyle=":", linewidth=0.7)
    median_x = np.median(repo_totals)
    ax.axvline(median_x, color="gray", linestyle=":", linewidth=0.7)
    ax.text(median_x + 5, 52, f"Median={median_x:.0f}", fontsize=7)
    ax.set_xscale("log")

    fig.tight_layout()
    fig.savefig(FIG_DIR / "fig_06_repo_violation_intensity.pdf")
    fig.savefig(FIG_DIR / "fig_06_repo_violation_intensity.png")
    plt.close(fig)

    # ---- Console summary ----
    print("=" * 62)
    print("       VIOLATION BREAKDOWN BY INVARIANT (Java only)")
    print("=" * 62)
    print(f"  Repos with verification data: {len(per_repo)}")
    total_viol = sum(global_counts.values())
    print(f"  Total violations flagged: {total_viol:,}")
    print(f"\n  Top 10 invariants by violation count:")
    for rank, (inv, cnt) in enumerate(global_counts.most_common(10), 1):
        pct = cnt / total_viol * 100 if total_viol else 0
        print(f"    {rank:2d}. {inv:<40s} {cnt:>8,}  ({pct:5.1f}%)")

    repos_with_no_viol = sum(1 for r in per_repo if sum(r["violations"].values()) == 0)
    print(f"\n  Repos with zero violations: {repos_with_no_viol}")
    print(f"  Figures saved to: {FIG_DIR}")
    print("=" * 62)


if __name__ == "__main__":
    main()
