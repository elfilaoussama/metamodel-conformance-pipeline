"""
PHASE 03 — Corpus Selection
Reads normalisation_params.json, scans ALL repos with extraction output,
filters by type-count interval, stratifies by tertile, and samples
exactly N repos per language. Produces selected_repos.csv.
"""
import csv, json, random
from pathlib import Path
import numpy as np

ROOT        = Path(__file__).resolve().parent.parent.parent
ANALYSIS    = ROOT / "analysis-output"
PARAMS_FILE = Path(__file__).resolve().parent.parent / "02_normalisation" / "normalisation_params.json"
SEL_OUT     = Path(__file__).resolve().parent / "selected_repos.csv"

random.seed(42)  # reproducible


def read_params():
    with open(PARAMS_FILE) as f:
        return json.load(f)


def scan_all_repos(lang_label: str, lang_subdir: str):
    """Walk ALL repos in analysis-output/<lang_subdir>/,
    return [{repo, typeCount, ...}] for those with extraction.json."""
    ld = ANALYSIS / lang_subdir
    if not ld.exists():
        return []
    repos = []
    for d in sorted(ld.iterdir()):
        if not d.is_dir():
            continue
        jf = d / "extraction.json"
        if not jf.exists():
            continue
        try:
            data = json.loads(jf.read_text(encoding="utf-8"))
        except Exception:
            continue
        tc = len(data.get("types", []))
        repos.append({
            "repo": d.name,
            "lang": lang_label,
            "typeCount": tc,
        })
    return repos


def main():
    params = read_params()
    target_n = params["target_n"]
    intervals = params["intervals"]
    lang_info = params["languages"]

    all_selected = []

    print("=" * 60)
    print("  PHASE 03 — CORPUS SELECTION")
    print("=" * 60)

    for lang in ["Java", "Python", "C++"]:
        subdir = {"Java": "java", "Python": "python", "C++": "cpp"}[lang]
        lo = intervals[lang]["min_types"]
        hi = intervals[lang]["max_types"]

        print(f"\n  {lang}: interval=[{lo}, {hi}], target N={target_n}")

        all_repos = scan_all_repos(lang, subdir)
        print(f"    Total repos with extraction: {len(all_repos)}")

        in_range = [r for r in all_repos if lo <= r["typeCount"] <= hi]
        print(f"    In IQR interval:            {len(in_range)}")

        if len(in_range) < target_n:
            print(f"    WARNING: only {len(in_range)} available, using all")
            selected = in_range
        else:
            terts = lang_info[lang]["tertiles"]
            t1, t2 = terts[1], terts[2]
            per_tert = target_n // 3
            remainder = target_n - per_tert * 3

            small  = [r for r in in_range if r["typeCount"] <= t1]
            medium = [r for r in in_range if t1 < r["typeCount"] <= t2]
            large  = [r for r in in_range if r["typeCount"] > t2]

            n_s = min(per_tert, len(small))
            n_m = min(per_tert, len(medium))
            n_l = min(per_tert + remainder, len(large))

            sample_s = random.sample(small, n_s) if len(small) >= n_s else small
            sample_m = random.sample(medium, n_m) if len(medium) >= n_m else medium
            sample_l = random.sample(large, n_l) if len(large) >= n_l else large

            selected = sample_s + sample_m + sample_l
            print(f"    Sampled: small={len(sample_s)}  medium={len(sample_m)}  large={len(sample_l)}")

        for r in selected:
            r["stratum"] = "small" if r["typeCount"] <= t1 else ("medium" if r["typeCount"] <= t2 else "large")
        all_selected.extend(selected)

    # Write CSV
    with open(SEL_OUT, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=["repo", "lang", "typeCount", "stratum"])
        writer.writeheader()
        writer.writerows(all_selected)

    # Summary
    print("\n" + "=" * 60)
    print("  SELECTION SUMMARY")
    for lang in ["Java", "Python", "C++"]:
        lr = [r for r in all_selected if r["lang"] == lang]
        tcs = [r["typeCount"] for r in lr]
        print(f"  {lang}: {len(lr)} repos, types median={int(np.median(tcs))}, "
              f"range=[{min(tcs)},{max(tcs)}]")
    print(f"  Total: {len(all_selected)} repos")
    print(f"  Output: {SEL_OUT}")


if __name__ == "__main__":
    main()
