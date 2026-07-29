"""
PHASE 05 — Analysis
Reads verification-report.csv for every repo in selected_repos.csv,
computes per-construct normalised violation rates (violations / typeCount),
and produces cross-language comparison figures.
"""
import csv, json
from pathlib import Path
from collections import defaultdict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

ROOT      = Path(__file__).resolve().parent.parent.parent
ANALYSIS  = ROOT / "analysis-output"
SELECTION = Path(__file__).resolve().parent.parent / "03_selection" / "selected_repos.csv"
PARAMS    = Path(__file__).resolve().parent.parent / "02_normalisation" / "normalisation_params.json"
FIGDIR    = Path(__file__).resolve().parent / "figures"
FIGDIR.mkdir(exist_ok=True)

plt.rcParams.update({
    "font.family": "serif", "font.size": 9,
    "axes.titlesize": 10, "axes.labelsize": 9,
    "figure.dpi": 150, "savefig.dpi": 300,
    "savefig.bbox": "tight",
})

LANG_COLOURS = {"Java": "#2b83ba", "Python": "#abdda4", "C++": "#fdae61"}
LANG_DIRS    = {"Java": "java", "Python": "python", "C++": "cpp"}

INVARIANT_DISPLAY = {
    "AbstractionPolicy": "Abstraction",
    "LocalMethodNamespace": "Local NS",
    "InterfacePolicy": "Interface",
    "ImplementationBindingPolicy": "Impl Binding",
    "IdentifierIntegrity": "ID Integrity",
    "InheritedConflictPolicy": "Inher. Conflict",
    "OverridePolicy": "Override",
    "AcyclicGeneralization": "Acyclic Gen.",
    "GeneralizationKindPolicy": "Gen. Kind",
    "InheritedMemberDerivation": "Inher. Deriv.",
    "LocalInheritedSeparation": "Local/Inher.",
    "ExclusiveDeclarationOwnership": "Excl. Owner.",
}


