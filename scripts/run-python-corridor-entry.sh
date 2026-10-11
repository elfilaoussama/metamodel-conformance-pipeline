#!/usr/bin/env bash
set -u -o pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: run-python-corridor-entry.sh owner/repository commit output-directory" >&2
  exit 64
fi

readonly repository="$1"
readonly commit="$2"
readonly output_root="$3"
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

if [[ ! "${repository}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
  echo "invalid repository name" >&2
  exit 64
fi
if [[ ! "${commit}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "invalid commit SHA" >&2
  exit 64
fi

mkdir -- "${output_root}" || { echo "attempt output must be new" >&2; exit 73; }
readonly work_root="$(mktemp -d)"
trap 'rm -rf -- "${work_root}"' EXIT

source_root="${work_root}/source"
result_root="${output_root}/result"
dependency_manifest="${work_root}/dependency-manifest.tsv"

clone_exit=0
git clone --quiet --no-checkout --filter=blob:none \
  "https://github.com/${repository}.git" "${source_root}" \
  >"${output_root}/clone.log" 2>&1 || clone_exit=$?
if [[ ${clone_exit} -eq 0 ]]; then
  git -C "${source_root}" checkout --quiet --detach "${commit}" \
    >>"${output_root}/clone.log" 2>&1 || clone_exit=$?
fi

python_files=0
dependency_resolution_exit=99
dependency_distribution_count=0
analysis_exit=99
verification_exit=99

if [[ ${clone_exit} -eq 0 ]]; then
  python_files="$(find "${source_root}" -type f -name '*.py' | wc -l)"

  if bash ./scripts/resolve-python-dependencies.sh "${source_root}" "${dependency_manifest}" \
      >"${output_root}/dependency-resolution.log" 2>&1; then
    dependency_resolution_exit=0
    dependency_distribution_count="$(awk -F '\t' '$1 == "distribution" {print $3}' "${dependency_manifest}" | sort -u | wc -l)"
    cp -- "${dependency_manifest}" "${output_root}/dependency-manifest.tsv"
    if [[ -f "${dependency_manifest}.provenance.json" ]]; then
      cp -- "${dependency_manifest}.provenance.json" "${output_root}/dependency-manifest.tsv.provenance.json"
    fi
  else
    dependency_resolution_exit=$?
  fi

  if [[ ${dependency_resolution_exit} -eq 0 && -s "${dependency_manifest}" ]]; then
    if [[ -z "${PYTHON_OBSERVER_JAVA_HOME:-}" ]]; then
      echo 'resolved Python execution requires PYTHON_OBSERVER_JAVA_HOME compatible with the provider image' >"${output_root}/analysis.log"
      analysis_exit=69
    else
      python3 "$(dirname "$0")/run-python-resolved-environment.py" --source "$source_root" --manifest "$dependency_manifest" \
        --jar "$pipeline_jar" --java-home "$PYTHON_OBSERVER_JAVA_HOME" --output "$output_root" >"$output_root/execution-launch.log" 2>&1
      execution_exit=$?
      if [[ -f "$output_root/resolved-environment-execution.json" ]]; then
        read -r analysis_exit verification_exit < <(python3 - "$output_root/resolved-environment-execution.json" <<'PYOUTCOME'
import json,sys
data=json.load(open(sys.argv[1]))
print(data['analysisExit'],data['verificationExit'])
PYOUTCOME
)
      else
        analysis_exit=$execution_exit
      fi
    fi
  else
    # Diagnostic source-only fallback; no imported graph or lock evidence guessed.
    java -jar "${pipeline_jar}" analyze --language python --source "${source_root}" --output "${result_root}" >"${output_root}/analysis.log" 2>&1
    analysis_exit=$?
    if [[ ${analysis_exit} =~ ^[023]$ && -f "${result_root}/verification-capsule.json" ]]; then
      java -jar "${pipeline_jar}" verify-capsule --capsule "${result_root}/verification-capsule.json" >"${output_root}/verification.log" 2>&1
      verification_exit=$?
    fi
  fi
fi

provider_status=FAILED; domain=SOURCE_ONLY
if [[ ${clone_exit} -ne 0 ]]; then
  provider_status=NOT_RUN; domain=UNOBSERVED
elif [[ ${dependency_resolution_exit} -eq 0 && -s "${dependency_manifest}" ]]; then
  # The Python adapter observes source declarations even with lock metadata.
  provider_status=RESOLVED; domain=SOURCE_ONLY
fi
python3 "$(dirname "$0")/corridor-attempt-report.py" "${output_root}" "${repository}" "${commit}" "$provider_status" "$domain" "${clone_exit}" "${dependency_resolution_exit}" "${analysis_exit}" "${verification_exit}" python
exit $?
