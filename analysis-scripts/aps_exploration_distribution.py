"""Exploration: type and violation distributions across all three languages."""
import csv, json, re, random
from pathlib import Path
import numpy as np

ANALYSIS = Path(__file__).resolve().parent.parent / "analysis-output"

def main():
    # ---- Java: need type counts from extraction.json samples ----
    java_dir = ANALYSIS / "java"
    java_csv = java_dir / "ingestion-export.csv"
    java_rows = []
    with open(java_csv, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            v = re.search(r"(\d+)\s*violation", r.get("Activity", ""))
            java_rows.append({
                "repo": r["Repository"],
                "violations": int(v.group(1)) if v else 0,
                "status": r["Status"],
            })
    # Sample extraction.json for type counts (all repos have them)
    java_dirs = [d for d in java_dir.iterdir() if d.is_dir()]
    sample_size = min(200, len(java_dirs))
    sampled = random.sample(java_dirs, sample_size)
    java_types = []
    for d in sampled:
        jf = d / "extraction.json"
        if jf.exists():
            try:
                data = json.loads(jf.read_text(encoding="utf-8"))
                tc = len(data.get("types", []))
                java_types.append(tc)
            except: pass

    # ---- C++ types from CSV ----
    cpp_csv = ANALYSIS / "cpp" / "ingestion-export.csv"
    cpp_types = []
    with open(cpp_csv, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            t = r.get("Types", "").strip()
            if t.isdigit() and int(t) > 0:
                cpp_types.append(int(t))

    # ---- Python types ----
    python_dir = ANALYSIS / "python"
    py_dirs = [d for d in python_dir.iterdir() if d.is_dir() and not d.name.startswith("ingestion")]
    sample_py = min(200, len(py_dirs))
    py_sampled = random.sample(py_dirs, sample_py)
    py_types = []
    for d in py_sampled:
        jf = d / "extraction.json"
        if jf.exists():
            try:
                data = json.loads(jf.read_text(encoding="utf-8"))
                tc = len(data.get("types", []))
                py_types.append(tc)
            except: pass

    # ---- Report ----
    print("=" * 70)
    print("  TYPE COUNT DISTRIBUTIONS (sampled)")
    print("=" * 70)

    for label, data in [("Java", java_types), ("Python", py_types), ("C++", cpp_types)]:
        if not data:
            print(f"\n  {label}: no data")
            continue
        a = np.array(data)
        print(f"\n  {label}: n={len(a)}")
        print(f"    min={a.min():.0f}  max={a.max():.0f}  mean={a.mean():.0f}  median={np.median(a):.0f}")
        for p in [10, 25, 50, 75, 90, 95]:
            print(f"    P{p:2d}: {int(np.percentile(a, p))}")
        # Bracket suggestions
        p25, p75 = int(np.percentile(a, 25)), int(np.percentile(a, 75))
        iqr = p75 - p25
        print(f"    IQR: {iqr}  [P25={p25}, P75={p75}]")
        print(f"    Suggested normalised range: [{max(1, p25 - iqr//2)}, {p75 + iqr//2}]")

    print("\n" + "=" * 70)
    print("  JAVA VIOLATION SUMMARY")
    print("=" * 70)
    viols = [r["violations"] for r in java_rows]
    print(f"  Total repos: {len(viols)}")
    print(f"  SAT (0 viol): {sum(1 for v in viols if v == 0)}")
    print(f"  UNSAT:        {sum(1 for v in viols if v > 0)}")
    viol_pos = [v for v in viols if v > 0]
    if viol_pos:
        a = np.array(viol_pos)
        print(f"\n  Violations (non-zero): n={len(a)}")
        print(f"    min={a.min()}  max={a.max()}  mean={a.mean():.0f}  median={np.median(a):.0f}")
        for p in [10, 25, 50, 75, 90, 95]:
            print(f"    P{p:2d}: {int(np.percentile(a, p))}")

if __name__ == "__main__":
    main()
