package metamodel.conformance.web;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public final class EngineGateway {
    private final Workspace workspace;
    private final OwnedProcesses processes;
    private final JsonMapper json = JsonMapper.builder().build();
    public EngineGateway(Workspace workspace, OwnedProcesses processes) throws Exception {
        this.workspace = workspace; this.processes = processes;
        var exit = processes.run(List.of(workspace.javac.toString(), "-encoding", "UTF-8", "--release", "17", "-cp",
                workspace.engine.toString(), "-d", workspace.bridgeClasses.toString(), workspace.bridgeSource.toString()),
                workspace.root.resolve("bridge-compile.log"), 60, new AtomicBoolean());
        if (exit.code() != 0) throw new IllegalStateException("engine inspector compilation failed; see bridge-compile.log");
    }
    public void checkEngine() throws Exception {
        if (!Workspace.sha256(workspace.engine).equals(workspace.engineSha256))
            throw new IllegalStateException("engine JAR changed since startup");
    }
    public OwnedProcesses.Exit analyze(Path run, AtomicBoolean cancel) throws Exception {
        checkEngine();
        return processes.run(List.of(workspace.java.toString(), "-Xmx1g", "-jar", workspace.engine.toString(),
                "analyze", "--source", run.resolve("source").toString(), "--output", run.resolve("result").toString()),
                run.resolve("worker.log"), 120, cancel);
    }
    public OwnedProcesses.Exit replay(Path run, Path target, AtomicBoolean cancel) throws Exception {
        checkEngine();
        return processes.run(List.of(workspace.java.toString(), "-Xmx1g", "-jar", workspace.engine.toString(),
                "verify-capsule", "--capsule", target.resolve("result/verification-capsule.json").toString()),
                run.resolve("worker.log"), 120, cancel);
    }
    public void inspect(Path run, AtomicBoolean cancel) throws Exception {
        checkEngine();
        Path staged = run.resolve("inspection.pending.json");
        Files.deleteIfExists(staged);
        var exit = processes.run(List.of(workspace.java.toString(), "-Xmx1g", "-cp",
                workspace.bridgeClasses + File.pathSeparator + workspace.engine, "ArtifactInspector",
                run.resolve("result").toString(), staged.toString()), run.resolve("inspection.log"), 45, cancel);
        if (exit.cancelled()) throw new java.util.concurrent.CancellationException("cancelled during artifact inspection");
        if (exit.timedOut()) throw new java.util.concurrent.TimeoutException("artifact inspection exceeded its budget");
        if (exit.code() != 0)
            throw new IllegalArgumentException("artifact inspection failed; inspect this attempt's inspection log");
        Workspace.requireRegular(staged, Workspace.MAX_ARTIFACT);
        Files.move(staged, run.resolve("inspection.json"), StandardCopyOption.ATOMIC_MOVE);
        Map<String,String> hashes = new TreeMap<>();
        for (String name : List.of("observation.xmi", "repository-instance.als", "verification-capsule.json"))
            hashes.put(name, Workspace.sha256(run.resolve("result").resolve(name)));
        hashes.put("inspection.json", Workspace.sha256(run.resolve("inspection.json")));
        Files.writeString(run.resolve("inspection-receipt.json"), json.writeValueAsString(hashes), StandardOpenOption.CREATE_NEW);
    }
    public JsonNode view(Path run) throws Exception {
        JsonNode receipt = json.readTree(Files.readString(run.resolve("inspection-receipt.json")));
        for (String name : List.of("observation.xmi", "repository-instance.als", "verification-capsule.json", "inspection.json")) {
            Path artifact = name.equals("inspection.json") ? run.resolve(name) : run.resolve("result").resolve(name);
            Workspace.requireRegular(artifact, Workspace.MAX_ARTIFACT);
            if (!Workspace.sha256(artifact).equals(receipt.path(name).asText()))
                throw new IllegalStateException("recorded artifact changed after inspection: " + name);
        }
        Path file = run.resolve("inspection.json");
        Workspace.requireRegular(file, Workspace.MAX_ARTIFACT);
        return json.readTree(file.toFile());
    }
    public void importResult(Path from, Path run) throws Exception {
        Files.createDirectory(run.resolve("result"));
        for (String name : List.of("observation.xmi", "repository-instance.als", "verification-capsule.json")) {
            Path source = from.resolve(name); Workspace.requireRegular(source, Workspace.MAX_ARTIFACT);
            Files.copy(source, run.resolve("result").resolve(name));
        }
    }
}
