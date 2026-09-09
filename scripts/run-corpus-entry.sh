#!/usr/bin/env bash
set -u -o pipefail

if [[ $# -ne 3 && $# -ne 4 ]]; then
  echo "usage: run-corpus-entry.sh owner/repository commit output-directory [auto|maven|gradle]" >&2
  exit 64
fi
readonly repository="$1"
readonly commit="$2"
readonly output_root="$3"
readonly dependency_provider="${4:-auto}"
if [[ -n "${PIPELINE_JAR:-}" ]]; then
  pipeline_jar="${PIPELINE_JAR}"
else
  shopt -s nullglob
  pipeline_jars=(target/metamodel-conformance-pipeline-next-*.jar)
  shopt -u nullglob
  if [[ ${#pipeline_jars[@]} -ne 1 ]]; then
    echo "Expected exactly one built pipeline JAR; found ${#pipeline_jars[@]}" >&2
    exit 66
  fi
  pipeline_jar="${pipeline_jars[0]}"
fi
readonly pipeline_jar
if [[ ! "${repository}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then echo "invalid repository name" >&2; exit 64; fi
if [[ ! "${commit}" =~ ^[0-9a-f]{40}$ ]]; then echo "invalid commit SHA" >&2; exit 64; fi
if [[ ! "${dependency_provider}" =~ ^(auto|maven|gradle)$ ]]; then echo "invalid dependency provider" >&2; exit 64; fi
mkdir -p "${output_root}"
readonly work_root="$(mktemp -d)"
trap 'rm -rf -- "${work_root}"' EXIT
source_root="${work_root}/source"
result_root="${output_root}/result"
dependency_manifest="${work_root}/dependency-manifest.tsv"
mkdir -p "${result_root}"
clone_exit=0
git clone --quiet --no-checkout --filter=blob:none "https://github.com/${repository}.git" "${source_root}" >"${output_root}/clone.log" 2>&1 || clone_exit=$?
if [[ ${clone_exit} -eq 0 ]]; then git -C "${source_root}" checkout --quiet --detach "${commit}" >>"${output_root}/clone.log" 2>&1 || clone_exit=$?; fi

java_files=0
dependency_resolution_exit=99
dependency_manifest_rows=0
dependency_jar_count=0
dependency_context_count=0
dependency_module_count=0
analysis_exit=99
verification_exit=99

if [[ ${clone_exit} -eq 0 ]]; then
  java_files="$(find "${source_root}" -type f -name '*.java' | wc -l)"
  dependency_args=()
  if JAVA_DEPENDENCY_PROVIDER="${dependency_provider}" bash ./scripts/resolve-java-dependencies.sh "${source_root}" "${dependency_manifest}" \
      >"${output_root}/dependency-resolution.log" 2>&1; then
    dependency_resolution_exit=0
    if [[ -s "${dependency_manifest}" ]] && \
        ./scripts/validate-java-dependency-manifest.sh "${dependency_manifest}" \
        >>"${output_root}/dependency-resolution.log" 2>&1; then
      dependency_args+=(--dependency-manifest "${dependency_manifest}")
      dependency_manifest_rows="$(wc -l < "${dependency_manifest}")"
      dependency_context_count="$(awk -F '\t' '$1 == "context" {print $2}' "${dependency_manifest}" | sort -u | wc -l)"
      dependency_module_count="$(awk -F '\t' '$1 == "context" {print $3}' "${dependency_manifest}" | sort -u | wc -l)"
      dependency_jar_count="$(awk -F '\t' '
        $1 ~ /^(classpath|module-path|processor-path|upgrade-module-path|platform-path)$/ && $3 ~ /\.jar$/ {print $3}
        $1 == "patch-module" && $4 ~ /\.jar$/ {print $4}
      ' "${dependency_manifest}" | sort -u | wc -l)"
    elif [[ -s "${dependency_manifest}" ]]; then
      dependency_resolution_exit=70
      printf 'Dependency manifest validation failed; analyzing without dependency evidence.\n' \
        >>"${output_root}/dependency-resolution.log"
    fi
  else
    dependency_resolution_exit=$?
  fi

  if [[ ${#dependency_args[@]} -eq 0 && ${dependency_resolution_exit} -ne 0 ]]; then
    printf 'Dependency resolution unavailable (exit=%s); analyzing without dependency evidence.\n' \
      "${dependency_resolution_exit}" >"${output_root}/analysis.log"
  else
    : >"${output_root}/analysis.log"
  fi
  java -jar "${pipeline_jar}" analyze --source "${source_root}" --output "${result_root}" \
    "${dependency_args[@]}" >>"${output_root}/analysis.log" 2>&1
  analysis_exit=$?
  if [[ -f "${result_root}/verification-capsule.json" ]]; then
    java -jar "${pipeline_jar}" verify-capsule --capsule "${result_root}/verification-capsule.json" >"${output_root}/verification.log" 2>&1
    verification_exit=$?
  fi
fi

common_jq_args=(
  --arg repository "${repository}"
  --arg commit "${commit}"
  --argjson cloneExit "${clone_exit}"
  --argjson dependencyResolutionExit "${dependency_resolution_exit}"
  --argjson dependencyManifestRows "${dependency_manifest_rows}"
  --argjson dependencyJars "${dependency_jar_count}"
  --argjson dependencyCompilationContexts "${dependency_context_count}"
  --argjson dependencyModules "${dependency_module_count}"
  --argjson analysisExit "${analysis_exit}"
  --argjson javaFiles "${java_files}"
)
if [[ -f "${result_root}/verification-capsule.json" ]]; then
  jq "${common_jq_args[@]}" --argjson verificationExit "${verification_exit}" '
    {
      repository: $repository, commit: $commit, javaFiles: $javaFiles,
      dependencyManifestRows: $dependencyManifestRows,
      dependencyJars: $dependencyJars,
      dependencyCompilationContexts: $dependencyCompilationContexts,
      dependencyModules: $dependencyModules,
      cloneExit: $cloneExit, dependencyResolutionExit: $dependencyResolutionExit,
      analysisExit: $analysisExit, verificationExit: $verificationExit,
      toolOutcome: (if $verificationExit == 0 then "ANALYZED" else "CAPSULE_INVALID" end),
      observationDiagnostics: [.observationDiagnostics[] | {kind, sourcePath, line, message}],
      decisions: [.decisions[] | {invariantId, status, witnessCount: (.witnesses | length)}]
    }' "${result_root}/verification-capsule.json" >"${output_root}/report.json"
else
  jq -n "${common_jq_args[@]}" '
    {
      repository: $repository, commit: $commit, javaFiles: $javaFiles,
      dependencyManifestRows: $dependencyManifestRows,
      dependencyJars: $dependencyJars,
      dependencyCompilationContexts: $dependencyCompilationContexts,
      dependencyModules: $dependencyModules,
      cloneExit: $cloneExit, dependencyResolutionExit: $dependencyResolutionExit,
      analysisExit: $analysisExit, verificationExit: 99,
      toolOutcome: "TOOL_FAILURE", decisions: []
    }' >"${output_root}/report.json"
fi
jq -c . "${output_root}/report.json" | sed 's/^/CORPUS_RESULT /'
exit 0
