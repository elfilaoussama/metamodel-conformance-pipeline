package metamodel.conformance.pipeline.model;

public record MethodBodyObservation(
        String technicalKey,
        String sourcePath,
        int startLine,
        int endLine) {

    public MethodBodyObservation {
        technicalKey = CanonicalObservationValue.technicalId(
                technicalKey, "body_", "technicalKey");
        sourcePath = CanonicalObservationValue.relativePath(sourcePath, "sourcePath");
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("invalid method-body source line range");
        }
    }
}
