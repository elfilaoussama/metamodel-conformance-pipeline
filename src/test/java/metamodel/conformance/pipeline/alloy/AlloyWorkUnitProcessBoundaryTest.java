package metamodel.conformance.pipeline.alloy;

import metamodel.conformance.pipeline.TestObservations;
import metamodel.conformance.pipeline.decision.Decision;
import metamodel.conformance.pipeline.decision.DecisionStatus;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.invariant.InvariantRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlloyWorkUnitProcessBoundaryTest {
    @Test
    void workerBoundaryReturnsTheSameDecisionAsTheSerialEvaluatorForOneUnit() {
        var observation = TestObservations.membersConformant();
        var encoder = new ExactAlloyEncoder();
        Decision decision = new AlloyInvariantEvaluator().evaluateWorkUnit(
                observation, encoder.encode(observation), "exclusive-declaration-ownership", 0);

        assertEquals(DecisionStatus.CONFORMANT, decision.status());
        assertEquals("exclusive-declaration-ownership", decision.invariantId());
    }

    @Test
    void processWorkersPreserveTheSerialDecisions() {
        var base = TestObservations.membersConformant();
        var observation = new metamodel.conformance.pipeline.model.Observation(
                "13", base.adapterId(), base.adapterVersion(), base.externalParents(),
                base.completeEvidence(), base.units(), base.classifiers(), base.members(),
                base.methodBodies(), base.implementationBindings(), base.unresolvedParents(),
                base.diagnostics(), base.platformEvidence());
        var alloy = new ExactAlloyEncoder().encode(observation);
        var evaluator = new AlloyInvariantEvaluator();
        List<?> serial = evaluator.evaluateAll(observation, alloy);
        System.setProperty("metamodel.conformance.alloy.processes", "2");
        try {
            assertEquals(serial, evaluator.evaluateAll(observation, alloy));
        } finally {
            System.clearProperty("metamodel.conformance.alloy.processes");
        }
    }

    @Test
    void processWorkersEvaluateMultipleUnitsPerChild() {
        var base = TestObservations.membersConformant();
        List<ClassifierObservation> classifiers = new ArrayList<>();
        for (int index = 0; index < 65; index++) {
            classifiers.add(new ClassifierObservation(
                    TestObservations.id("batch-class-" + index), "example.Batch" + index,
                    ClassifierKind.CLASS, base.units().get(0).path(), index + 1, index + 1,
                    List.of(), List.of()));
        }
        Observation observation = new Observation(
                "13", base.adapterId(), base.adapterVersion(), List.of(), Set.of(EvidenceKind.HIERARCHY),
                base.units(), classifiers, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        var definition = InvariantRegistry.load().require("acyclic-generalization");
        assertTrue(new AlloyWorkUnitPlanner().plan(observation, definition).size() > 2);
        var alloy = new ExactAlloyEncoder().encode(observation);
        var evaluator = new AlloyInvariantEvaluator();
        List<Decision> serial = evaluator.evaluateAll(observation, alloy);
        System.setProperty("metamodel.conformance.alloy.processes", "2");
        try {
            assertEquals(serial, evaluator.evaluateAll(observation, alloy));
        } finally {
            System.clearProperty("metamodel.conformance.alloy.processes");
        }
    }
}
