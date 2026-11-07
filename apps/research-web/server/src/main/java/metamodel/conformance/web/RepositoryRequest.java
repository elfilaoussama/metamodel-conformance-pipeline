package metamodel.conformance.web;

import java.net.URI;
import java.util.*;

/** A public repository request, separate from the measured source/build world. */
public record RepositoryRequest(String label, String url, String revision, String subdirectory,
                                boolean analyze, String idempotencyKey, String discoveryId) {
    public RepositoryRequest(String label, String url, String revision, String subdirectory, boolean analyze, String idempotencyKey) {
        this(label, url, revision, subdirectory, analyze, idempotencyKey, null);
    }
    public RepositoryRequest {
        url = canonicalUrl(url);
        validateRevision(revision);
        subdirectory = subdirectory == null ? "" : subdirectory.strip();
        if (!subdirectory.isEmpty()) validatePath(subdirectory);
        if (label == null || label.isBlank()) label = url.substring("https://github.com/".length());
        if (label.length() > 120) throw new IllegalArgumentException("repository label is too long");
        discoveryId = discoveryId == null || discoveryId.isBlank() ? null : discoveryId;
        if (discoveryId != null && !discoveryId.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException("invalid discovery ID");
    }
    static void validateRevision(String revision) {
        if (revision == null || !revision.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}")
                || revision.contains("..") || revision.endsWith("/") || revision.endsWith(".") || revision.contains("//"))
            throw new IllegalArgumentException("revision must be HEAD, a branch, a tag or a full commit ID");
    }
    public static String canonicalUrl(String raw) {
        try {
            URI uri = URI.create(Objects.requireNonNull(raw).strip());
            if (!"https".equals(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())
                    || uri.getRawAuthority().contains("@") || uri.getPort() != -1
                    || uri.getRawQuery() != null || uri.getRawFragment() != null)
                throw new IllegalArgumentException();
            String path = uri.getRawPath().replaceAll("/$", "").replaceAll("\\.git$", "");
            if (!path.matches("/[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}")
                    || Set.of(".", "..").contains(path.substring(path.lastIndexOf('/') + 1)))
                throw new IllegalArgumentException();
            return "https://github.com" + path;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("use a public https://github.com/OWNER/REPOSITORY URL without credentials or query parameters");
        }
    }
    static void validatePath(String path) {
        if (path.isEmpty() || path.length() > 500) throw new IllegalArgumentException("unsupported repository path");
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equalsIgnoreCase(".git")
                    || part.chars().anyMatch(c -> c < 32 || "\\:<>\"|?*".indexOf(c) >= 0) || part.endsWith(".") || part.endsWith(" ")
                    || part.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?"))
                throw new IllegalArgumentException("repository path cannot be retained safely on this host: " + path);
        }
    }
}
