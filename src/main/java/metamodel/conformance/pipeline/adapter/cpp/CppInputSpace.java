package metamodel.conformance.pipeline.adapter.cpp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Explicit compiler-visible source/build trees in a shared observation coordinate.
 * Root kind never establishes generated provenance, ownership or consumption. */
final class CppInputSpace {
    static final String FORMAT = "cpp-captured-input-space-v1";
    enum Kind { SOURCE, BUILD, DEPENDENCY }
    record Root(String id, Path path, Kind kind, String treeSha256) {
        Root {
            if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_-]*"))
                throw new IllegalArgumentException("invalid captured root identity");
            canonical(path);
            if (kind == null || treeSha256 == null || !treeSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("captured root kind/digest required");
        }
    }

    private final Path observationRoot;
    private final List<Root> roots;

    CppInputSpace(Path observationRoot, List<Root> roots) {
        canonical(observationRoot);
        if (roots == null || roots.isEmpty()) throw new IllegalArgumentException("captured roots required");
        var ids = new HashSet<String>();
        for (Root root : roots) {
            if (!ids.add(root.id()) || !root.path().startsWith(observationRoot))
                throw new IllegalArgumentException("duplicate or outside captured root");
            for (Root other : roots) {
                if (root != other && (root.path().startsWith(other.path()) || other.path().startsWith(root.path())))
                    throw new IllegalArgumentException("overlapping captured roots");
            }
        }
        this.observationRoot = observationRoot;
        this.roots = List.copyOf(roots);
    }

    Path observationRoot() { return observationRoot; }
    List<Root> roots() { return roots; }

    String bindingSha256() {
        var value=new StringBuilder("CPP_INPUT_SPACE_V1\0").append(observationRoot).append('\n');
        roots.stream().sorted(java.util.Comparator.comparing(Root::id)).forEach(root -> value.append(root.id())
                .append('\0').append(root.path()).append('\0').append(root.kind()).append('\0')
                .append(root.treeSha256()).append('\n'));
        return metamodel.conformance.pipeline.util.Hashing.sha256(value.toString());
    }

    static CppInputSpace parse(String text) {
        try {
            var mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode value = mapper.readTree(text);
            fields(value, Set.of("format", "observationRoot", "roots"));
            if (!FORMAT.equals(string(value, "format")) || !value.path("roots").isArray())
                throw new IllegalArgumentException("unsupported captured input space");
            var roots = new ArrayList<Root>();
            for (JsonNode root : value.get("roots")) {
                fields(root, Set.of("id", "path", "kind", "treeSha256"));
                roots.add(new Root(string(root,"id"), path(string(root,"path")),
                        Kind.valueOf(string(root,"kind")), string(root,"treeSha256")));
            }
            return new CppInputSpace(path(string(value,"observationRoot")), roots);
        } catch (IOException error) {
            throw new IllegalArgumentException("invalid captured input space JSON", error);
        }
    }

    boolean contains(Path path) {
        canonical(path);
        return roots.stream().anyMatch(root -> path.startsWith(root.path()));
    }

    String relativePath(Path path) {
        if (!contains(path)) throw new IllegalArgumentException("undeclared compiler-visible input root: " + path);
        return observationRoot.relativize(path).toString().replace('\\', '/');
    }

    /** Verify the exact retained trees and exclude undeclared observations between them. */
    void validate(Path actualObservationRoot) throws IOException {
        if (!observationRoot.equals(actualObservationRoot) || !Files.isDirectory(observationRoot)
                || !observationRoot.equals(observationRoot.toRealPath()))
            throw new IOException("captured observation coordinate unavailable or aliased");
        for (Root root : roots) {
            if (!Files.isDirectory(root.path()) || !root.path().equals(root.path().toRealPath())
                    || !root.treeSha256().equals(CppInputTree.digest(root.path())))
                throw new IOException("captured input root changed or unavailable: " + root.id());
        }
        Files.walkFileTree(observationRoot, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (roots.stream().anyMatch(root -> root.path().equals(directory))) return FileVisitResult.SKIP_SUBTREE;
                if (roots.stream().noneMatch(root -> root.path().startsWith(directory)))
                    throw new IOException("undeclared directory in observation coordinate: " + directory);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                throw new IOException("undeclared file in observation coordinate: " + file);
            }
        });
    }

    private static void canonical(Path path) {
        if (path == null || !path.isAbsolute() || !path.equals(path.normalize())
                || path.toString().indexOf('\0') >= 0 || path.toString().indexOf('\n') >= 0
                || path.toString().indexOf('\r') >= 0 || path.toString().indexOf('\t') >= 0)
            throw new IllegalArgumentException("canonical absolute captured coordinate required");
    }
    private static Path path(String text) {
        Path value=Path.of(text);canonical(value);
        if (!value.toString().equals(text)) throw new IllegalArgumentException("noncanonical captured coordinate spelling");
        return value;
    }
    private static void fields(JsonNode value, Set<String> names) {
        if (value == null || !value.isObject() || value.size() != names.size())
            throw new IllegalArgumentException("exact captured input-space fields required");
        for (String name : names) if (!value.has(name)) throw new IllegalArgumentException("missing captured field: " + name);
    }
    private static String string(JsonNode value, String name) {
        if (!value.path(name).isTextual()) throw new IllegalArgumentException("textual captured field required: " + name);
        return value.get(name).asText();
    }
}
