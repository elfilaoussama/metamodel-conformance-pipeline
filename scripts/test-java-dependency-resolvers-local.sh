#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then echo "usage: test-java-dependency-resolvers-local.sh repository-root" >&2; exit 64; fi
repo_root="$(realpath -e -- "$1")"
for script in resolve-maven-dependencies.sh resolve-gradle-dependencies.sh resolve-java-dependencies.sh; do
  [[ -f "$repo_root/scripts/$script" && ! -L "$repo_root/scripts/$script" ]] || { echo "$script unavailable" >&2; exit 66; }
  bash -n "$repo_root/scripts/$script"
done
work="$(mktemp -d)"; trap 'rm -rf -- "$work"' EXIT
mkdir -p "$work/bin"
arguments="$work/docker-arguments.txt"; : > "$arguments"

cat > "$work/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_DOCKER_ARGUMENTS"
out_mount=''
worktree_mount=''
init_mount=''
for argument in "$@"; do
  case "$argument" in
    type=bind,src=*,dst=/workspace/out) out_mount="${argument#type=bind,src=}"; out_mount="${out_mount%%,dst=/workspace/out}";;
    type=bind,src=*,dst=/workspace/worktree) worktree_mount="${argument#type=bind,src=}"; worktree_mount="${worktree_mount%%,dst=/workspace/worktree}";;
    type=bind,src=*,dst=/workspace/mcp-init.gradle,readonly) init_mount="${argument#type=bind,src=}"; init_mount="${init_mount%%,dst=/workspace/mcp-init.gradle,readonly}";;
  esac
done
[[ -n "$out_mount" ]]
map_out() { local p="$1"; printf '%s/%s\n' "$out_mount" "${p#/workspace/out/}"; }
all="$*"
if [[ "$all" == *"help:effective-pom"* ]]; then
  output=''
  for argument in "$@"; do [[ "$argument" == -Doutput=* ]] && output="${argument#*=}"; done
  host="$(map_out "$output")"; mkdir -p "$(dirname "$host")"
  cat > "$host" <<'XML'
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<build><sourceDirectory>/workspace/source/code/prod</sourceDirectory><testSourceDirectory>/workspace/source/code/check</testSourceDirectory>
<outputDirectory>/workspace/source/out/prod</outputDirectory><testOutputDirectory>/workspace/source/out/check</testOutputDirectory>
</build><properties><maven.compiler.release>17</maven.compiler.release></properties></project>
XML
elif [[ "$all" == *"fake:classpath"* ]]; then
  output=''
  for argument in "$@"; do [[ "$argument" == -Dmdep.outputFile=* ]] && output="${argument#*=}"; done
  mkdir -p "$out_mount/repository"; printf jar > "$out_mount/repository/dependency.jar"
  host="$(map_out "$output")"; mkdir -p "$(dirname "$host")"; printf '/workspace/out/repository/dependency.jar\n' > "$host"
elif [[ "$all" == *"__mcpObserveJavaContexts"* ]]; then
  [[ -n "$init_mount" ]]
  [[ -n "$worktree_mount" ]]
  grep -Fq "replace('\\n',' ')" "$init_mount"
  grep -Fq "mcp.build.output is required" "$init_mount"
  grep -Fq "def observationTask = null" "$init_mount"
  grep -Fq "observationTask = project.tasks.register('__mcpObserveJavaContexts')" "$init_mount"
  grep -Fq 'def owners = outputOwners[entry.path]' "$init_mount"
  ! grep -Fq 'mainContextOwners' "$init_mount"
  ! grep -Fq 'projectBuildRoots' "$init_mount"
  ! grep -Fq "entry.path.endsWith('.jar')" "$init_mount"
  ! grep -Fq "javaCompiler.orNull" "$init_mount"
  output=''
  for argument in "$@"; do [[ "$argument" == -Dmcp.context.output=* ]] && output="${argument#*=}"; done
  mkdir -p "$out_mount/gradle-cache"; printf jar > "$out_mount/gradle-cache/library.jar"
  host="$(map_out "$output")"; mkdir -p "$(dirname "$host")"
  cat > "$host" <<'ROWS'
context	gradle|.|:|custom	.	17	17		false	jdk-17-test
source	gradle|.|:|custom	weird/alpha
output	gradle|.|:|custom	/workspace/worktree/build/custom-classes
classpath	gradle|.|:|custom	/workspace/out/gradle-cache/library.jar
ROWS
else
  echo "unexpected fake docker invocation: $all" >&2; exit 98
fi
DOCKER
chmod +x "$work/bin/docker"

