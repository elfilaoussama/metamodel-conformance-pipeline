package com.javapipeline.github.search;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.javapipeline.core.CancellationToken;
import com.javapipeline.core.ProgressEvent;
import com.javapipeline.core.ProgressListener;
import com.javapipeline.core.search.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class GitHubRestRepositorySearchService implements GitHubRepositorySearchService {
    private static final URI DEFAULT_ENDPOINT = URI.create("https://api.github.com/search/repositories");
    private static final String API_VERSION = "2026-03-10";
    private final HttpClient client;
    private final URI endpoint;

    public GitHubRestRepositorySearchService() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), DEFAULT_ENDPOINT);
    }

    GitHubRestRepositorySearchService(HttpClient client) {
        this(client, DEFAULT_ENDPOINT);
    }

    GitHubRestRepositorySearchService(HttpClient client, URI endpoint) {
        this.client = client;
        this.endpoint = endpoint;
    }

    @Override
    public GitHubSearchResponse search(
            GitHubSearchCriteria criteria,
            char[] accessToken,
            ProgressListener progress,
            CancellationToken cancellation
    ) throws GitHubSearchException {
        ProgressListener listener = progress == null ? ProgressListener.NONE : progress;
        CancellationToken token = cancellation == null ? CancellationToken.NONE : cancellation;
        String query = GitHubSearchQueryBuilder.build(criteria);
        List<GitHubRepositorySummary> repositories = new ArrayList<>();
        long totalCount = 0;
        boolean incomplete = false;
        Integer rateRemaining = null;
        Instant rateReset = null;
        int page = 1;

        try {
            while (repositories.size() < criteria.resultLimit()) {
                token.throwIfCancellationRequested();
                int pageSize = Math.min(100, criteria.resultLimit() - repositories.size());
                URI uri = buildUri(query, criteria, pageSize, page);
                listener.onProgress(new ProgressEvent(
                        ProgressEvent.Stage.SEARCHING,
                        "Searching GitHub page " + page,
                        repositories.size(), criteria.resultLimit()));
                HttpResponse<String> response = client.send(
                        request(uri, accessToken), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                rateRemaining = response.headers().firstValue("x-ratelimit-remaining")
                        .map(Integer::valueOf).orElse(rateRemaining);
                rateReset = response.headers().firstValue("x-ratelimit-reset")
                        .map(Long::parseLong).map(Instant::ofEpochSecond).orElse(rateReset);
                if (response.statusCode() != 200) throw apiFailure(response);

                JsonObject payload = JsonParser.parseString(response.body()).getAsJsonObject();
                totalCount = payload.get("total_count").getAsLong();
                incomplete |= payload.get("incomplete_results").getAsBoolean();
                var items = payload.getAsJsonArray("items");
                if (items == null || items.isEmpty()) break;
                for (JsonElement element : items) {
                    if (repositories.size() >= criteria.resultLimit()) break;
                    repositories.add(mapRepository(element.getAsJsonObject()));
                }
                listener.onProgress(new ProgressEvent(
                        ProgressEvent.Stage.SEARCHING,
                        "Received " + repositories.size() + " repositories",
                        repositories.size(), Math.min(totalCount, criteria.resultLimit())));
                if (items.size() < pageSize || repositories.size() >= totalCount) break;
                page++;
            }
            return new GitHubSearchResponse(
                    totalCount, incomplete, repositories, rateRemaining, rateReset);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GitHubSearchException("GitHub search was interrupted", ex);
        } catch (GitHubSearchException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new GitHubSearchException("GitHub search failed: " + ex.getMessage(), ex);
        }
    }

    private static HttpRequest request(URI uri, char[] token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(45))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("User-Agent", "java-analysis-platform")
                .GET();
        if (token != null && token.length > 0) {
            String value = new String(token).trim();
            if (!value.isEmpty()) builder.header("Authorization", "Bearer " + value);
        }
        return builder.build();
    }

    private URI buildUri(
            String query, GitHubSearchCriteria criteria, int pageSize, int page
    ) {
        StringBuilder uri = new StringBuilder(endpoint.toString())
                .append("?q=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
        if (criteria.sort() != GitHubSearchCriteria.Sort.BEST_MATCH) {
            uri.append("&sort=").append(criteria.sort().name().toLowerCase().replace('_', '-'));
            uri.append("&order=").append(criteria.order() == GitHubSearchCriteria.Order.ASCENDING ? "asc" : "desc");
        }
        uri.append("&per_page=").append(pageSize).append("&page=").append(page);
        return URI.create(uri.toString());
    }

    private static GitHubSearchException apiFailure(HttpResponse<String> response) {
        String message = "HTTP " + response.statusCode();
        try {
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            if (body.has("message")) message += ": " + body.get("message").getAsString();
        } catch (RuntimeException ignored) { }
        if (response.statusCode() == 401) message += " (check the access token)";
        if (response.statusCode() == 403) message += " (rate limit or access policy)";
        return new GitHubSearchException(message);
    }

    private static GitHubRepositorySummary mapRepository(JsonObject item) {
        JsonObject license = object(item, "license");
        return new GitHubRepositorySummary(
                string(item, "full_name"),
                string(item, "description"),
                string(item, "clone_url"),
                string(item, "html_url"),
                string(item, "language"),
                license == null ? "" : string(license, "spdx_id"),
                string(item, "default_branch"),
                number(item, "stargazers_count"),
                number(item, "forks_count"),
                number(item, "size"),
                bool(item, "fork"),
                bool(item, "archived"),
                instant(item, "updated_at"),
                instant(item, "pushed_at"));
    }

    private static JsonObject object(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsJsonObject();
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static long number(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? 0 : value.getAsLong();
    }

    private static boolean bool(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && !value.isJsonNull() && value.getAsBoolean();
    }

    private static Instant instant(JsonObject object, String name) {
        String value = string(object, name);
        return value.isEmpty() ? null : Instant.parse(value);
    }
}
