package metamodel.conformance.pipeline.adapter.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class JavacUpstreamIsolationTest {
    @TempDir Path root;

    @Test
    void siblingUpstreamOutputCannotSatisfyAnUndeclaredDependency() throws Exception {
        Path secret = source("a/Secret.java", "package hidden; public class Secret {}");
        Path use = source("b/Use.java", "package other; public class Use extends hidden.Secret {}");
        Path app = source("app/App.java", "class App {}");
        JavaDependencyInputs inputs = inputs("""
                context\ta\t.\t\t\t17\tfalse\tjdk-17
                source\ta\ta
                context\tb\t.\t\t\t17\tfalse\tjdk-17
                source\tb\tb
                context\tapp\t.\t\t\t17\tfalse\tjdk-17
                source\tapp\tapp
                upstream\tapp\ta
                upstream\tapp\tb
                """);
        try (JavacCompilationContext context = JavacCompilationContext.prepare(root, inputs.context("app"),
                Map.of("a", List.of(secret), "b", List.of(use), "app", List.of(app)), inputs)) {
            assertFalse(context.complete(), "b must not see sibling a without an observed upstream edge");
            assertTrue(context.diagnostics().stream().anyMatch(item -> item.sourcePath().equals("b/Use.java")),
                    context.diagnostics().toString());
        }
        Files.writeString(root.resolve("contexts.tsv"), "upstream\tb\ta\n", java.nio.file.StandardOpenOption.APPEND);
        JavaDependencyInputs declared = JavaDependencyInputs.fromManifest(root.resolve("contexts.tsv"));
        try (JavacCompilationContext context = JavacCompilationContext.prepare(root, declared.context("app"),
                Map.of("a", List.of(secret), "b", List.of(use), "app", List.of(app)), declared)) {
            assertTrue(context.complete(), context.diagnostics().toString());
            String[] outputs = context.classpath().split(java.util.regex.Pattern.quote(java.io.File.pathSeparator));
            assertEquals(2, outputs.length);
            assertNotEquals(outputs[0], outputs[1]);
            assertTrue(Files.exists(Path.of(outputs[0]).resolve("hidden/Secret.class")));
            assertTrue(Files.exists(Path.of(outputs[1]).resolve("other/Use.class")));
        }
    }

    @Test
    void compiledUpstreamModuleKeepsItsExplicitModulePathRole() throws Exception {
        Path libraryModule = source("lib/module-info.java", "module sample.library { exports sample.api; }");
        Path api = source("lib/sample/api/Api.java", "package sample.api; public class Api { public int value() { return 1; } }");
        Path appModule = source("app/module-info.java", "module sample.app { requires sample.library; }");
        Path app = source("app/sample/app/App.java", "package sample.app; public class App { public int use() { return new sample.api.Api().value(); } }");
        Path declaredOutput = root.resolve("build/library");
        JavaDependencyInputs inputs = inputs("context\tlib\t.\t\t\t17\tfalse\tjdk-17\nsource\tlib\tlib\n"
                + "output\tlib\t" + declaredOutput + "\n"
                + "context\tapp\t.\t\t\t17\tfalse\tjdk-17\nsource\tapp\tapp\n"
                + "upstream\tapp\tlib\nmodule-path\tapp\t" + declaredOutput + "\n");
        try (JavacCompilationContext context = JavacCompilationContext.prepare(root, inputs.context("app"),
                Map.of("lib", List.of(libraryModule, api), "app", List.of(appModule, app)), inputs)) {
            assertTrue(context.complete(), context.diagnostics().toString());
            int index = context.options().indexOf("--module-path");
            assertTrue(index >= 0);
            Path actualOutput = Path.of(context.options().get(index + 1));
            assertTrue(Files.isRegularFile(actualOutput.resolve("module-info.class")),
                    "module path must refer to the independently compiled upstream output");
            assertFalse(context.classpath().contains(actualOutput.toString()));
            ArrayList<String> args = new ArrayList<>(context.options());
            args.addAll(List.of("-d", root.resolve("compiled-app").toString(), appModule.toString(), app.toString()));
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        }
        // The separately compiled output contains no Java sources; observation
        // still uses the original manifest and independently rebuilds upstreams.
        var observed = new JavaDependencyAwareSourceObserver(inputs).observe(root, java.util.Set.of());
        assertTrue(observed.diagnostics().isEmpty(), observed.diagnostics().toString());
        assertEquals(3, observed.classifiers().size());
        var objectSupport = observed.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("java.lang.Object"))
                .findFirst().orElseThrow();
        var apiClassifier = observed.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("sample.api.Api"))
                .findFirst().orElseThrow();
        var appClassifier = observed.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("sample.app.App"))
                .findFirst().orElseThrow();
        assertTrue(apiClassifier.parentIds().contains(objectSupport.id()));
        assertTrue(appClassifier.parentIds().contains(objectSupport.id()));
        Path xmi = root.resolve("modules.xmi");
        new metamodel.conformance.pipeline.emf.ObservationXmiWriter().write(observed, xmi);
        var replayed = new metamodel.conformance.pipeline.emf.ObservationXmiReader().read(xmi);
        assertEquals(observed, replayed);
        var decisions = new metamodel.conformance.pipeline.alloy.AlloyInvariantEvaluator().evaluateAll(replayed,
                new metamodel.conformance.pipeline.alloy.ExactAlloyEncoder().encode(replayed));
        assertFalse(decisions.isEmpty());
        decisions.forEach(item -> assertEquals(metamodel.conformance.pipeline.decision.DecisionStatus.CONFORMANT,
                item.status(), item.toString()));
    }

    private Path source(String name, String content) throws Exception {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    @Test
    void emptyDependencyBoundaryDoesNotUseAnalyzerClasspath() throws Exception {
        Path file = source("code/Uses.java", "class Uses { spoon.Launcher undeclared; }");
        JavaDependencyInputs inputs = inputs("""
                context\tisolated\t.\t\t\t17\tfalse\tjdk-17
                source\tisolated\tcode
                """);
        try (JavacCompilationContext context = JavacCompilationContext.prepare(root, inputs.context("isolated"),
                Map.of("isolated", List.of(file)), inputs)) {
            assertTrue(context.complete());
            assertFalse(context.classpath().isBlank());
            ArrayList<String> args = new ArrayList<>(context.options());
            args.addAll(List.of("-d", root.resolve("result").toString(), file.toString()));
            var errors = new java.io.ByteArrayOutputStream();
            assertNotEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, errors, args.toArray(String[]::new)));
            assertTrue(errors.toString(java.nio.charset.StandardCharsets.UTF_8).contains("spoon"));
        }
    }

    private JavaDependencyInputs inputs(String content) throws Exception {
        return JavaDependencyInputs.fromManifest(Files.writeString(root.resolve("contexts.tsv"), content));
    }
}
