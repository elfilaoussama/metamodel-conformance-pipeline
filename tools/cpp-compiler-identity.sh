#!/usr/bin/env bash
# Capture the actual selected compiler; never infer identity from an image tag.
set -euo pipefail
compiler="${1:-clang++}"
compiler_path="$(command -v "$compiler")"
[[ -f "$compiler_path" && -x "$compiler_path" ]] || { echo 'compiler is not a regular executable' >&2; exit 69; }
version_file="$(mktemp)"
trap 'rm -f -- "$version_file"' EXIT
"$compiler_path" --version >"$version_file"
target="$("$compiler_path" -dumpmachine)"
[[ "$target" =~ ^[A-Za-z0-9_.+-]+$ ]] || { echo 'unsupported target identity' >&2; exit 69; }
executable_hash="$(sha256sum "$compiler_path" | cut -d ' ' -f 1)"
version_hash="$(sha256sum "$version_file" | cut -d ' ' -f 1)"
command_base64="$(printf '%s' "$compiler_path" | base64 -w0)"
version_base64="$(base64 -w0 "$version_file")"
printf '{"commandBase64":"%s","versionBase64":"%s","identity":{"executableSha256":"%s","versionSha256":"%s","target":"%s"}}\n' \
  "$command_base64" "$version_base64" "$executable_hash" "$version_hash" "$target"
