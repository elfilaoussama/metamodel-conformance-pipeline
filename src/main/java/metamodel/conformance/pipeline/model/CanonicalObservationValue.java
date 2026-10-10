package metamodel.conformance.pipeline.model;

import java.util.regex.Pattern;

final class CanonicalObservationValue {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private CanonicalObservationValue() {
    }

    static String relativePath(String value, String name) {
        String path = text(value, name);
        if (path.startsWith("/")
                || path.endsWith("/")
                || path.contains("\\")
                || path.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException(name + " must be a canonical relative path: " + path);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(name + " must be a canonical relative path: " + path);
            }
        }
        return path;
    }

    static String sha256(String value, String name) {
        String digest = text(value, name);
        if (!SHA256.matcher(digest).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest");
        }
        return digest;
    }

    static String technicalId(String value, String prefix, String name) {
        String identifier = text(value, name);
        if (!identifier.startsWith(prefix)
                || !SHA256.matcher(identifier.substring(prefix.length())).matches()) {
            throw new IllegalArgumentException(
                    name + " must have the form " + prefix + "<lowercase SHA-256>");
        }
        return identifier;
    }

    static String text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
