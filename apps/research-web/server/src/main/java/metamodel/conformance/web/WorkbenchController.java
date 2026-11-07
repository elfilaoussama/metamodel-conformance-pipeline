package metamodel.conformance.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public final class WorkbenchController {
    private final Workspace workspace;
    private final Catalog catalog;
    private final RunService runs;
    private final ArtifactViews views;
    private final GitHubRepositories github;
    public WorkbenchController(Workspace workspace, Catalog catalog, RunService runs, ArtifactViews views, GitHubRepositories github) {
        this.workspace = workspace; this.catalog = catalog; this.runs = runs; this.views = views; this.github = github;
    }
    @GetMapping("/session") public Map<String,Object> session(HttpServletRequest req) {
        return Map.of("csrf", LocalSessionFilter.csrfToken(req), "mode", "LOCAL_TRUSTED_OPERATOR");
    }
    @GetMapping("/capabilities") public Map<String,Object> capabilities() {
        return Map.of("apiVersion", 1, "engineSha256", workspace.engineSha256,
                "executionModes", List.of("JAVA_DEFAULT_SOURCE_ONLY"), "importLanguages", List.of("JAVA", "PYTHON", "CPP"),
                "ruleProfile", "matching engine bundled profile only", "recoveryBlocked", runs.recoveryBlocked(),
                "inputRoot", workspace.inputRoot.toString(), "computeSlots", workspace.computeWorkers,
                "repositoryAcquisition", "public GitHub; pinned tracked blobs; import or Java source-only analysis");
    }
    @GetMapping("/runs") public Object list(@RequestParam(required=false) String group) throws Exception { return group == null ? catalog.list() : catalog.requestGroup(group); }
    @GetMapping("/runs/{id}") public Object get(@PathVariable String id) throws Exception { return catalog.get(id); }
    @PostMapping("/runs/preflight") public Object preflight(@RequestBody RunService.Submission request) throws Exception { return runs.preflight(request); }
    @PostMapping("/runs") public ResponseEntity<?> submit(@RequestBody RunService.Submission request) throws Exception {
        return ResponseEntity.accepted().body(runs.submit(request, false));
    }
    @PostMapping("/imports") public ResponseEntity<?> importResult(@RequestBody RunService.Submission request) throws Exception {
        return ResponseEntity.accepted().body(runs.submit(request, true));
    }
    @PostMapping("/repositories") public ResponseEntity<?> ingest(@RequestBody RepositoryRequest request) throws Exception {
        return ResponseEntity.accepted().body(runs.ingestBatch(List.of(request)).get(0));
    }
    @GetMapping("/repositories/search") public Object search(@RequestParam String q, @RequestParam(defaultValue="stars") String sort,
            @RequestParam(defaultValue="desc") String order, @RequestParam(defaultValue="1") int page) throws Exception {
        return github.search(q, sort, order, page);
    }
    @GetMapping("/discoveries/{id}") public ResponseEntity<?> discovery(@PathVariable String id) throws Exception {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("discovery-" + id + ".json").build().toString())
                .body(new FileSystemResource(github.discovery(id)));
    }
    public record ReplayRequest(String idempotencyKey) { }
    @PostMapping("/repositories/{id}/analyze") public ResponseEntity<?> analyzeRetained(@PathVariable String id, @RequestBody ReplayRequest request) throws Exception {
        return ResponseEntity.accepted().body(runs.analyzeRetained(id, request.idempotencyKey()));
    }
    @PostMapping("/runs/{id}/replay") public ResponseEntity<?> replay(@PathVariable String id, @RequestBody ReplayRequest request) throws Exception {
        return ResponseEntity.accepted().body(runs.replay(id, request.idempotencyKey()));
    }
    @PostMapping("/runs/{id}/cancel") public Object cancel(@PathVariable String id, @RequestBody Map<String,Object> ignored) throws Exception { return runs.cancel(id); }
    @GetMapping("/runs/{id}/events") public Object events(@PathVariable String id, @RequestParam(defaultValue="0") long after) throws Exception {
        catalog.get(id); return catalog.events(id, after);
    }
    @GetMapping("/runs/{id}/overview") public Object overview(@PathVariable String id) throws Exception { return views.overview(id); }
    @GetMapping("/runs/{id}/entities") public Object entities(@PathVariable String id, @RequestParam(defaultValue="classifiers") String kind,
            @RequestParam(defaultValue="0") int offset, @RequestParam(defaultValue="50") int limit) throws Exception { return views.entities(id, kind, offset, limit); }
    @GetMapping("/runs/{id}/entities/{key}") public Object entity(@PathVariable String id, @PathVariable String key) throws Exception { return views.entity(id, key); }
    @GetMapping("/runs/{id}/source") public Object source(@PathVariable String id, @RequestParam String path) throws Exception { return views.source(id, path); }
    @GetMapping("/runs/{id}/artifacts/{name}") public ResponseEntity<?> artifact(@PathVariable String id, @PathVariable String name) throws Exception {
        var file = views.artifact(id, name);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(new FileSystemResource(file));
    }
    @ExceptionHandler(Exception.class) public ResponseEntity<?> failure(Exception error) {
        int code = error instanceof NoSuchElementException ? 404 : error instanceof IllegalStateException ? 409
                : error instanceof IllegalArgumentException || error instanceof java.io.IOException
                || error instanceof org.springframework.http.converter.HttpMessageNotReadableException
                || error instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ? 400 : 500;
        return ResponseEntity.status(code).body(Map.of("error", code == 500 ? "operation failed; inspect server logs" : Objects.toString(error.getMessage(), "invalid request")));
    }
}
