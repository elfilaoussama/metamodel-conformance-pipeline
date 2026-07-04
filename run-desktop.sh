#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
JAR="$ROOT/apps/swing-desktop/target/swing-desktop-0.2.0-SNAPSHOT-all.jar"

cd "$ROOT"
if [[ ! -f "$JAR" ]]; then
  mvn -q -DskipTests package
fi
exec java -jar "$JAR"
