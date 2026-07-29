"""Generate all figures needed for the Experimental Results section (Section VII).

Reads batch_results CSVs and selected_repos.csv to compute per-repo data.
Uses hard-coded per-invariant totals for the concentration figure.
Outputs all figures to the journal_paper_draft/figures/ directory.
"""

import csv
import os
import math
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker
import numpy as np
import squarify

# ────────── paths ──────────
EXPERIMENT_DIR = Path(r"E:\java-analysis-pipeline\experiment")
SELECTED_CSV = EXPERIMENT_DIR / "03_selection" / "selected_repos.csv"
BATCH_DIR = EXPERIMENT_DIR / "04_verification"
OUT_DIR = Path(r"E:\Foundational oo fomalisation\journal_paper_draft\figures")
OUT_DIR.mkdir(parents=True, exist_ok=True)

# ────────── colour palette ──────────
LANG_COLORS = {"Java": "#1f77b4", "Python": "#ff7f0e", "C++": "#2ca02c"}
LANG_ORDER = ["Java", "Python", "C++"]

# ────────── style ──────────
plt.rcParams.update({
    "font.family": "serif",
    "font.size": 10,
    "axes.titlesize": 11,
    "axes.labelsize": 10,
    "figure.dpi": 150,
    "savefig.bbox": "tight",
    "savefig.pad_inches": 0.05,
})


def wilson_ci(count, nobs, z=1.96):
    """Wilson 95% confidence interval for a proportion."""
    if nobs == 0:
        return (0.0, 0.0), (0.0, 0.0)
    p = count / nobs
    denom = 1 + z * z / nobs
    center = (p + z * z / (2 * nobs)) / denom
    margin = z * math.sqrt(p * (1 - p) / nobs + z * z / (4 * nobs * nobs)) / denom
    lo = max(0.0, center - margin)
    hi = min(1.0, center + margin)
    return lo, hi


# ════════════════════════════════════════════════════════════════════
# 1.  LOAD DATA
# ════════════════════════════════════════════════════════════════════

# Read selected repos (typeCount per repo)
selected = {}
with open(SELECTED_CSV, encoding="utf-8") as f:
    for row in csv.DictReader(f):
        key = row["repo"]  # e.g. "owner__name"
        selected[key] = {
            "lang": row["lang"],
            "typeCount": int(row["typeCount"]),
        }

# Read batch results (SAT/UNSAT and total violations per repo)
repos = []  # list of dicts
for lang_file, lang_label in [
    ("batch_results_java.csv", "Java"),
    ("batch_results_python.csv", "Python"),
    ("batch_results_c++.csv", "C++"),
]:
    fp = BATCH_DIR / lang_file
    with open(fp, encoding="utf-8") as f:
        for row in csv.DictReader(f):
            repo = row["repo"]
            if repo not in selected:
                continue
            result_str = row["result"]
            if result_str == "SAT":
                sat = True
                total_violations = 0
            else:
                # e.g. UNSAT(3) → 3
                sat = False
                total_violations = int(result_str.split("(")[1].rstrip(")"))
            info = selected[repo]
            rate = total_violations / info["typeCount"] if info["typeCount"] > 0 else 0
            repos.append({
                "repo": repo,
                "lang": lang_label,
                "typeCount": info["typeCount"],
                "totalViolations": total_violations,
                "rate": rate,
                "sat": sat,
            })

# Build per-language lists
by_lang = {lg: [r for r in repos if r["lang"] == lg] for lg in LANG_ORDER}
for lg in LANG_ORDER:
    print(f"{lg}: {len(by_lang[lg])} repos loaded, SAT={sum(1 for r in by_lang[lg] if r['sat'])}")

# SAT counts
sat_counts = {lg: sum(1 for r in by_lang[lg] if r["sat"]) for lg in LANG_ORDER}
n_counts = {lg: len(by_lang[lg]) for lg in LANG_ORDER}

# Rates per language
rates_by_lang = {lg: [r["rate"] for r in by_lang[lg]] for lg in LANG_ORDER}

# ════════════════════════════════════════════════════════════════════
# 2.  FIGURE: SAT RATES  (fig_sat_rates.pdf)
# ════════════════════════════════════════════════════════════════════

fig, ax = plt.subplots(figsize=(4.5, 3.2))
xs = np.arange(len(LANG_ORDER))
heights = []
errors = []  # as fractions
for i, lg in enumerate(LANG_ORDER):
    cnt = sat_counts[lg]
    nobs = n_counts[lg]
    lo, hi = wilson_ci(cnt, nobs)
    heights.append(cnt)
    errors.append((lo * nobs, hi * nobs))
    # Annotate each bar with the count
    ax.text(i, cnt + 1, str(cnt), ha="center", va="bottom", fontsize=9, fontweight="bold")

