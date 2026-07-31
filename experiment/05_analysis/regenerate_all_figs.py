"""Regenerate all empirical figures from batch verification results."""
import csv, json, sys
from pathlib import Path
from collections import defaultdict
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import numpy as np
from scipy import stats as scipy_stats

def wilson_ci(success, n, alpha=0.05):
    if n == 0: return 0, 1
    p = success / n
    z = scipy_stats.norm.ppf(1 - alpha / 2)
    denom = 1 + z**2 / n
    centre = (p + z**2 / (2 * n)) / denom
    margin = z * np.sqrt(p * (1 - p) / n + z**2 / (4 * n**2)) / denom
    return max(0, centre - margin), min(1, centre + margin)

PIPELINE = Path(r"E:\metamodel-conformance-pipeline")
EXPERIMENT = PIPELINE / "experiment" / "04_verification"
ANALYSIS = PIPELINE / "analysis-output"
SELECTION = PIPELINE / "experiment" / "03_selection" / "selected_repos.csv"
PAPER_FIGDIR = Path(r"E:\Foundational oo fomalisation\journal_paper_draft\figures")
PAPER_FIGDIR.mkdir(exist_ok=True)

LANG_DIRS = {"Java": "java", "Python": "python", "C++": "cpp"}
LANG_COLOURS = {"Java": "#2b83ba", "Python": "#abdda4", "C++": "#fdae61"}
LANG_ORDER = ["Java", "Python", "C++"]

plt.rcParams.update({
    "font.family": "serif", "font.size": 9,
    "axes.titlesize": 10, "axes.labelsize": 9,
    "figure.dpi": 150, "savefig.dpi": 300,
    "savefig.bbox": "tight",
})

# Load all results
results = {}
all_violations = {}  # lang -> {repo -> {invariant -> count}}
invariant_set = set()

with open(SELECTION, newline="", encoding="utf-8") as f:
    rows = list(csv.DictReader(f))

for row in rows:
    lang = row["lang"]
    repo = row["repo"]
    lang_dir = LANG_DIRS[lang]
    rp = ANALYSIS / lang_dir / repo / "verification" / "verification-report.json"
    if not rp.exists():
        continue
    try:
        data = json.loads(rp.read_text(encoding="utf-8"))
    except:
        continue
    result = data.get("result", "UNKNOWN")
    type_count = int(row["typeCount"])
    violations = len(data.get("violations", []))
    if lang not in results:
        results[lang] = []
        all_violations[lang] = {}
    results[lang].append({
        "repo": repo, "result": result, "violations": violations,
        "typeCount": type_count, "rate": violations / max(type_count, 1)
    })
    inv_counts = defaultdict(int)
    for v in data.get("violations", []):
        inv = v.get("invariantName", "Unknown")
        inv_counts[inv] += 1
        invariant_set.add(inv)
    all_violations[lang][repo] = inv_counts

# ---------------------------------------------------------------
# Figure 1: SAT Rates bar chart
# ---------------------------------------------------------------
fig, ax = plt.subplots(figsize=(5.5, 3.5))
sat_rates = []
sat_ci_low = []
sat_ci_high = []
for lang in LANG_ORDER:
    repos = results[lang]
    n = len(repos)
    sat = sum(1 for r in repos if r["result"] == "SAT")
    p = sat / n
    ci = wilson_ci(sat, n)
    sat_rates.append(p * 100)
    sat_ci_low.append(ci[0] * 100)
    sat_ci_high.append(ci[1] * 100)

x = np.arange(len(LANG_ORDER))
bars = ax.bar(x, sat_rates, color=[LANG_COLOURS[l] for l in LANG_ORDER], edgecolor="white")
yerr_lo = [s - l for s, l in zip(sat_rates, sat_ci_low)]
yerr_hi = [h - s for s, h in zip(sat_rates, sat_ci_high)]
ax.errorbar(x, sat_rates, yerr=[yerr_lo, yerr_hi], fmt="none", capsize=4, color="black", linewidth=0.8)
ax.set_xticks(x)
ax.set_xticklabels([f"{l}\n({sat}/{n})" for l, sat, n in zip(LANG_ORDER, [sum(1 for r in results[l] if r["result"]=="SAT") for l in LANG_ORDER], [len(results[l]) for l in LANG_ORDER])])
ax.set_ylabel(r"SAT rate (\%)")
ax.set_ylim(0, 80)
ax.grid(axis="y", alpha=0.3)
for i, (v, lo, hi) in enumerate(zip(sat_rates, sat_ci_low, sat_ci_high)):
    ax.text(i, v + 2, f"{v:.1f}%", ha="center", fontsize=8)
