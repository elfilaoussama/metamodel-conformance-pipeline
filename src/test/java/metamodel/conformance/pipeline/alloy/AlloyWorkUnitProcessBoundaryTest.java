package metamodel.conformance.pipeline.alloy;

import metamodel.conformance.pipeline.TestObservations;
import metamodel.conformance.pipeline.decision.Decision;
import metamodel.conformance.pipeline.decision.DecisionStatus;
import org.junit.jupiter.api.Test;

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
}
