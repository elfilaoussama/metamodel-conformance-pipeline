#!/usr/bin/env bash
set -euo pipefail

ARGS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        -r) ARGS+=("-r" "$2"); shift 2 ;;
        -i) ARGS+=("-i" "$2"); shift 2 ;;
        -o) ARGS+=("-o" "$2"); shift 2 ;;
        --strict) ARGS+=("--strict"); shift ;;
        --details) ARGS+=("--details"); shift ;;
        --report) ARGS+=("--report" "$2"); shift 2 ;;
        --csv) ARGS+=("--csv" "$2"); shift 2 ;;
        *) echo "Unknown: $1"; exit 1 ;;
    esac
done

DIR="$(cd "$(dirname "$0")" && pwd)"
mvn -f "$DIR/pom.xml" exec:java -q -Dexec.args="${ARGS[*]}" 2>&1
