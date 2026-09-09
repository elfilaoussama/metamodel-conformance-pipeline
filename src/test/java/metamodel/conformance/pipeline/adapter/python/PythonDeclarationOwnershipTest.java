package metamodel.conformance.pipeline.adapter.python;

import metamodel.conformance.pipeline.ConformancePipeline;
import metamodel.conformance.pipeline.adapter.SourceObserverFactory;
import metamodel.conformance.pipeline.capsule.CapsuleVerifier;
import metamodel.conformance.pipeline.decision.DecisionStatus;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Inheritability;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberVisibility;
import metamodel.conformance.pipeline.model.Observation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonDeclarationOwnershipTest {
    @TempDir
    Path temporary;

    @Test
    void observesSourceDeclaredMembersWithoutInventingSignatures() throws Exception {
        Files.writeString(temporary.resolve("models.py"), """
                class Model:
                    plain = 1
                    typed: int

                    async def load(self, value):
                        return value

                    def save(self, value: int) -> str:
                        return str(value)
                """);

        Observation observation = new PythonAstObserver().observe(temporary, Set.of());

        assertEquals("8", observation.schemaVersion());
        assertTrue(observation.adapterVersion().startsWith("0.4.0/python-"));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.DECLARATION_OWNERSHIP));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertFalse(observation.completeEvidence().contains(EvidenceKind.LOCAL_SIGNATURES));
        assertFalse(observation.completeEvidence().contains(EvidenceKind.INHERITABILITY));
        assertFalse(observation.completeEvidence().contains(EvidenceKind.INHERITED_MEMBERS));

        var model = observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("models.Model"))
                .findFirst().orElseThrow();
        assertEquals(4, model.declaredMemberKeys().size());
        assertEquals(4, observation.members().size());
        assertEquals(2, observation.members().stream()
                .filter(item -> item.kind() == MemberKind.METHOD).count());
        assertEquals(2, observation.members().stream()
                .filter(item -> item.kind() == MemberKind.ATTRIBUTE).count());
        assertTrue(observation.members().stream()
                .allMatch(item -> item.visibility() == MemberVisibility.UNKNOWN));
        assertTrue(observation.members().stream()
                .allMatch(item -> item.inheritability() == Inheritability.UNKNOWN));
        assertTrue(observation.members().stream()
                .allMatch(item -> item.parameterTypes().isEmpty()));
    }

    @Test
    void declarationOwnershipRemainsEvaluableWhenHierarchyIsDynamic() throws Exception {
        Files.writeString(temporary.resolve("models.py"), """
                Factory = build_base()

                class Child(Factory):
                    value = 1

                    def work(self, input_value):
                        return input_value
                """);
        Path output = temporary.resolve("artifacts");

        var result = new ConformancePipeline(
                SourceObserverFactory.create(Language.PYTHON, List.of()))
                .analyze(temporary, output, Set.of());

        assertEquals("12", result.observation().schemaVersion());
        assertTrue(result.observation().completeEvidence().contains(EvidenceKind.DECLARATION_OWNERSHIP));
        assertFalse(result.observation().completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertFalse(result.observation().completeEvidence().contains(EvidenceKind.CLASSIFIER_ABSTRACTION));
        assertFalse(result.observation().completeEvidence().contains(EvidenceKind.METHOD_SCOPE));
        assertEquals(DecisionStatus.CONFORMANT,
                result.invariant("exclusive-declaration-ownership").status());
        assertEquals(DecisionStatus.NOT_EVALUATED,
                result.invariant("acyclic-generalization").status());
        assertEquals(DecisionStatus.NOT_EVALUATED,
                result.invariant("implementation-binding-consistency").status());
        assertEquals(DecisionStatus.NOT_EVALUATED,
                result.invariant("abstraction-implementation-consistency").status());
        assertEquals(DecisionStatus.NOT_EVALUATED,
                result.invariant("static-abstract-method-separation").status());
        assertEquals(DecisionStatus.NOT_EVALUATED,
                result.invariant("local-namespace-uniqueness").status());
        assertTrue(new CapsuleVerifier().verify(result.capsulePath()).valid());
    }

    @Test
    void nestedClassDeclarationsRemainOwnedByTheirActualClassifier() throws Exception {
        Files.writeString(temporary.resolve("models.py"), """
                class Outer:
                    outer_value = 1

                    class Nested:
                        inner_value = 2
                """);

        Observation observation = new PythonAstObserver().observe(temporary, Set.of());
        var outer = observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("models.Outer"))
                .findFirst().orElseThrow();
        var nested = observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("models.Outer.Nested"))
                .findFirst().orElseThrow();

        assertTrue(observation.completeEvidence().contains(EvidenceKind.DECLARATION_OWNERSHIP));
        assertEquals(1, outer.declaredMemberKeys().size());
        assertEquals(1, nested.declaredMemberKeys().size());
        assertFalse(outer.declaredMemberKeys().get(0).equals(nested.declaredMemberKeys().get(0)));
    }

    @Test
    void parseFailureStillForbidsEveryCompletenessClaim() throws Exception {
        Files.writeString(temporary.resolve("broken.py"), "class Broken(:\n    value = 1\n");

        Observation observation = new PythonAstObserver().observe(temporary, Set.of());

        assertTrue(observation.completeEvidence().isEmpty());
    }
}
