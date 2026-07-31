import json, csv
from pathlib import Path
from collections import defaultdict

PIPELINE = Path(r"E:\metamodel-conformance-pipeline")
ANALYSIS = PIPELINE / "analysis-output"
SELECTION = PIPELINE / "experiment" / "03_selection" / "selected_repos.csv"
LANG_DIRS = {"Java": "java", "Python": "python", "C++": "cpp"}

with open(SELECTION, newline="", encoding="utf-8") as f:
    rows = list(csv.DictReader(f))

for lang in ["Java", "Python", "C++"]:
    ld = LANG_DIRS[lang]
    lang_rows = [r for r in rows if r["lang"] == lang]
    sat_repos = []
    unsat_stats = defaultdict(list)  # invariant -> list of (repo, count)
    all_viols = []
    
    for row in lang_rows:
        rp = ANALYSIS / ld / row["repo"] / "verification" / "verification-report.json"
        if not rp.exists(): continue
        data = json.loads(rp.read_text(encoding="utf-8"))
        if data["result"] == "SAT":
            sat_repos.append(row["repo"])
            all_viols.append(0)
        else:
            inv_counts = defaultdict(int)
            for v in data["violations"]:
                inv_counts[v["invariantName"]] += 1
            total = sum(inv_counts.values())
            all_viols.append(total)
            for inv, cnt in inv_counts.items():
                unsat_stats[inv].append((row["repo"], cnt))
    
    viols_arr = sorted(all_viols)
    n = len(viols_arr)
    if n > 0:
        median_v = viols_arr[n//2] if n % 2 == 1 else (viols_arr[n//2-1] + viols_arr[n//2]) / 2
    
    print(f"\n=== {lang}: {len(lang_rows)} repos, {len(sat_repos)} SAT ===")
    print(f"  Violations per repo: min={min(all_viols) if all_viols else 0}, max={max(all_viols) if all_viols else 0}, "
          f"median={median_v if all_viols else 0}, mean={sum(all_viols)/n:.1f}")
    print(f"  Repos with 0 violations (SAT): {len(sat_repos)}")
    print(f"  Repos with >0 violations (UNSAT): {n - len(sat_repos)}")
    
    for inv in sorted(unsat_stats.keys()):
        repos_with = unsat_stats[inv]
        print(f"  {inv}: {len(repos_with)} repos, {sum(c for _,c in repos_with)} total violations")
        if len(repos_with) <= 5:
            for repo, cnt in repos_with:
                print(f"    {repo}: {cnt}")
        else:
            cnts = sorted([c for _,c in repos_with], reverse=True)
            print(f"    per-repo: min={min(cnts)}, max={max(cnts)}, median={cnts[len(cnts)//2]}")