package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Observation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SpoonCompilationContextIsolationTest {
    @TempDir Path root;

    @Test
    void unsupportedIndependentContextDoesNotEraseSupportedDeclarations() throws Exception {
        source("stable/Good.java", "class Good { void work() {} }");
        source("future/New.java", "class New {}");
        JavaDependencyInputs inputs = inputs("""
                context\tstable\t.\t\t\t17\tfalse\tjdk-17
                source\tstable\tstable
                context\tfuture\t.\t\t\t99\tfalse\tfuture
                source\tfuture\tfuture
                """);

        Observation result = new SpoonJavaObserver(inputs).observe(root, Set.of());

        assertEquals(2, result.units().size());
        assertEquals(1, result.classifiers().size());
        assertEquals("Good", result.classifiers().get(0).qualifiedName());
        assertTrue(result.members().stream().anyMatch(member -> member.memberName().equals("work")));
        assertTrue(result.completeEvidence().isEmpty());
        assertEquals(1, result.diagnostics().size());
        assertEquals("future/New.java", result.diagnostics().get(0).sourcePath());
        assertEquals(DiagnosticKind.EVIDENCE_INCOMPLETE, result.diagnostics().get(0).kind());
        assertEquals(result, new SpoonJavaObserver(inputs).observe(root, Set.of()));
    }

    @Test
    void independentDuplicateTypesKeepTheirPackageAnnotationPeers() throws Exception {
        for (String variant : new String[]{"alpha", "beta"}) {
            source(variant + "/package-info.java", "@Mark(value = VALUE) package example; import static example.Mode.VALUE;");
            source(variant + "/Mark.java", "package example; @interface Mark { Mode value(); }");
            source(variant + "/Mode.java", "package example; enum Mode { VALUE }");
            source(variant + "/Subject.java", "package example; class Subject {}");
        }
        JavaDependencyInputs inputs = inputs("""
                context\talpha\t.\t\t\t17\tfalse\tjdk-17
                source\talpha\talpha
                context\tbeta\t.\t\t\t17\tfalse\tjdk-17
                source\tbeta\tbeta
                """);

        Observation result = new SpoonJavaObserver(inputs).observe(root, Set.of());

        assertEquals(6, result.classifiers().size());
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        assertTrue(result.completeEvidence().contains(EvidenceKind.DECLARATION_OWNERSHIP));
    }

    @Test
    void identicalSharedDeclarationsAreNotDuplicated() throws Exception {
        source("shared/Shared.java", "class Shared { void work() {} }");
        JavaDependencyInputs inputs = inputs("""
                context\ta\t.\t\t\t17\tfalse\tjdk-17
                source\ta\tshared
                context\tb\t.\t\t\t17\tfalse\tjdk-17
                source\tb\tshared
                """);
        Observation result = new SpoonJavaObserver(inputs).observe(root, Set.of());
        assertEquals(1, result.classifiers().size());
        assertEquals(1, result.members().size());
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
    }

    private void source(String name, String text) throws Exception {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void conflictingSharedInterpretationsAreNotSelectedByContextOrder() throws Exception {
        source("shared/Shared.java", "package use; import one.*; import two.*; class Shared extends Parent {}");
        source("alpha/Parent.java", "package one; public class Parent {}");
        source("beta/Parent.java", "package two; public class Parent {}");
        JavaDependencyInputs inputs = inputs("""
                context\ta\t.\t\t\t17\tfalse\tjdk-17
                source\ta\tshared
                source\ta\talpha
                context\tb\t.\t\t\t17\tfalse\tjdk-17
                source\tb\tshared
                source\tb\tbeta
                """);
        Observation result = new SpoonJavaObserver(inputs).observe(root, Set.of());
        assertTrue(result.classifiers().stream().noneMatch(item -> item.qualifiedName().equals("use.Shared")));
        assertTrue(result.diagnostics().stream().anyMatch(item -> item.sourcePath().equals("shared/Shared.java")
                && item.message().contains("Conflicting structural")), result.diagnostics().toString());
        assertTrue(result.completeEvidence().isEmpty());
        assertEquals(3, result.units().size());
    }

    @Test
    void unownedSourcesArePreservedWithoutClaimingCompleteContextEvidence() throws Exception {
        source("owned/A.java", "class A {}");
        source("outside/B.java", "class B {}");
        Observation result = new SpoonJavaObserver(inputs("""
                context\tknown\t.\t\t\t17\tfalse\tjdk-17
                source\tknown\towned
                """)).observe(root, Set.of());
        assertEquals(2, result.units().size());
        assertEquals(2, result.classifiers().size());
        assertTrue(result.completeEvidence().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(item -> item.sourcePath().equals("outside/B.java")
                && item.message().contains("not owned")));
    }

    @Test
    void independentNestedHierarchiesRoundTripAndEvaluateAllInvariants() throws Exception {
        String code = """
                package same;
                class Outer {
                    static abstract class Base {
                        abstract int value();
                    }
                    static class Child extends Base {
                        int value() { return 1; }
                    }
                }
                """;
        source("alpha/Outer.java", code);
        source("beta/Outer.java", code);
        JavaDependencyInputs inputs = inputs("""
                context\ta\t.\t\t\t11\tfalse\tjdk-11
                source\ta\talpha
                context\tb\t.\t\t\t17\tfalse\tjdk-17
                source\tb\tbeta
                """);
        Observation result = new JavaDependencyAwareSourceObserver(inputs).observe(root, Set.of());
        // The platform is selected per compilation release, so the release-11 and
        // release-17 contexts observe distinct platform units and each source
        // context links to its own release's java.lang.Object.
        assertEquals(8, result.classifiers().size());
        String alphaPlatform = platformSha(result, "a");
        String betaPlatform = platformSha(result, "b");
        assertNotEquals(alphaPlatform, betaPlatform);
        var alphaObject = objectSupport(result, alphaPlatform);
        var betaObject = objectSupport(result, betaPlatform);
        var alphaOuter = result.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("same.Outer")
                        && item.sourcePath().equals("alpha/Outer.java"))
                .findFirst().orElseThrow();
        var alphaBase = result.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("same.Outer$Base")
                        && item.sourcePath().equals("alpha/Outer.java"))
                .findFirst().orElseThrow();
        var betaOuter = result.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("same.Outer")
                        && item.sourcePath().equals("beta/Outer.java"))
                .findFirst().orElseThrow();
        var betaBase = result.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("same.Outer$Base")
                        && item.sourcePath().equals("beta/Outer.java"))
                .findFirst().orElseThrow();
        assertTrue(alphaOuter.parentIds().contains(alphaObject.id()));
        assertTrue(alphaBase.parentIds().contains(alphaObject.id()));
        assertTrue(betaOuter.parentIds().contains(betaObject.id()));
        assertTrue(betaBase.parentIds().contains(betaObject.id()));
        assertEquals(2, result.methodBodies().size());
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        Path xmi = root.resolve("context-evidence.xmi");
        new metamodel.conformance.pipeline.emf.ObservationXmiWriter().write(result, xmi);
        Observation replayed = new metamodel.conformance.pipeline.emf.ObservationXmiReader().read(xmi);
        assertEquals(result, replayed);
        var decisions = new metamodel.conformance.pipeline.alloy.AlloyInvariantEvaluator().evaluateAll(replayed,
                new metamodel.conformance.pipeline.alloy.ExactAlloyEncoder().encode(replayed));
        assertFalse(decisions.isEmpty());
        decisions.forEach(item -> assertEquals(metamodel.conformance.pipeline.decision.DecisionStatus.CONFORMANT,
                item.status(), item.toString()));
    }

    private static String platformSha(Observation observation, String contextId) {
        return observation.platformEvidence().stream()
                .filter(item -> item.contextId().equals(contextId))
                .findFirst().orElseThrow().platformContentSha256();
    }

    private static metamodel.conformance.pipeline.model.ClassifierObservation objectSupport(
            Observation observation, String platformSha) {
        return observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("java.lang.Object")
                        && item.sourcePath().equals("platform/" + platformSha + "/release"))
                .findFirst().orElseThrow();
    }

    private JavaDependencyInputs inputs(String text) throws Exception {
        Path manifest = root.resolve("contexts.tsv");
        Files.writeString(manifest, text);
        return JavaDependencyInputs.fromManifest(manifest);
    }
}
