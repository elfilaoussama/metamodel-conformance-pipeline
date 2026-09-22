package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.adapter.SourceObserver;
import metamodel.conformance.pipeline.model.ClassifierAbstraction;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.SourceUnit;
import metamodel.conformance.pipeline.model.UnresolvedParent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDependencyAwareCompilationContextIsolationTest {
    @TempDir Path temporary;

    @Test
    void independentContextsMayResolveSameFqnToDifferentBytecode() throws Exception {
        Path productionSource = source("code/production/app/ProductionChild.java", "ProductionChild");
        Path verificationSource = source("verification/suite/app/VerificationChild.java", "VerificationChild");
        Path productionJar = dependencyJar(temporary.resolve("dependency-production"), "onlyProduction");
        Path verificationJar = dependencyJar(temporary.resolve("dependency-verification"), "onlyVerification");
        JavaDependencyInputs inputs = contexts(
                "code/production", productionJar,
                "verification/suite", verificationJar,
                false);

        String productionId = "cls_" + "1".repeat(64);
        String verificationId = "cls_" + "2".repeat(64);
        ClassifierObservation productionClassifier = classifier(
                productionId, "app.ProductionChild", "code/production/app/ProductionChild.java");
        ClassifierObservation verificationClassifier = classifier(
                verificationId, "app.VerificationChild", "verification/suite/app/VerificationChild.java");
        Observation base = baseObservation(
                List.of(productionClassifier, verificationClassifier),
                List.of(
                        new UnresolvedParent(productionId, "dep.Parent", productionClassifier.sourcePath(), 2),
                        new UnresolvedParent(verificationId, "dep.Parent", verificationClassifier.sourcePath(), 2)));
        SourceObserver delegate = (sourceRoot, externalParents) -> base;

        Observation observed = new JavaDependencyAwareSourceObserver(inputs, delegate)
                .observe(temporary, Set.of());

        assertEquals("13", observed.schemaVersion());
        assertTrue(observed.unresolvedParents().isEmpty(), () -> observed.unresolvedParents().toString());
        assertTrue(observed.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertTrue(observed.completeEvidence().contains(EvidenceKind.INHERITED_MEMBERS));
        Map<String, ClassifierObservation> classifiers = classifiersById(observed);
        ClassifierObservation production = classifiers.get(productionId);
        ClassifierObservation verification = classifiers.get(verificationId);
        assertEquals(1, production.parentIds().size());
        assertEquals(1, verification.parentIds().size());
        assertNotEquals(production.parentIds().get(0), verification.parentIds().get(0));

        Map<String, MemberObservation> members = membersByKey(observed);
        Set<String> productionInherited = memberNames(production.inheritedMemberKeys(), members);
        Set<String> verificationInherited = memberNames(verification.inheritedMemberKeys(), members);
        assertTrue(productionInherited.contains("onlyProduction"), productionInherited::toString);
        assertFalse(productionInherited.contains("onlyVerification"), productionInherited::toString);
        assertTrue(verificationInherited.contains("onlyVerification"), verificationInherited::toString);
        assertFalse(verificationInherited.contains("onlyProduction"), verificationInherited::toString);
        assertEquals(2, observed.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("dep.Parent")).count());
        assertTrue(Files.exists(productionSource));
        assertTrue(Files.exists(verificationSource));
    }

    @Test
    void compilationContextWithoutJarArchivesStillControlsSourceOwnership() throws Exception {
        Path source = temporary.resolve("outside/context/app/Only.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package app; public class Only {}\n");
        Path manifest = temporary.resolve("directory-only-context.tsv");
        Files.writeString(manifest, String.join("\n",
                "context\tobserved\t.\t\t\t17\tfalse\tjdk-17",
                "source\tobserved\towned/context") + "\n");
        JavaDependencyInputs inputs = JavaDependencyInputs.fromManifest(manifest);
        String id = "cls_" + "9".repeat(64);
        ClassifierObservation classifier = classifier(id, "app.Only", "outside/context/app/Only.java");
        Observation base = baseObservation(List.of(classifier), List.of());
        SourceObserver delegate = (sourceRoot, externalParents) -> base;

        Observation observed = new JavaDependencyAwareSourceObserver(inputs, delegate)
                .observe(temporary, Set.of());

        assertTrue(observed.diagnostics().stream().anyMatch(item ->
                item.kind() == DiagnosticKind.EVIDENCE_INCOMPLETE
                        && item.message().contains("not owned by an observed compilation context")));
    }

    @Test
    void materializesPlatformParentsThroughTheObservedCompilerContext() throws Exception {
        Path source = temporary.resolve("code/main/app/Child.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package app; public class Child extends java.lang.RuntimeException {}\n");
        Path manifest = temporary.resolve("platform-context.tsv");
        Files.writeString(manifest, String.join("\n",
                "context\tmain\t.\t\t\t17\tfalse\tjdk-17",
                "source\tmain\tcode/main") + "\n");
        JavaDependencyInputs inputs = JavaDependencyInputs.fromManifest(manifest);
        String id = "cls_" + "8".repeat(64);
        ClassifierObservation child = classifier(id, "app.Child", "code/main/app/Child.java");
        Observation base = baseObservation(
                List.of(child), List.of(new UnresolvedParent(id, "java.lang.RuntimeException", child.sourcePath(), 1)));

        Observation observed = new JavaDependencyAwareSourceObserver(inputs, (root, parents) -> base)
                .observe(temporary, Set.of());

        assertEquals("13", observed.schemaVersion());
        assertTrue(observed.unresolvedParents().isEmpty(), () -> observed.unresolvedParents().toString());
        assertTrue(observed.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertTrue(observed.completeEvidence().contains(EvidenceKind.INHERITED_MEMBERS));
        assertEquals(1, observed.platformEvidence().size());
        assertEquals("main", observed.platformEvidence().get(0).contextId());
        assertFalse(observed.platformEvidence().get(0).terminalTypeNames().isEmpty());
        assertTrue(observed.units().stream().anyMatch(unit -> unit.language() == Language.JAVA_PLATFORM));
        assertTrue(observed.classifiers().stream().anyMatch(item ->
                item.qualifiedName().equals("java.lang.RuntimeException")));
    }

    @Test
    void materializesTheImplicitEnumSuperclassFromTheCompilationProfile() throws Exception {
        Path source = temporary.resolve("code/main/app/Choice.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package app; public enum Choice { FIRST, SECOND }\n");
        Path manifest = temporary.resolve("enum-platform-context.tsv");
        Files.writeString(manifest, String.join("\n",
                "context\tmain\t.\t\t\t17\tfalse\tjdk-17",
                "source\tmain\tcode/main") + "\n");

        Observation observed = new JavaDependencyAwareSourceObserver(
                JavaDependencyInputs.fromManifest(manifest)).observe(temporary, Set.of());

        ClassifierObservation choice = observed.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("app.Choice")).findFirst().orElseThrow();
        ClassifierObservation enumType = observed.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("java.lang.Enum")).findFirst().orElseThrow();
        assertEquals(List.of(enumType.id()), choice.parentIds());
        assertTrue(observed.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertTrue(observed.completeEvidence().contains(EvidenceKind.INHERITED_MEMBERS));
        assertTrue(observed.diagnostics().isEmpty(), () -> observed.diagnostics().toString());
    }

    @Test
    void downstreamContextRecoversDependencyMembersThroughExplicitUpstreamContext() throws Exception {
        Path baseSource = temporary.resolve("code/production/app/Base.java");
        Files.createDirectories(baseSource.getParent());
        Files.writeString(baseSource, "package app; public class Base extends dep.Parent {}\n");
        Path childSource = temporary.resolve("verification/suite/app/Child.java");
        Files.createDirectories(childSource.getParent());
        Files.writeString(childSource, "package app; public class Child extends Base {}\n");

        Path dependency = dependencyJar(temporary.resolve("dependency-shared"), "throughUpstream");
        JavaDependencyInputs inputs = contexts(
                "code/production", dependency,
                "verification/suite", dependency,
                true);

        String baseId = "cls_" + "3".repeat(64);
        String childId = "cls_" + "4".repeat(64);
        ClassifierObservation baseClassifier = classifier(baseId, "app.Base", "code/production/app/Base.java");
        ClassifierObservation childClassifier = new ClassifierObservation(
                childId, "app.Child", "app", ClassifierKind.CLASS,
                "verification/suite/app/Child.java", 1, 1,
                List.of(baseId), List.of(), List.of(), ClassifierAbstraction.CONCRETE);
        Observation base = baseObservation(
                List.of(baseClassifier, childClassifier),
                List.of(new UnresolvedParent(baseId, "dep.Parent", baseClassifier.sourcePath(), 1)));
        SourceObserver delegate = (sourceRoot, externalParents) -> base;

        Observation observed = new JavaDependencyAwareSourceObserver(inputs, delegate)
                .observe(temporary, Set.of());

        assertTrue(observed.unresolvedParents().isEmpty(), () -> observed.unresolvedParents().toString());
        assertTrue(observed.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertTrue(observed.completeEvidence().contains(EvidenceKind.INHERITED_MEMBERS));
        Map<String, ClassifierObservation> classifiers = classifiersById(observed);
        Map<String, MemberObservation> members = membersByKey(observed);
        assertTrue(memberNames(classifiers.get(baseId).inheritedMemberKeys(), members).contains("throughUpstream"));
        assertTrue(memberNames(classifiers.get(childId).inheritedMemberKeys(), members).contains("throughUpstream"));
        assertEquals(1, observed.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("dep.Parent")).count());
    }

    @Test
    void downstreamContextRejectsDifferentBytecodeForUpstreamDependency() throws Exception {
        Path baseSource = temporary.resolve("code/production/app/Base.java");
        Files.createDirectories(baseSource.getParent());
        Files.writeString(baseSource, "package app; public class Base extends dep.Parent {}\n");
        Path childSource = temporary.resolve("verification/suite/app/Child.java");
        Files.createDirectories(childSource.getParent());
        Files.writeString(childSource, "package app; public class Child extends Base {}\n");

        Path productionDependency = dependencyJar(temporary.resolve("dependency-production"), "productionOnly");
        Path verificationDependency = dependencyJar(temporary.resolve("dependency-verification"), "verificationOnly");
        JavaDependencyInputs inputs = contexts(
                "code/production", productionDependency,
                "verification/suite", verificationDependency,
                true);

        String baseId = "cls_" + "6".repeat(64);
        String childId = "cls_" + "7".repeat(64);
        ClassifierObservation baseClassifier = classifier(baseId, "app.Base", "code/production/app/Base.java");
        ClassifierObservation childClassifier = new ClassifierObservation(
                childId, "app.Child", "app", ClassifierKind.CLASS,
                "verification/suite/app/Child.java", 1, 1,
                List.of(baseId), List.of(), List.of(), ClassifierAbstraction.CONCRETE);
        Observation base = baseObservation(
                List.of(baseClassifier, childClassifier),
                List.of(new UnresolvedParent(baseId, "dep.Parent", baseClassifier.sourcePath(), 1)));
        SourceObserver delegate = (sourceRoot, externalParents) -> base;

        Observation observed = new JavaDependencyAwareSourceObserver(inputs, delegate)
                .observe(temporary, Set.of());

        assertTrue(observed.unresolvedParents().isEmpty());
        assertFalse(observed.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertFalse(observed.completeEvidence().contains(EvidenceKind.INHERITED_MEMBERS));
        assertTrue(observed.diagnostics().stream().anyMatch(item ->
                item.kind() == DiagnosticKind.EVIDENCE_INCOMPLETE
                        && item.message().contains("different bytecode")));
    }

    @Test
    void upstreamContextCompilesWithItsOwnDependenciesNotDownstreamDependencies() throws Exception {
        Path baseSource = temporary.resolve("code/production/app/Base.java");
        Files.createDirectories(baseSource.getParent());
        Files.writeString(baseSource, "package app; public class Base { dep.MainOnly dependency; }\n");
        Path childSource = temporary.resolve("verification/suite/app/Child.java");
        Files.createDirectories(childSource.getParent());
        Files.writeString(childSource, "package app; public class Child extends Base {}\n");

        Path productionDependency = typeJar(temporary.resolve("production-dependency"), "dep", "MainOnly");
        Path verificationDependency = emptyJar(temporary.resolve("verification-dependency.jar"));
        JavaDependencyInputs inputs = contexts(
                "code/production", productionDependency,
                "verification/suite", verificationDependency,
                true);
        Map<String, List<Path>> files = Map.of(
                "production", List.of(baseSource),
                "verification", List.of(childSource));

        try (JavacCompilationContext context = JavacCompilationContext.prepare(
                temporary, inputs.context("verification"), files, inputs)) {
            assertTrue(context.complete(), () -> context.diagnostics().toString());
            assertTrue(context.classpath().contains(verificationDependency.toString()));
            assertFalse(context.classpath().contains(productionDependency.toString()));
        }
    }

    @Test
    void missingTransitiveDependencyKeepsHierarchyEvidenceIncomplete() throws Exception {
        Path source = source("odd/topology/app/Top.java", "Top");
        Files.writeString(source, "package app; public class Top extends dep.Child {}\n");

        Path missingClasses = Files.createDirectories(temporary.resolve("missing-classes"));
        Path missingSource = Files.createDirectories(temporary.resolve("missing-src/missing")).resolve("Base.java");
        Files.writeString(missingSource, "package missing; public class Base {}\n");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "--release", "17", "-d", missingClasses.toString(), missingSource.toString()));

        Path childClasses = Files.createDirectories(temporary.resolve("child-classes"));
        Path childSource = Files.createDirectories(temporary.resolve("child-src/dep")).resolve("Child.java");
        Files.writeString(childSource,
                "package dep; public class Child extends missing.Base { public int value; }\n");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "--release", "17", "-classpath", missingClasses.toString(),
                "-d", childClasses.toString(), childSource.toString()));
        Path childJar = temporary.resolve("child-only.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(childJar))) {
            JarEntry entry = new JarEntry("dep/Child.class");
            entry.setTime(0L);
            output.putNextEntry(entry);
            output.write(Files.readAllBytes(childClasses.resolve("dep/Child.class")));
            output.closeEntry();
        }

        Path manifest = temporary.resolve("single-context.tsv");
        Files.writeString(manifest, String.join("\n",
                "context\tsingle\t.\t\t\t17\tfalse\tjdk-17",
                "source\tsingle\todd/topology",
                "classpath\tsingle\t" + childJar) + "\n");
        JavaDependencyInputs inputs = JavaDependencyInputs.fromManifest(manifest);
        String id = "cls_" + "5".repeat(64);
        ClassifierObservation top = classifier(id, "app.Top", "odd/topology/app/Top.java");
        Observation base = baseObservation(
                List.of(top), List.of(new UnresolvedParent(id, "dep.Child", top.sourcePath(), 1)));
        SourceObserver delegate = (sourceRoot, externalParents) -> base;

        Observation observed = new JavaDependencyAwareSourceObserver(inputs, delegate)
                .observe(temporary, Set.of());

        assertFalse(observed.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertFalse(observed.completeEvidence().contains(EvidenceKind.INHERITED_MEMBERS));
        assertTrue(observed.diagnostics().stream()
                .anyMatch(item -> item.kind() == DiagnosticKind.EVIDENCE_INCOMPLETE));
    }

    private JavaDependencyInputs contexts(
            String productionRoot, Path productionJar,
            String verificationRoot, Path verificationJar,
            boolean upstream) throws Exception {
        Path manifest = temporary.resolve("contexts-" + System.nanoTime() + ".tsv");
        StringBuilder text = new StringBuilder()
                .append("context\tproduction\t.\t\t\t17\tfalse\tjdk-17\n")
                .append("source\tproduction\t").append(productionRoot).append('\n')
                .append("classpath\tproduction\t").append(productionJar).append('\n')
                .append("context\tverification\t.\t\t\t17\tfalse\tjdk-17\n")
                .append("source\tverification\t").append(verificationRoot).append('\n')
                .append("classpath\tverification\t").append(verificationJar).append('\n');
        if (upstream) text.append("upstream\tverification\tproduction\n");
        Files.writeString(manifest, text);
        return JavaDependencyInputs.fromManifest(manifest);
    }

    private Observation baseObservation(
            List<ClassifierObservation> classifiers,
            List<UnresolvedParent> unresolved) {
        List<SourceUnit> units = classifiers.stream()
                .map(item -> new SourceUnit(Language.JAVA, item.sourcePath(),
                        Integer.toHexString(item.sourcePath().hashCode()).repeat(64).substring(0, 64)))
                .toList();
        return new Observation(
                "13", "spoon-java", "test", List.of(), Set.of(), units, classifiers,
                List.of(), List.of(), List.of(), unresolved, List.of());
    }

    private Path source(String relative, String typeName) throws Exception {
        Path file = temporary.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package app; public class " + typeName + " extends dep.Parent {}\n");
        return file;
    }

    private static ClassifierObservation classifier(String id, String name, String path) {
        return new ClassifierObservation(
                id, name, "app", ClassifierKind.CLASS, path, 1, 1,
                List.of(), List.of(), List.of(), ClassifierAbstraction.CONCRETE);
    }

    private static Map<String, ClassifierObservation> classifiersById(Observation observation) {
        Map<String, ClassifierObservation> result = new HashMap<>();
        observation.classifiers().forEach(item -> result.put(item.id(), item));
        return result;
    }

    private static Map<String, MemberObservation> membersByKey(Observation observation) {
        Map<String, MemberObservation> result = new HashMap<>();
        observation.members().forEach(item -> result.put(item.technicalKey(), item));
        return result;
    }

    private static Set<String> memberNames(List<String> keys, Map<String, MemberObservation> members) {
        Set<String> names = new TreeSet<>();
        for (String key : keys) names.add(members.get(key).memberName());
        return names;
    }

    private Path dependencyJar(Path root, String fieldName) throws Exception {
        Path source = Files.createDirectories(root.resolve("source/dep"));
        Path classes = Files.createDirectories(root.resolve("classes"));
        Path java = source.resolve("Parent.java");
        Files.writeString(java, "package dep; public class Parent { public int " + fieldName + "; }\n");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "--release", "17", "-d", classes.toString(), java.toString()));
        Path jar = root.resolve("dependency.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            JarEntry entry = new JarEntry("dep/Parent.class");
            entry.setTime(0L);
            output.putNextEntry(entry);
            output.write(Files.readAllBytes(classes.resolve("dep/Parent.class")));
            output.closeEntry();
        }
        return jar;
    }

    private Path typeJar(Path root, String packageName, String typeName) throws Exception {
        Path source = Files.createDirectories(root.resolve("source").resolve(packageName.replace('.', '/')));
        Path classes = Files.createDirectories(root.resolve("classes"));
        Path java = source.resolve(typeName + ".java");
        Files.writeString(java, "package " + packageName + "; public class " + typeName + " {}\n");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "--release", "17", "-d", classes.toString(), java.toString()));
        Path jar = root.resolve("dependency.jar");
        String entryName = packageName.replace('.', '/') + "/" + typeName + ".class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            JarEntry entry = new JarEntry(entryName);
            entry.setTime(0L);
            output.putNextEntry(entry);
            output.write(Files.readAllBytes(classes.resolve(entryName)));
            output.closeEntry();
        }
        return jar;
    }

    private static Path emptyJar(Path jar) throws Exception {
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(jar))) { }
        return jar;
    }
}
