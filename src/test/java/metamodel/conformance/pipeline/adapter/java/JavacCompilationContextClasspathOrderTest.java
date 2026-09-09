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

class JavacCompilationContextClasspathOrderTest {
    @TempDir Path temporary;

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
