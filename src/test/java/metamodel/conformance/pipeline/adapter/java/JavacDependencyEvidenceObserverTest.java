package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.model.ClassifierAbstraction;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.Inheritability;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavacDependencyEvidenceObserverTest {
    @TempDir
    Path temporary;

    @Test
    void mapsInheritedAndOverrideFactsAcrossDependencyBytecode() throws Exception {
        Path dependencySource = temporary.resolve("dependency-source/dep");
        Path dependencyClasses = temporary.resolve("dependency-classes");
        Files.createDirectories(dependencySource);
        Files.createDirectories(dependencyClasses);
        Path parentSource = dependencySource.resolve("Parent.java");
        Files.writeString(parentSource, """
                package dep;
                public class Parent {
                    public String work(String value) { return value; }
                    protected int count;
                }
                """);
        int compiled = ToolProvider.getSystemJavaCompiler().run(
                null, null, null,
                "--release", "17",
                "-d", dependencyClasses.toString(),
                parentSource.toString());
        assertEquals(0, compiled);

        Path archive = temporary.resolve("dependency.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive));
                var paths = Files.walk(dependencyClasses)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String entryName = dependencyClasses.relativize(path).toString().replace('\\', '/');
                JarEntry entry = new JarEntry(entryName);
                entry.setTime(0L);
                output.putNextEntry(entry);
                output.write(Files.readAllBytes(path));
                output.closeEntry();
            }
        }

        Path sourceDirectory = temporary.resolve("verification/units/app");
        Files.createDirectories(sourceDirectory);
        Path childSource = sourceDirectory.resolve("Child.java");
        Files.writeString(childSource, """
                package app;
                public class Child extends dep.Parent {
                    @Override public String work(String value) { return value + "!"; }
                }
                """);

        String digest = "b".repeat(64);
        String unit = "dependencies/" + digest + "/dependency.jar";
        JavaDependencySymbols.TypeSymbol parent = new JavaDependencySymbols.TypeSymbol(
                unit,
                digest,
                "dep.Parent",
                "dep",
                ClassifierKind.CLASS,
                ClassifierAbstraction.CONCRETE,
                List.of(),
                List.of(
                        new JavaDependencySymbols.MemberSymbol(
                                MemberKind.METHOD,
                                "work",
                                List.of("java.lang.String"),
                                "java.lang.String",
                                Inheritability.INHERITABLE,
                                MemberVisibility.PUBLIC,
                                MethodAbstraction.CONCRETE,
                                MemberScope.INSTANCE),
                        new JavaDependencySymbols.MemberSymbol(
                                MemberKind.ATTRIBUTE,
                                "count",
                                List.of(),
                                null,
                                Inheritability.INHERITABLE,
                                MemberVisibility.PROTECTED,
                                MethodAbstraction.UNKNOWN,
                                MemberScope.UNKNOWN)));
        JavaDependencyObservation.Result support = JavaDependencyObservation.materialize(
                new JavaDependencySymbols.Result(List.of(parent), Set.of()));

        // javac's effective member view of Child also contains the members that
        // dep.Parent inherits from java.lang.Object. The production stack
        // materializes that platform root from the compilation profile before
        // the boundary audit can accept the observation, so the component
        // boundary must include the same platform support evidence.
        Path platformManifest = temporary.resolve("platform-context.tsv");
        Files.writeString(platformManifest, String.join("\n",
                "context\tplatform\t.\t\t\t17\tfalse\tjdk-17",
                "source\tplatform\tverification/units/app") + "\n");
        JavaPlatformProvenance platform = JavaPlatformProvenance.capture(
                JavaDependencyInputs.fromManifest(platformManifest).context("platform"));
        JavaDependencyObservation.Result platformSupport = JavaDependencyObservation.materialize(
                JavaDependencySymbols.resolve(
                        JavaDependencyClasspath.resolve(List.of()),
                        Set.of("java.lang.Object"),
                        platform,
                        List.of("--release", "17")));
        List<ClassifierObservation> boundaryClassifiers =
                new java.util.ArrayList<>(support.classifiers());
        boundaryClassifiers.addAll(platformSupport.classifiers());
        List<MemberObservation> boundaryMembers =
                new java.util.ArrayList<>(support.members());
        boundaryMembers.addAll(platformSupport.members());

        String childId = "cls_" + "1".repeat(64);
        String localWorkKey = "mem_" + "2".repeat(64);
        MemberObservation localWork = new MemberObservation(
                localWorkKey,
                null,
                MemberKind.METHOD,
                Inheritability.INHERITABLE,
                MemberVisibility.PUBLIC,
                "work",
                "verification/units/app/Child.java",
                3,
                3,
                List.of("java.lang.String"),
                MethodAbstraction.CONCRETE,
                MemberScope.INSTANCE,
                null,
                List.of());
        ClassifierObservation child = new ClassifierObservation(
                childId,
                "app.Child",
                "app",
                ClassifierKind.CLASS,
                "verification/units/app/Child.java",
                2,
                4,
                List.of(support.classifierId("dep.Parent")),
                List.of(localWorkKey),
                List.of(),
                ClassifierAbstraction.CONCRETE);

        JavacDependencyEvidenceObserver.Result evidence =
                JavacDependencyEvidenceObserver.observeCompilationContext(
                        temporary,
                        List.of(childSource),
                        List.of(child),
                        List.of(localWork),
                        List.of(),
                        List.of(),
                        boundaryClassifiers,
                        boundaryMembers,
                        List.of("--release", "17", "--class-path", archive.toString()));

        assertTrue(evidence.complete(), () -> evidence.diagnostics().toString());
        String inheritedWork = support.memberKey(
                "dep.Parent", MemberKind.METHOD, "work", List.of("java.lang.String"));
        String inheritedCount = support.memberKey(
                "dep.Parent", MemberKind.ATTRIBUTE, "count", List.of());
        String inheritedGetClass = platformSupport.memberKey(
                "java.lang.Object", MemberKind.METHOD, "getClass", List.of());
        assertFalse(evidence.inheritedByClassifier().get(childId).contains(inheritedWork));
        assertTrue(evidence.inheritedByClassifier().get(childId).contains(inheritedCount));
        assertTrue(evidence.inheritedByClassifier().get(childId).contains(inheritedGetClass));
        assertEquals(List.of(inheritedWork),
                evidence.overriddenMemberKeysByMember().get(localWorkKey));
        assertEquals("java.lang.String", evidence.returnTypesByMember().get(localWorkKey));
    }
}
