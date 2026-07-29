"""Batch verification runner — runs verification-cli against all selected repos."""
import csv, subprocess, sys, os, time
from pathlib import Path

ROOT = Path(r"E:\java-analysis-pipeline")
SELECTION = ROOT / "experiment" / "03_selection" / "selected_repos.csv"
VERIFIER  = ROOT / "modules" / "verification-cli"
METAMODEL = VERIFIER / "src" / "main" / "resources" / "metamodel.als"
LANG_DIRS = {"Java": "java", "Python": "python", "C++": "cpp"}

def run_one(repo, lang, extraction_json, outdir):
    outdir.mkdir(parents=True, exist_ok=True)
    csv_out = outdir / "verification-report.csv"
    json_out = outdir / "verification-report.json"
    
    env = os.environ.copy()
    env["JAVA_HOME"] = r"C:\Program Files\Java\jdk-17"
    
    cmd = [
        r"C:\ProgramData\chocolatey\lib\maven\apache-maven-3.9.14\bin\mvn.cmd",
        "-f", str(VERIFIER / "pom.xml"),
        "exec:java", "-q",
        "-Dexec.mainClass=com.verification.Main",
        f"-Dexec.args=-r {METAMODEL} -i {extraction_json} -o {outdir}"
        f" --strict --details --report {json_out} --csv {csv_out}",
    ]
    
    start = time.time()
    try:
        r = subprocess.run(cmd, cwd=str(VERIFIER), env=env,
                          capture_output=True, text=True, timeout=300)
        elapsed = time.time() - start
        if csv_out.exists():
            lines = csv_out.read_text(encoding="utf-8").splitlines()
            nc = sum(1 for l in lines[2:] if l.strip())
            return "SAT" if nc == 0 else f"UNSAT({nc})", elapsed
        return "NO_OUTPUT", elapsed
    except subprocess.TimeoutExpired:
        return "TIMEOUT", 300
    except Exception as e:
        return f"ERR:{e}", 0

def main():
    with open(SELECTION, newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    
    lang = sys.argv[1] if len(sys.argv) > 1 else "Java"
    rows = [r for r in rows if r["lang"] == lang]
    
    print(f"Processing {len(rows)} {lang} repos...")
    results = []
    start_all = time.time()
    
    for i, row in enumerate(rows):
        repo = row["repo"]
        subd = LANG_DIRS[lang]
        extraction = ROOT / "analysis-output" / subd / repo / "extraction.json"
        outdir = ROOT / "analysis-output" / subd / repo / "verification"
        
        if not extraction.exists():
            print(f"[{i+1:3d}/{len(rows)}] SKIP {repo} (no extraction.json)")
            results.append({"repo": repo, "result": "MISSING", "time": 0})
            continue
        
        print(f"[{i+1:3d}/{len(rows)}] {repo} ...", end=" ", flush=True)
        result, elapsed = run_one(repo, lang, str(extraction), outdir)
        print(f"{result} ({elapsed:.0f}s)")
        results.append({"repo": repo, "result": result, "time": elapsed})
    
    elapsed_all = time.time() - start_all
    sat = sum(1 for r in results if r["result"].startswith("SAT"))
    unsat = sum(1 for r in results if r["result"].startswith("UNSAT"))
    err = len(results) - sat - unsat
    print(f"\nDone in {elapsed_all:.0f}s. SAT={sat} UNSAT={unsat} ERR={err}")
    
    # Save results
    log = Path(__file__).resolve().parent / f"batch_results_{lang.lower()}.csv"
    with open(log, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["repo", "result", "time"])
        w.writeheader()
        w.writerows(results)

if __name__ == "__main__":
    main()
