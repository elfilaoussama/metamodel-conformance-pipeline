"""Batch verification using compiled classes (no Maven per invocation)."""
import csv, subprocess, os, time, sys, json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
VERIFIER = ROOT / "modules" / "verification-cli"
RECORE = str(VERIFIER / "src" / "main" / "resources" / "StructuralMetamodel.recore")
CLASSES = str(VERIFIER / "target" / "classes")
ANALYSIS = ROOT / "analysis-output"
SELECTION = ROOT / "experiment" / "03_selection" / "selected_repos.csv"
MAVEN_REPO = Path.home() / ".m2" / "repository"
GSON = str(MAVEN_REPO / "com" / "google" / "code" / "gson" / "gson" / "2.10.1" / "gson-2.10.1.jar")
CP = CLASSES + ";" + GSON
ENV = os.environ.copy()
ENV["JAVA_HOME"] = r"C:\Program Files\Java\jdk-17"
JAVA = r"C:\Program Files\Java\jdk-17\bin\java.exe"
TIMEOUT = 300


def run_one(repo, lang, extraction_json, outdir):
    outdir_path = Path(outdir)
    outdir_path.mkdir(parents=True, exist_ok=True)
    json_out = str(outdir_path / "verification-report.json")
    csv_out = str(outdir_path / "verification-report.csv")

    args = [
        JAVA, "-cp", CP, "com.verification.BatchRunner",
        str(extraction_json), str(outdir),
    ]
    start = time.time()
    try:
        r = subprocess.run(args, cwd=str(VERIFIER), env=ENV,
                           capture_output=True, text=True, timeout=TIMEOUT)
        elapsed = round(time.time() - start, 1)
        if json_out and Path(json_out).exists():
            try:
                data = json.loads(Path(json_out).read_text(encoding="utf-8"))
                result = data.get("result", "UNKNOWN")
                violations = len(data.get("violations", []))
                return result, violations, elapsed
            except Exception:
                return "PARSE_ERR", 0, elapsed
        if r.returncode != 0:
            return f"EXIT:{r.returncode}", 0, elapsed
        return "NO_OUTPUT", 0, elapsed
    except subprocess.TimeoutExpired:
        return "TIMEOUT", 0, TIMEOUT
    except Exception as e:
        return f"ERR:{e}", 0, 0


def run_batch(lang_label, lang_dir):
    results = []
    with open(SELECTION, newline="", encoding="utf-8") as f:
        rows = [r for r in csv.DictReader(f) if r["lang"] == lang_label]

    total = len(rows)
    sat_count = 0
    total_violations = 0
    total_time = 0.0

    print(f"\n{'='*60}")
    print(f"  {lang_label}: {total} repos")
    print(f"{'='*60}")

    for i, row in enumerate(rows):
        repo = row["repo"]
        json_path = ANALYSIS / lang_dir / repo / "extraction.json"
        if not json_path.exists():
            print(f"  [{i+1:3d}/{total}] {repo} : SKIP (no extraction.json)")
            results.append({"repo": repo, "lang": lang_label, "typeCount": row["typeCount"],
                            "result": "SKIP", "violations": 0, "elapsed_s": 0})
            continue

        out = str(ANALYSIS / lang_dir / repo / "verification")
        print(f"  [{i+1:3d}/{total}] {repo} ... ", end="", flush=True)
        result, violations, elapsed = run_one(repo, lang_label, json_path, out)
        sat_count += 1 if result == "SAT" else 0
        total_violations += violations
        total_time += elapsed
        print(f"{result} [{violations}v] ({elapsed:.1f}s)")

        results.append({"repo": repo, "lang": lang_label, "typeCount": row["typeCount"],
                        "result": result, "violations": violations, "elapsed_s": elapsed})

    print(f"\n  Summary: SAT={sat_count}/{total}, total_violations={total_violations}, "
          f"total_time={total_time:.1f}s, mean_rate={total_violations/total:.3f}")

    # Save batch results CSV
    out_csv = ROOT / "experiment" / "04_verification" / f"batch_results_{lang_dir}.csv"
    with open(out_csv, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["repo", "lang", "typeCount", "result", "violations", "elapsed_s"])
        w.writeheader()
        w.writerows(results)
    print(f"  Saved: {out_csv}")
    return results


if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", choices=["Java", "Python", "Cpp", "all"], default="all")
    args = ap.parse_args()

    lang_map = {"Java": ("Java", "java"), "Python": ("Python", "python"), "Cpp": ("C++", "cpp")}
    if args.lang == "all":
        all_results = []
        for label, directory in lang_map.values():
            all_results.extend(run_batch(label, directory))
    else:
        label, directory = lang_map[args.lang]
        run_batch(label, directory)