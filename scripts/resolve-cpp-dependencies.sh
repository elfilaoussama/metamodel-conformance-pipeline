#!/usr/bin/env bash
# C++ build provider: derive compilation contexts from a CMake compilation
# database. Only declared provider facts are emitted; a project without CMake
# keeps failing closed rather than guessing include directories or macros.
#
# usage: resolve-cpp-dependencies.sh source-root output-manifest [target-selection.json]
# env:   CPP_PROVIDER_IMAGE (default silkeh/clang:14, clang 14 + cmake)
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "usage: resolve-cpp-dependencies.sh source-root output-manifest [target-selection.json]" >&2
  exit 64
fi
source_root="$1"
output_file="$2"
mkdir -p "$(dirname "$output_file")"
: > "$output_file"
rm -f -- "${output_file}.provenance.json"
if [[ ! -d "$source_root" || -L "$source_root" ]]; then
  echo "source root is not a regular directory: $source_root" >&2
  exit 66
fi
source_root="$(cd "$source_root" && pwd -P)"

resolver_image="${CPP_PROVIDER_IMAGE:-silkeh/clang:14}"
[[ "$resolver_image" =~ ^[0-9A-Za-z._/:@-]+$ ]] || { echo "invalid CPP_PROVIDER_IMAGE" >&2; exit 64; }
command -v docker >/dev/null 2>&1 || { echo "Docker is required to isolate CMake resolution" >&2; exit 69; }
# Resolve a mutable tag once; configure and identity capture consume one image ID.
if ! resolver_image_id="$(docker image inspect "$resolver_image" --format '{{.Id}}' 2>/dev/null)"; then
  docker pull "$resolver_image"
  resolver_image_id="$(docker image inspect "$resolver_image" --format '{{.Id}}')"
fi

# The CMake source root must contain a top-level CMakeLists.txt; subdirectory
# fragments are only compiled as part of the root configure.
[[ -f "$source_root/CMakeLists.txt" ]] \
  || { echo "no CMakeLists.txt at the C++ source root; no declared build evidence" >&2; exit 70; }

resolution_root="${output_file}.cache"
rm -rf -- "$resolution_root"
mkdir -p "$resolution_root/home" "$resolution_root/build" "$resolution_root/tmp" "$resolution_root/worktree"

# CMake configuration can create project-local state. Run it on a private
# writable copy so the original evidence mount remains read-only.
worktree_root="$resolution_root/worktree"
(
  cd "$source_root"
  tar --exclude='./.git' --exclude='./build' --exclude='*/build' -cf - .
) | tar -C "$worktree_root" -xf -
chmod -R u+rwX "$worktree_root"

container_source=/workspace/source
container_worktree=/workspace/worktree
container_out=/workspace/out
container_home="$container_out/home"
container_tmp="$container_out/tmp"

# A declared provider fact, not a guess: the compiler is pinned to the image's
# clang++. Actual compiler identity is captured below; compatibility with the
# observing compiler must be checked before source observation.
compile_flags=(-DCMAKE_EXPORT_COMPILE_COMMANDS=ON -DCMAKE_BUILD_TYPE= -DCMAKE_CXX_COMPILER=clang++ -DCMAKE_C_COMPILER=clang)

# Ask the actual configured generator for target/source/link facts. A fresh
# resolution cache gives one reply index; no build-file text parsing is used.
mkdir -p "$resolution_root/build/.cmake/api/v1/query"
: > "$resolution_root/build/.cmake/api/v1/query/codemodel-v2"

docker run --rm --read-only --cap-drop=ALL --security-opt=no-new-privileges \
  --tmpfs /tmp:rw,nosuid,nodev,noexec \
  --user "$(id -u):$(id -g)" \
  --mount "type=bind,src=$source_root,dst=$container_source,readonly" \
  --mount "type=bind,src=$worktree_root,dst=$container_worktree" \
  --mount "type=bind,src=$resolution_root,dst=$container_out" \
  -e HOME="$container_home" \
  -e TMPDIR="$container_tmp" \
  -w "$container_worktree" "$resolver_image_id" \
  cmake -S "$container_worktree" -B "$container_out/build" "${compile_flags[@]}"

compile_commands="$resolution_root/build/compile_commands.json"
if [[ ! -f "$compile_commands" ]]; then
  echo "CMake produced no compile_commands.json; no declared build evidence" >&2
  exit 70
fi

# Probe the same immutable image and compiler command the database declares.
identity_script="$(realpath -e "$(dirname "$0")/cpp-compiler-identity.sh")"
docker run --rm --network=none --read-only --cap-drop=ALL --security-opt=no-new-privileges \
  --tmpfs /tmp:rw,nosuid,nodev,noexec \
  --mount "type=bind,src=$identity_script,dst=/compiler-identity.sh,readonly" \
  "$resolver_image_id" bash /compiler-identity.sh clang++ >"$resolution_root/compiler-identity.json"

python3 "$(dirname "$0")/cpp-cmake-model.py" "$resolution_root/build/.cmake/api/v1/reply" \
  "$resolution_root/configured-build-model.json"
manifest_arguments=("$compile_commands" "$output_file" "$worktree_root"
  "$resolution_root/compiler-identity.json" "$container_worktree" "$resolution_root/configured-build-model.json")
if [[ $# -eq 3 ]]; then
  [[ -f "$3" && ! -L "$3" ]] || { echo "target selection must be a regular file" >&2; exit 66; }
  manifest_arguments+=("$3")
fi
python3 "$(dirname "$0")/cpp-context-manifest.py" "${manifest_arguments[@]}"

# Preserve the actual provider image as an execution recipe, separate from the
# compiler byte receipt and semantic context. Never infer an image from a tag
# later when launching the observer.
python3 - "$output_file" "$resolver_image_id" "$source_root" "$resolution_root" <<'PYEXECUTION'
import json
from pathlib import Path
import sys
manifest, image, source, cache = sys.argv[1:]
path = Path(manifest + '.provenance.json')
data = json.loads(path.read_text())
data['configuredBuildCapture'] = json.loads((Path(cache) / 'configured-build-model.json.capture.json').read_text())
data['providerExecution'] = dict(recipeVersion=1, imageId=image,
    originalSourceRoot=str(Path(source).resolve()), cacheRoot=str(Path(cache).resolve()),
    configuredSourceRoot='/workspace/worktree', originalSourceMount='/workspace/source',
    cacheMount='/workspace/out')
path.write_text(json.dumps(data, indent=2) + '\n')
PYEXECUTION

echo "CPP_RESOLVE_OK"
