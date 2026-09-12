package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.emf.ObservationXmiReader;
import metamodel.conformance.pipeline.emf.ObservationXmiWriter;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.List;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaDeclarationCorrespondenceTest {
    @TempDir Path temporary;

    @Test
    void mapsAnnotatedMultilineClassAndMethodDeclarations() throws Exception {
        Observation observation = observe("""
                @Deprecated
                class Parent {
                    @Deprecated
                    public String work(String value) { return value; }
                }
                @Deprecated
                class Child
                        extends Parent {
                    @Override
                    public String work(String value) { return value; }
                }
                """);
        assertCompilerEvidence(observation);
    }

    @Test
    void mapsMixedGenericParametersForActualOverrideTargets() throws Exception {
        Observation observation = observe("""
                interface Parent<V> {
                    void putAll(Iterable<String> keys, V value);
                    void unrelated(int value);
                }
                abstract class Child<V> implements Parent<V> {
                    public void putAll(Iterable<String> keys, V value) {}
                }
                """);
        assertCompilerEvidence(observation);
        assertTrue(observation.members().stream().anyMatch(member ->
                member.memberName().equals("putAll") && !member.overriddenMemberKeys().isEmpty()));
    }

    @Test
    void mapsNamedTypesOnTheSameLineAndAnnotatedNestedTypes() throws Exception {
        assertCompilerEvidence(observe("""
                class First {} class Second {}
                class Outer {
                    @Deprecated
                    static class Nested {}
                }
                """));
    }

    @Test
    void mapsTransitiveGenericOverrideWithOriginalCompilerOwner() throws Exception {
        Observation observation = observe("""
                interface Root<V> {
                    void accept(V value);
                }
                abstract class Middle<V> implements Root<V> {}
                class Leaf extends Middle<String> {
                    public void accept(String value) {}
                }
                """);
        assertCompilerEvidence(observation);
        assertTrue(observation.members().stream().anyMatch(member ->
                member.memberName().equals("accept") && !member.overriddenMemberKeys().isEmpty()));
    }

    @Test
    void doesNotRequireMappingUnrelatedGenericAncestorMethod() throws Exception {
        assertCompilerEvidence(observe("""
                interface Parent<V> {
                    void putAll(Iterable<String> keys, V value);
                }
                abstract class Child<V> implements Parent<V> {
                    public void unrelated() {}
                }
                """));
    }

    @Test
    void mapsInheritedGenericOverloadsByTheirSourceDeclarations() throws Exception {
        assertCompilerEvidence(observe("""
                interface Parent<V> {
                    void putAll(Iterable<String> keys, V value);
                    void putAll(String[] keys, V value);
                }
                abstract class Child<V> implements Parent<V> {}
                """));
    }

    @Test
    void retainsDistinctSameLineOverloadsAndRoundTripsObservedOverrides() throws Exception {
        Observation observation = observe("""
                interface Parent { void accept(int value); void accept(String value); }
                abstract class Child implements Parent {
                    public void accept(String value) {}
                }
                """);
        assertCompilerEvidence(observation);
        var override = observation.members().stream()
                .filter(member -> !member.overriddenMemberKeys().isEmpty()).findFirst().orElseThrow();
        assertEquals(1, override.overriddenMemberKeys().size());
        var target = observation.members().stream()
                .filter(member -> member.technicalKey().equals(override.overriddenMemberKeys().get(0)))
                .findFirst().orElseThrow();
        assertEquals(List.of("java.lang.String"), target.parameterTypes());
        Path xmi = temporary.resolve("observation.xmi");
        new ObservationXmiWriter().write(observation, xmi);
        assertEquals(observation, new ObservationXmiReader().read(xmi));
    }

    @Test
    void rejectsAmbiguousCanonicalCarriersInsteadOfChoosingFirst() throws Exception {
        Observation observation = observe("class Sample {}\n");
        ClassifierObservation original = observation.classifiers().get(0);
        ClassifierObservation duplicate = new ClassifierObservation(
                "cls_" + "f".repeat(64), original.qualifiedName(), original.packageName(), original.kind(),
                original.sourcePath(), original.startLine(), original.endLine(), original.parentIds(),
                original.declaredMemberKeys(), original.inheritedMemberKeys(), original.abstraction());
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(null, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, files, null,
                    List.of("--release", "17", "-proc:none"), null,
                    files.getJavaFileObjects(temporary.resolve("source/Sample.java").toFile()));
            var units = new java.util.ArrayList<com.sun.source.tree.CompilationUnitTree>();
            task.parse().forEach(units::add);
            task.analyze();
            var index = JavacSourceTypeIndex.create(temporary.resolve("source"), units,
                    Trees.instance(task), task.getElements(), List.of(original, duplicate));
            assertNull(index.type(original));
            assertNull(index.type(duplicate));
        }
    }

    @Test
    void mapsNestedArrayAndMixedGenericParametersAcrossUpstreamBinaries() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("repository"));
        Path main = Files.createDirectories(root.resolve("code/production/example"));
        Path test = Files.createDirectories(root.resolve("code/verification/example"));
        Files.writeString(main.resolve("Parent.java"), """
                package example;
                public class Parent {
                    public static class Context {}
                    public void work(Context[] values) {}
                    public void work(String[] values) {}
                    public <V> void mixed(java.util.List<String> values, V value) {}
                }
                """);
        Files.writeString(test.resolve("Child.java"), """
                package example;
                public class Child extends Parent {
                    public void work(Context[] values) {}
                    public <V> void mixed(java.util.List<String> values, V value) {}
                }
                """);
        Path manifest = temporary.resolve("upstream.tsv");
        Files.writeString(manifest, """
                context\tproduction\t.\t\t\t17\tfalse\t
                source\tproduction\tcode/production
                context\tverification\t.\t\t\t17\tfalse\t
                source\tverification\tcode/verification
                upstream\tverification\tproduction
                """);
        Observation observation = new JavaDependencyAwareSourceObserver(JavaDependencyInputs.fromManifest(manifest))
                .observe(root, Set.of());
        assertCompilerEvidence(observation);
        var childMethods = observation.members().stream()
                .filter(member -> member.sourcePath().endsWith("Child.java")).toList();
        assertEquals(2, childMethods.size());
        for (var member : childMethods) assertEquals(1, member.overriddenMemberKeys().size());
    }

    @Test
    void retainsInheritedMembersAndOverridesFromCompiledNestedOwners() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("nested-owner"));
        Path main = Files.createDirectories(root.resolve("code/production/example"));
        Path test = Files.createDirectories(root.resolve("code/verification/example"));
        Files.writeString(main.resolve("Parent.java"), """
                package example;
                public class Parent {
                    public static class Base {
                        public int payload;
                        public void run() {}
                    }
                }
                """);
        Files.writeString(test.resolve("Child.java"), """
                package example;
                public class Child extends Parent.Base {
                    public void run() {}
                }
                """);
        Path manifest = temporary.resolve("nested-owner.tsv");
        Files.writeString(manifest, """
                context\tproduction\t.\t\t\t17\tfalse\t
                source\tproduction\tcode/production
                context\tverification\t.\t\t\t17\tfalse\t
                source\tverification\tcode/verification
                upstream\tverification\tproduction
                """);
        Observation observation = new JavaDependencyAwareSourceObserver(JavaDependencyInputs.fromManifest(manifest))
                .observe(root, Set.of());
        assertCompilerEvidence(observation);
        var child = observation.classifiers().stream()
                .filter(classifier -> classifier.qualifiedName().equals("example.Child")).findFirst().orElseThrow();
        var payload = observation.members().stream()
                .filter(member -> member.memberName().equals("payload")).findFirst().orElseThrow();
        assertTrue(child.inheritedMemberKeys().contains(payload.technicalKey()));
        var override = observation.members().stream()
                .filter(member -> member.sourcePath().endsWith("Child.java")).findFirst().orElseThrow();
        var target = observation.members().stream()
                .filter(member -> member.memberName().equals("run") && member.sourcePath().endsWith("Parent.java"))
                .findFirst().orElseThrow();
        assertEquals(List.of(target.technicalKey()), override.overriddenMemberKeys());
    }

    private Observation observe(String source) throws Exception {
        Path root = Files.createDirectories(temporary.resolve("source"));
        Files.writeString(root.resolve("Sample.java"), source);
        Path manifest = temporary.resolve("contexts.tsv");
        Files.writeString(manifest, "context\ttest\t.\t\t\t17\tfalse\t\nsource\ttest\t.\n");
        return new JavaDependencyAwareSourceObserver(JavaDependencyInputs.fromManifest(manifest))
                .observe(root, Set.of());
    }

    private static void assertCompilerEvidence(Observation observation) {
        for (EvidenceKind kind : Set.of(EvidenceKind.HIERARCHY, EvidenceKind.INHERITED_MEMBERS,
                EvidenceKind.IMPLEMENTATION_BINDINGS, EvidenceKind.OVERRIDE_RELATIONS,
                EvidenceKind.METHOD_RETURN_TYPES)) {
            assertTrue(observation.completeEvidence().contains(kind),
                    () -> kind + ": " + observation.diagnostics());
        }
    }
}
