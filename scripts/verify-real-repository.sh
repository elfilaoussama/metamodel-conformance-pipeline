#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "usage: verify-real-repository.sh repository-url commit [analyze-argument ...]" >&2
  exit 64
fi
readonly repository_url="$1"
readonly repository_commit="$2"
shift 2
[[ "$repository_url" =~ ^https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(\.git)?$ ]] \
  || { echo "repository URL must be a GitHub HTTPS repository" >&2; exit 64; }
[[ "$repository_commit" =~ ^[0-9a-f]{40}$ ]] || { echo "commit must be a 40-character lowercase SHA" >&2; exit 64; }

if [[ -n "${PIPELINE_JAR:-}" ]]; then
  pipeline_jar="${PIPELINE_JAR}"
else
  shopt -s nullglob
  pipeline_jars=(target/metamodel-conformance-pipeline-next-*.jar)
  shopt -u nullglob
  [[ ${#pipeline_jars[@]} -eq 1 ]] || { echo "Expected exactly one built pipeline JAR; found ${#pipeline_jars[@]}" >&2; exit 66; }
  pipeline_jar="${pipeline_jars[0]}"
fi
readonly pipeline_jar

integration_root="$(mktemp -d)"
readonly integration_root
trap 'rm -rf -- "${integration_root}"' EXIT

git clone --quiet --no-checkout --filter=blob:none "$repository_url" "${integration_root}/source"
git -C "${integration_root}/source" checkout --quiet --detach "$repository_commit"
[[ "$(git -C "${integration_root}/source" rev-parse HEAD)" == "$repository_commit" ]] \
  || { echo "checked-out commit differs from requested commit" >&2; exit 1; }

dependency_manifest="${integration_root}/dependency-manifest.tsv"
dependency_args=()
dependency_resolution_exit=0
if bash ./scripts/resolve-java-dependencies.sh "${integration_root}/source" "$dependency_manifest"; then
  if [[ -s "$dependency_manifest" ]] \
      && ! ./scripts/validate-java-dependency-manifest.sh "$dependency_manifest"; then
    dependency_resolution_exit=70
    printf 'Dependency manifest validation failed; analyzing without dependency evidence.\n' >&2
  elif [[ -s "$dependency_manifest" ]]; then
    dependency_args+=(--dependency-manifest "$dependency_manifest")
  fi
else
  dependency_resolution_exit=$?
  printf 'Dependency resolution unavailable (exit=%s); analyzing without dependency evidence.\n' \
    "$dependency_resolution_exit" >&2
fi

analysis_exit=0
java -jar "$pipeline_jar" analyze \
  --source "${integration_root}/source" \
  --output "${integration_root}/result" \
  "${dependency_args[@]}" "$@" || analysis_exit=$?

[[ -f "${integration_root}/result/verification-capsule.json" ]] \
  || { echo "analysis produced no verification capsule (exit=${analysis_exit})" >&2; exit 1; }
java -jar "$pipeline_jar" verify-capsule \
  --capsule "${integration_root}/result/verification-capsule.json"
printf 'REAL_REPOSITORY_VERIFICATION_OK analysisExit=%s dependencyResolutionExit=%s commit=%s\n' \
  "$analysis_exit" "$dependency_resolution_exit" "$repository_commit"
