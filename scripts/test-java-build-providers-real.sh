#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: test-java-build-providers-real.sh repository-root" >&2
  exit 64
fi
repo_root="$(realpath -e -- "$1")"
command -v docker >/dev/null 2>&1 || { echo "Docker is required for real build-provider smoke tests" >&2; exit 69; }
: "${MAVEN_RESOLVER_IMAGE:?MAVEN_RESOLVER_IMAGE is required}"
: "${MAVEN_DEPENDENCY_CLASSPATH_GOAL:?MAVEN_DEPENDENCY_CLASSPATH_GOAL is required}"
: "${GRADLE_RESOLVER_IMAGE:?GRADLE_RESOLVER_IMAGE is required}"

work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT

maven="$work/maven"
mkdir -p "$maven/code/alpha/app" "$maven/checks/beta/app"
cat > "$maven/pom.xml" <<'POM'
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>fixture</groupId><artifactId>arbitrary-layout</artifactId><version>1</version>
  <properties><maven.compiler.release>17</maven.compiler.release></properties>
  <build>
    <sourceDirectory>${project.basedir}/code/alpha</sourceDirectory>
    <testSourceDirectory>${project.basedir}/checks/beta</testSourceDirectory>
    <directory>${project.basedir}/opaque-output</directory>
  </build>
</project>
POM
printf 'package app; public class Alpha {}\n' > "$maven/code/alpha/app/Alpha.java"
printf 'package app; public class Beta {}\n' > "$maven/checks/beta/app/Beta.java"
"$repo_root/scripts/resolve-maven-dependencies.sh" "$maven" "$work/maven.tsv"
printf 'REAL_MAVEN_PROVIDER_MANIFEST\n'
cat "$work/maven.tsv"
grep -Fq $'context\tmaven|.|compile\t.\t\t\t17\tfalse\t' "$work/maven.tsv"
grep -Fq $'source\tmaven|.|compile\tcode/alpha' "$work/maven.tsv"
grep -Fq $'source\tmaven|.|test\tchecks/beta' "$work/maven.tsv"
! grep -Eq 'src/(main|test)/java' "$work/maven.tsv"
printf 'REAL_MAVEN_PROVIDER_OK\n'

gradle="$work/gradle"
mkdir -p "$gradle/odd/primary/app" "$gradle/odd/verification/app"
cat > "$gradle/settings.gradle" <<'GRADLE'
rootProject.name = 'arbitrary-layout'
GRADLE
cat > "$gradle/build.gradle" <<'GRADLE'
plugins { id 'java' }

sourceSets {
    main {
        java.setSrcDirs(['odd/primary'])
    }
    verification {
        java.setSrcDirs(['odd/verification'])
        compileClasspath += sourceSets.main.output
        runtimeClasspath += sourceSets.main.output
    }
}

tasks.withType(JavaCompile).configureEach {
    options.release = 17
}
GRADLE
printf 'package app; public class Primary {}\n' > "$gradle/odd/primary/app/Primary.java"
printf 'package app; public class Verification extends Primary {}\n' > "$gradle/odd/verification/app/Verification.java"
"$repo_root/scripts/resolve-gradle-dependencies.sh" "$gradle" "$work/gradle.tsv"
printf 'REAL_GRADLE_PROVIDER_MANIFEST\n'
cat "$work/gradle.tsv"
grep -Eq $'^context\tgradle\|\.\|:\|main\t\.\t.*\t17\tfalse\t' "$work/gradle.tsv"
grep -Fq $'source\tgradle|.|:|main\todd/primary' "$work/gradle.tsv"
grep -Fq $'source\tgradle|.|:|verification\todd/verification' "$work/gradle.tsv"
grep -Fq $'upstream\tgradle|.|:|verification\tgradle|.|:|main' "$work/gradle.tsv"
primary_output="$(awk -F '\t' '$1 == "output" && $2 == "gradle|.|:|main" { print $3; exit }' "$work/gradle.tsv")"
[[ -n "$primary_output" ]]
grep -Fq "$(printf 'classpath\tgradle|.|:|verification\t%s' "$primary_output")" "$work/gradle.tsv"
! grep -Eq 'src/(main|test)/java' "$work/gradle.tsv"

printf 'REAL_JAVA_BUILD_PROVIDERS_OK\n'
