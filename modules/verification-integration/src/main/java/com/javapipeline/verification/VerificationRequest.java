package com.javapipeline.verification;

import java.nio.file.Path;
import java.util.Objects;

public record VerificationRequest(
        Path verifierHome,
        Path metamodel,
        Path extractionJson,
        Path outputDirectory
) {
    public VerificationRequest {
        verifierHome = normalize(verifierHome, "verifierHome");
        metamodel = normalize(metamodel, "metamodel");
        extractionJson = normalize(extractionJson, "extractionJson");
        outputDirectory = normalize(outputDirectory, "outputDirectory");
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }
}
