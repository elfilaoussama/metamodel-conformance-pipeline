#!/usr/bin/env bash
set -euo pipefail

ROOT="$(mktemp -d)"
cleanup() { rm -rf "$ROOT"; }
trap cleanup EXIT

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "MISSING_REQUIRED_TOOL: $1" >&2
    exit 1
  }
}
need javac
need jar

# 1) Classpath precedence is semantically observable.
mkdir -p "$ROOT/first/src/ext" "$ROOT/second/src/ext" "$ROOT/consumer/src/c"
cat > "$ROOT/first/src/ext/Api.java" <<'EOF'
package ext; public final class Api { public static void onlyFirst() {} }
EOF
cat > "$ROOT/second/src/ext/Api.java" <<'EOF'
package ext; public final class Api { public static void onlySecond() {} }
EOF
cat > "$ROOT/consumer/src/c/Use.java" <<'EOF'
package c; import ext.Api; public final class Use { public void run() { Api.onlyFirst(); } }
EOF
mkdir -p "$ROOT/first/classes" "$ROOT/second/classes" "$ROOT/consumer/good" "$ROOT/consumer/bad"
javac --release 17 -d "$ROOT/first/classes" "$ROOT/first/src/ext/Api.java"
javac --release 17 -d "$ROOT/second/classes" "$ROOT/second/src/ext/Api.java"
jar --create --file "$ROOT/first.jar" -C "$ROOT/first/classes" .
jar --create --file "$ROOT/second.jar" -C "$ROOT/second/classes" .
javac --release 17 -cp "$ROOT/first.jar:$ROOT/second.jar" \
  -d "$ROOT/consumer/good" "$ROOT/consumer/src/c/Use.java"
if javac --release 17 -cp "$ROOT/second.jar:$ROOT/first.jar" \
    -d "$ROOT/consumer/bad" "$ROOT/consumer/src/c/Use.java" >/dev/null 2>&1; then
  echo "CLASSPATH_PRECEDENCE_VALIDATION_FAILED" >&2
  exit 1
fi
echo "JAVAC_CLASSPATH_PRECEDENCE_OK"

# 2) Module path and class path are not interchangeable.
mkdir -p "$ROOT/moddep/src/dep/pkg" "$ROOT/modconsumer/src/app/app"
cat > "$ROOT/moddep/src/module-info.java" <<'EOF'
module dep { exports dep.pkg; }
EOF
cat > "$ROOT/moddep/src/dep/pkg/Api.java" <<'EOF'
package dep.pkg; public final class Api { public static int value() { return 1; } }
EOF
cat > "$ROOT/modconsumer/src/module-info.java" <<'EOF'
module app { requires dep; }
EOF
cat > "$ROOT/modconsumer/src/app/app/Main.java" <<'EOF'
package app; import dep.pkg.Api; public final class Main { int x = Api.value(); }
EOF
mkdir -p "$ROOT/moddep/classes" "$ROOT/modconsumer/module-good" "$ROOT/modconsumer/classpath-bad"
mapfile -t dep_sources < <(find "$ROOT/moddep/src" -name '*.java' -type f | sort)
mapfile -t app_sources < <(find "$ROOT/modconsumer/src" -name '*.java' -type f | sort)
javac --release 17 -d "$ROOT/moddep/classes" "${dep_sources[@]}"
jar --create --file "$ROOT/dep.jar" -C "$ROOT/moddep/classes" .
javac --release 17 --module-path "$ROOT/dep.jar" \
  -d "$ROOT/modconsumer/module-good" "${app_sources[@]}"
if javac --release 17 -cp "$ROOT/dep.jar" \
    -d "$ROOT/modconsumer/classpath-bad" "${app_sources[@]}" >/dev/null 2>&1; then
  echo "MODULE_PATH_ROLE_VALIDATION_FAILED" >&2
  exit 1
fi
echo "JAVAC_MODULE_PATH_ROLE_OK"

# 3) --release and -source/-target with the same nominal source level can expose different APIs.
# RandomGenerator is part of the Java 17 platform but not Java 11. Running this
# fixture on JDK 17+ therefore demonstrates the platform distinction without
# requiring a particular newer host JDK such as 21.
mkdir -p "$ROOT/platform/src" "$ROOT/platform/release" "$ROOT/platform/source-target"
cat > "$ROOT/platform/src/UseNewApi.java" <<'EOF'
import java.util.random.RandomGenerator;
public final class UseNewApi { RandomGenerator x; }
EOF
if javac --release 11 -d "$ROOT/platform/release" \
    "$ROOT/platform/src/UseNewApi.java" >/dev/null 2>&1; then
  echo "RELEASE_PLATFORM_VALIDATION_FAILED" >&2
  exit 1
fi
javac -source 11 -target 11 -d "$ROOT/platform/source-target" \
  "$ROOT/platform/src/UseNewApi.java" >/dev/null 2>&1
echo "JAVAC_RELEASE_PLATFORM_MODE_OK"

# 4) Unconventional source/output locations are valid Java compilation inputs.
mkdir -p "$ROOT/unconventional/input-tree/x/y" "$ROOT/unconventional/opaque-output"
cat > "$ROOT/unconventional/input-tree/x/y/Thing.java" <<'EOF'
package x.y; public final class Thing {}
EOF
javac --release 17 -d "$ROOT/unconventional/opaque-output" \
  "$ROOT/unconventional/input-tree/x/y/Thing.java"
test -f "$ROOT/unconventional/opaque-output/x/y/Thing.class"
echo "JAVAC_CUSTOM_LAYOUT_OK"

echo "JAVAC_SEMANTICS_GATE_OK"