maven="$work/maven-project"
mkdir -p "$maven/code/prod/app" "$maven/code/check/app"
printf 'package app; class Main {}\n' > "$maven/code/prod/app/Main.java"
printf 'package app; class Check {}\n' > "$maven/code/check/app/Check.java"
cat > "$maven/pom.xml" <<'POM'
<project><modelVersion>4.0.0</modelVersion><groupId>x</groupId><artifactId>x</artifactId><version>1</version>
<build><sourceDirectory>code/prod</sourceDirectory><testSourceDirectory>code/check</testSourceDirectory></build></project>
POM
PATH="$work/bin:$PATH" FAKE_DOCKER_ARGUMENTS="$arguments" \
MAVEN_RESOLVER_IMAGE=test-maven MAVEN_DEPENDENCY_CLASSPATH_GOAL=fake:classpath \
  "$repo_root/scripts/resolve-maven-dependencies.sh" "$maven" "$work/maven.tsv"
grep -Fq $'context\tmaven|.|compile\t.\t\t\t17\tfalse\t' "$work/maven.tsv"
grep -Fq $'source\tmaven|.|compile\tcode/prod' "$work/maven.tsv"
grep -Fq $'source\tmaven|.|test\tcode/check' "$work/maven.tsv"
grep -Fq $'upstream\tmaven|.|test\tmaven|.|compile' "$work/maven.tsv"
grep -Fq $'classpath\tmaven|.|compile\t' "$work/maven.tsv"
! grep -q 'src/main/java\|src/test/java' "$work/maven.tsv"

# A test-only effective model must not acquire a nonexistent compile-context edge.
test_only="$work/maven-test-only"
mkdir -p "$test_only/code/check/app"
printf 'package app; class Check {}\n' > "$test_only/code/check/app/Check.java"
cp "$maven/pom.xml" "$test_only/pom.xml"
PATH="$work/bin:$PATH" FAKE_DOCKER_ARGUMENTS="$arguments" \
MAVEN_RESOLVER_IMAGE=test-maven MAVEN_DEPENDENCY_CLASSPATH_GOAL=fake:classpath \
  "$repo_root/scripts/resolve-maven-dependencies.sh" "$test_only" "$work/maven-test-only.tsv"
grep -Fq $'context\tmaven|.|test\t' "$work/maven-test-only.tsv"
! grep -q '^upstream' "$work/maven-test-only.tsv"

gradle="$work/gradle-project"
mkdir -p "$gradle/weird/alpha/app"
printf 'package app; class Custom {}\n' > "$gradle/weird/alpha/app/Custom.java"
printf 'plugins { id "java" }\nsourceSets { custom { java.srcDir("weird/alpha") } }\n' > "$gradle/build.gradle"
printf 'rootProject.name="fixture"\n' > "$gradle/settings.gradle"
printf '#!/usr/bin/env bash\nexit 99\n' > "$gradle/gradlew"
PATH="$work/bin:$PATH" FAKE_DOCKER_ARGUMENTS="$arguments" GRADLE_RESOLVER_IMAGE=test-gradle \
  "$repo_root/scripts/resolve-gradle-dependencies.sh" "$gradle" "$work/gradle.tsv"
grep -Fq $'source\tgradle|.|:|custom\tweird/alpha' "$work/gradle.tsv"
grep -Fq $'classpath\tgradle|.|:|custom\t' "$work/gradle.tsv"
grep -Fq "$work/gradle.tsv.cache/worktree/build/custom-classes" "$work/gradle.tsv"
! grep -q 'src/main/java\|src/test/java' "$work/gradle.tsv"

# Included builds inherit the nearest wrapper instead of silently switching to
# the provider image's system Gradle.
mkdir -p "$gradle/included/src/main/java/app"
printf 'package app; class Included {}\n' > "$gradle/included/src/main/java/app/Included.java"
printf 'rootProject.name="included"\n' > "$gradle/included/settings.gradle"
printf 'plugins { id "java" }\n' > "$gradle/included/build.gradle"
PATH="$work/bin:$PATH" FAKE_DOCKER_ARGUMENTS="$arguments" GRADLE_RESOLVER_IMAGE=test-gradle \
  "$repo_root/scripts/resolve-gradle-dependencies.sh" "$gradle" "$work/gradle-included.tsv"
[[ $(grep -c -- ' sh /workspace/worktree/gradlew ' "$arguments") -ge 2 ]]

