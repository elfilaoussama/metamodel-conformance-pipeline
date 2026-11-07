package metamodel.conformance.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;
import java.io.IOException;
import java.util.*;

@Controller
public final class RepositoryPages {
    private final GitHubRepositories github;
    private final RunService runs;
    private final CollectionStore collections;
    private final AnalysisSetup setup;
    public RepositoryPages(GitHubRepositories github, RunService runs, CollectionStore collections, AnalysisSetup setup) { this.github = github; this.runs = runs; this.collections = collections; this.setup = setup; }
    @GetMapping("/repositories")
    public String index(@RequestParam MultiValueMap<String,String> parameters, HttpServletRequest request,
                        jakarta.servlet.http.HttpServletResponse response, Model model) throws Exception {
        rejectRepeated(parameters);
        Map<String,String> values = parameters.toSingleValueMap();
        String collectionId = values.get("collection"), searchId = values.get("searchId");
        if (collectionId == null && !"1".equals(values.get("import")) && !values.containsKey("url"))
            collectionId = setup.currentOrCreate(request.getSession());
        ResearchCollection group = collectionId == null ? null : collections.get(collectionId);
        model.addAttribute("csrf", LocalSessionFilter.csrfToken(request));
        model.addAttribute("collection", group);
        model.addAttribute("workflowMode", group != null && group.id().equals(request.getSession().getAttribute("workflowCollection")));
        model.addAttribute("candidateUrls", group == null ? Set.of() : group.candidates().stream().map(ResearchCollection.Project::location).collect(java.util.stream.Collectors.toSet()));
        model.addAttribute("requestKey", UUID.randomUUID().toString());
        model.addAttribute("blocked", runs.recoveryBlocked());
        model.addAttribute("importOpen", "1".equals(values.get("import")));
        String url = values.getOrDefault("url", ""), revision = values.getOrDefault("revision", "HEAD");
        model.addAttribute("url", url.isBlank() ? "" : RepositoryRequest.canonicalUrl(url));
        String discovery = values.get("discovery");
        if (discovery != null && !url.isBlank()) github.selectedDiscovery(discovery, RepositoryRequest.canonicalUrl(url));
        model.addAttribute("discoveryId", discovery);
        RepositoryRequest.validateRevision(revision); model.addAttribute("revision", revision);
        model.addAttribute("search", null); model.addAttribute("searchError", null); model.addAttribute("errors", Map.of());
        if (!values.containsKey("mode") && values.containsKey("q")) {
            values = new LinkedHashMap<>(values); values.put("mode", "RAW");
        }
        var criteria = new RepositorySearch(values);
        if (searchId != null) {
            var retained = github.retainedSearch(searchId); model.addAttribute("search", retained);
            criteria = retained.criteria().isEmpty() ? RepositorySearch.raw(retained.query(), retained.sort(), retained.order()) : new RepositorySearch(retained.criteria());
        }
        model.addAttribute("filters", criteria.fields()); model.addAttribute("queryPreview", "");
        boolean requested = searchId != null || values.containsKey("search") || values.containsKey("q");
        try {
            String query = criteria.query(); model.addAttribute("queryPreview", query);
            if (requested && searchId == null && !"preview".equals(values.get("search"))) {
                int page = Integer.parseInt(values.getOrDefault("page", "1"));
                model.addAttribute("search", github.search(criteria, page));
            }
        } catch (RepositorySearch.Invalid invalid) {
            if (requested) { model.addAttribute("searchError", invalid.getMessage()); model.addAttribute("errors", Map.of(invalid.field, invalid.getMessage())); response.setStatus(400); }
        } catch (IOException unavailable) { model.addAttribute("searchError", unavailable.getMessage()); }
        var previous = org.springframework.web.util.UriComponentsBuilder.fromPath("/repositories");
        if (collectionId != null) previous.queryParam("collection", collectionId);
        else previous.queryParam("import", "1");
        criteria.fields().forEach((key, value) -> previous.queryParam(key, "{" + key + "}")); previous.queryParam("search", "1");
        model.addAttribute("pageBase", previous.encode().buildAndExpand(criteria.fields()).toUriString());
        return "repositories";
    }
    @GetMapping("/repositories/preview") @ResponseBody
    public Map<String,Object> preview(@RequestParam MultiValueMap<String,String> parameters) {
        rejectRepeated(parameters);
        try { return Map.of("valid", true, "query", new RepositorySearch(parameters.toSingleValueMap()).query()); }
        catch (RepositorySearch.Invalid invalid) { return Map.of("valid", false, "message", invalid.getMessage(), "field", invalid.field); }
    }
    private static void rejectRepeated(MultiValueMap<String,String> fields) {
        if (fields.values().stream().anyMatch(value -> value.size() != 1)) throw new IllegalArgumentException("Repeated search field.");
    }
    @PostMapping("/ui/repositories")
    public RedirectView submit(@RequestParam MultiValueMap<String,String> fields) throws Exception {
        Set<String> allowed = Set.of("_csrf", "urls", "revision", "subdirectory", "mode", "idempotencyKey", "discoveryId");
        if (!allowed.containsAll(fields.keySet()) || fields.values().stream().anyMatch(v -> v.size() != 1))
            throw new IllegalArgumentException("unsupported or repeated repository form field");
        String mode = fields.getFirst("mode");
        if (!Set.of("IMPORT", "JAVA").contains(Objects.toString(mode, ""))) throw new IllegalArgumentException("select an ingestion/analysis mode");
        String[] urls = Objects.requireNonNullElse(fields.getFirst("urls"), "").strip().split("\\R");
        List<RepositoryRequest> requests = new ArrayList<>();
        for (int i = 0; i < urls.length; i++) requests.add(new RepositoryRequest(null, urls[i], fields.getFirst("revision"),
                fields.getFirst("subdirectory"), mode.equals("JAVA"), Objects.toString(fields.getFirst("idempotencyKey"), "") + "-" + i, fields.getFirst("discoveryId")));
        List<RunRecord> submitted = runs.ingestBatch(requests);
        RedirectView redirect = new RedirectView(submitted.size() == 1 ? "/?run=" + submitted.get(0).id() : "/runs?group=" + fields.getFirst("idempotencyKey"));
        redirect.setStatusCode(HttpStatus.SEE_OTHER); return redirect;
    }
}
