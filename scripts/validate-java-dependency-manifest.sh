#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: validate-java-dependency-manifest.sh manifest" >&2
  exit 64
fi

manifest="$1"
if [[ ! -f "$manifest" || -L "$manifest" ]]; then
  echo "dependency manifest is not a regular file: $manifest" >&2
  exit 66
fi

# Resolver manifests may contain directories and compiler outputs in addition to
# archives.  Only archive entries are validated here; the Java adapter applies
# the same regular-file and suffix checks before fingerprinting them.
python3 - "$manifest" <<'PY'
import sys
from pathlib import Path

manifest = Path(sys.argv[1])
archive_kinds = {
    "classpath",
    "module-path",
    "processor-path",
    "upgrade-module-path",
    "platform-path",
}
bad = False

with manifest.open(encoding="utf-8") as source:
    for number, line in enumerate(source, 1):
        if not line.strip():
            continue
        fields = line.rstrip("\n").rstrip("\r").split("\t")
        kind = fields[0] if fields else ""
        if kind in archive_kinds and len(fields) == 3:
            candidates = [fields[2]]
        elif kind == "patch-module" and len(fields) == 4:
            candidates = [fields[3]]
        elif len(fields) == 2 and kind not in {
            "context", "source", "generated-source", "output", "upstream"
        }:
            # Compatibility with the legacy source-root<TAB>archive format.
            candidates = [fields[1]]
        else:
            candidates = []

        for value in candidates:
            if not value.endswith(".jar"):
                continue
            path = Path(value)
            resolved = path if path.is_absolute() else Path.cwd() / path
            if path.is_symlink() or not resolved.is_file():
                print(
                    f"dependency archive is not a regular file on manifest row "
                    f"{number}: {value}",
                    file=sys.stderr,
                )
                bad = True

if bad:
    raise SystemExit(70)
PY
