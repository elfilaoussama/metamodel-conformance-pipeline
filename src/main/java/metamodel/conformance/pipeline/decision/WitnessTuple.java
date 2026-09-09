package metamodel.conformance.pipeline.decision;

import java.util.List;

public record WitnessTuple(List<String> technicalKeys) {
    public WitnessTuple {
        technicalKeys = technicalKeys == null ? List.of() : List.copyOf(technicalKeys);
        if (technicalKeys.isEmpty() || technicalKeys.stream().anyMatch(key -> key == null || key.isBlank())) {
            throw new IllegalArgumentException("witness tuples must contain technical keys");
        }
    }
}