err_lo = np.array([e[0] for e in errors])
err_hi = np.array([e[1] for e in errors])
bars = ax.bar(xs, heights, color=[LANG_COLORS[lg] for lg in LANG_ORDER], width=0.55, edgecolor="white", linewidth=0.5)
ax.set_xticks(xs)
ax.set_xticklabels(LANG_ORDER)
ax.set_ylabel("Repositories returning SAT")
ax.set_ylim(0, max(heights) * 1.25)

# Error bars
for i in range(len(xs)):
    ax.plot([xs[i], xs[i]], [err_lo[i], err_hi[i]], "k-", linewidth=1.2)
    ax.plot([xs[i] - 0.08, xs[i] + 0.08], [err_lo[i], err_lo[i]], "k-", linewidth=1.2)
    ax.plot([xs[i] - 0.08, xs[i] + 0.08], [err_hi[i], err_hi[i]], "k-", linewidth=1.2)
    # Annotate SAT percentage
    pct = sat_counts[lg] / n_counts[lg] * 100
    ax.text(xs[i], err_hi[i] + 2, f"{pct:.1f}%", ha="center", fontsize=8, color="grey")

ax.spines["top"].set_visible(False)
ax.spines["right"].set_visible(False)
fig.tight_layout()
fig.savefig(OUT_DIR / "fig_sat_rates.pdf")
plt.close(fig)
print("OK fig_sat_rates.pdf")

# ════════════════════════════════════════════════════════════════════
# 3.  FIGURE: VIOLATION RATE DISTRIBUTION  (fig_violation_rate_distribution.pdf)
# ════════════════════════════════════════════════════════════════════

fig, ax = plt.subplots(figsize=(4.5, 3.2))
violin_data = [rates_by_lang[lg] for lg in LANG_ORDER]
parts = ax.violinplot(violin_data, positions=np.arange(len(LANG_ORDER)), showmeans=False, showmedians=True,
                      widths=0.55)
for i, pc in enumerate(parts["bodies"]):
    pc.set_facecolor(LANG_COLORS[LANG_ORDER[i]])
    pc.set_alpha(0.55)
parts["cmedians"].set_color("black")
parts["cmedians"].set_linewidth(1.2)

# Overlay strip plot
for i, lg in enumerate(LANG_ORDER):
    data = rates_by_lang[lg]
    jitter = np.random.default_rng(42).uniform(-0.12, 0.12, len(data))
    ax.scatter(np.full(len(data), i) + jitter, data, s=6, alpha=0.5, color=LANG_COLORS[lg], edgecolors="none")

ax.set_xticks(np.arange(len(LANG_ORDER)))
ax.set_xticklabels(LANG_ORDER)
ax.set_ylabel("Normalised violation rate  ($v / t$)")
ax.set_yscale("log")
ax.set_ylim(bottom=0.0008)
ax.spines["top"].set_visible(False)
ax.spines["right"].set_visible(False)
fig.tight_layout()
fig.savefig(OUT_DIR / "fig_violation_rate_distribution.pdf")
plt.close(fig)
print("OK fig_violation_rate_distribution.pdf")

# ════════════════════════════════════════════════════════════════════
# 4.  FIGURE: VIOLATION CONCENTRATION TREEMAP  (fig_violation_concentration.pdf)
# ════════════════════════════════════════════════════════════════════

# Hard-coded per-invariant totals (from the verified table)
invariants = [
    ("AbstractionPolicy", 7551),
    ("LocalMethodNamespace", 2605),
    ("InterfacePolicy", 1572),
    ("IdentifierIntegrity", 11),
    ("AcyclicGeneralization", 9),
    ("GeneralizationKindPolicy", 8),
    ("StaticMethodPolicy", 5),
    ("OverridePolicy", 0),
    ("ExclDeclOwnership", 0),
    ("InheritedConflict", 0),
    ("InheritedMethodView", 0),
    ("InheritedAttributeView", 0),
    ("LocalInheritedSep", 0),
    ("ImplementationBind", 0),
    ("ParamWellFormed", 0),
]

# Group tiny invariants into "Other" for cleaner treemap
big = [("AbstractionPolicy", 7551),
       ("LocalMethodNamespace", 2605),
       ("InterfacePolicy", 1572)]
small = [("IdentifierIntegrity", 11),
         ("AcyclicGeneralization", 9),
         ("GeneralizationKindPolicy", 8),
         ("StaticMethodPolicy", 5)]
other_val = sum(v for _, v in small)
nonzero = big + [("Other (4\\,invariants)", other_val)]
zero_names = [name for name, val in invariants if val == 0]

