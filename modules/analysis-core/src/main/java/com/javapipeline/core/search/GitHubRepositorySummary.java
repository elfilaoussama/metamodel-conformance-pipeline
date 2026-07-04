package com.javapipeline.core.search;

import java.time.Instant;

public record GitHubRepositorySummary(
        String fullName,
        String description,
        String cloneUrl,
        String htmlUrl,
        String language,
        String license,
        String defaultBranch,
        long stars,
        long forks,
        long sizeKb,
        boolean fork,
        boolean archived,
        Instant updatedAt,
        Instant pushedAt
) { }
