"""
PHASE 01 — Exploration
Analyses type-count and structural-feature distributions across
Java, Python, and C++ extracted repositories. Produces figures
and a summary CSV used by Phase 02 normalisation.
"""
import csv, json, random, sys
from pathlib import Path
from collections import defaultdict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import numpy as np

ROOT   = Path(__file__).resolve().parent.parent.parent
OUTPUT = ROOT / "analysis-output"
FIGDIR = Path(__file__).resolve().parent / "figures"
FIGDIR.mkdir(exist_ok=True)
CSVOUT = Path(__file__).resolve().parent / "exploration_summary.csv"

plt.rcParams.update({
    "font.family": "serif", "font.size": 9,
    "axes.titlesize": 10, "axes.labelsize": 9,
    "figure.dpi": 150, "savefig.dpi": 300,
    "savefig.bbox": "tight",
})

LANG_INFO = {
    "Java":   {"dir": "java",   "colour": "#2b83ba"},
    "Python": {"dir": "python", "colour": "#abdda4"},
    "C++":    {"dir": "cpp",    "colour": "#fdae61"},
}


def collect_repos(lang_key: str, lang_dir: Path, max_sample=300):
    """Walk language output directory, read extraction.json if present."""
    repos = []
    dirs = [d for d in lang_dir.iterdir() if d.is_dir()]
    if len(dirs) > max_sample:
        dirs = random.sample(dirs, max_sample)

    for d in dirs:
        jf = d / "extraction.json"
        if not jf.exists():
            continue
        try:
            data = json.loads(jf.read_text(encoding="utf-8"))
        except Exception:
            continue
        typs = data.get("types", [])
        tc = len(typs)
        exec_count = 0
        field_count = 0
        iface_count = 0
        abstract_count = 0
        has_super = 0
        has_interfaces = 0

        for t in typs:
            k = t.get("kind", "class")
            if k == "interface":
                iface_count += 1
            if t.get("abstractType") is True or t.get("abstractType") == "true":
                abstract_count += 1
            if t.get("superClass"):
                has_super += 1
            if t.get("interfaces"):
                has_interfaces += 1

            exs = t.get("executables") or t.get("methods") or []
            exec_count += len(exs)
            fds = t.get("fields") or []
            field_count += len(fds)

        repos.append({
            "repo":     d.name,
            "lang":     lang_key,
            "typeCount":           tc,
            "executableCount":     exec_count,
            "fieldCount":          field_count,
            "interfaceCount":      iface_count,
            "abstractCount":       abstract_count,
            "typesWithSuper":      has_super,
            "typesWithInterfaces": has_interfaces,
        })
    return repos


