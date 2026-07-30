"""Quick analysis: reads verification-report.json per selected repo, produces per-condition breakdown."""
import csv, json
from pathlib import Path
from collections import defaultdict

ROOT = Path(__file__).resolve().parent.parent.parent
ANALYSIS = ROOT / "analysis-output"
SELECTION = ROOT / "experiment" / "03_selection" / "selected_repos.csv"
LANG_DIRS = {"Java": "java", "Python": "python", "C++": "cpp"}

lang_counts = {}
invariant_totals = defaultdict(lambda: defaultdict(int))
all_violations = []

with open(SELECTION, newline="", encoding="utf-8") as f:
    rows = list(csv.DictReader(f))

for row in rows:
    lang = row["lang"]
    repo = row["repo"]
    lang_dir = LANG_DIRS[lang]
    report_path = ANALYSIS / lang_dir / repo / "verification" / "verification-report.json"
    if not report_path.exists():
        print(f"  SKIP: {lang}/{repo} — no report")
        continue
    try:
        data = json.loads(report_path.read_text(encoding="utf-8"))
    except Exception as e:
        print(f"  ERR: {lang}/{repo}: {e}")
        continue
    lang_counts[lang] = lang_counts.get(lang, 0) + 1
    result = data.get("result", "UNKNOWN")
    violations = data.get("violations", [])
    for v in violations:
        inv = v.get("invariantName", "Unknown")
        desc = v.get("description", "")
        invariant_totals[lang][inv] += 1
        all_violations.append({"lang": lang, "repo": repo, "invariant": inv, "desc": desc})

print("\n=== RESULTS BY LANGUAGE ===")
for lang in ["Java", "Python", "C++"]:
    sat = 0
    unsat = 0
    for row in rows:
        if row["lang"] != lang: continue
        repo = row["repo"]
        rp = ANALYSIS / LANG_DIRS[lang] / repo / "verification" / "verification-report.json"
        if rp.exists():
            d = json.loads(rp.read_text(encoding="utf-8"))
            if d.get("result") == "SAT": sat += 1
            else: unsat += 1
    total = sat + unsat
    print(f"\n  {lang}: {total} repos, SAT={sat} ({sat/total*100:.1f}%), UNSAT={unsat}")
    total_v = sum(invariant_totals[lang].values())
    print(f"    Total violations: {total_v}, mean={total_v/total:.2f}/repo")
    
    for inv, count in sorted(invariant_totals[lang].items(), key=lambda x: -x[1]):
        print(f"    {inv:40s}: {count:5d}")

# Cross-language summary
print("\n=== CROSS-LANGUAGE SUMMARY ===")
all_invariants = set()
for d in invariant_totals.values():
    all_invariants.update(d.keys())

print(f"{'Invariant':40s} {'Java':>6s} {'Python':>6s} {'C++':>6s} {'Total':>6s}")
print("-" * 70)
for inv in sorted(all_invariants):
    j = invariant_totals["Java"].get(inv, 0)
    p = invariant_totals["Python"].get(inv, 0)
    c = invariant_totals["C++"].get(inv, 0)
    print(f"{inv:40s} {j:6d} {p:6d} {c:6d} {j+p+c:6d}")
print(f"{'TOTAL':40s} {sum(invariant_totals['Java'].values()):6d} {sum(invariant_totals['Python'].values()):6d} {sum(invariant_totals['C++'].values()):6d} {sum(sum(d.values()) for d in invariant_totals.values()):6d}")