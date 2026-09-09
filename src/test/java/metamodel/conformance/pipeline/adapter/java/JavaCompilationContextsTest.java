package metamodel.conformance.pipeline.adapter.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaCompilationContextsTest {
    @TempDir Path temporary;

    @Test
    void assignsArbitraryRootsFromExplicitContextsWithoutLayoutInference() throws Exception {
        Path alpha = Files.createDirectories(temporary.resolve("code/alpha/app"))
                .resolve("Alpha.java");
        Path beta = Files.createDirectories(temporary.resolve("verification/beta/app"))
                .resolve("Beta.java");
        Files.writeString(alpha, "package app; class Alpha {}\n");
        Files.writeString(beta, "package app; class Beta {}\n");

        JavaCompilationContext production = new JavaCompilationContext(
                "production", ".", List.of("code/alpha"), List.of(), List.of(), List.of(), Set.of(),
                new JavaCompilerSemantics(null, null, 17, false, "jdk-17"));
        JavaCompilationContext verification = new JavaCompilationContext(
                "verification", ".", List.of("verification/beta"), List.of(), List.of(), List.of(),
                Set.of("production"), new JavaCompilerSemantics(null, null, 17, false, "jdk-17"));
        JavaDependencyInputs inputs = inputs(production, verification);

        JavaCompilationContexts.Grouping grouping = JavaCompilationContexts.group(
                temporary, List.of(alpha, beta), inputs);

        assertEquals(Map.of("production", List.of(alpha), "verification", List.of(beta)),
                grouping.filesByContext());
        assertTrue(grouping.unownedFiles().isEmpty());
        assertEquals(List.of("production"), inputs.upstreamTopological("verification"));
    }

    @Test
    void retainsOverlappingVariantOwnershipInsteadOfSelectingOneContext() throws Exception {
        Path shared = Files.createDirectories(temporary.resolve("shared/app")).resolve("Shared.java");
        Files.writeString(shared, "package app; class Shared {}\n");
        JavaCompilationContext a = context("variant-a", "shared");
        JavaCompilationContext b = context("variant-b", "shared");
        JavaDependencyInputs inputs = inputs(a, b);

        assertEquals(2, inputs.contextsForSourcePath("shared/app/Shared.java").size());
        JavaCompilationContexts.Grouping grouping = JavaCompilationContexts.group(
                temporary, List.of(shared), inputs);
        assertEquals(List.of(shared), grouping.filesByContext().get("variant-a"));
        assertEquals(List.of(shared), grouping.filesByContext().get("variant-b"));
    }

    @Test
    void rejectsNonCanonicalRootsAndUnknownUpstreamContexts() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> context("bad", "../outside"));
        Path manifest = temporary.resolve("invalid.tsv");
        Files.writeString(manifest, String.join("\n",
                "context\tchild\t.\t\t\t17\tfalse\tjdk-17",
                "source\tchild\tcode",
                "upstream\tchild\tmissing") + "\n");
        assertThrows(java.io.IOException.class, () -> JavaDependencyInputs.fromManifest(manifest));
    }

    private JavaDependencyInputs inputs(JavaCompilationContext... contexts) throws Exception {
        Path manifest = temporary.resolve("contexts-" + System.nanoTime() + ".tsv");
        StringBuilder text = new StringBuilder();
        for (JavaCompilationContext context : contexts) {
            text.append("context\t").append(context.id()).append("\t").append(context.moduleKey())
                    .append("\t\t\t17\tfalse\tjdk-17\n");
            for (String root : context.sourceRoots()) {
                text.append("source\t").append(context.id()).append("\t").append(root).append('\n');
            }
            for (String upstream : context.upstreamContextIds()) {
                text.append("upstream\t").append(context.id()).append("\t").append(upstream).append('\n');
            }
        }
        Files.writeString(manifest, text);
        return JavaDependencyInputs.fromManifest(manifest);
    }

    private static JavaCompilationContext context(String id, String root) {
        return new JavaCompilationContext(
                id, ".", List.of(root), List.of(), List.of(), List.of(), Set.of(),
                new JavaCompilerSemantics(null, null, 17, false, "jdk-17"));
    }
}
