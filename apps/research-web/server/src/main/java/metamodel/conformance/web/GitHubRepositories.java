package metamodel.conformance.web;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.*;
import java.util.*;

/** Public discovery only. Search results are candidates, never a scientific sampling frame. */
@Service
public final class GitHubRepositories {
    private final Workspace workspace;
    public GitHubRepositories(Workspace workspace) { this.workspace = workspace; }
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json = JsonMapper.builder().build();
    public record Repository(String name, String url, String description, String language, long stars, long sizeKiB, String branch) { }
    public record Search(String id, String retrievedAt, String query, String sort, String order, int page, long total, boolean incomplete,
                         List<Repository> items, String rateRemaining, String rateReset, Map<String,String> criteria) {
        public Search { criteria = criteria == null ? Map.of() : Map.copyOf(criteria); }
        public Search(String id, String retrievedAt, String query, String sort, String order, int page, long total, boolean incomplete,
                      List<Repository> items, String rateRemaining, String rateReset) {
            this(id, retrievedAt, query, sort, order, page, total, incomplete, items, rateRemaining, rateReset, Map.of());
        }
    }
    public Search search(String query, String sort, String order, int page) throws Exception {
        return search(query, sort, order, page, Map.of());
    }
    public Search search(RepositorySearch criteria, int page) throws Exception {
        return search(criteria.query(), criteria.value("sort"), criteria.value("order"), page, criteria.fields());
    }
    private Search search(String query, String sort, String order, int page, Map<String,String> criteria) throws Exception {
        if (query == null || query.isBlank() || query.length() > 500 || page < 1 || page > 50
                || !Set.of("stars", "forks", "updated", "best-match").contains(sort)
                || !Set.of("asc", "desc").contains(order)) throw new IllegalArgumentException("invalid GitHub search query or page");
        String uri = "https://api.github.com/search/repositories?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&per_page=20&page=" + page + "&order=" + order + (sort.equals("best-match") ? "" : "&sort=" + sort);
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "conformance-research-workbench").GET().build();
        // A subscriber bounds both the body and the entire response deadline, unlike an unbounded InputStream body.
        var pending = client.sendAsync(request, info -> new BoundedBody(2 * 1024 * 1024));
        HttpResponse<byte[]> response;
        try { response = pending.get(20, java.util.concurrent.TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException failure) { throw new IOException("GitHub search could not connect securely: " + failure.getCause().getMessage(), failure); }
        catch (java.util.concurrent.TimeoutException timeout) { pending.cancel(true); throw new IOException("GitHub search exceeded 20 seconds; direct URL import is still available", timeout); }
        catch (InterruptedException interrupted) { pending.cancel(true); Thread.currentThread().interrupt(); throw interrupted; }
        String remaining = response.headers().firstValue("x-ratelimit-remaining").orElse("unknown");
        String reset = response.headers().firstValue("x-ratelimit-reset").orElse("unknown");
        if (response.statusCode() != 200) throw new IOException("GitHub search returned HTTP " + response.statusCode()
                + "; remaining requests: " + remaining + "; reset (Unix seconds): " + reset + ". You can still import a repository URL directly.");
        JsonNode body = json.readTree(response.body()); List<Repository> items = new ArrayList<>();
        for (JsonNode item : body.path("items")) items.add(new Repository(item.path("full_name").asText(),
                RepositoryRequest.canonicalUrl(item.path("html_url").asText()), item.path("description").asText(""),
                item.path("language").asText(""), item.path("stargazers_count").asLong(), item.path("size").asLong(), item.path("default_branch").asText("HEAD")));
        Search search = new Search(UUID.randomUUID().toString(), java.time.Instant.now().toString(), query, sort, order, page, body.path("total_count").asLong(), body.path("incomplete_results").asBoolean(),
                List.copyOf(items), remaining, reset, criteria);
        Files.createDirectories(workspace.root.resolve("discoveries"));
        Files.writeString(workspace.root.resolve("discoveries").resolve(search.id() + ".json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(search), StandardOpenOption.CREATE_NEW);
        return search;
    }
    public Path discovery(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException("invalid discovery ID");
        Path path = workspace.root.resolve("discoveries").resolve(id + ".json"); Workspace.requireRegular(path, 2 * 1024 * 1024); return path;
    }
    public Search retainedSearch(String id) throws IOException {
        return json.readValue(Files.readString(discovery(id)), Search.class);
    }
    public Path selectedDiscovery(String id, String url) throws IOException {
        Path path = discovery(id);
        for (JsonNode item : json.readTree(Files.readString(path)).path("items")) if (url.equals(item.path("url").asText())) return path;
        throw new IllegalArgumentException("repository is not a candidate in the selected discovery receipt");
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> result = new java.util.concurrent.CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int maximum; private java.util.concurrent.Flow.Subscription subscription;
        BoundedBody(int maximum) { this.maximum = maximum; }
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<java.nio.ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (bytes.size() + buffer.remaining() > maximum) { subscription.cancel(); result.completeExceptionally(new IOException("GitHub response exceeded 2 MiB")); return; }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(failure); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
