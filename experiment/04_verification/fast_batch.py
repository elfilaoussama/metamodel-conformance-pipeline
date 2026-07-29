"""Fast batch verification — uses Java directly with classpath, no Maven per-invocation."""
import csv, subprocess, os, time, sys
from pathlib import Path

ROOT = Path(r"E:\java-analysis-pipeline")
VERIFIER = ROOT / "modules" / "verification-cli"
METAMODEL = VERIFIER / "src" / "main" / "resources" / "metamodel.als"
CLASSES = VERIFIER / "target" / "classes"
SELECTION = ROOT / "experiment" / "03_selection" / "selected_repos.csv"
LANG_DIRS = {"Java": "java", "Python": "python", "C++": "cpp"}

# Build classpath from Maven dependencies
MAVEN_REPO = Path.home() / ".m2" / "repository"
CP_JARS = [
    str(CLASSES),
    str(MAVEN_REPO / "com" / "google" / "code" / "gson" / "gson" / "2.11.0" / "gson-2.11.0.jar"),
]

def find_jar(group, artifact, version):
    p = MAVEN_REPO / group.replace(".", "/") / artifact / version / f"{artifact}-{version}.jar"
    return str(p) if p.exists() else None

def run_one(repo, lang, extraction_json, outdir):
    outdir.mkdir(parents=True, exist_ok=True)
    csv_out = outdir / "verification-report.csv"
    json_out = outdir / "verification-report.json"
    
    env = os.environ.copy()
    env["JAVA_HOME"] = r"C:\Program Files\Java\jdk-17"
    java = r"C:\Program Files\Java\jdk-17\bin\java.exe"
    
    cp = ";".join(CP_JARS)
    args = [
        java, "-cp", cp, "com.verification.Main",
        "-r", str(METAMODEL), "-i", str(extraction_json),
        "-o", str(outdir), "--strict", "--details",
        "--report", str(json_out), "--csv", str(csv_out),
    ]
    
    start = time.time()
    try:
        r = subprocess.run(args, cwd=str(VERIFIER), env=env,
                          capture_output=True, text=True, timeout=120)
        elapsed = time.time() - start
        if csv_out.exists():
            lines = [l for l in csv_out.read_text(encoding="utf-8").splitlines() if l.strip()]
            nc = len(lines) - 2  # header + result row
            return ("SAT", 0) if nc <= 0 else ("UNSAT", max(0, nc)), elapsed
        return ("NO_OUTPUT", 0), elapsed
    except subprocess.TimeoutExpired:
        return ("TIMEOUT", 0), 120
    except Exception as e:
        return (f"ERR:{e}", 0), 0

def main():
    with open(SELECTION, newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    
    lang = sys.argv[1] if len(sys.argv) > 1 else "Java"
    start_idx = int(sys.argv[2]) if len(sys.argv) > 2 else 0
    end_idx = int(sys.argv[3]) if len(sys.argv) > 3 else len(rows)
    
    rows = [r for r in rows if r["lang"] == lang]
    rows = rows[start_idx:end_idx]
    
    print(f"Processing {len(rows)} {lang} repos (index {start_idx}-{end_idx-1})...")
    results = []
    start_all = time.time()
    sat = unsat = errs = 0
    
    for i, row in enumerate(rows):
        repo = row["repo"]
        subd = LANG_DIRS[lang]
        extraction = ROOT / "analysis-output" / subd / repo / "extraction.json"
        outdir = ROOT / "analysis-output" / subd / repo / "verification"
        
        if not extraction.exists():
            print(f"[{i+1:3d}/{len(rows)}] SKIP {repo}")
            results.append({"repo": repo, "violations": 0, "result": "MISSING", "time": 0})
            errs += 1; continue
        
        idx = start_idx + i + 1
        print(f"[{idx:3d}/75] {repo} ...", end=" ", flush=True)
        (result, nc), elapsed = run_one(repo, lang, str(extraction), outdir)
        tag = result if result in ("SAT","TIMEOUT","NO_OUTPUT") else f"UNSAT({nc})"
        print(f"{tag} ({elapsed:.0f}s)")
        
        if result == "SAT": sat += 1
        elif result == "UNSAT": unsat += 1
        else: errs += 1
        results.append({"repo": repo, "violations": nc, "result": tag, "time": elapsed})
    
    elapsed_all = time.time() - start_all
    print(f"\nDone in {elapsed_all:.0f}s. SAT={sat} UNSAT={unsat} ERR={errs}")
    log = Path(__file__).resolve().parent / f"batch_results_java_{start_idx}_{end_idx}.csv"
    with open(log, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["repo","violations","result","time"])
        w.writeheader(); w.writerows(results)

if __name__ == "__main__":
    main()