fig, ax = plt.subplots(figsize=(5.5, 3.8))
values = [v for _, v in nonzero]
labels = [f"{n}\n({v:,})" for n, v in nonzero]
cmap = plt.cm.Blues_r
norm = plt.Normalize(min(values), max(values))
colors = [cmap(norm(v)) for v in values]

squarify.plot(sizes=values, label=labels, color=colors, alpha=0.85, edgecolor="white", linewidth=0.8, ax=ax,
              text_kwargs={"fontsize": 8, "fontweight": "bold"},
              pad=True)
ax.set_title(f"3 invariants = 99.6\\%  |  {len(zero_names)} invariants = 0 violations", fontsize=9, pad=8)
ax.axis("off")
fig.tight_layout(pad=0.5)
fig.savefig(OUT_DIR / "fig_violation_concentration.pdf")
plt.close(fig)
print("OK fig_violation_concentration.pdf")

# ════════════════════════════════════════════════════════════════════
# 5.  FIGURE: RESULTS PANEL  (fig_results_panel.pdf)
# ════════════════════════════════════════════════════════════════════

fig, axes = plt.subplots(1, 3, figsize=(9.5, 3.2))

# --- panel (a): SAT rates ---
ax = axes[0]
xs = np.arange(len(LANG_ORDER))
heights = [sat_counts[lg] for lg in LANG_ORDER]
bars = ax.bar(xs, heights, color=[LANG_COLORS[lg] for lg in LANG_ORDER], width=0.55, edgecolor="white", linewidth=0.5)
for i, lg in enumerate(LANG_ORDER):
    cnt = sat_counts[lg]
    nobs = n_counts[lg]
    lo, hi = wilson_ci(cnt, nobs)
    ax.plot([xs[i], xs[i]], [lo * nobs, hi * nobs], "k-", linewidth=1.0)
    ax.plot([xs[i] - 0.06, xs[i] + 0.06], [lo * nobs, lo * nobs], "k-", linewidth=1.0)
    ax.plot([xs[i] - 0.06, xs[i] + 0.06], [hi * nobs, hi * nobs], "k-", linewidth=1.0)
    pct = cnt / nobs * 100
    ax.text(xs[i], hi * nobs + 1.5, f"{cnt}\n({pct:.1f}%)", ha="center", fontsize=7.5, color="grey")
ax.set_xticks(xs)
ax.set_xticklabels(LANG_ORDER, fontsize=8)
ax.set_ylabel("SAT repos", fontsize=8)
ax.set_title("(a) SAT rates", fontsize=9, fontweight="bold")
ax.spines["top"].set_visible(False)
ax.spines["right"].set_visible(False)

# --- panel (b): violation rate distribution ---
ax = axes[1]
violin_data = [rates_by_lang[lg] for lg in LANG_ORDER]
parts = ax.violinplot(violin_data, positions=np.arange(len(LANG_ORDER)), showmeans=False, showmedians=True,
                      widths=0.55)
for i, pc in enumerate(parts["bodies"]):
    pc.set_facecolor(LANG_COLORS[LANG_ORDER[i]])
    pc.set_alpha(0.55)
parts["cmedians"].set_color("black")
parts["cmedians"].set_linewidth(1.0)
for i, lg in enumerate(LANG_ORDER):
    data = rates_by_lang[lg]
    jitter = np.random.default_rng(42).uniform(-0.12, 0.12, len(data))
    ax.scatter(np.full(len(data), i) + jitter, data, s=5, alpha=0.45, color=LANG_COLORS[lg], edgecolors="none")
ax.set_xticks(np.arange(len(LANG_ORDER)))
ax.set_xticklabels(LANG_ORDER, fontsize=8)
ax.set_ylabel("Violation rate ($v/t$)", fontsize=8)
ax.set_yscale("log")
ax.set_ylim(bottom=0.0008)
ax.set_title("(b) Violation rate distributions", fontsize=9, fontweight="bold")
ax.spines["top"].set_visible(False)
ax.spines["right"].set_visible(False)

# --- panel (c): violation concentration treemap ---
ax = axes[2]
cmap = plt.cm.Blues_r
norm = plt.Normalize(min(values), max(values))
colors = [cmap(norm(v)) for v in values]
short_labels = [f"{n}\n({v:,})" for n, v in nonzero]
squarify.plot(sizes=values, label=short_labels, color=colors, alpha=0.85, edgecolor="white", linewidth=0.5, ax=ax,
              text_kwargs={"fontsize": 6.5, "fontweight": "bold"})
ax.set_title(f"(c) Violation concentration\n(3 of 15 = 99.6%)", fontsize=9, fontweight="bold")
ax.axis("off")

fig.tight_layout(pad=1.0)
fig.savefig(OUT_DIR / "fig_results_panel.pdf")
plt.close(fig)
print("OK fig_results_panel.pdf")

print("\nAll figures generated.")
