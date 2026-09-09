package metamodel.conformance.pipeline.decision;

import java.util.List;

public record Decision(
        DecisionStatus status,
        String invariantId,
        String message,
        List<WitnessTuple> witnesses) {

    public Decision {
        if (status == null || invariantId == null || invariantId.isBlank() || message == null) {
            throw new IllegalArgumentException("decision fields must be complete");
        }
        witnesses = witnesses == null ? List.of() : List.copyOf(witnesses);
    }

    public List<String> witnessTechnicalKeys() {
        return witnesses.stream()
                .flatMap(witness -> witness.technicalKeys().stream())
                .distinct()
                .sorted()
                .toList();
    }
}
