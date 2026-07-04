#!/usr/bin/env bash
set -euo pipefail
if [ $# -lt 2 ]; then
    echo "Usage: $0 <extraction.json> <output.aie>"
    exit 1
fi
DIR="$(cd "$(dirname "$0")" && pwd)"
mvn -f "$DIR/pom.xml" exec:java -q -Dexec.mainClass="com.verification.mapper.JsonToAieMapper" \
    -Dexec.args="$1 $2" 2>&1
