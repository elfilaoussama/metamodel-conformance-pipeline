package metamodel.conformance.pipeline.alloy;

import metamodel.conformance.pipeline.TestObservations;
import metamodel.conformance.pipeline.decision.Decision;
import metamodel.conformance.pipeline.decision.DecisionStatus;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.SourceUnit;
import metamodel.conformance.pipeline.decision.WitnessTuple;
import metamodel.conformance.pipeline.util.Hashing;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AlloyInvariantEvaluatorTest {
    private final ExactAlloyEncoder encoder = new ExactAlloyEncoder();
    private final AlloyInvariantEvaluator runner = new AlloyInvariantEvaluator();

    @Test
    void evaluatesAllCataloguedInvariantsIndependently() {
        Observation observation = TestObservations.membersConformant();
        List<Decision> decisions = runner.evaluateAll(observation, encoder.encode(observation));

        assertEquals(DecisionStatus.CONFORMANT, decision(decisions, "exclusive-declaration-ownership").status());
        assertEquals(DecisionStatus.CONFORMANT, decision(decisions, "acyclic-generalization").status());
        assertEquals(DecisionStatus.NOT_EVALUATED, decision(decisions, "inherited-view-consistency").status());
        assertEquals(DecisionStatus.NOT_EVALUATED, decision(decisions, "local-inherited-separation").status());
        assertEquals(DecisionStatus.NOT_EVALUATED, decision(decisions, "implementation-binding-consistency").status());
        assertEquals(DecisionStatus.NOT_EVALUATED, decision(decisions, "abstraction-implementation-consistency").status());
        assertEquals(DecisionStatus.NOT_EVALUATED, decision(decisions, "static-abstract-method-separation").status());
        assertEquals(DecisionStatus.CONFORMANT, decision(decisions, "local-namespace-uniqueness").status());
        assertEquals(DecisionStatus.NOT_EVALUATED,
                decision(decisions, "inherited-namespace-uniqueness").status());
    }

    @Test
    void returnsAlloyMemberWitnessForExclusiveOwnershipViolation() {
        Observation observation = TestObservations.unownedMember();
        Decision decision = decision(runner.evaluateAll(observation, encoder.encode(observation)), "exclusive-declaration-ownership");

        assertEquals(DecisionStatus.NON_CONFORMANT, decision.status());
        assertEquals(List.of(observation.members().get(0).technicalKey()), decision.witnessTechnicalKeys());
    }

    @Test
    void orderedParametersDistinguishOverloadsButDetectExactDuplicateKeys() {
        Observation duplicate = TestObservations.duplicateLocalMethods();
        Observation overload = TestObservations.overloadedMethods();

        Decision duplicateDecision = decision(
                runner.evaluateAll(duplicate, encoder.encode(duplicate)), "local-namespace-uniqueness");
        Decision overloadDecision = decision(
                runner.evaluateAll(overload, encoder.encode(overload)), "local-namespace-uniqueness");

        assertEquals(DecisionStatus.NON_CONFORMANT, duplicateDecision.status());
        assertEquals(2, duplicateDecision.witnessTechnicalKeys().size());
        assertEquals(DecisionStatus.CONFORMANT, overloadDecision.status());
    }

    @Test
    void detectsDuplicateLocalAttributeNames() {
        Observation observation = TestObservations.duplicateLocalAttributes();
        Decision decision = decision(
                runner.evaluateAll(observation, encoder.encode(observation)), "local-namespace-uniqueness");

        assertEquals(DecisionStatus.NON_CONFORMANT, decision.status());
        assertEquals(2, decision.witnessTechnicalKeys().size());
    }

    @Test
    void ownershipRelationMutationFlipsOnlyExclusiveDeclarationOwnership() {
        Observation base = TestObservations.membersConformant();
        ClassifierObservation secondOwner = new ClassifierObservation(
                TestObservations.B,
                "example.B",
                ClassifierKind.CLASS,
                "example/B.java",
                3,
                4,
                List.of(),
                List.of(base.members().get(0).technicalKey()));
        Observation mutated = new Observation(
                base.schemaVersion(), base.adapterId(), base.adapterVersion(), base.externalParents(),
                base.completeEvidence(), List.of(
                        base.units().get(0),
                        new SourceUnit(
                                Language.JAVA, "example/B.java",
                                Hashing.sha256("source\0example/B.java"))),
                List.of(base.classifiers().get(0), secondOwner), base.members(), base.unresolvedParents());

        List<Decision> before = runner.evaluateAll(base, encoder.encode(base));
        List<Decision> after = runner.evaluateAll(mutated, encoder.encode(mutated));

        assertEquals(DecisionStatus.CONFORMANT, decision(before, "exclusive-declaration-ownership").status());
        assertEquals(DecisionStatus.NON_CONFORMANT, decision(after, "exclusive-declaration-ownership").status());
        assertEquals(decision(before, "acyclic-generalization").status(), decision(after, "acyclic-generalization").status());
        assertEquals(decision(before, "local-namespace-uniqueness").status(), decision(after, "local-namespace-uniqueness").status());
    }

    @Test
    void missingConditionSpecificEvidenceDoesNotBlockOtherInvariants() {
        Observation observation = TestObservations.incompleteLocalSignatures();
        List<Decision> decisions = runner.evaluateAll(observation, encoder.encode(observation));

        assertEquals(DecisionStatus.CONFORMANT, decision(decisions, "exclusive-declaration-ownership").status());
        assertEquals(DecisionStatus.CONFORMANT, decision(decisions, "acyclic-generalization").status());
        assertEquals(DecisionStatus.NOT_EVALUATED, decision(decisions, "local-namespace-uniqueness").status());
        assertFalse(decision(decisions, "local-namespace-uniqueness").message().isBlank());
    }

    @Test
    void comparesFrontendInheritedViewWithAlloyDerivation() {
        Observation conformant = TestObservations.inheritedViewConformant();
        Observation missing = TestObservations.missingInheritedMember();

        Decision conformantDecision = decision(
                runner.evaluateAll(conformant, encoder.encode(conformant)), "inherited-view-consistency");
        Decision missingDecision = decision(
                runner.evaluateAll(missing, encoder.encode(missing)), "inherited-view-consistency");

        assertEquals(DecisionStatus.CONFORMANT, conformantDecision.status());
        assertEquals(DecisionStatus.NON_CONFORMANT, missingDecision.status());
        assertEquals(List.of(new WitnessTuple(List.of(
                TestObservations.B, missing.members().get(0).technicalKey()))), missingDecision.witnesses());
    }

    @Test
    void detectsAtomLevelLocalInheritedOverlap() {
        Observation observation = TestObservations.localInheritedOverlap();
        Decision decision = decision(
                runner.evaluateAll(observation, encoder.encode(observation)), "local-inherited-separation");

        assertEquals(DecisionStatus.NON_CONFORMANT, decision.status());
        assertEquals(List.of(new WitnessTuple(List.of(
                TestObservations.A, observation.members().get(0).technicalKey()))), decision.witnesses());
    }

    @Test
    void detectsDuplicateInheritedMemberKeys() {
        Observation observation = TestObservations.duplicateInheritedMethods();
        Decision decision = decision(
                runner.evaluateAll(observation, encoder.encode(observation)),
                "inherited-namespace-uniqueness");

        assertEquals(DecisionStatus.NON_CONFORMANT, decision.status());
        assertEquals(2, decision.witnesses().size());
        assertEquals(3, decision.witnessTechnicalKeys().size());
        assertEquals(1, decision.witnesses().stream()
                .map(witness -> witness.technicalKeys().get(0)).distinct().count());
    }

    @Test
    void inconsistentExactObservationCannotProduceConformance() {
        Observation observation = TestObservations.membersConformant();
        String inconsistentModel = encoder.encode(observation)
                .replaceFirst("(?m)^  kind = .*$", "  no kind");

        List<Decision> decisions = runner.evaluateAll(observation, inconsistentModel);

        assertEquals(11, decisions.size());
        decisions.forEach(decision -> assertEquals(DecisionStatus.NOT_EVALUATED, decision.status()));
    }

    @Test
    void skipsAlloyWhenNoInvariantHasCompleteEvidence() {
        Observation base = TestObservations.membersConformant();
        Observation incomplete = new Observation(
                base.schemaVersion(), base.adapterId(), base.adapterVersion(), base.externalParents(),
                Set.of(), base.units(), base.classifiers(), base.members(), base.unresolvedParents());

        List<Decision> decisions = runner.evaluateAll(incomplete, "this is deliberately not Alloy");

        assertEquals(11, decisions.size());
        decisions.forEach(decision -> {
            assertEquals(DecisionStatus.NOT_EVALUATED, decision.status());
            assertFalse(decision.message().contains("parsing failed"));
        });
    }

    @Test
    void aggregatesWitnessesAcrossExactHierarchyComponents() {
        ClassifierObservation first = new ClassifierObservation(
                TestObservations.A, "example.A", ClassifierKind.CLASS,
                "example/A.java", 1, 1, List.of(TestObservations.B));
        ClassifierObservation second = new ClassifierObservation(
                TestObservations.B, "example.B", ClassifierKind.CLASS,
                "example/A.java", 2, 2, List.of(TestObservations.A));
        String isolatedId = TestObservations.id("isolated");
        ClassifierObservation isolated = new ClassifierObservation(
                isolatedId, "example.Isolated", ClassifierKind.CLASS,
                "example/A.java", 3, 3, List.of());
        Observation observation = new Observation(
                "7", "test-adapter", "1.0.0", List.of(), Set.of(EvidenceKind.HIERARCHY),
                List.of(new SourceUnit(
                        Language.JAVA, "example/A.java", Hashing.sha256("source"))),
                List.of(first, second, isolated), List.of(), List.of());

        Decision decision = decision(
                runner.evaluateAll(observation, encoder.encode(observation)),
                "acyclic-generalization");

        assertEquals(DecisionStatus.NON_CONFORMANT, decision.status());
        assertEquals(List.of(TestObservations.A, TestObservations.B).stream().sorted().toList(),
                decision.witnessTechnicalKeys());
    }

    private static Decision decision(List<Decision> decisions, String id) {
        return decisions.stream().filter(item -> id.equals(item.invariantId())).findFirst().orElseThrow();
    }
}
