package metamodel.conformance.pipeline.capsule;

import metamodel.conformance.pipeline.decision.Decision;
import metamodel.conformance.pipeline.alloy.AlloyExecutionConfig;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;

import java.util.List;

public record VerificationCapsule(
        String formatVersion,
        String toolId,
        String toolVersion,
        String schemaVersion,
        String adapterId,
        String adapterVersion,
        String sourceSetSha256,
        String observationPath,
        String observationSha256,
        String alloyPath,
        String alloySha256,
        AlloyExecutionConfig alloyExecution,
        List<ObservationDiagnostic> observationDiagnostics,
        List<Decision> decisions) {

    public VerificationCapsule {
        observationDiagnostics = observationDiagnostics == null
                ? List.of() : List.copyOf(observationDiagnostics);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }
}
