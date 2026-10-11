#!/usr/bin/env bash
set -u -o pipefail
if [[ $# -ne 3 ]]; then
  echo 'usage: run-cpp-corridor-entry.sh owner/repository commit NEW-output-directory' >&2; exit 64
fi
repository="$1"; commit="$2"; output_root="$3"
if [[ ! "$repository" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ || ! "$commit" =~ ^[0-9a-f]{40}$ ]]; then
  echo 'invalid pinned repository identity' >&2; exit 64
fi
if [[ -n "${PIPELINE_JAR:-}" ]]; then
  pipeline_jar="$PIPELINE_JAR"
else
  shopt -s nullglob
  pipeline_jars=(target/metamodel-conformance-pipeline-next-*.jar)
  [[ ${#pipeline_jars[@]} -eq 1 ]] || { echo 'expected exactly one JAR' >&2; exit 66; }
  pipeline_jar="${pipeline_jars[0]}"
fi
mkdir -- "$output_root" || { echo 'attempt output must be new' >&2; exit 73; }
work_root="$(mktemp -d)" || exit 73
trap 'rm -rf -- "$work_root"' EXIT
source_root="$work_root/source"; result_root="$output_root/result"
manifest="$work_root/contexts.tsv"
clone_exit=0; resolution_exit=99; analysis_exit=99; replay_exit=99
domain=UNOBSERVED; provider_status=NOT_RUN
git clone --quiet --no-checkout --filter=blob:none "https://github.com/${repository}.git" "$source_root" >"$output_root/clone.log" 2>&1 || clone_exit=$?
if [[ $clone_exit -eq 0 ]]; then
  git -C "$source_root" checkout --quiet --detach "$commit" >>"$output_root/clone.log" 2>&1 || clone_exit=$?
fi
if [[ $clone_exit -eq 0 ]]; then
  resolution_exit=0
  bash ./scripts/resolve-cpp-dependencies.sh "$source_root" "$manifest" >"$output_root/dependency-resolution.log" 2>&1 || resolution_exit=$?
  dependency_args=()
  if [[ $resolution_exit -eq 0 && -s "$manifest" ]]; then
    domain=SELECTED_BUILD; provider_status=RESOLVED
    cp -- "$manifest" "$output_root/dependency-manifest.tsv"
    cp -- "$manifest.provenance.json" "$output_root/dependency-manifest.tsv.provenance.json"
    dependency_args=(--dependency-manifest "$manifest")
  else
    domain=SOURCE_ONLY; provider_status=FAILED
  fi
  # External-name hints cannot certify an unobserved graph: one analysis suffices.
  if [[ "$domain" == SELECTED_BUILD ]]; then
    if [[ -z "${CPP_OBSERVER_JAVA_HOME:-}" ]]; then
      echo 'selected C++ execution requires CPP_OBSERVER_JAVA_HOME compatible with the provider image; host relocation is not supported' >"$output_root/analysis.log"
      analysis_exit=69
    else
      python3 "$(dirname "$0")/run-cpp-selected-build.py" --source "$source_root" --manifest "$manifest" \
        --jar "$pipeline_jar" --java-home "$CPP_OBSERVER_JAVA_HOME" --output "$output_root" >"$output_root/execution-launch.log" 2>&1
      execution_exit=$?
      if [[ -f "$output_root/selected-build-execution.json" ]]; then
        read -r analysis_exit replay_exit < <(python3 - "$output_root/selected-build-execution.json" <<'PYOUTCOME'
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
    java -jar "$pipeline_jar" analyze --language cpp --source "$source_root" --output "$result_root" "${dependency_args[@]}" >"$output_root/analysis.log" 2>&1
    analysis_exit=$?
    if [[ $analysis_exit =~ ^[023]$ && -f "$result_root/verification-capsule.json" ]]; then
      java -jar "$pipeline_jar" verify-capsule --capsule "$result_root/verification-capsule.json" >"$output_root/verification.log" 2>&1
      replay_exit=$?
    fi
  fi
fi
python3 "$(dirname "$0")/cpp-attempt-report.py" "$output_root" "$repository" "$commit" "$provider_status" "$domain" "$clone_exit" "$resolution_exit" "$analysis_exit" "$replay_exit"
exit $?
