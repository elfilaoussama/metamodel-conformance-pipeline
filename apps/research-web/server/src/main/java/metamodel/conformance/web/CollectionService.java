package metamodel.conformance.web;

import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static metamodel.conformance.web.ResearchCollection.*;

/** Collection edits never launch tools. Reviewed immutable inputs cross the run boundary explicitly. */
@Service
public final class CollectionService {
    private final CollectionStore store; private final Workspace workspace;
    private final GitHubRepositories github; private final RunService runs; private final Catalog catalog;
    public CollectionService(CollectionStore store, Workspace workspace, GitHubRepositories github, RunService runs, Catalog catalog) {
        this.store = store; this.workspace = workspace; this.github = github; this.runs = runs; this.catalog = catalog;
    }
    public ResearchCollection create(String name) throws Exception {
        var collection = new ResearchCollection(UUID.randomUUID().toString(), name, 0, Instant.now().toString(), List.of(), Set.of(), List.of());
        store.create(collection); return collection;
    }
    ResearchCollection current(String id, long version) throws Exception {
        var collection = store.get(id);
        if (collection.version() != version) throw new IllegalStateException("This collection changed in another page. Reload it before applying your changes.");
        return collection;
    }
    public Project project(String id, String label, String origin, String location, String revision, String subdirectory, String language, String discovery) throws Exception {
        if ("LOCAL".equals(origin)) location = workspace.input(location).toString();
        if ("GITHUB".equals(origin) && discovery != null && !discovery.isBlank()) github.selectedDiscovery(discovery, RepositoryRequest.canonicalUrl(location));
        return new Project(id == null ? UUID.randomUUID().toString() : id, label, origin, location, revision, subdirectory, language, discovery);
    }
    public void add(String id, long version, List<Project> additions) throws Exception {
        var collection = current(id, version); List<Project> projects = new ArrayList<>(collection.projects());
        Set<String> identities = new HashSet<>(); projects.forEach(p -> identities.add(p.identity()));
        for (Project project : additions) if (identities.add(project.identity())) projects.add(project);
        store.save(collection.change(projects, collection.selected(), collection.candidates()), version);
    }
    public void edit(String id, long version, Project replacement) throws Exception {
        var collection = current(id, version); List<Project> projects = new ArrayList<>(collection.projects());
        int index = -1; for (int i = 0; i < projects.size(); i++) if (projects.get(i).id().equals(replacement.id())) index = i;
        if (index < 0) throw new NoSuchElementException("Project not found in this collection.");
        Project previous = projects.get(index);
        if (replacement.origin().equals("GITHUB") && previous.origin().equals("GITHUB") && previous.location().equals(replacement.location()))
            replacement = new Project(replacement.id(), replacement.label(), replacement.origin(), replacement.location(), replacement.revision(), replacement.subdirectory(), replacement.language(), previous.discoveryId());
        projects.set(index, replacement); store.save(collection.change(projects, collection.selected(), collection.candidates()), version);
    }
    public void selection(String id, long version, Set<String> visible, Set<String> checked, String action) throws Exception {
        var collection = current(id, version); Set<String> all = new HashSet<>(); collection.projects().forEach(p -> all.add(p.id()));
        if (!all.containsAll(visible) || !visible.containsAll(checked)) throw new IllegalArgumentException("Invalid project selection.");
        Set<String> selected = new HashSet<>(collection.selected());
        switch (action) {
            case "PAGE" -> { selected.removeAll(visible); selected.addAll(checked); }
            case "ALL" -> selected = all;
            case "NONE" -> selected.clear();
            case "REMOVE" -> {
                if (selected.isEmpty()) throw new IllegalArgumentException("Select projects before removing them.");
                Set<String> removed = Set.copyOf(selected);
                store.save(collection.change(collection.projects().stream().filter(p -> !removed.contains(p.id())).toList(), Set.of(), collection.candidates()), version); return;
            }
            default -> throw new IllegalArgumentException("Unsupported selection action.");
        }
        store.save(collection.change(collection.projects(), selected, collection.candidates()), version);
    }
    public Set<String> candidates(String id, long version, String searchId, Set<String> checked, boolean add) throws Exception {
        return candidates(id, version, searchId, checked, add ? "ADD" : "SAVE");
    }
    public Set<String> candidates(String id, long version, String searchId, Set<String> checked, String action) throws Exception {
        if (!Set.of("ADD", "SAVE", "NEXT", "PREVIOUS", "SELECT_PAGE", "CLEAR_PAGE").contains(action)) throw new IllegalArgumentException("Unsupported candidate action.");
        boolean add = action.equals("ADD");
        var collection = current(id, version); var search = github.retainedSearch(searchId);
        Map<String,GitHubRepositories.Repository> page = new LinkedHashMap<>(); search.items().forEach(p -> page.put(p.url(), p));
        if (action.equals("SELECT_PAGE")) checked = Set.copyOf(page.keySet());
        if (action.equals("CLEAR_PAGE")) checked = Set.of();
        if (!page.keySet().containsAll(checked)) throw new IllegalArgumentException("Selection contains a repository outside this candidate page.");
        Map<String,Project> basket = new LinkedHashMap<>(); collection.candidates().forEach(p -> basket.put(p.location(), p));
        for (String url : page.keySet()) {
            if (!checked.contains(url)) basket.remove(url);
            else if (!basket.containsKey(url)) {
                var item = page.get(url); String language = switch (item.language()) { case "Java" -> "JAVA"; case "Python" -> "PYTHON"; case "C++" -> "CPP"; default -> "UNSPECIFIED"; };
                basket.put(url, project(null, item.name(), "GITHUB", url, item.branch(), "", language, searchId));
            }
        }
        if (add && basket.isEmpty()) throw new IllegalArgumentException("Select at least one repository before configuring inputs.");
        List<Project> projects = new ArrayList<>(collection.projects());
        Set<String> admitted = new HashSet<>();
        if (add) {
            Set<String> chosen = new HashSet<>(); basket.values().forEach(p -> chosen.add(p.identity()));
            Set<String> identities = new HashSet<>(); projects.forEach(p -> identities.add(p.identity()));
            basket.values().forEach(p -> { if (identities.add(p.identity())) projects.add(p); }); basket.clear();
            projects.stream().filter(p -> chosen.contains(p.identity())).forEach(p -> admitted.add(p.id()));
        }
        store.save(collection.change(projects, collection.selected(), List.copyOf(basket.values())), version);
        return Set.copyOf(admitted);
    }
    public Review review(String id, long version, String operation) throws Exception {
        var collection = current(id, version);
        if (!Set.of("ANALYZE", "ACQUIRE").contains(operation)) throw new IllegalArgumentException("Select Analyze or Acquire source.");
        List<Project> selected = collection.projects().stream().filter(p -> collection.selected().contains(p.id())).toList();
        if (selected.isEmpty()) throw new IllegalArgumentException("Select at least one project first.");
        for (Project project : selected) {
            if (operation.equals("ANALYZE") && !project.language().equals("JAVA")) throw new IllegalArgumentException(project.label() + ": this executable web mode currently supports Java only. Edit its language/scope, or choose Acquire source for GitHub projects.");
            if (operation.equals("ACQUIRE") && project.origin().equals("LOCAL")) throw new IllegalArgumentException("Acquire source applies to GitHub projects. Registered local sources can be analyzed directly.");
            if (project.origin().equals("LOCAL")) runs.preflight(new RunService.Submission(project.label(), project.location(), null, null));
        }
        var review = new Review(UUID.randomUUID().toString(), id, collection.name(), collection.version(), operation, Instant.now().toString(), workspace.engineSha256, selected);
        store.saveReview(review); return review;
    }
    public List<RunRecord> launch(String reviewId) throws Exception {
        Review review = store.review(reviewId);
        if (!review.engineSha256().equals(workspace.engineSha256)) throw new IllegalStateException("The engine changed. Create a new review before starting work.");
        List<RunService.Submission> local = new ArrayList<>(); List<RepositoryRequest> remote = new ArrayList<>();
        for (Project project : review.projects()) {
            String key = review.requestKey(project);
            if (project.origin().equals("LOCAL")) local.add(new RunService.Submission(project.label(), project.location(), null, key));
            else remote.add(new RepositoryRequest(project.label(), project.location(), project.revision(), project.subdirectory(), review.operation().equals("ANALYZE"), key, project.discoveryId()));
        }
        return runs.submitInputs(local, remote);
    }
    public record Attempt(Project project, RunRecord run) {}
    public List<Attempt> attempts(Review review) throws Exception {
        List<Attempt> result = new ArrayList<>();
        Map<String,RunRecord> submitted = new HashMap<>();
        for (RunRecord run : catalog.requestGroup(review.id())) submitted.put(run.idempotencyKey(), run);
        for (Project project : review.projects()) {
            result.add(new Attempt(project, submitted.get(review.requestKey(project))));
        }
        return result;
    }
    public GitHubRepositories.Search searchReceipt(String searchId) throws Exception {
        return github.retainedSearch(searchId);
    }
}
