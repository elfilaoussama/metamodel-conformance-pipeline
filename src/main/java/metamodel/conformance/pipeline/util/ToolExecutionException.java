package metamodel.conformance.pipeline.util;

import java.io.IOException;

/** A resource/launch failure, never missing semantic evidence. */
public final class ToolExecutionException extends IOException {
    private static final long serialVersionUID = 1L;
    public enum Reason { START_FAILED, DEADLINE, INTERRUPTED, SUPERVISION_FAILED, CLEANUP_FAILED, LIVE_DESCENDANTS }
    private final Reason reason;
    private final String policyIdentity;
    public ToolExecutionException(Reason reason, ToolProcessPolicy policy, String message, Throwable cause) {
        super(message,cause);this.reason=reason;this.policyIdentity=policy.identity();
    }
    public Reason reason() { return reason; }
    public String policyIdentity() { return policyIdentity; }
}