def main():
    all_repos = []
    print("[PHASE 01] Exploration — collecting extraction data...")
    for lang, cfg in LANG_INFO.items():
        ld = OUTPUT / cfg["dir"]
        if not ld.exists():
            print(f"  SKIP {lang}: directory not found ({ld})")
            continue
        repos = collect_repos(lang, ld)
        print(f"  {lang}: {len(repos)} repos analysed")
        all_repos.extend(repos)

    if not all_repos:
        print("  No data found. Aborting.")
        return

    # ---- FIGURE 1: Type count distributions (violin + box) ----
    fig, axes = plt.subplots(1, 2, figsize=(10, 4))
    langs = list(LANG_INFO)
    tc_data = {}
    for lang in langs:
        vals = [r["typeCount"] for r in all_repos if r["lang"] == lang]
        tc_data[lang] = vals

    # Left: log-scale violin
    positions = [1, 2, 3]
    vp = axes[0].violinplot(
        [np.log1p(tc_data[l]) for l in langs],
        positions=positions, showmeans=True, showmedians=True,
    )
    for i, (lang, body) in enumerate(zip(langs, vp["bodies"])):
        body.set_facecolor(LANG_INFO[lang]["colour"])
        body.set_alpha(0.7)
    axes[0].set_xticks(positions)
    axes[0].set_xticklabels(langs)
    axes[0].set_ylabel("log(1 + type count)")
    axes[0].set_title("Type count distribution (log scale)")

    # Right: cumulative distribution
    for lang in langs:
        vals = sorted(tc_data[lang])
        y = np.arange(1, len(vals) + 1) / len(vals) * 100
        axes[1].plot(vals, y, color=LANG_INFO[lang]["colour"], linewidth=1.5,
                     label=f"{lang} (n={len(vals)})")
    axes[1].set_xlabel("Type count")
    axes[1].set_ylabel("Cumulative %")
    axes[1].set_title("Cumulative type-count distribution")
    axes[1].legend(fontsize=7)
    axes[1].set_xscale("log")
    fig.tight_layout()
    fig.savefig(FIGDIR / "exp_01_type_distribution.pdf")
    fig.savefig(FIGDIR / "exp_01_type_distribution.png")
    plt.close(fig)

    # ---- FIGURE 2: Structural proportions ----
    fig, axes = plt.subplots(1, 3, figsize=(10, 3.5))
    metrics = [
        ("executableCount", "Executables"),
        ("fieldCount", "Fields"),
        ("interfaceCount", "Interfaces"),
    ]
    for ax, (key, title) in zip(axes, metrics):
        data_langs = []
        for lang in langs:
            vals = [r[key] for r in all_repos if r["lang"] == lang]
            data_langs.append(vals)
        bp = ax.boxplot(data_langs, labels=langs, patch_artist=True,
                         showfliers=False, widths=0.5)
        for i, (lang, patch) in enumerate(zip(langs, bp["boxes"])):
            patch.set_facecolor(LANG_INFO[lang]["colour"])
            patch.set_alpha(0.7)
        ax.set_ylabel("Count per repo")
        ax.set_title(title)
        ax.set_yscale("log")
    fig.tight_layout()
    fig.savefig(FIGDIR / "exp_02_structural_features.pdf")
    fig.savefig(FIGDIR / "exp_02_structural_features.png")
    plt.close(fig)

    # ---- FIGURE 3: Inheritance depth proxy ----
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.5))
    for ax, (key, title) in zip(axes, [
        ("typesWithSuper", "Types with superClass"),
        ("typesWithInterfaces", "Types with interfaces"),
    ]):
        data_langs = []
        for lang in langs:
            vals = [r[key] / max(1, r["typeCount"]) * 100
                    for r in all_repos if r["lang"] == lang]
            data_langs.append(vals)
        bp = ax.boxplot(data_langs, labels=langs, patch_artist=True,
                         showfliers=False, widths=0.5)
        for i, (lang, patch) in enumerate(zip(langs, bp["boxes"])):
            patch.set_facecolor(LANG_INFO[lang]["colour"])
            patch.set_alpha(0.7)
        ax.set_ylabel("% of types")
        ax.set_title(title)
    fig.tight_layout()
    fig.savefig(FIGDIR / "exp_03_inheritance_proxy.pdf")
    fig.savefig(FIGDIR / "exp_03_inheritance_proxy.png")
    plt.close(fig)

    # ---- CSV Summary ----
    with open(CSVOUT, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=[
            "repo", "lang", "typeCount", "executableCount", "fieldCount",
            "interfaceCount", "abstractCount", "typesWithSuper", "typesWithInterfaces",
        ])
        writer.writeheader()
        writer.writerows(all_repos)

    # ---- Console Report ----
    print("\n" + "=" * 65)
    print("  EXPLORATION SUMMARY")
    print("=" * 65)
    for lang in langs:
        vals = tc_data[lang]
        arr = np.array(vals)
        p25 = int(np.percentile(arr, 25))
        p75 = int(np.percentile(arr, 75))
        iqr = p75 - p25
        lo = max(0, p25 - iqr)
        hi = p75 + iqr
        exec_vals = [r["executableCount"] for r in all_repos if r["lang"] == lang]
        field_vals = [r["fieldCount"] for r in all_repos if r["lang"] == lang]
        has_sup = [r["typesWithSuper"] for r in all_repos if r["lang"] == lang]
        sup_pct = np.mean([s / max(1, r["typeCount"]) * 100
                           for s, r in zip(has_sup, [r for r in all_repos if r["lang"] == lang])])
        print(f"\n  {lang}: n={len(arr)}")
        print(f"    Types:       median={np.median(arr):.0f}  mean={arr.mean():.0f}  "
              f"P25={p25}  P75={p75}  IQR={iqr}")
        print(f"    Executables: median={np.median(exec_vals):.0f}  mean={np.mean(exec_vals):.0f}")
        print(f"    Fields:      median={np.median(field_vals):.0f}  mean={np.mean(field_vals):.0f}")
        print(f"    With super:  {sup_pct:.1f}% of types")
        print(f"    Normalised interval: [{lo}, {hi}]")

    print(f"\n  Figures: {FIGDIR}")
    print(f"  CSV:     {CSVOUT}")
    print("=" * 65)


if __name__ == "__main__":
    main()
