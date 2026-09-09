#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
production=("$repo_root/src/main/java" "$repo_root/scripts/resolve-java-dependencies.sh" "$repo_root/scripts/resolve-maven-dependencies.sh" "$repo_root/scripts/resolve-gradle-dependencies.sh")
patterns=(
  '/mnt/data'
  'elfilaoussama|OsmGetHub'
  'src/main/java|src/test/java'
  'O-0[0-9]|O_0[0-9]'
  '3153|3,153'
  'random-java-20|paper-224'
  '--memory(=|[[:space:]])|--cpus(=|[[:space:]])|--pids-limit(=|[[:space:]])'
)
for pattern in "${patterns[@]}"; do
  if grep -REn -- "$pattern" "${production[@]}" >/tmp/mcp-audit-hit 2>/dev/null; then
    echo "forbidden production special case matched: $pattern" >&2
    cat /tmp/mcp-audit-hit >&2
    exit 1
  fi
done
printf 'NO_SPECIAL_CASE_AUDIT_OK\n'
