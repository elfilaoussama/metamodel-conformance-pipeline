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
! grep -Eq $'^module-path\t' "$work/maven.tsv"
printf 'REAL_MAVEN_PROVIDER_OK\n'

maven_modular="$work/maven-modular"
mkdir -p "$maven_modular/src/main/java/app"
cat > "$maven_modular/pom.xml" <<'POM'
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>fixture</groupId><artifactId>modular-app</artifactId><version>1</version>
  <properties><maven.compiler.release>17</maven.compiler.release></properties>
  <dependencies>
    <dependency>
      <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.13</version>
    </dependency>
  </dependencies>
</project>
POM
cat > "$maven_modular/src/main/java/module-info.java" <<'JAVA'
module app.modular {
    requires org.slf4j;
    exports app;
}
JAVA
cat > "$maven_modular/src/main/java/app/Alpha.java" <<'JAVA'
package app;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public class Alpha {
    private static final Logger LOG = LoggerFactory.getLogger(Alpha.class);
}
JAVA
"$repo_root/scripts/resolve-maven-dependencies.sh" "$maven_modular" "$work/maven-modular.tsv"
printf 'REAL_MAVEN_MODULAR_MANIFEST\n'
cat "$work/maven-modular.tsv"
grep -Eq $'^module-path\tmaven\|\.\|compile\t.*slf4j-api-2\.0\.13\.jar$' "$work/maven-modular.tsv"
! grep -Eq $'^classpath\tmaven\|\.\|compile\t.*slf4j-api-2\.0\.13\.jar$' "$work/maven-modular.tsv"
printf 'REAL_MAVEN_MODULAR_PROVIDER_OK\n'

# A descriptor the compiler execution excludes is not compiled: the context is
# non-modular and must keep dependencies on the classpath, not the module path.
maven_modular_excluded="$work/maven-modular-excluded"
mkdir -p "$maven_modular_excluded/src/main/java/app"
cat > "$maven_modular_excluded/pom.xml" <<'POM'
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>fixture</groupId><artifactId>modular-excluded</artifactId><version>1</version>
  <properties><maven.compiler.release>17</maven.compiler.release></properties>
  <dependencies>
    <dependency>
      <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.13</version>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <executions>
          <execution>
            <id>default-compile</id>
            <configuration>
              <excludes><exclude>module-info.java</exclude></excludes>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
POM
cat > "$maven_modular_excluded/src/main/java/module-info.java" <<'JAVA'
module app.modular {
    requires org.slf4j;
    exports app;
}
JAVA
cat > "$maven_modular_excluded/src/main/java/app/Alpha.java" <<'JAVA'
package app;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public class Alpha {
    private static final Logger LOG = LoggerFactory.getLogger(Alpha.class);
}
JAVA
"$repo_root/scripts/resolve-maven-dependencies.sh" "$maven_modular_excluded" "$work/maven-modular-excluded.tsv"
printf 'REAL_MAVEN_MODULAR_EXCLUDED_MANIFEST\n'
cat "$work/maven-modular-excluded.tsv"
grep -Fq $'exclude\tmaven|.|compile\tsrc/main/java/module-info.java' "$work/maven-modular-excluded.tsv"
grep -Eq $'^classpath\tmaven\|\.\|compile\t.*slf4j-api-2\.0\.13\.jar$' "$work/maven-modular-excluded.tsv"
! grep -Eq $'^module-path\t' "$work/maven-modular-excluded.tsv"
printf 'REAL_MAVEN_MODULAR_EXCLUDED_PROVIDER_OK\n'

# Declared generated sources must be materialized and attributed to the compile
# context; the template tree itself remains a declared non-compilation input.
maven_templated="$work/maven-templated"
mkdir -p "$maven_templated/src/main/java-templates/app" "$maven_templated/src/main/java/app"
cat > "$maven_templated/pom.xml" <<'POM'
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>fixture</groupId><artifactId>templated-app</artifactId><version>7</version>
  <properties><maven.compiler.release>17</maven.compiler.release></properties>
  <build>
    <plugins>
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>templating-maven-plugin</artifactId>
        <version>3.0.0</version>
        <executions>
          <execution>
            <id>filtering-java-templates</id>
            <goals><goal>filter-sources</goal></goals>
            <configuration>
              <sourceDirectory>${project.basedir}/src/main/java-templates</sourceDirectory>
              <outputDirectory>${project.build.directory}/generated-sources/java-templates</outputDirectory>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
