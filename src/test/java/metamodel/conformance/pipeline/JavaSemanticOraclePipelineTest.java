package metamodel.conformance.pipeline;

import metamodel.conformance.pipeline.adapter.java.JavaImplementationSourceObserver;
import metamodel.conformance.pipeline.alloy.ExactAlloyEncoder;
import metamodel.conformance.pipeline.capsule.CapsuleVerifier;
import metamodel.conformance.pipeline.decision.DecisionStatus;
import metamodel.conformance.pipeline.emf.ObservationXmiReader;
import metamodel.conformance.pipeline.model.EvidenceKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end semantic oracles spanning extraction, XMI, Alloy, decisions and capsule replay. */
class JavaSemanticOraclePipelineTest {
    @TempDir Path temporary;

    @Test
    void preservesExpectedSemanticsAcrossEveryPipelineStage() throws Exception {
        List<Scenario> scenarios = List.of(
                new Scenario("exact-override", """
                        class Base { Number value() { return 1; } }
                        class Child extends Base { @Override Number value() { return 2; } }
                        """, 1, DecisionStatus.CONFORMANT, DecisionStatus.CONFORMANT, true),
                new Scenario("covariant-return", """
                        class Base { Number value() { return 1; } }
                        class Child extends Base { @Override Integer value() { return 2; } }
                        """, 1, DecisionStatus.CONFORMANT, DecisionStatus.NON_CONFORMANT, true),
                new Scenario("generic-substitution", """
                        class Base<T> { T value() { return null; } }
                        class Child extends Base<String> { @Override String value() { return "value"; } }
                        """, 1, DecisionStatus.CONFORMANT, DecisionStatus.NON_CONFORMANT, true),
                new Scenario("static-hiding", """
                        class Base { static Number value() { return 1; } }
                        class Child extends Base { static Number value() { return 2; } }
                        """, 0, DecisionStatus.NON_CONFORMANT, DecisionStatus.CONFORMANT, true),
                new Scenario("missing-return-dependency", """
                        class Uses { third.party.External value() { return null; } }
                        """, 0, DecisionStatus.NOT_EVALUATED, DecisionStatus.NOT_EVALUATED, false));

        ConformancePipeline pipeline = new ConformancePipeline(new JavaImplementationSourceObserver(List.of()));
        for (Scenario scenario : scenarios) {
            Path source = Files.createDirectories(temporary.resolve("sources").resolve(scenario.name()));
            Files.writeString(source.resolve("Corpus.java"), scenario.source());
            PipelineResult result = pipeline.analyze(
                    source, temporary.resolve("results").resolve(scenario.name()), Set.of());

            assertEquals(scenario.overrideEdges(), result.observation().members().stream()
                    .mapToLong(member -> member.overriddenMemberKeys().size()).sum(), scenario.name());
            assertEquals(scenario.completeOverrideEvidence(), result.observation().completeEvidence()
                    .contains(EvidenceKind.OVERRIDE_RELATIONS), scenario.name());
            assertEquals(scenario.completeOverrideEvidence(), result.observation().completeEvidence()
                    .contains(EvidenceKind.METHOD_RETURN_TYPES), scenario.name());
            assertEquals(scenario.bridge(), result.invariant("override-relation-consistency").status(), scenario.name());
            assertEquals(scenario.discipline(), result.invariant("override-discipline").status(), scenario.name());

            var replayed = new ObservationXmiReader().read(result.observationPath());
            assertEquals(result.observation(), replayed, scenario.name());
            assertEquals(new ExactAlloyEncoder().encode(replayed), Files.readString(result.alloyPath()), scenario.name());
            assertTrue(new CapsuleVerifier().verify(result.capsulePath()).valid(), scenario.name());
            assertFalse(Files.readString(result.capsulePath()).isBlank(), scenario.name());
        }
    }

    @Test
    void preservesAnonymousClassPackageAcrossObservationAndFormalViews() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("anonymous-package/example"));
        Files.writeString(source.resolve("Corpus.java"), """
                package example;
                abstract class Base {
                    void inheritedPackageMethod() {}
                    abstract void implementedHere();
                }
                class Factory {
                    Base create() {
                        return new Base() {
                            @Override void implementedHere() {}
                        };
                    }
                }
                """);

        PipelineResult result = new ConformancePipeline(
                new JavaImplementationSourceObserver(List.of())).analyze(
                temporary.resolve("anonymous-package"),
                temporary.resolve("anonymous-package-result"), Set.of());

        assertTrue(result.observation().diagnostics().isEmpty(),
                result.observation().diagnostics().toString());
        assertTrue(result.observation().classifiers().stream()
                .allMatch(classifier -> classifier.packageName().equals("example")),
                result.observation().classifiers().toString());
        assertEquals(DecisionStatus.CONFORMANT,
                result.invariant("inherited-view-consistency").status());
        assertEquals(DecisionStatus.CONFORMANT,
                result.invariant("override-relation-consistency").status());
    }

    private record Scenario(
            String name,
            String source,
            long overrideEdges,
            DecisionStatus bridge,
            DecisionStatus discipline,
            boolean completeOverrideEvidence) { }
}
