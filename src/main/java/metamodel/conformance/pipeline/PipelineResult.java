package metamodel.conformance.pipeline;

import metamodel.conformance.pipeline.decision.Decision;
import metamodel.conformance.pipeline.model.Observation;

import java.nio.file.Path;
import java.util.List;

public record PipelineResult(
        Observation observation,
        List<Decision> decisions,
        Path observationPath,
        Path alloyPath,
        Path capsulePath) {

    public PipelineResult {
        decisions = List.copyOf(decisions);
    }

    public Decision invariant(String invariantId) {
        return decisions.stream().filter(item -> invariantId.equals(item.invariantId())).findFirst().orElseThrow();
    }
}
