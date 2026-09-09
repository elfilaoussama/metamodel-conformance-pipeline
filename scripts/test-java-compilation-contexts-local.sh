#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
work="$(mktemp -d)"; trap 'rm -rf -- "$work"' EXIT
mkdir -p "$work/classes" "$work/src/metamodel/conformance/pipeline/adapter/java"
cat > "$work/src/metamodel/conformance/pipeline/adapter/java/ContextContractTestMain.java" <<'JAVA'
package metamodel.conformance.pipeline.adapter.java;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ContextContractTestMain {
    private ContextContractTestMain() { }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("context-contract-");
        Path a = Files.write(root.resolve("a.jar"), new byte[] {1});
        Path b = Files.write(root.resolve("b.jar"), new byte[] {2});
        Path m = root.resolve("manifest.tsv");
        Files.writeString(m, String.join("\n",
                "context\talpha\tmodule-x\t\t\t17\tfalse\tjdk-17",
                "source\talpha\tcode/primary",
                "classpath\talpha\t" + a,
                "classpath\talpha\t" + b,
                "context\tbeta\tmodule-x\t17\t17\t\tfalse\tjdk-17",
                "source\tbeta\tcode/checks",
                "source\tbeta\tcode/primary",
                "module-path\tbeta\t" + b,
                "upstream\tbeta\talpha") + "\n");
        JavaDependencyInputs inputs = JavaDependencyInputs.fromManifest(m);
        require(inputs.contexts().size() == 2, "context count");
        require(inputs.context("alpha").compilerSemantics().releaseLevel() == 17, "release");
        require(inputs.pathsForContext("alpha").equals(List.of(a.toAbsolutePath(), b.toAbsolutePath())),
                "classpath order");
        require(inputs.context("beta").resolutionEntries(JavaResolutionPathRole.MODULE_PATH)
                .equals(List.of(b.toAbsolutePath())), "module path role");
        require(inputs.upstreamClosure("beta").contains("alpha"), "upstream");
        require(inputs.contextsForSourcePath("code/primary/App.java").size() == 2, "overlap retained");
        require(inputs.contextsForSourcePath("code/checks/Test.java").size() == 1, "arbitrary root");

        Path legacy = root.resolve("legacy.tsv");
        Files.writeString(legacy, "unusual/tree\t" + a + "\n");
        JavaDependencyInputs legacyInputs = JavaDependencyInputs.fromManifest(legacy);
        require(legacyInputs.contextsForSourcePath("unusual/tree/A.java").size() == 1,
                "legacy arbitrary root");

        Path invalid = root.resolve("invalid.tsv");
        Files.writeString(invalid, "context\tx\t.\t\t\t17\tfalse\t\nsource\tx\tcode\nupstream\tx\ty\n");
        boolean rejected = false;
        try { JavaDependencyInputs.fromManifest(invalid); } catch (java.io.IOException expected) { rejected = true; }
        require(rejected, "unknown upstream rejected");
        System.out.println("JAVA_COMPILATION_CONTEXT_CONTRACT_OK");
    }
    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
JAVA
javac --release 17 -Xlint:all -Werror -d "$work/classes" \
  "$repo_root/src/main/java/metamodel/conformance/pipeline/adapter/java/JavaResolutionPathRole.java" \
  "$repo_root/src/main/java/metamodel/conformance/pipeline/adapter/java/JavaResolutionPath.java" \
  "$repo_root/src/main/java/metamodel/conformance/pipeline/adapter/java/JavaCompilerSemantics.java" \
  "$repo_root/src/main/java/metamodel/conformance/pipeline/adapter/java/JavaCompilationContext.java" \
  "$repo_root/src/main/java/metamodel/conformance/pipeline/adapter/java/JavaDependencyInputs.java" \
  "$work/src/metamodel/conformance/pipeline/adapter/java/ContextContractTestMain.java"
java -cp "$work/classes" metamodel.conformance.pipeline.adapter.java.ContextContractTestMain
