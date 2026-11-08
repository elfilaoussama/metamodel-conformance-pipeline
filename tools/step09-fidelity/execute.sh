#!/usr/bin/env bash
set -uo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$ROOT/../.." && pwd)"
OUT="$REPO/step09-results"
mkdir -p "$OUT"
JAR="$REPO/target/metamodel-conformance-pipeline-next-0.15.0.jar"
BASE="5ea604fb29b0ff9807c621b7286691440ee9a630"
{
 echo "audit=Step09 current-engine controlled native source correspondence"
 echo "source_main_sha=$BASE"
 echo "research_commit=$(git -C "$REPO" rev-parse HEAD)"
 echo "original_224_corpus=NOT_EXECUTED"
 echo "original_alloy42_61=NOT_EXECUTED"
 java -version 2>&1
 javac -version 2>&1
 python3 --version 2>&1
 clang++ --version 2>&1 | head -n 1
 mvn -version 2>&1 | head -n 4
} > "$OUT/environment.txt"
git -C "$REPO" diff --name-only "$BASE" HEAD > "$OUT/research_only_changes.txt"
printf 'case,language,oracle_expected,oracle_exit,observe_exit,analyze_exit,capsule,conformant,non_conformant,not_evaluated,other,replay_exit\n' > "$OUT/case_census.csv"
printf 'case,rule,status,witness_count,message\n' > "$OUT/decision_census.csv"
bad_oracles=0
while IFS=, read -r id lang expected; do
 [ -n "$id" ] || continue
 src="$ROOT/fixtures/$id"
 out="$OUT/$id"
 mkdir -p "$out" "$out/oracle-classfiles"
 find "$src" -type f -print0 | sort -z | xargs -0 sha256sum > "$out/source_sha256.txt"
 oracle_rc=127
 case "$lang" in
   java)
     javac --release 17 -d "$out/oracle-classfiles" "$src/Case.java" > "$out/oracle.log" 2>&1
     oracle_rc=$? ;;
   python)
     python3 -m py_compile "$src/Case.py" > "$out/oracle.log" 2>&1
     oracle_rc=$?
     if [ "$oracle_rc" -eq 0 ]; then
       PYTHONDONTWRITEBYTECODE=1 python3 "$src/Case.py" >> "$out/oracle.log" 2>&1
       oracle_rc=$?
     fi ;;
   cpp)
     clang++ -std=c++17 -fsyntax-only -Wall -Wextra -pedantic "$src/Case.cpp" > "$out/oracle.log" 2>&1
     oracle_rc=$? ;;
 esac
 if { [ "$expected" = "VALID" ] && [ "$oracle_rc" -ne 0 ]; } || { [ "$expected" = "INVALID" ] && [ "$oracle_rc" -eq 0 ]; }; then
   bad_oracles=$((bad_oracles+1))
   echo "STEP09_BAD_ORACLE|$id|expected=$expected|rc=$oracle_rc"
 fi
 timeout 135s java -Xmx2300m -jar "$JAR" observe --language "$lang" --source "$src" --output "$out/observe" > "$out/observe.log" 2>&1
 observe_rc=$?
 timeout 135s java -Xmx2300m -jar "$JAR" analyze --language "$lang" --source "$src" --output "$out/analyze" > "$out/analyze.log" 2>&1
 analyze_rc=$?
 capsule="$out/analyze/verification-capsule.json"
 replay_rc=-1
 if [ -s "$capsule" ]; then
   timeout 55s java -Xmx2300m -jar "$JAR" verify-capsule --capsule "$capsule" > "$out/replay.log" 2>&1
   replay_rc=$?
 fi
 python3 - "$OUT" "$id" "$lang" "$expected" "$oracle_rc" "$observe_rc" "$analyze_rc" "$replay_rc" <<'PY'
import csv,json,pathlib,sys
out=pathlib.Path(sys.argv[1]); ident,lang,expect=sys.argv[2:5]
oracle,obs,ana,replay=sys.argv[5:9]
cap=out/ident/"analyze"/"verification-capsule.json"
counts={"CONFORMANT":0,"NON_CONFORMANT":0,"NOT_EVALUATED":0}
other=0; decisions=[]
if cap.exists():
    try: decisions=json.loads(cap.read_text()).get("decisions",[])
    except Exception as ex: print("STEP09_PARSE_ERROR",ident,str(ex))
for d in decisions:
    status=str(d.get("status","UNKNOWN")); counts[status]=counts.get(status,0)+1
    if status not in ("CONFORMANT","NON_CONFORMANT","NOT_EVALUATED"): other+=1
    with (out/"decision_census.csv").open("a",newline="") as f:
        csv.writer(f).writerow([ident,d.get("invariantId"),status,len(d.get("witnesses",[])),d.get("message","")])
    print("STEP09_DECISION|"+ident+"|"+str(d.get("invariantId"))+"|"+status+"|"+str(len(d.get("witnesses",[]))))
row=[ident,lang,expect,oracle,obs,ana,"YES" if cap.exists() else "NO",
     counts["CONFORMANT"],counts["NON_CONFORMANT"],counts["NOT_EVALUATED"],other,replay]
with (out/"case_census.csv").open("a",newline="") as f: csv.writer(f).writerow(row)
print("STEP09_CASE|"+"|".join(map(str,row)))
PY
done <<'CASES'
java_baseline,java,VALID
java_covariant,java,VALID
java_abstract_diamond,java,VALID
java_fully_implemented_abstract,java,VALID
java_bad_cycle,java,INVALID
python_abc_static,python,VALID
python_rebinding,python,VALID
python_bad_syntax,python,INVALID
cpp_covariant,cpp,VALID
cpp_bad_cycle,cpp,INVALID
CASES
python3 - "$OUT" <<'PY'
import csv,pathlib,sys
p=pathlib.Path(sys.argv[1])/"case_census.csv"
rows=list(csv.DictReader(p.open()))
print("STEP09_TOTAL|cases=%d|capsules=%d|nonconformant_decisions=%d|not_evaluated_decisions=%d" %
      (len(rows),sum(r["capsule"]=="YES" for r in rows),
       sum(int(r["non_conformant"]) for r in rows),sum(int(r["not_evaluated"]) for r in rows)))
PY
if [ "$bad_oracles" -gt 0 ]; then
  echo "STEP09_ORACLE_HARNESS_INVALID|$bad_oracles"
  exit 8
fi
echo "STEP09_HARNESS_COMPLETED_RESULTS_NOT_EQUIVALENCE_PROOF"
