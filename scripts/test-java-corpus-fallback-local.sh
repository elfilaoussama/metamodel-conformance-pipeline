#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: test-java-corpus-fallback-local.sh repository-root" >&2
  exit 64
fi

repo_root="$(realpath -e -- "$1")"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
mkdir -p "$work/bin"

cat > "$work/bin/git" <<'GIT'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == clone ]]; then
  target="${@: -1}"
  mkdir -p "$target/src/main/java/example"
  printf 'package example; public class Type {}\n' > "$target/src/main/java/example/Type.java"
  printf '<project><modelVersion>4.0.0</modelVersion></project>\n' > "$target/pom.xml"
  exit 0
fi
if [[ "${1:-}" == -C ]]; then
  exit 0
fi
echo "unexpected fake git invocation: $*" >&2
exit 98
GIT
cat > "$work/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
exit 69
DOCKER
chmod +x "$work/bin/git" "$work/bin/docker"

mapfile -t pipeline_jars < <(printf '%s\n' "$repo_root"/target/metamodel-conformance-pipeline-next-*.jar)
[[ ${#pipeline_jars[@]} -eq 1 && -f "${pipeline_jars[0]}" ]] || {
  echo "expected exactly one built pipeline JAR" >&2
  exit 66
}

PATH="$work/bin:$PATH" PIPELINE_JAR="${pipeline_jars[0]}" \
  MAVEN_RESOLVER_IMAGE=test-maven MAVEN_DEPENDENCY_CLASSPATH_GOAL=fake:classpath \
  bash "$repo_root/scripts/run-corpus-entry.sh" \
    example/example 0123456789012345678901234567890123456789 "$work/output" \
    >"$work/run.log"

report="$work/output/report.json"
jq -e '
  .toolOutcome == "ANALYZED"
  and .verificationExit == 0
  and .dependencyResolutionExit == 69
  and (.decisions | length) > 0
' "$report" >/dev/null
expected="$(jq '[.invariants[].id] | length' "$repo_root/src/main/resources/invariants/registry.json")"
actual="$(jq '.decisions | length' "$report")"
[[ "$actual" == "$expected" ]] || {
  echo "fallback capsule decision count mismatch: expected=$expected actual=$actual" >&2
  exit 1
}
grep -Fq 'analyzing without dependency evidence' "$work/output/analysis.log"

printf 'LOCAL_JAVA_CORPUS_FALLBACK_OK\n'
