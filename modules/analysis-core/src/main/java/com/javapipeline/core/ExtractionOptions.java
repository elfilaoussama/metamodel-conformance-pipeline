package com.javapipeline.core;

import java.util.Collections;
import java.util.Map;

public record ExtractionOptions(int javaComplianceLevel, boolean includeTests, Language language, Map<String, String> languageOptions) {
    public static final ExtractionOptions DEFAULT = new ExtractionOptions(17, false, Language.JAVA, Map.of());

    public ExtractionOptions {
        if (javaComplianceLevel < 8 || javaComplianceLevel > 23) {
            throw new IllegalArgumentException("Unsupported Java compliance level: " + javaComplianceLevel);
        }
        if (language == null) language = Language.JAVA;
        if (languageOptions == null) languageOptions = Map.of();
    }

    public ExtractionOptions(int javaComplianceLevel, boolean includeTests) {
        this(javaComplianceLevel, includeTests, Language.JAVA, Map.of());
    }

    public static ExtractionOptions forLanguage(Language language) {
        return new ExtractionOptions(17, false, language, Map.of());
    }

    public static ExtractionOptions forLanguage(Language language, Map<String, String> options) {
        return new ExtractionOptions(17, false, language, Collections.unmodifiableMap(options));
    }
}