grep -q -- '--read-only' "$arguments"
grep -q -- '--cap-drop=ALL' "$arguments"
grep -q -- '--security-opt=no-new-privileges' "$arguments"
grep -q -- '--project-cache-dir /workspace/out/project-cache-0' "$arguments"
grep -q -- '--no-configuration-cache' "$arguments"
grep -q -- '--no-configure-on-demand' "$arguments"
grep -q -- '-Dmcp.build.output=/workspace/out/build-0' "$arguments"
grep -q -- 'type=bind,src=.*dst=/workspace/source,readonly' "$arguments"
grep -q -- 'type=bind,src=.*dst=/workspace/worktree' "$arguments"
grep -q -- '-Dmcp.repository.root=/workspace/worktree' "$arguments"
grep -q -- ' sh /workspace/worktree/gradlew ' "$arguments"
! grep -q -- ' bash /workspace/worktree/gradlew ' "$arguments"
! grep -q -- '--memory\|--cpus\|--pids-limit' "$arguments"

# Dispatcher preserves independent build observations instead of flattening them.
dispatch="$work/dispatch"; mkdir -p "$dispatch/scripts" "$dispatch/project"
cp "$repo_root/scripts/resolve-java-dependencies.sh" "$dispatch/scripts/"
cp "$repo_root/scripts/validate-java-dependency-manifest.sh" "$dispatch/scripts/"
cat > "$dispatch/scripts/resolve-maven-dependencies.sh" <<'M'
#!/usr/bin/env bash
mkdir -p "$2.cache"
printf jar > "$2.cache/maven.jar"
printf 'context\tmaven|.|compile\t.\t\t\t17\tfalse\t\nsource\tmaven|.|compile\tcode/shared\nclasspath\tmaven|.|compile\t%s.cache/maven.jar\n' "$2" > "$2"
M
cat > "$dispatch/scripts/resolve-gradle-dependencies.sh" <<'G'
#!/usr/bin/env bash
mkdir -p "$2.cache"
printf jar > "$2.cache/gradle.jar"
printf 'context\tgradle|.|:|main\t.\t17\t17\t\tfalse\tjdk-17\nsource\tgradle|.|:|main\tcode/shared\nclasspath\tgradle|.|:|main\t%s.cache/gradle.jar\n' "$2" > "$2"
G
chmod +x "$dispatch/scripts"/*.sh
"$dispatch/scripts/resolve-java-dependencies.sh" "$dispatch/project" "$work/merged.tsv"
[[ $(grep -c '^context' "$work/merged.tsv") -eq 2 ]]
grep -Fq 'maven|.|compile' "$work/merged.tsv"
grep -Fq 'gradle|.|:|main' "$work/merged.tsv"
grep -Fq "$work/merged.tsv.cache/maven.tsv.cache/maven.jar" "$work/merged.tsv"
grep -Fq "$work/merged.tsv.cache/gradle.tsv.cache/gradle.jar" "$work/merged.tsv"
[[ -f "$work/merged.tsv.cache/maven.tsv.cache/maven.jar" ]]
[[ -f "$work/merged.tsv.cache/gradle.tsv.cache/gradle.jar" ]]

JAVA_DEPENDENCY_PROVIDER=maven "$dispatch/scripts/resolve-java-dependencies.sh" "$dispatch/project" "$work/maven-only.tsv"
[[ $(grep -c '^context' "$work/maven-only.tsv") -eq 1 ]]
grep -Fq 'maven|.|compile' "$work/maven-only.tsv"
! grep -Fq 'gradle|.|:|main' "$work/maven-only.tsv"
JAVA_DEPENDENCY_PROVIDER=gradle "$dispatch/scripts/resolve-java-dependencies.sh" "$dispatch/project" "$work/gradle-only.tsv"
[[ $(grep -c '^context' "$work/gradle-only.tsv") -eq 1 ]]
grep -Fq 'gradle|.|:|main' "$work/gradle-only.tsv"
! grep -Fq 'maven|.|compile' "$work/gradle-only.tsv"
if JAVA_DEPENDENCY_PROVIDER=unknown "$dispatch/scripts/resolve-java-dependencies.sh" "$dispatch/project" "$work/invalid-provider.tsv" >/dev/null 2>&1; then
  echo 'dispatcher accepted an invalid provider selection' >&2
  exit 1
fi

invalid="$work/invalid.tsv"
printf 'classpath\tctx\t%s\n' "$work/missing.jar" > "$invalid"
if "$repo_root/scripts/validate-java-dependency-manifest.sh" "$invalid" >/dev/null 2>&1; then
  echo 'manifest validator accepted a missing archive' >&2
  exit 1
fi

printf 'LOCAL_JAVA_DEPENDENCY_RESOLVERS_OK\n'
