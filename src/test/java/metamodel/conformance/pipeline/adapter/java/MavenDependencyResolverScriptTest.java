package metamodel.conformance.pipeline.adapter.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenDependencyResolverScriptTest {
    @TempDir Path temporary;

    @Test
    void resolvesBuildObservedArbitraryRootsIntoTypedContextsWithoutFlattening() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        source(project, "code/alpha", "ProductionType");
        source(project, "checks/beta", "VerificationType");

        Path effective = temporary.resolve("effective.xml");
        Files.writeString(effective, """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <build>
                    <sourceDirectory>/workspace/source/code/alpha</sourceDirectory>
                    <testSourceDirectory>/workspace/source/checks/beta</testSourceDirectory>
                    <outputDirectory>/workspace/source/build/alpha</outputDirectory>
                    <testOutputDirectory>/workspace/source/build/beta</testOutputDirectory>
                    <plugins><plugin><artifactId>maven-compiler-plugin</artifactId>
                      <configuration><release>17</release></configuration>
                    </plugin></plugins>
                  </build>
                </project>
                """);
        Path bin = fakeDocker();
        Path output = temporary.resolve("dependencies.tsv");
        Path arguments = temporary.resolve("docker-arguments.txt");
        Process result = resolver(project, output, bin, effective, arguments,
                "/workspace/out/repository/first.jar:/workspace/out/repository/second.jar:/workspace/out/repository/first.jar")
                .start();
        String log = new String(result.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, result.waitFor(), log);

        List<String> rows = Files.readAllLines(output);
        assertTrue(rows.contains("context\tmaven|.|compile\t.\t\t\t17\tfalse\t"), rows::toString);
        assertTrue(rows.contains("source\tmaven|.|compile\tcode/alpha"), rows::toString);
        assertTrue(rows.contains("context\tmaven|.|test\t.\t\t\t17\tfalse\t"), rows::toString);
        assertTrue(rows.contains("source\tmaven|.|test\tchecks/beta"), rows::toString);
        assertEquals(2, rows.stream().filter(row -> row.startsWith("classpath\tmaven|.|compile\t")).count());
        assertEquals(2, rows.stream().filter(row -> row.startsWith("classpath\tmaven|.|test\t")).count());
        assertFalse(rows.stream().anyMatch(row -> row.contains("src/main/java") || row.contains("src/test/java")));

        List<String> invocations = Files.readAllLines(arguments);
        assertEquals(3, invocations.size());
        for (String invoked : invocations) {
            assertTrue(invoked.contains("--read-only"));
            assertTrue(invoked.contains("--cap-drop=ALL"));
            assertTrue(invoked.contains("--security-opt=no-new-privileges"));
            assertTrue(invoked.contains("--tmpfs /tmp:rw,nosuid,nodev,noexec"));
            assertTrue(invoked.contains("MAVEN_CONFIG=/workspace/out/home/.m2"));
            assertTrue(invoked.contains("-Djava.io.tmpdir=/workspace/out/tmp"));
            assertTrue(invoked.contains("-Djansi.tmpdir=/workspace/out/jansi"));
            assertTrue(invoked.contains("test-maven-image"));
            assertFalse(invoked.contains("--memory"));
            assertFalse(invoked.contains("--cpus"));
            assertFalse(invoked.contains("--pids-limit"));
        }
        assertEquals(2, invocations.stream().filter(item -> item.contains("fake:classpath")).count());
    }

    @Test
    void normalizesLegacyCompilerNotationFromEffectiveModel() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("legacy-level"));
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        source(project, "odd/source", "Type");
        Path effective = temporary.resolve("legacy-effective.xml");
        Files.writeString(effective, """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <build><sourceDirectory>/workspace/source/odd/source</sourceDirectory>
                    <outputDirectory>/workspace/source/out/classes</outputDirectory>
                    <plugins><plugin><artifactId>maven-compiler-plugin</artifactId>
                      <configuration><source>1.8</source><target>1.8</target></configuration>
                    </plugin></plugins>
                  </build>
                </project>
                """);
        Path bin = fakeDocker();
        Path output = temporary.resolve("legacy.tsv");
        Path arguments = temporary.resolve("legacy-arguments.txt");
        Process result = resolver(project, output, bin, effective, arguments,
                "/workspace/out/repository/only.jar").start();
        result.getInputStream().readAllBytes();
        assertEquals(0, result.waitFor());
        assertTrue(Files.readAllLines(output).contains("context\tmaven|.|compile\t.\t8\t8\t\tfalse\t"));
    }


    @Test
    void readsCompilerReleaseFromEffectiveProjectPropertiesWithoutExplicitPluginConfiguration() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("property-release"));
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        source(project, "unconventional/source", "Type");
        Path effective = temporary.resolve("property-effective.xml");
        Files.writeString(effective, """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <properties><maven.compiler.release>17</maven.compiler.release></properties>
                  <build><sourceDirectory>/workspace/source/unconventional/source</sourceDirectory>
                    <outputDirectory>/workspace/source/out/classes</outputDirectory></build>
                </project>
                """);
        Path bin = fakeDocker();
        Path output = temporary.resolve("property-release.tsv");
        Path arguments = temporary.resolve("property-release-arguments.txt");
        Process result = resolver(project, output, bin, effective, arguments,
                "/workspace/out/repository/only.jar").start();
        String log = new String(result.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, result.waitFor(), log);
        assertTrue(Files.readAllLines(output).contains(
                "context\tmaven|.|compile\t.\t\t\t17\tfalse\t"));
    }

    @Test
    void requiresExplicitResolverConfigurationForMavenProjects() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project-config"));
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        Path output = temporary.resolve("dependencies.tsv");
        ProcessBuilder process = new ProcessBuilder(
                "bash", "scripts/resolve-maven-dependencies.sh", project.toString(), output.toString());
        process.environment().remove("MAVEN_DEPENDENCY_CLASSPATH_GOAL");
        process.environment().remove("MAVEN_RESOLVER_IMAGE");
        Process result = process.start();
        result.getInputStream().readAllBytes();
        result.getErrorStream().readAllBytes();
        assertEquals(64, result.waitFor());
    }

    @Test
    void rejectsResolverOutputOutsideIsolatedMounts() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project-escape"));
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        source(project, "arbitrary/root", "Type");
        Path effective = temporary.resolve("escape-effective.xml");
        Files.writeString(effective, """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <build><sourceDirectory>/workspace/source/arbitrary/root</sourceDirectory>
                    <outputDirectory>/workspace/source/out/classes</outputDirectory></build>
                </project>
                """);
        Path bin = fakeDocker();
        Path output = temporary.resolve("escape.tsv");
        Path arguments = temporary.resolve("escape-arguments.txt");
        Process result = resolver(project, output, bin, effective, arguments, "/outside/dependency.jar").start();
        result.getInputStream().readAllBytes();
        result.getErrorStream().readAllBytes();
        assertEquals(70, result.waitFor());
        assertFalse(Files.readString(output).contains("classpath\t"));
    }

    private Path fakeDocker() throws Exception {
        Path bin = Files.createDirectories(temporary.resolve("bin-" + System.nanoTime()));
        Path fakeDocker = bin.resolve("docker");
        Files.writeString(fakeDocker, """
                #!/usr/bin/env bash
                set -euo pipefail
                printf '%s\\n' "$*" >> "$FAKE_DOCKER_ARGUMENTS"
                out_mount=''; output=''
                for argument in "$@"; do
                  case "$argument" in
                    type=bind,src=*,dst=/workspace/out)
                      out_mount="${argument#type=bind,src=}"
                      out_mount="${out_mount%%,dst=/workspace/out}" ;;
                    -Doutput=*) output="${argument#*=}" ;;
                    -Dmdep.outputFile=*) output="${argument#*=}" ;;
                  esac
                done
                host_output="$out_mount/${output#/workspace/out/}"
                mkdir -p "$(dirname "$host_output")" "$out_mount/repository"
                if [[ "$*" == *"help:effective-pom"* ]]; then
                  cp "$FAKE_EFFECTIVE_POM" "$host_output"
                elif [[ "$*" == *"fake:classpath"* ]]; then
                  IFS=: read -r -a entries <<< "$FAKE_CLASSPATH"
                  for entry in "${entries[@]}"; do
                    if [[ "$entry" == /workspace/out/* ]]; then
                      file="$out_mount/${entry#/workspace/out/}"; mkdir -p "$(dirname "$file")"; printf jar > "$file"
                    fi
                  done
                  printf '%s\\n' "$FAKE_CLASSPATH" > "$host_output"
                else
                  echo "unexpected fake docker invocation: $*" >&2; exit 98
                fi
                """);
        Files.setPosixFilePermissions(fakeDocker, PosixFilePermissions.fromString("rwxr-xr-x"));
        return bin;
    }

    private ProcessBuilder resolver(
            Path project, Path output, Path bin, Path effective, Path arguments, String classpath) {
        ProcessBuilder process = new ProcessBuilder(
                "bash", "scripts/resolve-maven-dependencies.sh", project.toString(), output.toString());
        process.redirectErrorStream(true);
        process.environment().put("PATH", bin + ":" + process.environment().get("PATH"));
        process.environment().put("MAVEN_DEPENDENCY_CLASSPATH_GOAL", "fake:classpath");
        process.environment().put("MAVEN_RESOLVER_IMAGE", "test-maven-image");
        process.environment().put("FAKE_EFFECTIVE_POM", effective.toString());
        process.environment().put("FAKE_DOCKER_ARGUMENTS", arguments.toString());
        process.environment().put("FAKE_CLASSPATH", classpath);
        return process;
    }

    private void source(Path project, String root, String typeName) throws Exception {
        Path source = Files.createDirectories(project.resolve(root).resolve("example"));
        Files.writeString(source.resolve(typeName + ".java"),
                "package example; public class " + typeName + " {}\n");
    }
}
