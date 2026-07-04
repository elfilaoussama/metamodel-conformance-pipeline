#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
echo "Building standalone-verifier..."
mvn -f "$DIR/pom.xml" clean compile -q
echo "Build successful."
