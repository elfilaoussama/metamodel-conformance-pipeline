package metamodel.conformance.pipeline.adapter.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDependencyInputsTest {
    @TempDir Path temporary;

    @Test
    void retainsExactCompilationContextsRolesAndClasspathOrder() throws Exception {
        Path first = Files.write(temporary.resolve("first.jar"), new byte[]{1}).toAbsolutePath().normalize();
        Path second = Files.write(temporary.resolve("second.jar"), new byte[]{2}).toAbsolutePath().normalize();
        Path module = Files.write(temporary.resolve("module.jar"), new byte[]{3}).toAbsolutePath().normalize();
        Path nested = Files.write(temporary.resolve("nested.jar"), new byte[]{4}).toAbsolutePath().normalize();
        Path manifest = temporary.resolve("dependencies.tsv");
        Files.writeString(manifest, String.join("\n",
                "context\talpha\tcomponent-a\t\t\t17\tfalse\tjdk-17",
                "source\talpha\tcode/alpha",
                "classpath\talpha\t" + first,
                "classpath\talpha\t" + second,
                "module-path\talpha\t" + module,
                "context\tbeta\tcomponent-a\t17\t17\t\tfalse\tjdk-17",
                "source\tbeta\tchecks/beta",
                "upstream\tbeta\talpha",
                "context\tnested\tcomponent-b/sub\t\t\t17\tfalse\tjdk-17",
                "source\tnested\todd/tree",
                "classpath\tnested\t" + nested) + "\n");

        JavaDependencyInputs inputs = JavaDependencyInputs.fromManifest(manifest);

        assertTrue(inputs.scoped());
        assertEquals(List.of(first, second), inputs.pathsForContext("alpha"));
        assertEquals(List.of(module), inputs.context("alpha")
                .resolutionEntries(JavaResolutionPathRole.MODULE_PATH));
        assertEquals(List.of("alpha"), inputs.upstreamTopological("beta"));
        assertEquals(Set.of("component-a", "component-b/sub"), inputs.moduleKeys());
        assertEquals(Set.of("alpha", "beta", "nested"), inputs.contextIds());
        assertEquals(List.of(first, second, module, nested), inputs.allPaths());
        assertEquals(inputs.allPaths(), List.copyOf(inputs));
        assertSame(inputs, JavaDependencyInputs.global(inputs));
        assertEquals(List.of(inputs.context("alpha")), inputs.contextsForSourcePath("code/alpha/A.java"));
        assertTrue(inputs.contextsForSourcePath("unowned/A.java").isEmpty());
    }

    @Test
    void rejectsNonCanonicalRootsAndInvalidGraphs() throws Exception {
        Path manifest = temporary.resolve("dependencies.tsv");
        Files.writeString(manifest, String.join("\n",
                "context\ta\t.\t\t\t17\tfalse\t",
                "source\ta\t../outside") + "\n");
        assertThrows(Exception.class, () -> JavaDependencyInputs.fromManifest(manifest));

        Files.writeString(manifest, String.join("\n",
                "context\ta\t.\t\t\t17\tfalse\t",
                "source\ta\tcode",
                "upstream\ta\tb") + "\n");
        assertThrows(Exception.class, () -> JavaDependencyInputs.fromManifest(manifest));
        assertThrows(IllegalArgumentException.class, () -> JavaDependencyInputs.none().pathsForModule("../module"));
    }

    @Test
    void legacyManifestIsOnlyACompatibilityAdapterAndDoesNotInferRelationships() throws Exception {
        Path jar = Files.write(temporary.resolve("legacy.jar"), new byte[]{1}).toAbsolutePath().normalize();
        Path manifest = temporary.resolve("legacy.tsv");
        Files.writeString(manifest, "unusual/root\t" + jar + "\n");
        JavaDependencyInputs inputs = JavaDependencyInputs.fromManifest(manifest);
        assertEquals(1, inputs.contexts().size());
        assertEquals(List.of(jar), inputs.pathsForContext(inputs.contexts().get(0).id()));
        assertTrue(inputs.contexts().get(0).upstreamContextIds().isEmpty());
        assertEquals(List.of(inputs.contexts().get(0)), inputs.contextsForSourcePath("unusual/root/X.java"));
    }
}
