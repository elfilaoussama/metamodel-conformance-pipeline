package com.javapipeline.core;

public record ExtractionOptions(int javaComplianceLevel, boolean includeTests) {
    public static final ExtractionOptions DEFAULT = new ExtractionOptions(17, false);

    public ExtractionOptions {
        if (javaComplianceLevel < 8 || javaComplianceLevel > 23) {
            throw new IllegalArgumentException("Unsupported Java compliance level: " + javaComplianceLevel);
        }
    }
}
