#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "usage: run-java-corpus-local.sh pipeline-jar corpus.tsv output-directory repository-root" >&2
  exit 64
fi

pipeline_jar="$(realpath -e -- "$1")"
corpus_tsv="$(realpath -e -- "$2")"
mkdir -p "$3"
output_root="$(realpath -e -- "$3")"
repository_root="$(realpath -e -- "$4")"

for required in java git jq bash; do
  command -v "$required" >/dev/null 2>&1 || { echo "$required is required" >&2; exit 69; }
done
[[ -f "$pipeline_jar" && ! -L "$pipeline_jar" ]] || { echo "pipeline JAR is not a regular file" >&2; exit 66; }
[[ -f "$corpus_tsv" && ! -L "$corpus_tsv" ]] || { echo "corpus TSV is not a regular file" >&2; exit 66; }
[[ -x "$repository_root/scripts/run-corpus-entry.sh" ]] || { echo "run-corpus-entry.sh is unavailable" >&2; exit 66; }
[[ -f "$repository_root/scripts/summarize-java-corpus-local.sh" && ! -L "$repository_root/scripts/summarize-java-corpus-local.sh" ]] || { echo "local summarizer is unavailable" >&2; exit 66; }

mkdir -p "$output_root"
expected_header=$'repository\tcommit\tdefault_branch\tsize_kb\trank_hash'
IFS= read -r header < "$corpus_tsv"
[[ "$header" == "$expected_header" ]] || { echo "unexpected corpus TSV header" >&2; exit 65; }

index=0
while IFS=$'\t' read -r repository commit default_branch size_kb rank_hash; do
  [[ -n "$repository" ]] || continue
  index=$((index + 1))
  entry_dir=$(printf '%s/%02d' "$output_root" "$index")
  mkdir -p "$entry_dir"
  printf 'LOCAL_CORPUS_START %s %s\n' "$repository" "$commit"
  (
    cd "$repository_root"
    PIPELINE_JAR="$pipeline_jar" ./scripts/run-corpus-entry.sh \
      "$repository" "$commit" "$entry_dir"
  ) | tee "$entry_dir/runner.log"
done < <(tail -n +2 "$corpus_tsv")

bash "$repository_root/scripts/summarize-java-corpus-local.sh" \
  "$corpus_tsv" "$output_root" "$repository_root/src/main/resources/invariants/registry.json"