ax.set_title("SAT rate by language")
fig.savefig(PAPER_FIGDIR / "fig_sat_rates.pdf")
plt.close(fig)
print("Saved: fig_sat_rates.pdf")

# ---------------------------------------------------------------
# Figure 2: Violation rate distribution (violin)
# ---------------------------------------------------------------
fig, ax = plt.subplots(figsize=(4.5, 3))
violin_data = []
positions = []
labels = []
for i, lang in enumerate(LANG_ORDER):
    rates = [r["rate"] for r in results[lang]]
    violin_data.append(rates)
    positions.append(i)
    labels.append(lang)
vp = ax.violinplot(violin_data, positions=positions, showmedians=True, showextrema=True)
for i, body in enumerate(vp["bodies"]):
    body.set_facecolor(LANG_COLOURS[LANG_ORDER[i]])
    body.set_alpha(0.6)
ax.set_xticks(positions)
ax.set_xticklabels(labels)
ax.set_ylabel("Violations per type ($v/t$)")
ax.set_title("Violation rate distribution by language")
ax.grid(axis="y", alpha=0.3)
fig.savefig(PAPER_FIGDIR / "fig_violation_rate_distribution.pdf")
plt.close(fig)
print("Saved: fig_violation_rate_distribution.pdf")

# ---------------------------------------------------------------
# Figure 3: Violation concentration (bar chart by invariant)
# ---------------------------------------------------------------
invariant_totals = defaultdict(int)
for lang in LANG_ORDER:
    for repo, invs in all_violations[lang].items():
        for inv, count in invs.items():
            invariant_totals[inv] += count

sorted_invs = sorted(invariant_totals.items(), key=lambda x: -x[1])
total_v = sum(invariant_totals.values())

fig, ax = plt.subplots(figsize=(6, 3.5))
names = [f"{inv}\n({count})" if count > 0 else inv for inv, count in sorted_invs]
values = [c for _, c in sorted_invs]
pcts = [c / total_v * 100 for c in values]
colours = ["#2b83ba" if pct > 1 else "#999999" for pct in pcts]
ax.barh(range(len(names)), pcts, color=colours, edgecolor="white")
ax.set_yticks(range(len(names)))
ax.set_yticklabels(names, fontsize=8)
ax.set_xlabel("Percentage of all violations")
ax.set_title(f"Violation concentration ({total_v} total)")
ax.invert_yaxis()
ax.grid(axis="x", alpha=0.3)
fig.tight_layout()
fig.savefig(PAPER_FIGDIR / "fig_violation_concentration.pdf")
plt.close(fig)
print("Saved: fig_violation_concentration.pdf")

# ---------------------------------------------------------------
# Figure 4: Per-invariant distribution by language
# ---------------------------------------------------------------
invariant_lang = defaultdict(lambda: defaultdict(int))
for lang in LANG_ORDER:
    for repo, invs in all_violations[lang].items():
        for inv, count in invs.items():
            invariant_lang[inv][lang] += count

all_invs = sorted(invariant_set)
fig, ax = plt.subplots(figsize=(8, 3.5))
x = np.arange(len(all_invs))
width = 0.25
for i, lang in enumerate(LANG_ORDER):
    vals = [invariant_lang[inv][lang] for inv in all_invs]
    ax.bar(x + i * width, vals, width, label=lang, color=LANG_COLOURS[lang], edgecolor="white")
ax.set_xticks(x + width)
ax.set_xticklabels([inv.replace("Policy", "").replace("Generalization", "Gen.") for inv in all_invs], fontsize=7, rotation=25, ha="right")
ax.set_ylabel("Violations")
ax.legend(fontsize=7)
ax.grid(axis="y", alpha=0.3)
ax.set_title("Violations by invariant and language")
fig.savefig(PAPER_FIGDIR / "fig_violation_distribution.pdf")
plt.close(fig)
print("Saved: fig_violation_distribution.pdf")

