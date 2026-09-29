package metamodel.conformance.pipeline.adapter.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JavacCompilationContextClasspathOrderTest {
    @TempDir Path temporary;

    @Test
    void unsupportedUpstreamReleaseProducesDiagnosticInsteadOfEscaping() throws Exception {
        Path upstream = Files.createDirectories(temporary.resolve("upstream"));
        Path downstream = Files.createDirectories(temporary.resolve("downstream"));
        Path base = Files.writeString(upstream.resolve("Base.java"), "class Base {}\n");
        Path child = Files.writeString(downstream.resolve("Child.java"), "class Child extends Base {}\n");
        Path manifest = temporary.resolve("contexts.tsv");
        Files.writeString(manifest, "context\tup\t.\t\t\t99\tfalse\tfuture\nsource\tup\tupstream\n"
                + "context\tdown\t.\t\t\t17\tfalse\tjdk-17\nsource\tdown\tdownstream\nupstream\tdown\tup\n");
        JavaDependencyInputs inputs = JavaDependencyInputs.fromManifest(manifest);

        try (JavacCompilationContext context = JavacCompilationContext.prepare(temporary,
                inputs.context("down"), Map.of("up", List.of(base), "down", List.of(child)), inputs)) {
            assertFalse(context.complete());
            assertTrue(context.diagnostics().stream().anyMatch(item -> item.message().contains("99")
                    && item.sourcePath().equals("upstream/Base.java")), context.diagnostics().toString());
        }
    }

    @Test
    void compiledUpstreamContextPrecedesExternalClasspathForDependentContext() throws Exception {
        Path productionRoot = Files.createDirectories(temporary.resolve("code/production/app"));
        Path verificationRoot = Files.createDirectories(temporary.resolve("checks/integration/app"));
        Path base = productionRoot.resolve("Base.java");
        Files.writeString(base,
                "package app; public class Base { protected int productionOnly = 1; }\n");
        Path child = verificationRoot.resolve("Child.java");
        Files.writeString(child,
                "package app; public class Child extends Base { int x() { return productionOnly; } }\n");

        Path shadow = shadowBaseJar();
        Path manifest = temporary.resolve("dependencies.tsv");
        Files.writeString(manifest, String.join("\n",
                "context\tproduction\t.\t\t\t17\tfalse\tjdk-17",
                "source\tproduction\tcode/production",
                "context\tverification\t.\t\t\t17\tfalse\tjdk-17",
                "source\tverification\tchecks/integration",
                "classpath\tverification\t" + shadow,
                "upstream\tverification\tproduction") + "\n");
        JavaDependencyInputs inputs = JavaDependencyInputs.fromManifest(manifest);
        Map<String, List<Path>> files = Map.of(
                "production", List.of(base),
                "verification", List.of(child));

        try (JavacCompilationContext context = JavacCompilationContext.prepare(
                temporary, inputs.context("verification"), files, inputs)) {
            assertTrue(context.complete(), () -> context.diagnostics().toString());
            String[] entries = context.classpath().split(java.util.regex.Pattern.quote(File.pathSeparator));
            assertTrue(entries.length >= 2);
            assertTrue(Files.isDirectory(Path.of(entries[0])));
            assertEquals(shadow, Path.of(entries[1]));

            Path output = Files.createDirectories(temporary.resolve("verification-classes"));
            int compiled = ToolProvider.getSystemJavaCompiler().run(
                    null, null, null,
                    "--release", "17",
                    "-classpath", context.classpath(),
                    "-d", output.toString(),
                    child.toString());
            assertEquals(0, compiled,
                    "an external app.Base must not shadow the compiled upstream app.Base");
        }

        // With an explicitly recorded output slot, preserve its order after the
        // external archive instead of applying the implicit upstream precedence.
        Path declaredOutput = temporary.resolve("declared-production-output");
        Files.writeString(manifest, "output\tproduction\t" + declaredOutput + "\n"
                + "classpath\tverification\t" + declaredOutput + "\n",
                java.nio.file.StandardOpenOption.APPEND);
        JavaDependencyInputs ordered = JavaDependencyInputs.fromManifest(manifest);
        try (JavacCompilationContext context = JavacCompilationContext.prepare(
                temporary, ordered.context("verification"), files, ordered)) {
            assertTrue(context.complete(), context.diagnostics().toString());
            String[] entries = context.classpath().split(java.util.regex.Pattern.quote(File.pathSeparator));
            assertEquals(shadow.toString(), entries[0]);
            assertTrue(Files.exists(Path.of(entries[1]).resolve("app/Base.class")));
            var errors = new java.io.ByteArrayOutputStream();
            var arguments = new java.util.ArrayList<>(context.options());
            arguments.addAll(List.of("-d", temporary.resolve("ordered-classes").toString(), child.toString()));
            assertTrue(ToolProvider.getSystemJavaCompiler().run(null, null, errors,
                    arguments.toArray(String[]::new)) != 0);
            assertTrue(errors.toString(java.nio.charset.StandardCharsets.UTF_8).contains("productionOnly"));
        }
    }

    @Test
    void declaredProcessorArgumentsAreNotReplayed() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("code/main/app"));
        Path file = Files.writeString(source.resolve("A.java"), "package app; public class A {}\n");
        Path manifest = temporary.resolve("processor-args.tsv");
        Files.writeString(manifest, String.join("\n",
                "context\tmain\t.\t\t\t17\tfalse\tjdk-17",
                "source\tmain\tcode/main",
                "compiler-arg\tmain\t--add-exports",
                "compiler-arg\tmain\ta/b=c",
                "compiler-arg\tmain\t-parameters",
                "compiler-arg\tmain\t-Xplugin:ErrorProne",
                "compiler-arg\tmain\t-processor",
                "compiler-arg\tmain\tcom.example.Processor") + "\n");
        JavaDependencyInputs inputs = JavaDependencyInputs.fromManifest(manifest);

        try (JavacCompilationContext context = JavacCompilationContext.prepare(
                temporary, inputs.context("main"), Map.of("main", List.of(file)), inputs)) {
            List<String> options = context.options();
            // Replayable declared arguments survive.
            assertTrue(options.contains("--add-exports"), options.toString());
            assertTrue(options.contains("a/b=c"), options.toString());
            assertTrue(options.contains("-parameters"), options.toString());
            // Compiler plugins and processor arguments (including their following
            // token) are not replayed because their processor path is not observed.
            assertFalse(options.contains("-Xplugin:ErrorProne"), options.toString());
            assertFalse(options.contains("-processor"), options.toString());
            assertFalse(options.contains("com.example.Processor"), options.toString());
        }
    }

    private Path shadowBaseJar() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("shadow-source/app"));
        Path classes = Files.createDirectories(temporary.resolve("shadow-classes"));
        Path java = source.resolve("Base.java");
        Files.writeString(java, "package app; public class Base {}\n");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "--release", "17", "-d", classes.toString(), java.toString()));
        Path jar = temporary.resolve("shadow.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            JarEntry entry = new JarEntry("app/Base.class");
            entry.setTime(0L);
            output.putNextEntry(entry);
            output.write(Files.readAllBytes(classes.resolve("app/Base.class")));
            output.closeEntry();
        }
        return jar.toAbsolutePath().normalize();
    }
}
