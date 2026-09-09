package metamodel.conformance.pipeline.adapter.java;

/** Build-observed javac language/platform semantics. Null values mean not independently observed. */
record JavaCompilerSemantics(
        Integer sourceLevel,
        Integer targetLevel,
        Integer releaseLevel,
        boolean previewEnabled,
        String platformIdentity) {
    JavaCompilerSemantics {
        validateLevel(sourceLevel, "source");
        validateLevel(targetLevel, "target");
        validateLevel(releaseLevel, "release");
        platformIdentity = platformIdentity == null ? "" : platformIdentity.trim();
        if (releaseLevel != null && (sourceLevel != null || targetLevel != null)) {
            throw new IllegalArgumentException("release cannot be combined with source/target semantics");
        }
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
