package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.model.ClassifierAbstraction;
import metamodel.conformance.pipeline.model.Inheritability;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberScope;
import metamodel.conformance.pipeline.model.MemberVisibility;
import metamodel.conformance.pipeline.model.MethodAbstraction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDependencySymbolsTest {
    @TempDir
    Path temporary;

    @Test
    void materializesReachableDependencyHierarchyAndMembersWithoutLoadingClasses() throws Exception {
        Path source = temporary.resolve("source");
        Path classes = temporary.resolve("classes");
        Files.createDirectories(source.resolve("dep"));
        Files.createDirectories(classes);
        Files.writeString(source.resolve("dep/Parent.java"), """
                package dep;
                public class Parent {
                    protected int value;
                    public void work() {}
                    private void secret() {}
                }
                """);
        Files.writeString(source.resolve("dep/Api.java"), """
                package dep;
                public interface Api {
                    void run();
                    default String label() { return "x"; }
                    static void utility() {}
                }
                """);
        Files.writeString(source.resolve("dep/Child.java"), """
                package dep;
                public abstract class Child extends Parent implements Api {
                    public abstract void local();
                }
                """);
        int compiled = ToolProvider.getSystemJavaCompiler().run(
                null, null, null,
                "-d", classes.toString(),
                source.resolve("dep/Parent.java").toString(),
                source.resolve("dep/Api.java").toString(),
                source.resolve("dep/Child.java").toString());
        assertEquals(0, compiled);

        Path archive = temporary.resolve("dependency.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
            for (String entry : List.of("dep/Parent.class", "dep/Api.class", "dep/Child.class")) {
                JarEntry jarEntry = new JarEntry(entry);
                jarEntry.setTime(0L);
                output.putNextEntry(jarEntry);
                output.write(Files.readAllBytes(classes.resolve(entry)));
                output.closeEntry();
            }
        }

        JavaDependencyClasspath.Result classpath = JavaDependencyClasspath.resolve(List.of(archive));
        JavaDependencySymbols.Result result = JavaDependencySymbols.resolve(classpath, Set.of("dep.Child"));

        assertTrue(result.unresolvedRootTypes().isEmpty());
        assertEquals(Set.of("dep.Child", "dep.Parent", "dep.Api"), result.types().stream()
                .map(JavaDependencySymbols.TypeSymbol::qualifiedName)
                .collect(java.util.stream.Collectors.toSet()));

        var child = result.requireType("dep.Child");
        assertEquals(ClassifierAbstraction.ABSTRACT, child.abstraction());
        assertEquals(List.of("dep.Api", "dep.Parent"), child.parentQualifiedNames());
        var local = child.members().stream().filter(member -> member.name().equals("local")).findFirst().orElseThrow();
        assertEquals(MethodAbstraction.ABSTRACT, local.abstraction());
        assertEquals(MemberScope.INSTANCE, local.scope());

        var parent = result.requireType("dep.Parent");
        var value = parent.members().stream().filter(member -> member.name().equals("value")).findFirst().orElseThrow();
        assertEquals(MemberKind.ATTRIBUTE, value.kind());
        assertEquals(MemberVisibility.PROTECTED, value.visibility());
        var secret = parent.members().stream().filter(member -> member.name().equals("secret")).findFirst().orElseThrow();
        assertEquals(Inheritability.NOT_INHERITABLE, secret.inheritability());

        var api = result.requireType("dep.Api");
        var run = api.members().stream().filter(member -> member.name().equals("run")).findFirst().orElseThrow();
        assertEquals(MethodAbstraction.ABSTRACT, run.abstraction());
        var label = api.members().stream().filter(member -> member.name().equals("label")).findFirst().orElseThrow();
        assertEquals(MethodAbstraction.CONCRETE, label.abstraction());
        var utility = api.members().stream().filter(member -> member.name().equals("utility")).findFirst().orElseThrow();
        assertEquals(MemberScope.STATIC, utility.scope());
        assertEquals(Inheritability.NOT_INHERITABLE, utility.inheritability());
        assertTrue(result.types().stream().allMatch(type -> type.archiveUnitPath().startsWith("dependencies/")));
    }

    @Test
    void reportsUnknownOrPlatformRootsAsUnresolvedDependencyRoots() throws Exception {
        Path archive = temporary.resolve("empty.jar");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(archive))) {
        }
        JavaDependencyClasspath.Result classpath = JavaDependencyClasspath.resolve(List.of(archive));

        JavaDependencySymbols.Result result = JavaDependencySymbols.resolve(
                classpath, Set.of("missing.Type", "java.lang.String"));

        assertEquals(Set.of("missing.Type", "java.lang.String"), result.unresolvedRootTypes());
        assertTrue(result.types().isEmpty());
    }
}
