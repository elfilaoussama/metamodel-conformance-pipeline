#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: summarize-build-backed-java-corpus.sh corpus.tsv output-directory invariant-registry.json" >&2
  exit 64
fi

corpus_tsv="$1"
output_root="$2"
registry="$3"

for required in awk diff find jq sort; do
  command -v "$required" >/dev/null 2>&1 || { echo "$required is required" >&2; exit 69; }
done
[[ -f "$corpus_tsv" ]] || { echo "corpus TSV unavailable" >&2; exit 66; }
[[ -f "$registry" ]] || { echo "invariant registry unavailable" >&2; exit 66; }

expected_count="$(awk 'NR > 1 && NF { count++ } END { print count + 0 }' "$corpus_tsv")"
mapfile -d '' reports < <(find "$output_root" -mindepth 2 -maxdepth 2 -name report.json -print0 | sort -z)
if [[ ${#reports[@]} -ne $expected_count ]]; then
  echo "expected ${expected_count} reports, found ${#reports[@]}" >&2
  exit 1
fi

jq -r '.invariants[].id' "$registry" | sort > "$output_root/expected-invariants.txt"
failures=0
for report in "${reports[@]}"; do
  repository="$(jq -r '.repository' "$report")"
  outcome="$(jq -r '.toolOutcome' "$report")"
  verification_exit="$(jq -r '.verificationExit' "$report")"
  resolver_exit="$(jq -r '.dependencyResolutionExit // 99' "$report")"
  manifest_rows="$(jq -r '.dependencyManifestRows // 0' "$report")"
  contexts="$(jq -r '.dependencyCompilationContexts // 0' "$report")"
  jars="$(jq -r '.dependencyJars // 0' "$report")"

  if [[ "$outcome" != ANALYZED || "$verification_exit" != 0 ]]; then
    echo "$repository: capsule was not analyzed and replayable" >&2
    failures=$((failures + 1))
  fi
  if [[ "$resolver_exit" != 0 || "$manifest_rows" == 0 || "$contexts" == 0 || "$jars" == 0 ]]; then
    echo "$repository: expected resolved build-backed evidence; resolverExit=$resolver_exit rows=$manifest_rows contexts=$contexts jars=$jars" >&2
    failures=$((failures + 1))
  fi

  jq -r '.decisions[].invariantId' "$report" | sort > "$output_root/actual-invariants.txt"
  if ! diff -u "$output_root/expected-invariants.txt" "$output_root/actual-invariants.txt" >/dev/null; then
    echo "$repository: decision set differs from invariant registry" >&2
    failures=$((failures + 1))
  fi
done

jq -s 'sort_by(.repository)' "${reports[@]}" > "$output_root/summary.json"
jq '
  {
    repositories: length,
    resolvedBuilds: ([.[] | select(.dependencyResolutionExit == 0 and .dependencyManifestRows > 0 and .dependencyCompilationContexts > 0 and .dependencyJars > 0)] | length),
    javaFiles: (map(.javaFiles // 0) | add // 0),
    dependencyJars: (map(.dependencyJars // 0) | add // 0),
    compilationContexts: (map(.dependencyCompilationContexts // 0) | add // 0),
    analyzedRepositories: ([.[] | select(.toolOutcome == "ANALYZED")] | length),
    totalWitnesses: ([.[] | .decisions[]? | .witnessCount] | add // 0),
    invariantResults: (
      [.[] | .decisions[]?]
      | sort_by(.invariantId)
      | group_by(.invariantId)
      | map({
          invariantId: .[0].invariantId,
          conformant: ([.[] | select(.status == "CONFORMANT")] | length),
          nonConformant: ([.[] | select(.status == "NON_CONFORMANT")] | length),
          notEvaluated: ([.[] | select(.status == "NOT_EVALUATED")] | length),
          witnessCount: (map(.witnessCount) | add // 0)
        })
    )
  }
' "$output_root/summary.json" > "$output_root/metrics.json"

jq -c . "$output_root/metrics.json" | sed 's/^/BUILD_BACKED_CORPUS_METRICS /'
if [[ $failures -ne 0 ]]; then
  echo "build-backed corpus regression contract failed for ${failures} check(s)" >&2
  exit 1
fi
printf 'BUILD_BACKED_CORPUS_GATE_OK\n'
