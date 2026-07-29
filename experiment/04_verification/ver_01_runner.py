"""
PHASE 04 — Verification Runner
Rebuilds the verification-cli module and runs it against
every repo in selected_repos.csv. Produces verification-report.csv
per repo in analysis-output/<lang>/<repo>/verification/.

Usage:
  python ver_01_runner.py
    --skip-build    (skip Maven rebuild, use existing JAR)
    --lang Java     (only process one language)
    --dry-run       (list repos without running)
"""
import csv, json, subprocess, sys, os, time
from pathlib import Path
from datetime import datetime

ROOT        = Path(__file__).resolve().parent.parent.parent
ANALYSIS    = ROOT / "analysis-output"
SELECTION   = Path(__file__).resolve().parent.parent / "03_selection" / "selected_repos.csv"
VERIFIER    = ROOT / "modules" / "verification-cli"
METAMODEL   = VERIFIER / "src" / "main" / "resources" / "metamodel.als"

LANG_DIRS = {"Java": "java", "Python": "python", "C++": "cpp"}


def parse_args():
    args = {"skip_build": False, "lang": None, "dry_run": False}
    for a in sys.argv[1:]:
        if a == "--skip-build":
            args["skip_build"] = True
        elif a.startswith("--lang"):
            parts = a.split("=", 1)
            args["lang"] = parts[1] if len(parts) > 1 else sys.argv[sys.argv.index(a) + 1]
        elif a == "--dry-run":
            args["dry_run"] = True
    return args


def rebuild_verifier():
    print("  Rebuilding verification-cli module...")
    env = os.environ.copy()
    env["JAVA_HOME"] = r"C:\Program Files\Java\jdk-17"
    result = subprocess.run(
        ["mvn", "package", "-DskipTests", "-q"],
        cwd=str(VERIFIER), env=env, capture_output=True, text=True, timeout=300,
    )
    if result.returncode != 0:
        print(f"  BUILD FAILED:\n{result.stderr[-800:]}")
        sys.exit(1)
    print("  Build OK.")


def run_verification(repo_name: str, lang: str, extraction_json: Path, output_dir: Path):
    output_dir.mkdir(parents=True, exist_ok=True)
    mapped = output_dir / "MappedInstance.aie"
    report_json = output_dir / "verification-report.json"
    report_csv  = output_dir / "verification-report.csv"

    print(f"    {repo_name} ...", end=" ", flush=True)
    start = time.time()

    env = os.environ.copy()
    env["JAVA_HOME"] = r"C:\Program Files\Java\jdk-17"

    cmd = [
        "mvn", "exec:java", "-q",
        f"-Dexec.mainClass=com.verification.Main",
        f"-Dexec.args=-r {METAMODEL} -i {extraction_json} -o {output_dir}"
        f" --strict --details --report {report_json} --csv {report_csv}",
    ]
    try:
        result = subprocess.run(
            cmd, cwd=str(VERIFIER), env=env,
            capture_output=True, text=True, timeout=300,
        )
        elapsed = time.time() - start
        if report_csv.exists():
            lines = report_csv.read_text().count("\n") - 1
            status = "SAT" if "SAT" in (report_csv.read_text()[:50]) else "UNSAT"
            print(f"{status} ({lines} violation lines) in {elapsed:.1f}s")
            return {"status": "OK", "result": status, "violations": max(0, lines - 1)}
        else:
            print(f"NO OUTPUT in {elapsed:.1f}s")
            return {"status": "NO_OUTPUT", "result": "ERROR", "violations": 0}
    except subprocess.TimeoutExpired:
        print("TIMEOUT")
        return {"status": "TIMEOUT", "result": "ERROR", "violations": 0}
    except Exception as e:
        print(f"ERROR: {e}")
        return {"status": "ERROR", "result": "ERROR", "violations": 0, "error": str(e)}


def main():
    args = parse_args()

    if not args["skip_build"]:
        rebuild_verifier()

    with open(SELECTION, newline="", encoding="utf-8") as f:
        repos = list(csv.DictReader(f))

    if args["lang"]:
        repos = [r for r in repos if r["lang"] == args["lang"]]

    print(f"\n  Processing {len(repos)} repos...")
    results = []

    for i, row in enumerate(repos):
        lang = row["lang"]
        subdir = LANG_DIRS[lang]
        repo_name = row["repo"]
        extraction = ANALYSIS / subdir / repo_name / "extraction.json"

        if not extraction.exists():
            print(f"  [{i+1}/{len(repos)}] {repo_name}: extraction.json not found, SKIP")
            results.append({"repo": repo_name, "lang": lang, "status": "MISSING"})
            continue

        if args["dry_run"]:
            print(f"  [{i+1}/{len(repos)}] {repo_name}: {row['typeCount']} types")
            continue

        print(f"  [{i+1}/{len(repos)}]", end="")
        ver_dir = ANALYSIS / subdir / repo_name / "verification"
        res = run_verification(repo_name, lang, extraction, ver_dir)
        res["repo"] = repo_name
        res["lang"] = lang
        results.append(res)

    if not args["dry_run"]:
        run_log = Path(__file__).resolve().parent / "verification_run_log.csv"
        with open(run_log, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=["repo", "lang", "status", "result", "violations"])
            w.writeheader()
            w.writerows(results)
        print(f"\n  Run log: {run_log}")

        ok = sum(1 for r in results if r["status"] == "OK")
        sat = sum(1 for r in results if r.get("result") == "SAT")
        unsat = sum(1 for r in results if r.get("result") == "UNSAT")
        err = sum(1 for r in results if r["status"] != "OK")
        print(f"  OK={ok}  SAT={sat}  UNSAT={unsat}  Errors={err}")


if __name__ == "__main__":
    main()
