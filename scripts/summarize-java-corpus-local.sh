#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: summarize-java-corpus-local.sh corpus.tsv output-directory invariant-registry.json" >&2
  exit 64
fi
corpus_tsv="$1"
output_root="$2"
registry="$3"

for required in jq find sort diff; do
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
  repository=$(jq -r '.repository' "$report")
  outcome=$(jq -r '.toolOutcome' "$report")
  verification_exit=$(jq -r '.verificationExit' "$report")
  dependency_resolution_exit=$(jq -r '.dependencyResolutionExit // 99' "$report")
  if [[ "$dependency_resolution_exit" != 0 ]]; then
    printf '%s: dependency evidence unavailable with resolver exit=%s; checks remain fail-closed\n' \
      "$repository" "$dependency_resolution_exit" >&2
  fi
  if [[ "$outcome" != ANALYZED || "$verification_exit" != 0 ]]; then
    printf '%s: expected ANALYZED/replayable capsule, got %s verificationExit=%s\n' \
      "$repository" "$outcome" "$verification_exit" >&2
    failures=$((failures + 1))
  fi
  jq -r '.decisions[].invariantId' "$report" | sort > "$output_root/actual-invariants.txt"
  if ! diff -u "$output_root/expected-invariants.txt" "$output_root/actual-invariants.txt" >/dev/null; then
    printf '%s: decision set differs from invariant registry\n' "$repository" >&2
    failures=$((failures + 1))
  fi
done

jq -s 'sort_by(.repository)' "${reports[@]}" > "$output_root/summary.json"
jq '
  {
    repositories: length,
    javaFiles: (map(.javaFiles // 0) | add // 0),
    dependencyManifestRows: (map(.dependencyManifestRows // 0) | add // 0),
    dependencyJars: (map(.dependencyJars // 0) | add // 0),
    dependencyCompilationContexts: (map(.dependencyCompilationContexts // 0) | add // 0),
    dependencyModules: (map(.dependencyModules // 0) | add // 0),
    repositoriesWithResolvedDependencies: ([.[] | select((.dependencyResolutionExit // 99) == 0 and (.dependencyJars // 0) > 0)] | length),
    dependencyResolutionFailures: ([.[] | select((.dependencyResolutionExit // 0) != 0)] | length),
    analyzedRepositories: ([.[] | select(.toolOutcome == "ANALYZED")] | length),
    totalWitnesses: ([.[] | .decisions[]? | .witnessCount] | add // 0),
    diagnostics: (
      [.[] | .observationDiagnostics[]?]
      | sort_by(.kind)
      | group_by(.kind)
      | map({key: .[0].kind, value: length})
      | from_entries
    ),
    invariantResults: (
      [.[] | .decisions[]?]
      | sort_by(.invariantId)
      | group_by(.invariantId)
      | map({
          invariantId: .[0].invariantId,
          conformant: ([.[] | select(.status == "CONFORMANT")] | length),
          nonConformant: ([.[] | select(.status == "NON_CONFORMANT")] | length),
          notEvaluated: ([.[] | select(.status == "NOT_EVALUATED")] | length),
          evaluatedRepositories: ([.[] | select(.status != "NOT_EVALUATED")] | length),
          repositoriesWithFindings: ([.[] | select(.status == "NON_CONFORMANT")] | length),
          witnessCount: (map(.witnessCount) | add // 0)
        })
    )
  }
' "$output_root/summary.json" > "$output_root/metrics.json"

jq -c . "$output_root/metrics.json" | sed 's/^/LOCAL_CORPUS_METRICS /'
if [[ $failures -ne 0 ]]; then
  echo "local corpus regression contract failed for ${failures} check(s)" >&2
  exit 1
fi
printf 'LOCAL_CORPUS_GATE_OK\n'
