package metamodel.conformance.pipeline.adapter.java;

import java.util.List;

/** Build-observed javac language/platform semantics. Null values mean not independently observed. */
record JavaCompilerSemantics(
        Integer sourceLevel,
        Integer targetLevel,
        Integer releaseLevel,
        boolean previewEnabled,
        String platformIdentity,
        List<String> compilerArgs) {
    JavaCompilerSemantics {
        validateLevel(sourceLevel, "source");
        validateLevel(targetLevel, "target");
        validateLevel(releaseLevel, "release");
        platformIdentity = platformIdentity == null ? "" : platformIdentity.trim();
        List<String> args = new java.util.ArrayList<>();
        for (String value : compilerArgs == null ? List.<String>of() : compilerArgs) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String trimmed = value.trim();
            if (trimmed.indexOf('\t') >= 0 || trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("compiler argument must not contain control separators");
            }
            args.add(trimmed);
        }
        compilerArgs = List.copyOf(args);
        if (releaseLevel != null && (sourceLevel != null || targetLevel != null)) {
            throw new IllegalArgumentException("release cannot be combined with source/target semantics");
        }
    }

    JavaCompilerSemantics(
            Integer sourceLevel,
            Integer targetLevel,
            Integer releaseLevel,
            boolean previewEnabled,
            String platformIdentity) {
        this(sourceLevel, targetLevel, releaseLevel, previewEnabled, platformIdentity, List.of());
    }

    static JavaCompilerSemantics unknown() {
        return new JavaCompilerSemantics(null, null, null, false, "");
    }

    static JavaCompilerSemantics runtime() {
        int feature = Runtime.version().feature();
        return new JavaCompilerSemantics(null, null, feature, false, "runtime-jdk-" + feature);
    }

    private static void validateLevel(Integer value, String label) {
        if (value != null && value < 1) {
            throw new IllegalArgumentException(label + " level must be positive");
        }
    }
}