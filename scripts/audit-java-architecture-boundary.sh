#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
adapter="$repo_root/src/main/java/metamodel/conformance/pipeline/adapter/java"
if grep -REni -- 'maven|gradle|pom\.xml|build\.gradle|settings\.gradle' "$adapter" >/tmp/mcp-arch-hit; then
  echo 'build-system vocabulary leaked beyond provider boundary:' >&2
  cat /tmp/mcp-arch-hit >&2
  exit 1
fi
for removed in JavaSourceSets.java JavaCompilerProfile.java JavacSourceSetContext.java; do
  [[ ! -e "$adapter/$removed" ]] || { echo "obsolete layout helper remains: $removed" >&2; exit 1; }
done
printf 'JAVA_ARCHITECTURE_BOUNDARY_AUDIT_OK\n'
