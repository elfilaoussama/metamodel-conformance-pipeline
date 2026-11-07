package metamodel.conformance.web;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public final class RunService {
    public record Submission(String label, String source, String result, String idempotencyKey) { }
    private final Workspace workspace;
    private final Catalog catalog;
    private final EngineGateway engine;
    private final GitRepositorySource repositories;
    private final GitHubRepositories github;
    private final JobDispatcher dispatcher;
    private final JsonMapper json = JsonMapper.builder().build();
    private boolean recoveryBlocked;
    public RunService(Workspace workspace, Catalog catalog, EngineGateway engine, GitRepositorySource repositories, GitHubRepositories github) throws Exception {
        this.workspace = workspace; this.catalog = catalog; this.engine = engine; this.repositories = repositories; this.github = github;
        recoveryBlocked = Files.exists(workspace.root.resolve("recovery-required"));
        for (RunRecord row : catalog.active()) {
            if (Set.of("QUEUED", "RUNNING", "CANCELLING").contains(row.state())) {
                recoveryBlocked = true;
                Files.writeString(workspace.root.resolve("recovery-required"), "Interrupted work requires local ownership inspection. No automatic PID killing or reuse.\n");
                catalog.save(row.transition("INTERRUPTED", "server stopped before durable completion; manual ownership reconciliation required", null));
            }
        }
        dispatcher = new JobDispatcher(catalog, workspace.computeWorkers, this::execute);
    }
    public boolean recoveryBlocked() { return recoveryBlocked || dispatcher.blocked(); }
    private void requireReady() {
        if (recoveryBlocked) throw new IllegalStateException("Worker ownership needs reconciliation before new work can start.");
        dispatcher.requireAvailable();
    }
    public synchronized List<RunRecord> submitInputs(List<Submission> local, List<RepositoryRequest> remote) throws Exception {
        if (local.isEmpty() && remote.isEmpty()) throw new IllegalArgumentException("Select at least one project.");
        requireReady();
        Set<String> keys = new HashSet<>();
        for (String key : java.util.stream.Stream.concat(local.stream().map(Submission::idempotencyKey), remote.stream().map(RepositoryRequest::idempotencyKey)).toList()) {
            requireKey(key); if (!keys.add(key)) throw new IllegalArgumentException("Duplicate project request.");
        }
        for (Submission request : local) if (priorSubmission(request, false).isEmpty()) preflight(request);
        List<RunRecord> result = new ArrayList<>();
        if (!remote.isEmpty()) result.addAll(ingestBatch(remote));
        for (Submission request : local) result.add(submit(request, false));
        return List.copyOf(result);
    }
    public synchronized List<RunRecord> ingestBatch(List<RepositoryRequest> requests) throws Exception {
        if (requests.isEmpty()) throw new IllegalArgumentException("Submit at least one repository.");
        requireReady();
        Set<String> keys = new HashSet<>();
        for (RepositoryRequest request : requests) {
            requireKey(request.idempotencyKey());
            if (!keys.add(request.idempotencyKey())) throw new IllegalArgumentException("duplicate request key in batch");
            if (request.discoveryId() != null) github.selectedDiscovery(request.discoveryId(), request.url());
            var old = catalog.byRequest(request.idempotencyKey());
            if (old.isPresent() && (!old.get().operation().equals(request.analyze() ? "GITHUB_ANALYZE" : "GITHUB_IMPORT")
                    || !json.readValue(Files.readString(workspace.run(old.get().id()).resolve("request.json")), RepositoryRequest.class).equals(request)))
                throw new IllegalStateException("idempotency key already belongs to a different request");
        }
        if (requests.stream().anyMatch(RepositoryRequest::analyze)) engine.checkEngine();
        List<RunRecord> result = new ArrayList<>();
        for (RepositoryRequest request : requests) {
            var old = catalog.byRequest(request.idempotencyKey());
            if (old.isPresent()) { result.add(old.get()); continue; }
            RunRecord run = fresh(request.label(), request.analyze() ? "GITHUB_ANALYZE" : "GITHUB_IMPORT", null, request.idempotencyKey());
            Path directory = Files.createDirectory(workspace.run(run.id()));
            Files.writeString(directory.resolve("request.json"), json.writeValueAsString(request), StandardOpenOption.CREATE_NEW);
            if (request.discoveryId() != null) Files.copy(github.selectedDiscovery(request.discoveryId(), request.url()), directory.resolve("discovery.json"));
            catalog.save(run); dispatcher.dispatch(); result.add(run);
        }
        return List.copyOf(result);
    }
    public Map<String,Object> preflight(Submission request) throws Exception {
        Path source = workspace.input(Objects.requireNonNull(request.source(), "source directory is required"));
        try (var files = Files.walk(source)) {
            if (files.noneMatch(p -> p.toString().endsWith(".java"))) throw new IllegalArgumentException("no Java source files in selected source-only input");
        }
        engine.checkEngine();
        boolean blocked = recoveryBlocked();
        return Map.of("ready", !blocked, "mode", "JAVA_DEFAULT_SOURCE_ONLY", "engineSha256", workspace.engineSha256,
                "analysisBudgetSeconds", 120, "message", blocked ? "worker ownership or dispatch needs reconciliation" : "uses the default Java observer; does not establish author-build ownership");
    }
    public synchronized RunRecord submit(Submission request, boolean importing) throws Exception {
        Optional<RunRecord> prior = priorSubmission(request, importing);
        if (prior.isPresent()) return prior.get();
        requireReady();
        if (!importing) preflight(request);
        Path source = !importing ? workspace.input(request.source()) : null;
        Path result = importing ? workspace.input(Objects.requireNonNull(request.result(), "result directory is required")) : null;
        RunRecord run = fresh(request.label(), importing ? "IMPORT" : "ANALYZE", null, request.idempotencyKey());
        Path directory = workspace.run(run.id()); Files.createDirectory(directory);
        try {
            if (source != null) workspace.snapshot(source, directory.resolve("source"));
            if (result != null) {
                engine.importResult(result, directory);
                if (request.source() != null && !request.source().isBlank()) workspace.snapshot(workspace.input(request.source()), directory.resolve("source"));
            }
            Files.writeString(directory.resolve("request.json"), json.writeValueAsString(request), StandardOpenOption.CREATE_NEW);
            catalog.save(run);
            dispatcher.dispatch();
        } catch (Exception failure) {
            catalog.save(run.transition("FAILED", safeMessage(failure), null)); throw failure;
        }
        return run;
    }
    public synchronized RunRecord replay(String targetId, String key) throws Exception {
        requireKey(key); var prior = catalog.byRequest(key);
        if (prior.isPresent()) {
            if (!prior.get().operation().equals("REPLAY") || !targetId.equals(prior.get().replayOf()))
                throw new IllegalStateException("idempotency key already belongs to a different request");
            return prior.get();
        }
        requireReady();
        RunRecord target = catalog.get(targetId);
        if (!target.state().equals("COMPLETED") || target.operation().equals("REPLAY") || target.operation().equals("GITHUB_IMPORT"))
            throw new IllegalArgumentException("replay requires a completed artifact-bearing run");
        engine.view(workspace.run(targetId));
        RunRecord run = fresh("Replay: " + target.label(), "REPLAY", targetId, key);
        Files.createDirectory(workspace.run(run.id())); catalog.save(run); dispatcher.dispatch(); return run;
    }
    public synchronized RunRecord analyzeRetained(String targetId, String key) throws Exception {
        requireKey(key); var prior = catalog.byRequest(key); Map<String,String> specification = Map.of("sourceRepositoryRun", targetId, "idempotencyKey", key);
        if (prior.isPresent()) {
            RunRecord old = prior.get();
            if (!old.operation().equals("ANALYZE") || !json.readTree(Files.readString(workspace.run(old.id()).resolve("request.json"))).equals(json.valueToTree(specification)))
                throw new IllegalStateException("idempotency key already belongs to a different request");
            return old;
        }
        requireReady();
        RunRecord target = catalog.get(targetId);
        if (!target.state().equals("COMPLETED") || !target.operation().equals("GITHUB_IMPORT"))
            throw new IllegalArgumentException("select a completed repository import");
        engine.checkEngine(); Path retained = workspace.run(targetId);
        repositories.verifySnapshot(retained.resolve("source"), retained.resolve("acquisition.json"));
        try (var files = Files.walk(retained.resolve("source"))) {
            if (files.noneMatch(file -> file.toString().endsWith(".java"))) throw new IllegalArgumentException("retained source contains no Java files");
        }
        String label = "Analyze: " + target.label();
        RunRecord run = fresh(label.substring(0, Math.min(120, label.length())), "ANALYZE", null, key);
        Path directory = Files.createDirectory(workspace.run(run.id()));
        try {
            workspace.snapshot(retained.resolve("source"), directory.resolve("source"));
            Files.copy(retained.resolve("acquisition.json"), directory.resolve("acquisition.json"));
            if (Files.exists(retained.resolve("discovery.json"))) Files.copy(retained.resolve("discovery.json"), directory.resolve("discovery.json"));
            repositories.verifySnapshot(directory.resolve("source"), directory.resolve("acquisition.json"));
            Files.writeString(directory.resolve("request.json"), json.writeValueAsString(specification), StandardOpenOption.CREATE_NEW);
            catalog.save(run); dispatcher.dispatch(); return run;
        } catch (Exception failure) { catalog.save(run.transition("FAILED", safeMessage(failure), null)); throw failure; }
    }
    private RunRecord fresh(String label, String operation, String replayOf, String key) {
        if (label == null || label.isBlank() || label.length() > 120) throw new IllegalArgumentException("label must contain 1–120 characters");
        String now = Instant.now().toString();
        return new RunRecord(UUID.randomUUID().toString(), label, operation, "QUEUED", now, now,
                workspace.engineSha256, "waiting for an available compute slot", null, replayOf, key);
    }
    private Optional<RunRecord> priorSubmission(Submission request, boolean importing) throws Exception {
        requireKey(request.idempotencyKey()); var prior = catalog.byRequest(request.idempotencyKey());
        if (prior.isPresent()) {
            RunRecord old = prior.get(); Path retained = workspace.run(old.id()).resolve("request.json");
            if (!old.operation().equals(importing ? "IMPORT" : "ANALYZE") || !Files.isRegularFile(retained)
                    || !json.readTree(Files.readString(retained)).equals(json.valueToTree(request)))
                throw new IllegalStateException("idempotency key already belongs to a different request");
        }
        return prior;
    }
    private synchronized void progress(RunRecord run, String message, AtomicBoolean cancel) throws Exception {
        if (!cancel.get()) catalog.save(run.transition("RUNNING", message, null));
    }
    private synchronized void terminal(RunRecord run, String state, String message, Integer exit) throws Exception {
        catalog.save(run.transition(state, message, exit));
    }
    private void execute(RunRecord original, AtomicBoolean cancel) {
        long started = System.nanoTime();
        boolean inspected = false;
        try {
            if (cancel.get()) { terminal(original, "CANCELLED", "cancelled before launch", null); return; }
            progress(original, original.operation().equals("IMPORT") ? "checking typed artifacts; no formal solve" : "owned worker running", cancel);
            Path path = workspace.run(original.id());
            if (original.operation().startsWith("GITHUB_")) {
                progress(original, "acquiring repository and resolving the requested revision to a commit", cancel);
                RepositoryRequest request = json.readValue(Files.readString(path.resolve("request.json")), RepositoryRequest.class);
                repositories.acquire(request, path, cancel);
                if (!request.analyze()) {
                    complete(original, "pinned Git source retained; no extraction, build or formal verification requested", 0, cancel); return;
                }
                try (var files = Files.walk(path.resolve("source"))) {
                    if (files.noneMatch(file -> file.toString().endsWith(".java")))
                        throw new IllegalArgumentException("repository retained, but selected source contains no Java files; no formal analysis performed");
                }
                progress(original, "pinned source retained; running default Java source-only analysis", cancel);
            }
            OwnedProcesses.Exit exit = null;
            if (original.operation().equals("ANALYZE") || original.operation().equals("GITHUB_ANALYZE")) exit = engine.analyze(path, cancel);
            else if (original.operation().equals("REPLAY")) exit = engine.replay(path, workspace.run(original.replayOf()), cancel);
            if (exit != null && (exit.timedOut() || exit.cancelled())) {
                terminal(original, exit.cancelled() ? "CANCELLED" : "TIMED_OUT", "owned process ended; no decisions manufactured", exit.code()); return;
            }
            if (original.operation().equals("REPLAY")) {
                if (exit.code() == 0) complete(original, "formal replay VALID; source fidelity is a separate question", exit.code(), cancel);
                else terminal(original, "FAILED", "formal replay INVALID or failed; see worker log", exit.code());
            } else {
                if (exit != null && !Set.of(0, 2, 3).contains(exit.code())) throw new IllegalArgumentException("analysis worker failed; see log");
                engine.inspect(path, cancel);
                inspected = true;
                JsonNode capsule = engine.view(path).path("capsule");
                int expected = 0;
                for (JsonNode decision : capsule.path("decisions")) {
                    if (decision.path("status").asText().equals("NON_CONFORMANT")) { expected = 2; break; }
                    if (decision.path("status").asText().equals("NOT_EVALUATED")) expected = 3;
                }
                if (exit != null && exit.code() != expected) throw new IllegalArgumentException("process exit disagrees with capsule decisions");
                complete(original, "typed artifacts checked; formal replay not requested", exit == null ? null : exit.code(), cancel);
            }
        } catch (Exception failure) {
            String state = failure instanceof CancellationException ? "CANCELLED" : failure instanceof TimeoutException ? "TIMED_OUT" : "FAILED";
            try { terminal(original, state, safeMessage(failure), null); }
            catch (Exception catalogFailure) { System.err.println("Cannot persist run failure: " + original.id()); }
        } finally {
            try { Files.writeString(workspace.run(original.id()).resolve("timing.json"), json.writeValueAsString(Map.of("workerElapsedSeconds", (System.nanoTime() - started) / 1e9, "includesInspection", inspected, "configuredWorkerSlots", workspace.computeWorkers)), StandardOpenOption.CREATE_NEW); }
            catch (Exception failure) { System.err.println("Cannot persist timing: " + original.id()); }
        }
    }
    public synchronized RunRecord cancel(String id) throws Exception { return dispatcher.cancel(id); }
    private synchronized void complete(RunRecord original, String message, Integer exit, AtomicBoolean cancel) throws Exception {
        catalog.save(original.transition(cancel.get() ? "CANCELLED" : "COMPLETED", cancel.get() ? "cancellation completed after owned worker cleanup" : message, exit));
    }
    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9-]{8,100}")) throw new IllegalArgumentException("idempotency key required");
    }
    private static String safeMessage(Exception failure) {
        String message = failure.getMessage(); return message == null ? failure.getClass().getSimpleName() : message.substring(0, Math.min(500, message.length()));
    }
    @PreDestroy public void close() throws Exception {
        if (dispatcher.shutdown()) { workspace.lease.release(); workspace.leaseChannel.close(); }
    }
}
