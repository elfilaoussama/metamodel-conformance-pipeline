"""
PHASE 02 — Normalisation
Reads exploration_summary.csv, computes IQR-based type-count intervals,
counts available repos in each interval, determines equal N per language,
and produces normalisation parameters (normalisation_params.json).
"""
import csv, json
from pathlib import Path
import numpy as np

EXPLORATION_CSV = Path(__file__).resolve().parent.parent / "01_exploration" / "exploration_summary.csv"
PARAMS_OUT     = Path(__file__).resolve().parent / "normalisation_params.json"
FIGDIR         = Path(__file__).resolve().parent / "figures"
FIGDIR.mkdir(exist_ok=True)

# Star bracket definitions (from pipeline paper)
STAR_BRACKETS = {
    "Java":   {"Average": (25, 100),  "High": (100, 1000), "Elite": (1000, None)},
    "Python": {"Average": (25, 100),  "High": (100, 1000), "Elite": (1000, None)},
    "C++":    {"Average": (10, 100),  "High": (100, 500),  "Elite": (500, None)},
}


def load_exploration():
    repos = []
    with open(EXPLORATION_CSV, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            repos.append({
                "repo":   row["repo"],
                "lang":   row["lang"],
                "typeCount": int(row["typeCount"]),
                "executableCount": int(row["executableCount"]),
                "fieldCount": int(row["fieldCount"]),
                "interfaceCount": int(row["interfaceCount"]),
                "abstractCount": int(row["abstractCount"]),
                "typesWithSuper": int(row["typesWithSuper"]),
                "typesWithInterfaces": int(row["typesWithInterfaces"]),
            })
    return repos


def compute_interval(vals, lo_pct=25, hi_pct=75):
    """Return (min, max) for the percentile-bounded interval."""
    arr = np.array([v for v in vals if v > 0])
    if len(arr) < 10:
        return int(arr.min()), int(arr.max())
    return int(np.percentile(arr, lo_pct)), int(np.percentile(arr, hi_pct))


def main():
    repos = load_exploration()
    langs = sorted(set(r["lang"] for r in repos))

    params = {"languages": {}, "common_n": None, "intervals": {}}

    print("=" * 60)
    print("  PHASE 02 — NORMALISATION")
    print("=" * 60)

    for lang in langs:
        lr = [r for r in repos if r["lang"] == lang]
        tc = [r["typeCount"] for r in lr]
        tc_pos = [t for t in tc if t > 0]

        lo, hi = compute_interval(tc_pos, 25, 75)
        params["intervals"][lang] = {"min_types": lo, "max_types": hi}

        in_range = [r for r in lr if lo <= r["typeCount"] <= hi]
        print(f"\n  {lang}")
        print(f"    Total analysed:        {len(lr)}")
        print(f"    Types P25–P75:         [{lo}, {hi}]")
        print(f"    Repos in IQR interval: {len(in_range)}")
        print(f"    Median types (IQR):    {int(np.median([r['typeCount'] for r in in_range]))}")

    # Determine common N (minimum available across languages in IQR)
    in_range_counts = {}
    for lang in langs:
        lo = params["intervals"][lang]["min_types"]
        hi = params["intervals"][lang]["max_types"]
        lr = [r for r in repos if r["lang"] == lang]
        in_range_counts[lang] = len([r for r in lr if lo <= r["typeCount"] <= hi])

    common_n = min(in_range_counts.values())
    params["common_n"] = common_n
    print(f"\n  Common N (min repos in IQR across languages): {common_n}")

    # Target: 80 repos per language (balancing depth vs availability)
    target_n = min(100, common_n)
    params["target_n"] = target_n
    print(f"  Target N per language: {target_n}")

    # Size brackets for stratification within each language
    print("\n  Stratification: small / medium / large tertiles per language")
    for lang in langs:
        lo = params["intervals"][lang]["min_types"]
        hi = params["intervals"][lang]["max_types"]
        lr = [r for r in repos if r["lang"] == lang and lo <= r["typeCount"] <= hi]
        tcs = sorted([r["typeCount"] for r in lr])
        t1 = tcs[int(len(tcs) * 0.33)]
        t2 = tcs[int(len(tcs) * 0.67)]
        params["languages"][lang] = {
            "interval": [lo, hi],
            "tertiles": [lo, t1, t2, hi],
            "target_per_tertile": target_n // 3,
        }
        print(f"  {lang}: small=[{lo},{t1}]  medium=[{t1+1},{t2}]  large=[{t2+1},{hi}]")

    params["violation_normalisation"] = "violation_rate = violation_count / type_count"

    with open(PARAMS_OUT, "w", encoding="utf-8") as f:
        json.dump(params, f, indent=2, default=int)
    print(f"\n  Parameters saved: {PARAMS_OUT}")


if __name__ == "__main__":
    main()
