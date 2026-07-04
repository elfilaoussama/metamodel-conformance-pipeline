package com.javapipeline.core;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

public record RepositoryRequest(
        URI remoteUri,
        String owner,
        String name,
        String branch,
        int cloneDepth,
        ExistingRepositoryPolicy existingPolicy
) {
    public RepositoryRequest {
        Objects.requireNonNull(remoteUri, "remoteUri");
        owner = requireSegment(owner, "owner");
        name = requireSegment(name, "name");
        branch = branch == null ? "" : branch.trim();
        if (cloneDepth < 1) throw new IllegalArgumentException("cloneDepth must be positive");
        existingPolicy = Objects.requireNonNullElse(existingPolicy, ExistingRepositoryPolicy.REUSE);
    }

    public static RepositoryRequest fromGitHubUrl(String raw, int depth, ExistingRepositoryPolicy policy) {
        URI uri = URI.create(Objects.requireNonNull(raw, "raw").trim());
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("GitHub URL must use HTTPS");
        }
        String host = Objects.toString(uri.getHost(), "").toLowerCase(Locale.ROOT);
        if (!host.equals("github.com") && !host.equals("www.github.com")) {
            throw new IllegalArgumentException("Only github.com repository URLs are supported");
        }
        if ((uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("GitHub URL must not contain credentials, query parameters, or fragments");
        }
        String[] parts = Objects.toString(uri.getPath(), "").replaceFirst("^/", "").split("/");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Expected a URL such as https://github.com/owner/repository");
        }
        String repository = parts[1].replaceFirst("(?i)\\.git$", "");
        URI normalized = URI.create("https://github.com/" + parts[0] + "/" + repository + ".git");
        return new RepositoryRequest(normalized, parts[0], repository, "", depth, policy);
    }

    public String coordinate() {
        return owner + "/" + name;
    }

    private static String requireSegment(String value, String label) {
        String result = Objects.requireNonNull(value, label).trim();
        if (result.isEmpty() || !result.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid repository " + label + ": " + value);
        }
        return result;
    }
}
