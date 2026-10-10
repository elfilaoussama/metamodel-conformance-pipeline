#!/usr/bin/env bash
# Python build provider: derive the declared dependency environment from a
# hash-locked uv.lock. Only declared lockfile evidence is emitted; a project
# without a lock keeps failing closed rather than guessing distributions.
#
# usage: resolve-python-dependencies.sh source-root output-manifest
# env:   PYTHON_PROVIDER_IMAGE (default ghcr.io/astral-sh/uv:python3.12-bookworm-slim)
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: resolve-python-dependencies.sh source-root output-manifest" >&2
  exit 64
fi
source_root="$1"
output_file="$2"
if [[ ! -d "$source_root" || -L "$source_root" ]]; then
  echo "source root is not a regular directory: $source_root" >&2
  exit 66
fi
source_root="$(cd "$source_root" && pwd -P)"
mkdir -p "$(dirname "$output_file")"
: > "$output_file"

resolver_image="${PYTHON_PROVIDER_IMAGE:-ghcr.io/astral-sh/uv:python3.12-bookworm-slim}"
[[ "$resolver_image" =~ ^[0-9A-Za-z._/:@-]+$ ]] || { echo "invalid PYTHON_PROVIDER_IMAGE" >&2; exit 64; }
command -v docker >/dev/null 2>&1 || { echo "Docker is required to isolate Python resolution" >&2; exit 69; }

# The declared environment is the hash-locked uv.lock; without it there is no
# deterministic dependency evidence to resolve.
[[ -f "$source_root/uv.lock" ]] \
  || { echo "no uv.lock at the Python source root; no declared dependency evidence" >&2; exit 70; }

resolution_root="${output_file}.cache"
rm -rf -- "$resolution_root"
mkdir -p "$resolution_root/home" "$resolution_root/env" "$resolution_root/tmp" "$resolution_root/worktree"

# Resolution can create project-local state (caches, build artifacts). Run it on
# a private writable copy so the original evidence mount stays read-only.
worktree_root="$resolution_root/worktree"
(
  cd "$source_root"
  tar --exclude='./.git' --exclude='./.venv' --exclude='*/__pycache__' -cf - .
) | tar -C "$worktree_root" -xf -
chmod -R u+rwX "$worktree_root"

container_source=/workspace/source
container_worktree=/workspace/worktree
container_out=/workspace/out
container_home="$container_out/home"
container_tmp="$container_out/tmp"

# --frozen installs exactly the locked distributions without rewriting the
# lockfile; --no-install-workspace installs the external locked dependencies,
# not the project's own code (the classpath analogue).
docker run --rm --cap-drop=ALL --security-opt=no-new-privileges \
  --tmpfs /tmp:rw,nosuid,nodev,noexec \
  --user "$(id -u):$(id -g)" \
  --mount "type=bind,src=$source_root,dst=$container_source,readonly" \
  --mount "type=bind,src=$worktree_root,dst=$container_worktree" \
  --mount "type=bind,src=$resolution_root,dst=$container_out" \
  -e HOME="$container_home" \
  -e TMPDIR="$container_tmp" \
  -e UV_PROJECT_ENVIRONMENT="$container_out/env" \
  -e UV_CACHE_DIR="$container_out/uv-cache" \
  -e UV_PYTHON_DOWNLOADS=automatic \
  -w "$container_worktree" "$resolver_image" \
  uv sync --frozen --no-install-workspace --no-progress

environment="$resolution_root/env"
# The venv interpreter is a symlink to the container image's Python; the venv
# marker file is the portable proof the environment exists.
[[ -f "$environment/pyvenv.cfg" ]] \
  || { echo "uv sync produced no environment; no declared dependency evidence" >&2; exit 70; }

cat > "$resolution_root/inventory.py" <<'PYINVENTORY'
import hashlib
import importlib.metadata
import pathlib
import sys
import sysconfig


