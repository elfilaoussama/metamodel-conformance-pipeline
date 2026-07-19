#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
JAR="$ROOT/apps/swing-desktop/target/swing-desktop-0.2.0-SNAPSHOT-all.jar"

if [ -d "$HOME/.sdkman" ]; then
    source "$HOME/.sdkman/bin/sdkman-init.sh" 2>/dev/null || true
fi

if [ ! -f "$JAR" ]; then
    echo "Building uber-JAR..."
    mvn -q -DskipTests package -pl apps/swing-desktop -am || exit 1
fi

java -cp "$JAR" com.javapipeline.desktop.PipelineCli "$@"
exit $?
