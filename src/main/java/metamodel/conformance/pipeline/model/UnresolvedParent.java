package metamodel.conformance.pipeline.model;

public record UnresolvedParent(String ownerId, String targetName, String sourcePath, int line) {
    public UnresolvedParent {
        ownerId = CanonicalObservationValue.technicalId(ownerId, "cls_", "ownerId");
        targetName = CanonicalObservationValue.text(targetName, "targetName");
        sourcePath = CanonicalObservationValue.relativePath(sourcePath, "sourcePath");
        if (line < 1) {
            throw new IllegalArgumentException("unresolved parent line must be positive");
        }
    }
}
