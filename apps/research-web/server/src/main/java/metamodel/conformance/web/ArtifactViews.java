package metamodel.conformance.web;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;

@Service
public final class ArtifactViews {
    private final Workspace workspace;
    private final Catalog catalog;
    private final EngineGateway engine;
    private static final Set<String> COLLECTIONS = Set.of("classifiers", "members", "methodBodies", "implementationBindings", "units", "diagnostics");
    public ArtifactViews(Workspace workspace, Catalog catalog, EngineGateway engine) {
        this.workspace = workspace; this.catalog = catalog; this.engine = engine;
    }
    public JsonNode view(String id) throws Exception {
        RunRecord run = catalog.get(id);
        if (!run.state().equals("COMPLETED") || run.operation().equals("REPLAY") || run.operation().equals("GITHUB_IMPORT"))
            throw new IllegalArgumentException("no completed observation available for this operation");
        return engine.view(workspace.run(id));
    }
    public Map<String,Object> overview(String id) throws Exception {
        JsonNode v = view(id), o = v.path("observation");
        Map<String,Integer> counts = new TreeMap<>();
        COLLECTIONS.forEach(key -> counts.put(key, o.path(key).size()));
        return Map.of("inspection", v.path("inspection"), "capsule", v.path("capsule"),
                "rules", v.path("rules"), "counts", counts, "completeEvidence", o.path("completeEvidence"),
                "nativeEvidence", o.path("nativeEvidence"), "replayAttempts", catalog.replays(id));
    }
    public Map<String,Object> entities(String id, String kind, int offset, int limit) throws Exception {
        if (!COLLECTIONS.contains(kind) || offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("invalid page");
        JsonNode rows = view(id).path("observation").path(kind);
        List<JsonNode> page = new ArrayList<>();
        for (int i = offset; i < Math.min(rows.size(), offset + limit); i++) page.add(rows.get(i));
        return Map.of("items", page, "offset", offset, "total", rows.size(), "limit", limit);
    }
    public JsonNode entity(String id, String key) throws Exception {
        JsonNode observation = view(id).path("observation");
        for (String kind : List.of("classifiers", "members", "methodBodies", "implementationBindings"))
            for (JsonNode row : observation.path(kind))
                if (key.equals(row.path("id").asText()) || key.equals(row.path("technicalKey").asText())) return row;
        throw new NoSuchElementException("entity not found in this observation");
    }
    public Map<String,Object> source(String id, String unitPath) throws Exception {
        JsonNode unit = null;
        for (JsonNode row : view(id).path("observation").path("units"))
            if (unitPath.equals(row.path("path").asText())) { unit = row; break; }
        if (unit == null) throw new NoSuchElementException("source is not in the observation inventory");
        Path root = workspace.run(id).resolve("source");
        Path path = root.resolve(unitPath).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("invalid source coordinate");
        Workspace.requireRegular(path, Workspace.MAX_SOURCE);
        String actual = Workspace.sha256(path);
        if (!actual.equals(unit.path("sha256").asText())) throw new IllegalStateException("retained source hash mismatch; measured source is unavailable");
        return Map.of("path", unitPath, "sha256", actual, "text", Files.readString(path));
    }
    public Path artifact(String id, String artifact) throws Exception {
        RunRecord run = catalog.get(id);
        Path root = workspace.run(id);
        Path file = switch (artifact) {
            case "observation.xmi", "repository-instance.als", "verification-capsule.json" -> {
                if (!run.state().equals("COMPLETED") || run.operation().equals("REPLAY")) throw new IllegalArgumentException("final artifact unavailable");
                view(id);
                yield root.resolve("result").resolve(artifact);
            }
            case "worker.log", "inspection.log", "timing.json", "acquisition.json", "discovery.json", "git-fetch-error.log", "git-fetch.log",
                    "git-version.log", "git-init-error.log", "git-pin-error.log", "git-tree-error.log", "git-blobs-error.log" -> root.resolve(artifact);
            default -> throw new IllegalArgumentException("unknown artifact ID");
        };
        Workspace.requireRegular(file, Workspace.MAX_ARTIFACT); return file;
    }
}