POM
cat > "$maven_templated/src/main/java-templates/app/Generated.java" <<'JAVA'
package app;
public final class Generated {
    public static final String VERSION = "${project.version}";
}
JAVA
cat > "$maven_templated/src/main/java/app/Use.java" <<'JAVA'
package app;
public final class Use {
    public String version() {
        return Generated.VERSION;
    }
}
JAVA
"$repo_root/scripts/resolve-maven-dependencies.sh" "$maven_templated" "$work/maven-templated.tsv"
printf 'REAL_MAVEN_TEMPLATING_MANIFEST\n'
cat "$work/maven-templated.tsv"
grep -Fq $'noncompiled\tsrc/main/java-templates/app/Generated.java' "$work/maven-templated.tsv"
grep -Fq $'generated-source\tmaven|.|compile\ttarget/generated-sources/java-templates' "$work/maven-templated.tsv"
generated_template="$maven_templated/target/generated-sources/java-templates/app/Generated.java"
[[ -f "$generated_template" ]]
grep -Fq 'VERSION = "7"' "$generated_template"
printf 'REAL_MAVEN_TEMPLATING_PROVIDER_OK\n'

# A replacer execution writes a single declared output file; the file-exact
# generated root must be attributed to the same compile context.
maven_replaced="$work/maven-replaced"
mkdir -p "$maven_replaced/src/main/version/app" "$maven_replaced/src/main/java/app"
cat > "$maven_replaced/pom.xml" <<'POM'
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>fixture</groupId><artifactId>replaced-app</artifactId><version>9</version>
  <properties><maven.compiler.release>17</maven.compiler.release></properties>
  <build>
    <plugins>
      <plugin>
        <groupId>com.google.code.maven-replacer-plugin</groupId>
        <artifactId>replacer</artifactId>
        <version>1.5.3</version>
        <executions>
          <execution>
            <id>process-packageVersion</id>
            <phase>generate-sources</phase>
            <goals><goal>replace</goal></goals>
            <configuration>
              <file>${project.basedir}/src/main/version/app/Version.java.in</file>
              <outputFile>${project.build.directory}/generated-sources/version/app/Version.java</outputFile>
              <replacements>
                <replacement><token>@version@</token><value>${project.version}</value></replacement>
              </replacements>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
POM
cat > "$maven_replaced/src/main/version/app/Version.java.in" <<'JAVA'
package app;
public final class Version {
    public static final String VALUE = "@version@";
}
JAVA
cat > "$maven_replaced/src/main/java/app/Use.java" <<'JAVA'
package app;
public final class Use {
    public String value() {
        return Version.VALUE;
    }
}
JAVA
"$repo_root/scripts/resolve-maven-dependencies.sh" "$maven_replaced" "$work/maven-replaced.tsv"
printf 'REAL_MAVEN_REPLACER_MANIFEST\n'
cat "$work/maven-replaced.tsv"
grep -Fq $'generated-source\tmaven|.|compile\ttarget/generated-sources/version/app/Version.java' "$work/maven-replaced.tsv"
generated_replaced="$maven_replaced/target/generated-sources/version/app/Version.java"
[[ -f "$generated_replaced" ]]
grep -Fq 'VALUE = "9"' "$generated_replaced"
printf 'REAL_MAVEN_REPLACER_PROVIDER_OK\n'

gradle="$work/gradle"
mkdir -p "$gradle/odd/primary/app" "$gradle/odd/verification/app"
cat > "$gradle/settings.gradle" <<'GRADLE'
rootProject.name = 'arbitrary-layout'
GRADLE
cat > "$gradle/build.gradle" <<'GRADLE'
plugins { id 'java' }

configurations {
    verificationArchive
}

dependencies {
    verificationArchive project(path: ':', configuration: 'archives')
}