def file_digest(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


site_packages = pathlib.Path(sysconfig.get_paths()["purelib"])
print("python\t" + ".".join(str(part) for part in sys.version_info[:3]))

distributions = sorted(
    importlib.metadata.distributions(path=[str(site_packages)]),
    key=lambda item: (item.metadata["Name"] or "").lower())

for distribution in distributions:
    name = distribution.metadata["Name"]
    version = distribution.version
    if not name or not version:
        continue

    files = [str(entry).replace("\\", "/") for entry in (distribution.files or [])]
    top_levels = set()
    for entry in files:
        parts = entry.split("/")
        if not parts:
            continue
        head = parts[0]
        if head.endswith((".dist-info", ".egg-info")) or head == "__pycache__":
            continue
        if len(parts) == 1 and head.endswith(".py"):
            stem = head[:-3]
            if stem != "__init__":
                top_levels.add(stem)
        elif len(parts) > 1 and parts[1] == "__init__.py":
            top_levels.add(head)

    explicit = distribution.read_text("top_level.txt")
    if explicit:
        top_levels |= {line.strip() for line in explicit.splitlines() if line.strip()}

    owned = []
    for entry in files:
        candidate = site_packages / entry
        if not candidate.is_file() or "__pycache__" in candidate.parts:
            continue
        owned.append((entry, file_digest(candidate)))
    digest = hashlib.sha256()
    for entry, content in sorted(owned):
        digest.update(entry.encode("utf-8"))
        digest.update(b"\0")
        digest.update(content.encode("utf-8"))
        digest.update(b"\0")

    print("distribution\t%s\t%s\t%s" % (name, version, digest.hexdigest()))
    for module in sorted(top_levels):
        print("module\t%s\t%s" % (module, name))
PYINVENTORY

# Inventory every installed distribution inside the container: name, version,
# top-level import names, and a deterministic content digest over its files.
docker run --rm --read-only --cap-drop=ALL --security-opt=no-new-privileges \
  --tmpfs /tmp:rw,nosuid,nodev,noexec \
  --user "$(id -u):$(id -g)" \
  --mount "type=bind,src=$resolution_root,dst=$container_out" \
  -e TMPDIR="$container_tmp" \
  "$resolver_image" \
  "$container_out/env/bin/python" "$container_out/inventory.py" > "$resolution_root/inventory.tsv"

python3 - "$resolution_root/inventory.tsv" "$output_file" <<'PYHOST'
import sys

inventory, output = sys.argv[1:]

python_version = None
distributions = []
modules = []
with open(inventory, encoding="utf-8") as source:
    for number, line in enumerate(source, 1):
        fields = line.rstrip("\n").split("\t")
        kind = fields[0]
        if kind == "python":
            if len(fields) != 2 or not fields[1]:
                raise SystemExit(f"invalid python row {number}")
            if python_version is not None and python_version != fields[1]:
                raise SystemExit("conflicting python versions in inventory")
            python_version = fields[1]
        elif kind == "distribution":
            if len(fields) != 4 or not all(fields[1:]):
                raise SystemExit(f"invalid distribution row {number}")
            distributions.append(fields[1:])
        elif kind == "module":
            if len(fields) != 3 or not all(fields[1:]):
                raise SystemExit(f"invalid module row {number}")
            modules.append(fields[1:])
        else:
            raise SystemExit(f"unknown inventory row kind on row {number}: {kind}")

if python_version is None:
    raise SystemExit("inventory has no python version")

context_id = "python|environment"
with open(output, "w", encoding="utf-8") as sink:
    sink.write("\t".join(("context", context_id, python_version)) + "\n")
    for name, version, digest in sorted(distributions):
        sink.write("\t".join(("distribution", context_id, name, version, digest)) + "\n")
    for module, name in sorted(modules):
        sink.write("\t".join(("module", context_id, module, name)) + "\n")
PYHOST

echo "PYTHON_RESOLVE_OK"