# ---------------------------------------------------------------
# Figure 5: Per-invariant normalised rates
# ---------------------------------------------------------------
fig, ax = plt.subplots(figsize=(8, 3.5))
for i, lang in enumerate(LANG_ORDER):
    total_types = sum(r["typeCount"] for r in results[lang])
    vals = [invariant_lang[inv][lang] / max(total_types, 1) * 100 for inv in all_invs]
    ax.bar(x + i * width, vals, width, label=lang, color=LANG_COLOURS[lang], edgecolor="white")
ax.set_xticks(x + width)
ax.set_xticklabels([inv.replace("Policy", "").replace("Generalization", "Gen.") for inv in all_invs], fontsize=7, rotation=25, ha="right")
ax.set_ylabel("Violations per 100 types")
ax.legend(fontsize=7)
ax.grid(axis="y", alpha=0.3)
ax.set_title("Normalised violation rates by invariant and language")
fig.savefig(PAPER_FIGDIR / "per_invariant_rates.pdf")
plt.close(fig)
print("Saved: per_invariant_rates.pdf")

# ---------------------------------------------------------------
# Figure 6: Results summary panel
# ---------------------------------------------------------------
fig, axes = plt.subplots(2, 2, figsize=(7, 5))

# Top-left: SAT
ax = axes[0, 0]
for i, lang in enumerate(LANG_ORDER):
    reps = results[lang]
    n = len(reps)
    sat = sum(1 for r in reps if r["result"] == "SAT")
    ax.bar(i, sat / n * 100, color=LANG_COLOURS[lang], edgecolor="white")
ax.set_xticks(range(3))
ax.set_xticklabels(LANG_ORDER)
ax.set_ylabel(r"SAT \%")
ax.set_title("SAT rate")
ax.grid(axis="y", alpha=0.3)

# Top-right: Mean violations per repo
ax = axes[0, 1]
for i, lang in enumerate(LANG_ORDER):
    means = [np.mean([r["violations"] for r in results[lang]]) for lang in LANG_ORDER]
    ax.bar(range(3), means, color=[LANG_COLOURS[l] for l in LANG_ORDER], edgecolor="white")
ax.set_xticks(range(3))
ax.set_xticklabels(LANG_ORDER)
ax.set_ylabel("Mean violations")
ax.set_title("Violations per repository")
ax.grid(axis="y", alpha=0.3)

# Bottom-left: Normalised rate histograms
ax = axes[1, 0]
for lang in LANG_ORDER:
    rates = [r["rate"] for r in results[lang]]
    ax.hist(rates, bins=20, alpha=0.5, label=lang, color=LANG_COLOURS[lang])
ax.set_xlabel("v/t")
ax.set_ylabel("Repos")
ax.set_title("Normalised rate distribution")
ax.legend(fontsize=7)

# Bottom-right: Violation heatmap text
ax = axes[1, 1]
ax.axis("off")
summary_lines = []
summary_lines.append(f"Corpus: {sum(len(results[l]) for l in LANG_ORDER)} repos")
for lang in LANG_ORDER:
    reps = results[lang]
    n = len(reps)
    sat = sum(1 for r in reps if r["result"] == "SAT")
    total_v = sum(r["violations"] for r in reps)
    m = total_v / n if n > 0 else 0
    summary_lines.append(f"{lang}: {sat}/{n} SAT, {total_v} viol, mean={m:.1f}/repo")
summary_lines.append(f"Total viol: {total_v}")
summary_lines.append(f"Dominant: LocalMethodNamespace ({invariant_totals.get('LocalMethodNamespace', 0)})")
for i, line in enumerate(summary_lines):
    ax.text(0.05, 0.95 - i * 0.12, line, transform=ax.transAxes, fontsize=9, family="monospace")

fig.suptitle("Verification Results Summary", y=1.01)
fig.tight_layout()
fig.savefig(PAPER_FIGDIR / "fig_results_panel.pdf")
plt.close(fig)
print("Saved: fig_results_panel.pdf")

print("\nDone - all figures regenerated.")