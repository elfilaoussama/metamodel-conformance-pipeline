package metamodel.conformance.pipeline.alloy;

import metamodel.conformance.pipeline.TestObservations;
import metamodel.conformance.pipeline.decision.Decision;
import metamodel.conformance.pipeline.decision.DecisionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
