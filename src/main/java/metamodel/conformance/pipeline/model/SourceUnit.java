package metamodel.conformance.pipeline.model;

import java.util.Objects;

public record SourceUnit(Language language, String path, String sha256) {
    public SourceUnit {
        language = Objects.requireNonNull(language, "language");
        path = CanonicalObservationValue.relativePath(path, "path");
        sha256 = CanonicalObservationValue.sha256(sha256, "sha256");
    }
}
