"""Regenerate type_distribution.pdf from full corpus (all repos with extraction.json)."""
import json, sys
from pathlib import Path
from collections import defaultdict
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

ROOT = Path(__file__).resolve().parent.parent.parent
ANALYSIS = ROOT / "analysis-output"
PAPER_FIGDIR = Path(r"E:\Foundational oo fomalisation\journal_paper_draft\figures")
PAPER_FIGDIR.mkdir(exist_ok=True)

LANG_INFO = {"Java": "java", "Python": "python", "C++": "cpp"}
COLOURS = {"Java": "#2b83ba", "Python": "#abdda4", "C++": "#fdae61"}

plt.rcParams.update({
    "font.family": "serif", "font.size": 10,
    "axes.titlesize": 11, "axes.labelsize": 10,
    "figure.dpi": 150, "savefig.dpi": 300,
    "savefig.bbox": "tight",
})

data = {}
stats = {}

for lang, subdir in LANG_INFO.items():
    ld = ANALYSIS / subdir
    counts = []
    for d in ld.iterdir():
        if not d.is_dir(): continue
        jf = d / "extraction.json"
        if not jf.exists(): continue
        try:
            content = json.loads(jf.read_text(encoding="utf-8"))
        except Exception:
            continue
        tc = len(content.get("types", []))
        if tc > 0:
            counts.append(tc)
    data[lang] = counts
    arr = np.array(counts)
    stats[lang] = {
        "n": len(arr), "median": np.median(arr), "mean": np.mean(arr),
        "p25": np.percentile(arr, 25), "p75": np.percentile(arr, 75),
        "min": arr.min(), "max": arr.max(),
    }

# Print stats
for lang in ["Java", "Python", "C++"]:
    s = stats[lang]
    print(f"{lang}: n={s['n']}, median={s['median']:.0f}, mean={s['mean']:.1f}, "
          f"P25={s['p25']:.0f}, P75={s['p75']:.0f}, range=[{s['min']},{s['max']}]")

# Plot
fig, axes = plt.subplots(1, 3, figsize=(12, 3.5), sharey=False)

for idx, lang in enumerate(["Java", "Python", "C++"]):
    ax = axes[idx]
    vals = data[lang]
    s = stats[lang]
    ax.hist(vals, bins=30, color=COLOURS[lang], alpha=0.85, edgecolor="white", linewidth=0.5)
    ax.axvline(s["p25"], color="black", linestyle="--", linewidth=0.8, alpha=0.6)
    ax.axvline(s["p75"], color="black", linestyle="--", linewidth=0.8, alpha=0.6)
    ax.set_title(f"{lang} (n={s['n']})")
    ax.set_xlabel("Type count")
    if idx == 0:
        ax.set_ylabel("Repositories")
    ax.set_xscale("log")

plt.suptitle("Type-count distributions across the three languages (log scale)", y=1.01)
plt.tight_layout()
out = PAPER_FIGDIR / "type_distribution.pdf"
plt.savefig(out)
print(f"Saved: {out}")

# Also print the exact numbers for the paper
print("\n=== FOR PAPER ===")
for lang in ["Java", "Python", "C++"]:
    s = stats[lang]
    print(f"{lang}: n={s['n']}, P25={s['p25']:.0f}, P75={s['p75']:.0f}, median={s['median']:.0f}, range=[{s['min']},{s['max']}]")