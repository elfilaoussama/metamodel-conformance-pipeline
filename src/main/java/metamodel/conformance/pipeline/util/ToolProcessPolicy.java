package metamodel.conformance.pipeline.util;

/** Operational deadlines never truncate a complete semantic observation. */
public record ToolProcessPolicy(long timeoutMillis) {
    public static final String PROPERTY = "metamodel.conformance.toolTimeoutMillis";
    public static final long DEFAULT_TIMEOUT_MILLIS = 180_000;
    public ToolProcessPolicy {
        if (timeoutMillis < 1 || timeoutMillis > Long.MAX_VALUE / 1_000_000)
            throw new IllegalArgumentException("tool deadline must be a positive finite millisecond duration");
    }
    public static ToolProcessPolicy configured() {
        String configured = System.getProperty(PROPERTY);
        try {
            return new ToolProcessPolicy(configured == null ? DEFAULT_TIMEOUT_MILLIS : Long.parseLong(configured));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid " + PROPERTY, invalid);
        }
    }
    public String identity() { return "tool-runtime-v1/timeout-ms-" + timeoutMillis + "/cleanup-ms-2000"; }
}
