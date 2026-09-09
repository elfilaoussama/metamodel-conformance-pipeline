#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: resolve-java-dependencies.sh source-root output-manifest" >&2
  exit 64
fi
source_root="$1"; output_file="$2"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
mkdir -p "$(dirname "$output_file")"; : > "$output_file"
provider="${JAVA_DEPENDENCY_PROVIDER:-auto}"
case "$provider" in
  auto|maven|gradle) ;;
  *) echo "JAVA_DEPENDENCY_PROVIDER must be auto, maven, or gradle" >&2; exit 64 ;;
esac

# Manifest entries may refer to isolated archives, so their backing cache must
# remain available for the entire observation rather than disappearing with a
# dispatcher-owned temporary directory.
resolution_root="${output_file}.cache"
rm -rf -- "$resolution_root"
mkdir -p "$resolution_root"
maven_manifest="$resolution_root/maven.tsv"; gradle_manifest="$resolution_root/gradle.tsv"
: > "$maven_manifest"; : > "$gradle_manifest"

case "$provider" in
  auto)
    "$script_dir/resolve-maven-dependencies.sh" "$source_root" "$maven_manifest"
    "$script_dir/resolve-gradle-dependencies.sh" "$source_root" "$gradle_manifest"
    ;;
  maven) "$script_dir/resolve-maven-dependencies.sh" "$source_root" "$maven_manifest" ;;
  gradle) "$script_dir/resolve-gradle-dependencies.sh" "$source_root" "$gradle_manifest" ;;
esac

cat "$maven_manifest" "$gradle_manifest" > "$output_file"
python3 - "$output_file" <<'PY'
import sys
p=sys.argv[1]
rows=[]; contexts={}
for n,line in enumerate(open(p,encoding='utf-8'),1):
    if not line.strip(): continue
    fields=line.rstrip('\n').split('\t')
    if fields[0]=='context':
        if len(fields)!=8: raise SystemExit(f'invalid context row at {n}')
        prior=contexts.get(fields[1])
        if prior is not None and prior!=fields:
            raise SystemExit(f'compilation context collision: {fields[1]}')
        contexts[fields[1]]=fields
    rows.append(fields)
seen=set()
with open(p,'w',encoding='utf-8') as f:
    for row in rows:
        key=tuple(row)
        if key in seen: continue
        seen.add(key); f.write('\t'.join(row)+'\n')
PY

"$script_dir/validate-java-dependency-manifest.sh" "$output_file"
