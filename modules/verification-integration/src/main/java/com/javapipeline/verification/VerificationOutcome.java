package com.javapipeline.verification;

import java.nio.file.Path;
import java.util.List;

public record VerificationOutcome(
        Status status,
        List<Violation> violations,
        Path jsonReport,
        Path csvReport,
        String processOutput
) {
    public VerificationOutcome {
        violations = violations == null ? List.of() : List.copyOf(violations);
        processOutput = processOutput == null ? "" : processOutput;
    }

    public enum Status { SAT, UNSAT, ERROR }

    public record Violation(Integer line, String invariantName, String description, String formula) { }
}
