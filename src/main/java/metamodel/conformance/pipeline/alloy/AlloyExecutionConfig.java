package metamodel.conformance.pipeline.alloy;

public record AlloyExecutionConfig(
        String alloyVersion,
        String solverId,
        boolean inferPartialInstance,
        int symmetry,
        int skolemDepth,
        int coreMinimization,
        int coreGranularity,
        boolean recordKodkod,
        boolean noOverflow,
        int unrolls,
        int decomposeMode,
        int decomposeThreads) {

    private static final AlloyExecutionConfig FROZEN = new AlloyExecutionConfig(
            "6.2.0", "sat4j", true, 20, 0, 2, 0,
            false, false, -1, 0, 4);

    public AlloyExecutionConfig {
        if (alloyVersion == null || alloyVersion.isBlank()
                || solverId == null || solverId.isBlank()) {
            throw new IllegalArgumentException("Alloy version and solver id are required");
        }
        if (symmetry < 0 || skolemDepth < 0 || coreMinimization < 0
                || coreGranularity < 0 || decomposeMode < 0 || decomposeThreads < 1) {
            throw new IllegalArgumentException("invalid Alloy execution configuration");
        }
    }

    public static AlloyExecutionConfig frozen() {
        return FROZEN;
    }

    public boolean supported() {
        return equals(FROZEN);
    }

    public void requireSupported() {
        if (!supported()) {
            throw new IllegalArgumentException("unsupported Alloy execution configuration");
        }
    }
}
