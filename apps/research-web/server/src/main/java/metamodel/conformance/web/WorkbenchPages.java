package metamodel.conformance.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** HTML presentation of the same services used by the JSON API. */
@Controller
public final class WorkbenchPages {
    private static final List<String> TABS = List.of("Overview", "Decisions", "Model", "Evidence", "Sources", "Artifacts");
    private final Catalog catalog;
    private final RunService runs;
    private final ArtifactViews views;
    private final Workspace workspace;
    private final JsonMapper json = JsonMapper.builder().build();
    public WorkbenchPages(Catalog catalog, RunService runs, ArtifactViews views, Workspace workspace) {
        this.catalog = catalog; this.runs = runs; this.views = views; this.workspace = workspace;
    }
    @GetMapping("/")
    public String index(@RequestParam(required=false) String run, @RequestParam(defaultValue="Overview") String tab,
            @RequestParam(required=false) String form, @RequestParam(defaultValue="classifiers") String kind,
            @RequestParam(defaultValue="0") int offset, @RequestParam(defaultValue="ALL") String decisionStatus, @RequestParam(required=false) String entity,
            @RequestParam(required=false) String source, HttpServletRequest request, Model model) throws Exception {
        if ((run == null || run.isBlank()) && form == null) return "redirect:/workflow";
        if (!TABS.contains(tab) || (form != null && !Set.of("ANALYZE", "IMPORT").contains(form)))
            throw new IllegalArgumentException("unsupported page selection");
        model.addAttribute("csrf", LocalSessionFilter.csrfToken(request));
        model.addAttribute("runs", catalog.list()); model.addAttribute("inputRoot", workspace.inputRoot.toString());
        model.addAttribute("engineHash", workspace.engineSha256); model.addAttribute("blocked", runs.recoveryBlocked());
        model.addAttribute("tabs", TABS); model.addAttribute("tab", tab); model.addAttribute("form", form);
        model.addAttribute("requestKey", UUID.randomUUID().toString());
        model.addAttribute("selected", null); model.addAttribute("overview", null); model.addAttribute("active", false);
        model.addAttribute("entityText", null); model.addAttribute("sourceView", null); model.addAttribute("sourceError", null);
        model.addAttribute("acquisition", null);
        model.addAttribute("line", 0); model.addAttribute("kind", tab.equals("Sources") ? "units" : kind);
        if (run == null || run.isBlank()) return "workbench";
        RunRecord selected = catalog.get(run);
        model.addAttribute("selected", selected); model.addAttribute("events", catalog.events(run, 0));
        model.addAttribute("active", Set.of("QUEUED", "RUNNING", "CANCELLING").contains(selected.state()));
        java.nio.file.Path receipt = workspace.run(run).resolve("acquisition.json");
        if (java.nio.file.Files.isRegularFile(receipt)) {
            Workspace.requireRegular(receipt, Workspace.MAX_ARTIFACT);
            model.addAttribute("acquisition", object(json.readTree(java.nio.file.Files.readString(receipt))));
        }
        if (!selected.state().equals("COMPLETED") || selected.operation().equals("REPLAY") || selected.operation().equals("GITHUB_IMPORT")) return "workbench";
        Map<String,Object> overview = views.overview(run);
        Map<String,Object> display = object(overview);
        model.addAttribute("overview", display);
        JsonNode capsule = (JsonNode) overview.get("capsule");
        var summary = ResultSummary.from(capsule, (JsonNode) overview.get("rules"), (JsonNode) overview.get("completeEvidence"), (Map<String,?>) overview.get("counts"));
        model.addAttribute("resultSummary", summary);
        model.addAttribute("decisionCounts", summary.statusCounts());
        if (!Set.of("ALL","CONFORMANT","NON_CONFORMANT","NOT_EVALUATED").contains(decisionStatus)) throw new IllegalArgumentException("Unsupported decision filter.");
        model.addAttribute("decisionStatus", decisionStatus);
        List<Map<String,Object>> decisionRows = new ArrayList<>();
        for (JsonNode decision : capsule.path("decisions")) if (decisionStatus.equals("ALL") || decision.path("status").asText().equals(decisionStatus)) decisionRows.add(object(decision));
        model.addAttribute("displayDecisions", decisionRows);
        Map<String,Object> rules = new LinkedHashMap<>();
        for (JsonNode rule : (JsonNode) overview.get("rules")) rules.put(rule.path("id").asText(), object(rule));
        model.addAttribute("rulesById", rules);
        model.addAttribute("evidenceText", json.writerWithDefaultPrettyPrinter().writeValueAsString(overview.get("nativeEvidence")));
        model.addAttribute("diagnosticText", json.writerWithDefaultPrettyPrinter().writeValueAsString(capsule.path("observationDiagnostics")));
        if (Set.of("Model", "Sources").contains(tab)) {
            Map<String,Object> page = views.entities(run, tab.equals("Sources") ? "units" : kind, offset, 50);
            List<Map<String,Object>> rows = new ArrayList<>();
            for (Object item : (List<?>) page.get("items")) {
                JsonNode row = (JsonNode) item;
                Map<String,Object> formatted = new LinkedHashMap<>();
                formatted.put("name", first(row, "qualifiedName", "memberName", "path", "technicalKey", "kind"));
                formatted.put("detail", first(row, "id", "technicalKey", "sha256"));
                formatted.put("kind", first(row, "kind", "language", "scope"));
                formatted.put("source", first(row, "sourcePath", "path"));
                formatted.put("key", first(row, "id", "technicalKey"));
                formatted.put("line", row.path("startLine").asInt()); rows.add(formatted);
            }
            model.addAttribute("rows", rows); model.addAttribute("page", page);
        }
        if (entity != null) {
            JsonNode item = views.entity(run, entity);
            model.addAttribute("entityText", json.writerWithDefaultPrettyPrinter().writeValueAsString(item));
            if (source == null) source = first(item, "sourcePath");
            model.addAttribute("line", item.path("startLine").asInt());
        }
        if (source != null) {
            try {
                Map<String,Object> sourceView = new LinkedHashMap<>(views.source(run, source));
                sourceView.put("lines", Arrays.asList(sourceView.get("text").toString().split("\n", -1)));
                model.addAttribute("sourceView", sourceView);
            } catch (IllegalArgumentException | IllegalStateException | java.io.IOException unavailable) {
                model.addAttribute("sourceError", "Measured source unavailable: " + unavailable.getMessage());
            }
        }
        return "workbench";
    }
    @GetMapping({"/runs", "/results"})
    public String history(HttpServletRequest request, Model model) throws Exception {
        boolean results = request.getRequestURI().equals("/results");
        model.addAttribute("resultsOnly", results);
        String group = Objects.toString(request.getParameter("group"), "");
        var population = group.isEmpty() ? catalog.list() : catalog.requestGroup(group);
        var available = population.stream().filter(row -> !results || row.state().equals("COMPLETED") && !Set.of("REPLAY", "GITHUB_IMPORT").contains(row.operation())).toList();
        model.addAttribute("group", group);
        model.addAttribute("groupActive", !group.isEmpty() && available.stream().anyMatch(r -> Set.of("QUEUED", "RUNNING", "CANCELLING").contains(r.state())));
        String q = Objects.toString(request.getParameter("q"), "").strip();
        String state = Objects.toString(request.getParameter("state"), "").strip().toUpperCase(Locale.ROOT);
        if (q.length() > 200 || state.length() > 40) throw new IllegalArgumentException("Keep the history filter within 200 characters and state within 40.");
        var states = new TreeSet<String>(); available.forEach(row -> states.add(row.state()));
        if (!state.isEmpty()) states.add(state);
        model.addAttribute("states", states); model.addAttribute("q", q); model.addAttribute("state", state);
        model.addAttribute("availableCount", available.size());
        model.addAttribute("history", available.stream().filter(row -> state.isEmpty() || row.state().equals(state))
                .filter(row -> (row.label() + " " + row.id() + " " + row.operation()).toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))).toList());
        return "run-history";
    }
    @PostMapping({"/ui/runs", "/ui/imports"})
    public RedirectView submit(@RequestParam MultiValueMap<String,String> fields, HttpServletRequest request) throws Exception {
        requireFields(fields, Set.of("_csrf", "label", "source", "result", "idempotencyKey"));
        RunRecord run = runs.submit(new RunService.Submission(fields.getFirst("label"), fields.getFirst("source"),
                fields.getFirst("result"), fields.getFirst("idempotencyKey")), request.getRequestURI().equals("/ui/imports"));
        return redirect(run.id());
    }
    @PostMapping("/ui/runs/{id}/replay")
    public RedirectView replay(@PathVariable String id, @RequestParam MultiValueMap<String,String> fields) throws Exception {
        requireFields(fields, Set.of("_csrf", "idempotencyKey"));
        return redirect(runs.replay(id, fields.getFirst("idempotencyKey")).id());
    }
    @PostMapping("/ui/runs/{id}/cancel")
    public RedirectView cancel(@PathVariable String id, @RequestParam MultiValueMap<String,String> fields) throws Exception {
        requireFields(fields, Set.of("_csrf")); return redirect(runs.cancel(id).id());
    }
    @PostMapping("/ui/repositories/{id}/analyze")
    public RedirectView analyzeRetained(@PathVariable String id, @RequestParam MultiValueMap<String,String> fields) throws Exception {
        requireFields(fields, Set.of("_csrf", "idempotencyKey")); return redirect(runs.analyzeRetained(id, fields.getFirst("idempotencyKey")).id());
    }
    private RedirectView redirect(String id) {
        RedirectView view = new RedirectView("/?run=" + id); view.setStatusCode(HttpStatus.SEE_OTHER); return view;
    }
    private static void requireFields(MultiValueMap<String,String> fields, Set<String> allowed) {
        if (!allowed.containsAll(fields.keySet()) || fields.values().stream().anyMatch(values -> values.size() != 1))
            throw new IllegalArgumentException("unsupported or repeated form field");
    }
    @SuppressWarnings("unchecked") private Map<String,Object> object(Object value) { return json.convertValue(value, Map.class); }
    private static String first(JsonNode node, String... names) {
        for (String name : names) if (node.hasNonNull(name) && !node.path(name).asText().isBlank()) return node.path(name).asText();
        return null;
    }
}
