package metamodel.conformance.pipeline.model;

public record ObservationDiagnostic(
        DiagnosticKind kind,
        String sourcePath,
        int line,
        String message) {

    public ObservationDiagnostic {
        if (kind == null) {
            throw new IllegalArgumentException("diagnostic kind is required");
        }
        sourcePath = CanonicalObservationValue.relativePath(sourcePath, "sourcePath");
        if (line < 0) {
            throw new IllegalArgumentException("diagnostic line must not be negative");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("diagnostic message must not be blank");
        }
    }
}
