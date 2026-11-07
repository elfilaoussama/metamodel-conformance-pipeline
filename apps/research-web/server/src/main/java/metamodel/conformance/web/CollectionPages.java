package metamodel.conformance.web;

import jakarta.servlet.http.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;
import java.util.*;
import static metamodel.conformance.web.ResearchCollection.*;

@Controller
public final class CollectionPages {
    private final CollectionStore store; private final CollectionService service; private final Workspace workspace;
    public CollectionPages(CollectionStore store, CollectionService service, Workspace workspace) { this.store = store; this.service = service; this.workspace = workspace; }
    @GetMapping("/collections") public String list(HttpServletRequest request, Model model) throws Exception {
        common(request, model); model.addAttribute("collections", store.list()); model.addAttribute("collection", null); model.addAttribute("error", null); model.addAttribute("draft", Map.of()); return "collections";
    }
    @GetMapping("/collections/{id}") public String detail(@PathVariable String id, @RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="0") int page, HttpServletRequest request, Model model) throws Exception { return render(id, q, page, null, request, model); }
    @GetMapping("/collections/{id}/projects/{project}") public String edit(@PathVariable String id, @PathVariable String project, HttpServletRequest request, Model model) throws Exception {
        var collection = store.get(id); var item = collection.projects().stream().filter(p -> p.id().equals(project)).findFirst().orElseThrow();
        render(id, "", 0, null, request, model);
        model.addAttribute("draft", Map.of("project", item.id(), "label", item.label(), "origin", item.origin(), "location", item.location(), "revision", item.revision(), "subdirectory", item.subdirectory(), "language", item.language(), "version", Long.toString(collection.version()))); return "collections";
    }
    @PostMapping("/ui/collections") public Object create(@RequestParam MultiValueMap<String,String> fields, HttpServletRequest request, HttpServletResponse response, Model model) throws Exception {
        validate(fields, Set.of("_csrf", "name"), Set.of());
        try { return redirect("/collections/" + service.create(fields.getFirst("name")).id()); }
        catch (IllegalArgumentException invalid) { list(request, model); model.addAttribute("error", invalid.getMessage()); model.addAttribute("draft", fields.toSingleValueMap()); response.setStatus(400); return "collections"; }
    }
    @PostMapping("/ui/collections/{id}/projects") public Object saveProject(@PathVariable String id, @RequestParam MultiValueMap<String,String> fields, HttpServletRequest request, HttpServletResponse response, Model model) throws Exception {
        validate(fields, Set.of("_csrf", "version", "project", "label", "origin", "location", "revision", "subdirectory", "language"), Set.of());
        try {
            String projectId = fields.getFirst("project");
            var project = service.project(projectId == null || projectId.isBlank() ? null : projectId, fields.getFirst("label"), fields.getFirst("origin"), fields.getFirst("location"), fields.getFirst("revision"), fields.getFirst("subdirectory"), fields.getFirst("language"), null);
            if (projectId == null || projectId.isBlank()) service.add(id, version(fields), List.of(project)); else service.edit(id, version(fields), project);
            return redirect("/collections/" + id);
        } catch (IllegalArgumentException | IllegalStateException | java.io.IOException invalid) {
            String message = invalid instanceof java.nio.file.NoSuchFileException
                    ? "Source directory not found. Enter an existing directory under the registered input root."
                    : invalid.getMessage();
            render(id, "", 0, message, request, model); model.addAttribute("draft", fields.toSingleValueMap()); response.setStatus(invalid instanceof IllegalStateException ? 409 : 400); return "collections";
        }
    }
    @PostMapping("/ui/collections/{id}/selection") public Object selection(@PathVariable String id, @RequestParam MultiValueMap<String,String> fields, HttpServletRequest request, HttpServletResponse response, Model model) throws Exception {
        validate(fields, Set.of("_csrf", "version", "visible", "selected", "action", "q", "page"), Set.of("visible", "selected"));
        String q = Objects.requireNonNullElse(fields.getFirst("q"), ""); int page = Integer.parseInt(fields.getFirst("page"));
        if (page < 0) throw new IllegalArgumentException("Invalid project page.");
        var matches = matching(store.get(id), q); requirePage(page, matches.size());
        if ("NEXT".equals(fields.getFirst("action")) && (long)(page + 1) * 25 >= matches.size()
                || "PREVIOUS".equals(fields.getFirst("action")) && page == 0) throw new IllegalArgumentException("No further project page in that direction.");
        try {
            String action = fields.getFirst("action"); long version = version(fields);
            if (!Set.of("ALL", "NONE", "REMOVE", "SAVE", "NEXT", "PREVIOUS", "ANALYZE", "ACQUIRE").contains(Objects.toString(action, ""))) throw new IllegalArgumentException("Unsupported collection action.");
            if (action.equals("ALL") || action.equals("NONE")) service.selection(id, version, Set.of(), Set.of(), action);
            else {
                service.selection(id, version, values(fields, "visible"), values(fields, "selected"), "PAGE");
                if (action.equals("REMOVE")) service.selection(id, version + 1, Set.of(), Set.of(), "REMOVE");
                if (action.equals("ANALYZE") || action.equals("ACQUIRE")) return redirect("/collections/reviews/" + service.review(id, version + 1, action).id());
            }
            if (action.equals("NEXT")) page++; if (action.equals("PREVIOUS")) page = Math.max(0, page - 1);
            if (action.equals("REMOVE")) page = Math.min(page, Math.max(0, (matching(store.get(id), q).size() - 1) / 25));
            return redirect(org.springframework.web.util.UriComponentsBuilder.fromPath("/collections/" + id).queryParam("q", "{q}").queryParam("page", page).encode().buildAndExpand(Map.of("q", q)).toUriString());
        } catch (IllegalArgumentException | IllegalStateException invalid) { response.setStatus(invalid instanceof IllegalStateException ? 409 : 400); return render(id, q, page, invalid.getMessage(), request, model); }
    }
    @GetMapping("/collections/reviews/{review}") public String review(@PathVariable String review, HttpServletRequest request, Model model) throws Exception {
        common(request, model); var specification = store.review(review); var attempts = service.attempts(specification);
        var submitted = attempts.stream().map(CollectionService.Attempt::run).filter(Objects::nonNull).toList();
        model.addAttribute("review", specification); model.addAttribute("attempts", attempts);
        model.addAttribute("startedCount", submitted.size());
        model.addAttribute("activeCount", submitted.stream().filter(r -> Set.of("QUEUED", "RUNNING", "CANCELLING").contains(r.state())).count());
        model.addAttribute("completedCount", submitted.stream().filter(r -> r.state().equals("COMPLETED")).count());
        model.addAttribute("attentionCount", submitted.stream().filter(r -> !Set.of("QUEUED", "RUNNING", "CANCELLING", "COMPLETED").contains(r.state())).count());
        model.addAttribute("error", null); return "collection-review";
    }
    @GetMapping("/api/v1/collections/reviews/{review}/status") @ResponseBody
    public List<RunRecord> status(@PathVariable String review) throws Exception {
        return service.attempts(store.review(review)).stream().map(CollectionService.Attempt::run).filter(Objects::nonNull).toList();
    }
    @PostMapping("/ui/collections/reviews/{review}/start") public Object start(@PathVariable String review, @RequestParam MultiValueMap<String,String> fields, HttpServletRequest request, HttpServletResponse response, Model model) throws Exception {
        validate(fields, Set.of("_csrf"), Set.of());
        try {
            var attempts = service.launch(review);
            if (attempts.size() == 1 && store.review(review).collectionId().equals(request.getSession().getAttribute("workflowCollection")))
                return redirect("/?run=" + attempts.get(0).id());
            return redirect("/collections/reviews/" + review);
        }
        catch (Exception failure) { review(review, request, model); model.addAttribute("error", "Some projects may already have started; their attempts are shown below. " + failure.getMessage()); response.setStatus(409); return "collection-review"; }
    }
    @PostMapping("/ui/collections/{id}/candidates") public Object candidates(@PathVariable String id, @RequestParam MultiValueMap<String,String> fields, HttpServletRequest request, HttpServletResponse response, Model model) throws Exception {
        validate(fields, Set.of("_csrf", "version", "searchId", "checked", "action", "q", "sort", "order", "page"), Set.of("checked"));
        if (!Set.of("ADD", "SAVE", "NEXT", "PREVIOUS", "SELECT_PAGE", "CLEAR_PAGE").contains(Objects.toString(fields.getFirst("action"), ""))) throw new IllegalArgumentException("Unsupported candidate action.");
        var receipt = service.searchReceipt(fields.getFirst("searchId"));
        int page = Integer.parseInt(fields.getFirst("page")); String action = fields.getFirst("action");
        if (page < 1 || page > 50 || page != receipt.page()) throw new IllegalArgumentException("The candidate page does not match its retained search receipt.");
        if (action.equals("NEXT") && (page >= 50 || page * 20L >= receipt.total()) || action.equals("PREVIOUS") && page <= 1)
            throw new IllegalArgumentException("No further candidate page is available in that direction.");
        boolean add = "ADD".equals(action);
        var admitted = service.candidates(id, version(fields), fields.getFirst("searchId"), values(fields, "checked"), fields.getFirst("action"));
        if (add && id.equals(request.getSession().getAttribute("workflowCollection")))
            service.selection(id, version(fields) + 1, admitted, admitted, "PAGE");
        if (add) return redirect("/collections/" + id);
        var url = org.springframework.web.util.UriComponentsBuilder.fromPath("/repositories").queryParam("collection", id);
        if (Set.of("NEXT", "PREVIOUS").contains(action)) {
            var retained = receipt.criteria().isEmpty() ? RepositorySearch.raw(receipt.query(), receipt.sort(), receipt.order()) : new RepositorySearch(receipt.criteria());
            retained.fields().forEach((key, value) -> url.queryParam(key, "{" + key + "}"));
            url.queryParam("search", "1").queryParam("page", action.equals("NEXT") ? page + 1 : Math.max(1, page - 1));
            return redirect(url.encode().buildAndExpand(retained.fields()).toUriString());
        }
        else url.queryParam("searchId", fields.getFirst("searchId"));
        return redirect(url.build().encode().toUriString());
    }
    private String render(String id, String q, int page, String error, HttpServletRequest request, Model model) throws Exception {
        common(request, model); var collection = store.get(id);
        List<Project> filtered = matching(collection, q); requirePage(page, filtered.size());
        int start = Math.min(filtered.size(), page * 25);
        var visible = filtered.subList(start, Math.min(start + 25, filtered.size()));
        model.addAttribute("collection", collection); model.addAttribute("projects", visible);
        model.addAttribute("otherSelected", collection.selected().size() - visible.stream().filter(p -> collection.selected().contains(p.id())).count());
        model.addAttribute("workflowMode", id.equals(request.getSession().getAttribute("workflowCollection")));
        model.addAttribute("q", q); model.addAttribute("page", page); model.addAttribute("total", filtered.size()); model.addAttribute("hasNext", start + 25 < filtered.size());
        model.addAttribute("error", error); model.addAttribute("draft", Map.of()); model.addAttribute("reviews", store.reviews(id)); return "collections";
    }
    private void common(HttpServletRequest request, Model model) { model.addAttribute("csrf", LocalSessionFilter.csrfToken(request)); model.addAttribute("inputRoot", workspace.inputRoot.toString()); model.addAttribute("computeWorkers", workspace.computeWorkers); }
    private static List<Project> matching(ResearchCollection collection, String q) {
        return collection.projects().stream().filter(p -> (p.label() + " " + p.location()).toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))).toList();
    }
    private static void requirePage(int page, int size) {
        if (page < 0 || (long)page * 25 >= Math.max(1, size)) throw new IllegalArgumentException("Invalid project page.");
    }
    private static long version(MultiValueMap<String,String> fields) { return Long.parseLong(fields.getFirst("version")); }
    private static Set<String> values(MultiValueMap<String,String> fields, String name) { return new HashSet<>(fields.getOrDefault(name, List.of())); }
    private static void validate(MultiValueMap<String,String> fields, Set<String> allowed, Set<String> multiple) {
        if (!allowed.containsAll(fields.keySet()) || fields.entrySet().stream().anyMatch(e -> !multiple.contains(e.getKey()) && e.getValue().size() != 1)) throw new IllegalArgumentException("Unsupported or repeated form field.");
    }
    private static RedirectView redirect(String url) { RedirectView redirect = new RedirectView(url); redirect.setStatusCode(HttpStatus.SEE_OTHER); return redirect; }
}