def load_selection():
    with open(SELECTION, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def read_verification(lang: str, repo: str):
    vf = ANALYSIS / LANG_DIRS[lang] / repo / "verification" / "verification-report.csv"
    if not vf.exists():
        return None
    counts = defaultdict(int)
    with open(vf, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            inv = (row.get("InvariantName") or "").strip()
            if inv:
                counts[inv] += 1
    return dict(counts)


def main():
    selection = load_selection()

    # Aggregate data: per language, per invariant
    lang_invariant_rates = defaultdict(lambda: defaultdict(list))
    per_repo_data = []

    verified = 0
    missing = 0

    for row in selection:
        lang = row["lang"]
        repo = row["repo"]
        tc  = int(row["typeCount"])
        v   = read_verification(lang, repo)

        if v is None:
            missing += 1
            continue
        verified += 1

        total_viol = sum(v.values())
        entry = {"repo": repo, "lang": lang, "typeCount": tc,
                 "totalViolations": total_viol, "rate": total_viol / max(1, tc)}
        entry.update(v)
        per_repo_data.append(entry)

        for inv, cnt in v.items():
            lang_invariant_rates[lang][inv].append(cnt / max(1, tc))

    print("=" * 65)
    print("  PHASE 05 — ANALYSIS")
    print("=" * 65)
    print(f"  Selected repos:  {len(selection)}")
    print(f"  Verified:        {verified}")
    print(f"  Missing data:    {missing}")
    langs = [l for l in ["Java", "Python", "C++"]
             if any(r["lang"] == l for r in per_repo_data)]

    if verified == 0:
        print("\n  No verification data available. Run Phase 04 first.")
        return

    # ---- FIGURE 1: Violation rate distributions ----
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.8))
    rate_data = {}
    for lang in langs:
        rates = [r["rate"] for r in per_repo_data if r["lang"] == lang]
        rate_data[lang] = rates

    # Left: box plot of rates
    bp = axes[0].boxplot(
        [rate_data[l] for l in langs],
        tick_labels=langs, patch_artist=True,
        showfliers=False, widths=0.5,
    )
    for i, (lang, patch) in enumerate(zip(langs, bp["boxes"])):
        patch.set_facecolor(LANG_COLOURS[lang])
        patch.set_alpha(0.8)
    axes[0].set_ylabel("Violation rate (viol / type)")
    axes[0].set_title("Violation rate per repo")

    # Right: cumulative distribution
    for lang in langs:
        vals = sorted(rate_data[lang])
        y = np.arange(1, len(vals) + 1) / len(vals) * 100
        axes[1].step(vals, y, color=LANG_COLOURS[lang], linewidth=1.5,
                     label=f"{lang} (n={len(vals)})", where="post")
    axes[1].set_xlabel("Violation rate (viol / type)")
    axes[1].set_ylabel("Cumulative %")
    axes[1].set_title("Cumulative rate distribution")
    axes[1].legend(fontsize=7)
    axes[1].set_xscale("log")
    fig.tight_layout()
    fig.savefig(FIGDIR / "anl_01_violation_rates.pdf")
    fig.savefig(FIGDIR / "anl_01_violation_rates.png")
    plt.close(fig)

    # ---- FIGURE 2: Per-construct violation rates ----
    all_invariants = set()
    for lang in langs:
        all_invariants.update(lang_invariant_rates[lang].keys())
    top_inv = sorted(all_invariants,
                     key=lambda inv: -max(
                         np.mean(lang_invariant_rates[l].get(inv, [0]))
                         for l in langs if lang_invariant_rates[l].get(inv)),
    )[:10]

    fig, ax = plt.subplots(figsize=(8, 4.5))
    x = np.arange(len(top_inv))
    w = 0.25
    for j, lang in enumerate(langs):
        means = [np.mean(lang_invariant_rates[lang].get(inv, [0])) for inv in top_inv]
        bars = ax.bar(x + j * w, means, w, color=LANG_COLOURS[lang], alpha=0.85,
                      label=lang, edgecolor="white")
    ax.set_xticks(x + w)
    display_names = [INVARIANT_DISPLAY.get(inv, inv) for inv in top_inv]
    ax.set_xticklabels(display_names, rotation=35, ha="right", fontsize=8)
    ax.set_ylabel("Mean violation rate (viol / type)")
    ax.set_title("Per-construct violation rates by language")
    ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(FIGDIR / "anl_02_per_invariant_rates.pdf")
    fig.savefig(FIGDIR / "anl_02_per_invariant_rates.png")
    plt.close(fig)

    # ---- FIGURE 3: Stratum comparison (if enough data) ----
    strata_data = defaultdict(lambda: defaultdict(list))
    for r in per_repo_data:
        if "stratum" in r:
            strata_data[r["lang"]][r.get("stratum", "unknown")].append(r["rate"])

    strata_langs = [l for l in langs if len(strata_data[l]) > 1]
    if len(strata_langs) >= 1:
        fig, axes = plt.subplots(1, len(strata_langs), figsize=(4 * len(strata_langs), 3.5))
        if len(strata_langs) == 1:
            axes = [axes]
        for ax, lang in zip(axes, strata_langs):
            strata = strata_data[lang]
            order = ["small", "medium", "large"]
            present = [s for s in order if s in strata]
            bp = ax.boxplot(
                [strata[s] for s in present],
                tick_labels=present, patch_artist=True,
                showfliers=False, widths=0.5,
            )
            for patch in bp["boxes"]:
                patch.set_facecolor(LANG_COLOURS[lang])
                patch.set_alpha(0.8)
            ax.set_ylabel("Violation rate")
            ax.set_title(f"{lang} by stratum")
        fig.tight_layout()
        fig.savefig(FIGDIR / "anl_03_stratum_comparison.pdf")
        fig.savefig(FIGDIR / "anl_03_stratum_comparison.png")
        plt.close(fig)

    # ---- CSV output ----
    csv_out = Path(__file__).resolve().parent / "analysis_results.csv"
    fields = ["repo", "lang", "typeCount", "totalViolations", "rate"] + sorted(all_invariants)
    with open(csv_out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        w.writeheader()
        w.writerows(per_repo_data)

    # Console summary
    print(f"\n  Per-language summary:")
    for lang in langs:
        rates = rate_data[lang]
        arr = np.array(rates)
        n_sat = sum(1 for r in per_repo_data
                    if r["lang"] == lang and r["totalViolations"] == 0)
        print(f"  {lang}: n={len(arr)}  SAT={n_sat}  median_rate={np.median(arr):.3f}  "
              f"mean_rate={arr.mean():.3f}")

    print(f"\n  Figures: {FIGDIR}")
    print(f"  CSV:     {csv_out}")


if __name__ == "__main__":
    main()
