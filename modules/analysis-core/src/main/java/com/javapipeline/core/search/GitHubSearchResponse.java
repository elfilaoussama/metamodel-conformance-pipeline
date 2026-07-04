package com.javapipeline.core.search;

import java.time.Instant;
import java.util.List;

public record GitHubSearchResponse(
        long totalCount,
        boolean incomplete,
        List<GitHubRepositorySummary> repositories,
        Integer rateLimitRemaining,
        Instant rateLimitReset
) {
    public GitHubSearchResponse {
        repositories = List.copyOf(repositories);
    }
}
