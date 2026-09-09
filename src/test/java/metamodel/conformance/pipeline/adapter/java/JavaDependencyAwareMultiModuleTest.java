package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.Observation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDependencyAwareMultiModuleTest {
    @TempDir Path temporary;

    @Test
    void sameQualifiedDependencyTypeRemainsIsolatedByOwningCompilationContext() throws Exception {
        Path jarA = dependencyJar(temporary.resolve("dependency-a"), "onlyA");
        Path jarB = dependencyJar(temporary.resolve("dependency-b"), "onlyB");
        javaModule("component-a", "sources/alpha", "ChildA");
        javaModule("component-b", "unusual/beta", "ChildB");

        Path manifest = temporary.resolve("dependencies.tsv");
        Files.writeString(manifest, String.join("\n",
                "context\tcontext-a\tcomponent-a\t\t\t17\tfalse\tjdk-17",
                "source\tcontext-a\tcomponent-a/sources/alpha",
                "classpath\tcontext-a\t" + jarA,
                "context\tcontext-b\tcomponent-b\t\t\t17\tfalse\tjdk-17",
                "source\tcontext-b\tcomponent-b/unusual/beta",
                "classpath\tcontext-b\t" + jarB) + "\n");
        JavaDependencyInputs inputs = JavaDependencyInputs.fromManifest(manifest);

        Observation observation = new JavaDependencyAwareSourceObserver(inputs)
                .observe(temporary, Set.of());

        assertTrue(observation.unresolvedParents().isEmpty(), () -> observation.unresolvedParents().toString());
        assertTrue(observation.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.INHERITED_MEMBERS));

        Map<String, ClassifierObservation> classifiersById = observation.classifiers().stream()
                .collect(Collectors.toMap(ClassifierObservation::id, Function.identity()));
        Map<String, MemberObservation> membersByKey = observation.members().stream()
                .collect(Collectors.toMap(MemberObservation::technicalKey, Function.identity()));
        ClassifierObservation childA = observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("app.ChildA")).findFirst().orElseThrow();
        ClassifierObservation childB = observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("app.ChildB")).findFirst().orElseThrow();

        assertEquals(1, childA.parentIds().size());
        assertEquals(1, childB.parentIds().size());
        assertNotEquals(childA.parentIds().get(0), childB.parentIds().get(0));
        ClassifierObservation parentA = classifiersById.get(childA.parentIds().get(0));
        ClassifierObservation parentB = classifiersById.get(childB.parentIds().get(0));
        assertEquals("dep.Parent", parentA.qualifiedName());
        assertEquals("dep.Parent", parentB.qualifiedName());
        assertNotEquals(parentA.sourcePath(), parentB.sourcePath());

        Set<String> inheritedA = childA.inheritedMemberKeys().stream()
                .map(membersByKey::get).map(MemberObservation::memberName).collect(Collectors.toSet());
        Set<String> inheritedB = childB.inheritedMemberKeys().stream()
                .map(membersByKey::get).map(MemberObservation::memberName).collect(Collectors.toSet());
        assertTrue(inheritedA.contains("onlyA"), inheritedA::toString);
        assertTrue(!inheritedA.contains("onlyB"), inheritedA::toString);
        assertTrue(inheritedB.contains("onlyB"), inheritedB::toString);
        assertTrue(!inheritedB.contains("onlyA"), inheritedB::toString);
        assertEquals(2, observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("dep.Parent")).count());
    }

    private void javaModule(String module, String root, String childName) throws Exception {
        Path moduleRoot = Files.createDirectories(temporary.resolve(module));
        Path source = Files.createDirectories(moduleRoot.resolve(root).resolve("app"));
        Files.writeString(source.resolve(childName + ".java"),
                "package app; public class " + childName + " extends dep.Parent {}\n");
    }

    private Path dependencyJar(Path root, String methodName) throws Exception {
        Path sourceRoot = root.resolve("source");
        Path source = Files.createDirectories(sourceRoot.resolve("dep"));
        Path classes = Files.createDirectories(root.resolve("classes"));
        Path java = source.resolve("Parent.java");
        Files.writeString(java, "package dep; public class Parent { public void " + methodName + "() {} }\n");
        int compiled = ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "--release", "17", "-d", classes.toString(), java.toString());
        assertEquals(0, compiled);

        Path jar = root.resolve("dependency.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            JarEntry entry = new JarEntry("dep/Parent.class");
            entry.setTime(0L);
            output.putNextEntry(entry);
            output.write(Files.readAllBytes(classes.resolve("dep/Parent.class")));
            output.closeEntry();
        }
        deleteTree(sourceRoot);
        return jar;
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
