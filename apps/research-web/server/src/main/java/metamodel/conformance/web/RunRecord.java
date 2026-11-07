package metamodel.conformance.web;

/** Job state is deliberately independent of the engine's per-rule decisions. */
public record RunRecord(String id, String label, String operation, String state,
                        String created, String updated, String engineSha256,
                        String message, Integer exitCode, String replayOf,
                        String idempotencyKey) {
    public RunRecord transition(String next, String detail, Integer exit) {
        return new RunRecord(id, label, operation, next, created, java.time.Instant.now().toString(),
                engineSha256, detail, exit, replayOf, idempotencyKey);
    }
}