sourceSets {
    main {
        java.setSrcDirs(['odd/primary'])
    }
    verification {
        java.setSrcDirs(['odd/verification'])
        compileClasspath += sourceSets.main.output
        runtimeClasspath += sourceSets.main.output
        compileClasspath += configurations.verificationArchive
        runtimeClasspath += configurations.verificationArchive
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
jar_entry="$(awk -F '\t' '$1 == "classpath" && $2 == "gradle|.|:|verification" && $3 ~ /\.jar$/ { print $3; exit }' "$work/gradle.tsv")"
[[ -n "$jar_entry" ]]
[[ -f "$jar_entry" ]]
! grep -Eq 'src/(main|test)/java' "$work/gradle.tsv"

# A Gradle source set that compiles a module descriptor needs its dependency
# archives on the module path; classpath archives stay in the unnamed module
# and cannot satisfy requires clauses.
gradle_modular="$work/gradle-modular"
mkdir -p "$gradle_modular/src/main/java/app"
cat > "$gradle_modular/settings.gradle" <<'GRADLE'
rootProject.name = 'gradle-modular'
GRADLE
cat > "$gradle_modular/build.gradle" <<'GRADLE'
plugins { id 'java' }
repositories { mavenCentral() }
dependencies { implementation 'org.slf4j:slf4j-api:2.0.13' }
tasks.withType(JavaCompile).configureEach { options.release = 17 }
GRADLE
cat > "$gradle_modular/src/main/java/module-info.java" <<'JAVA'
module fixture.modular {
    requires org.slf4j;
    exports app;
}
JAVA
cat > "$gradle_modular/src/main/java/app/Alpha.java" <<'JAVA'
package app;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public class Alpha {
    private static final Logger LOG = LoggerFactory.getLogger(Alpha.class);
}
JAVA
"$repo_root/scripts/resolve-gradle-dependencies.sh" "$gradle_modular" "$work/gradle-modular.tsv"
printf 'REAL_GRADLE_MODULAR_MANIFEST\n'
cat "$work/gradle-modular.tsv"
grep -Eq $'^module-path\tgradle\|\.\|:\|main\t.*slf4j-api-2\.0\.13\.jar$' "$work/gradle-modular.tsv"
! grep -Eq $'^classpath\tgradle\|\.\|:\|main\t.*slf4j-api-2\.0\.13\.jar$' "$work/gradle-modular.tsv"
printf 'REAL_GRADLE_MODULAR_PROVIDER_OK\n'

# Configuration on demand activates only projects needed by the requested task.
# Cross-project archive producers registered as observation dependencies must
# still run, and the emitted classpath must point at the built archive.
gradle_cod="$work/gradle-cod"
mkdir -p "$gradle_cod/lib/src/main/java/lib" "$gradle_cod/app/src/main/java/app"
cat > "$gradle_cod/settings.gradle" <<'GRADLE'
rootProject.name = 'cod-consumer'
include 'lib', 'app'
GRADLE
printf 'org.gradle.configureondemand=true\n' > "$gradle_cod/gradle.properties"
cat > "$gradle_cod/lib/build.gradle" <<'GRADLE'
plugins { id 'java' }
GRADLE
cat > "$gradle_cod/app/build.gradle" <<'GRADLE'
plugins { id 'java' }
dependencies { implementation project(':lib') }
GRADLE
printf 'package lib; public class Lib {}\n' > "$gradle_cod/lib/src/main/java/lib/Lib.java"
printf 'package app; public class App { lib.Lib value; }\n' > "$gradle_cod/app/src/main/java/app/App.java"
"$repo_root/scripts/resolve-gradle-dependencies.sh" "$gradle_cod" "$work/gradle-cod.tsv"
printf 'REAL_GRADLE_CONFIGURATION_ON_DEMAND_MANIFEST\n'
cat "$work/gradle-cod.tsv"
grep -Fq $'source\tgradle|app|:app|main\tapp/src/main/java' "$work/gradle-cod.tsv"
grep -Fq $'source\tgradle|lib|:lib|main\tlib/src/main/java' "$work/gradle-cod.tsv"
cod_jar="$(awk -F '\t' '$1 == "classpath" && $2 == "gradle|app|:app|main" && $3 ~ /lib.*\.jar$/ { print $3; exit }' "$work/gradle-cod.tsv")"
[[ -n "$cod_jar" ]]
[[ -f "$cod_jar" ]]
printf 'REAL_GRADLE_CONFIGURATION_ON_DEMAND_OK\n'

printf 'REAL_JAVA_BUILD_PROVIDERS_OK\n'
