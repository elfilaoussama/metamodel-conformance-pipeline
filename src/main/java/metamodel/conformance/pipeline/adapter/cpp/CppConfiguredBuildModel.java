package metamodel.conformance.pipeline.adapter.cpp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import metamodel.conformance.pipeline.util.Hashing;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Immutable declared CMake target facts. Neither dependencies nor link command
 * fragments certify selected-target membership or exact consumed linker inputs. */
public final class CppConfiguredBuildModel {
    private final String json;
    private final String sha256;
    private final Path sourceRoot;
    private final Path buildRoot;
    private final Set<Path> sources;

    private CppConfiguredBuildModel(String json, Path sourceRoot, Path buildRoot, Set<Path> sources) {
        this.json = json;
        this.sha256 = Hashing.sha256(json);
        this.sourceRoot = sourceRoot;
        this.buildRoot = buildRoot;
        this.sources = Set.copyOf(sources);
    }

    public String json() { return json; }
    public String sha256() { return sha256; }
    public Path sourceRoot() { return sourceRoot; }
    public Path buildRoot() { return buildRoot; }
    public boolean declaresSource(String relativePath) { return sources.contains(sourceRoot.resolve(relativePath).normalize()); }

    public static CppConfiguredBuildModel parse(String json) {
        try {
            var mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode root = mapper.readTree(json);
            require(root != null && root.isObject() && root.size() == 4
                    && root.path("formatVersion").isIntegralNumber() && root.path("formatVersion").canConvertToInt()
                    && root.path("formatVersion").asInt() == 1
                    && root.path("cmake").isObject() && root.path("codemodel").isObject()
                    && root.path("targets").isArray(), "invalid configured build model envelope");
            JsonNode model = root.get("codemodel");
            require("codemodel".equals(model.path("kind").asText())
                    && model.path("version").path("major").isIntegralNumber()
                    && model.path("version").path("major").canConvertToInt()
                    && model.path("version").path("major").asInt() == 2
                    && model.path("configurations").isArray(), "unsupported configured codemodel");
            Path source = directory(model.path("paths").get("source"));
            Path build = directory(model.path("paths").get("build"));
            Set<String> configurations = new HashSet<>();
            Map<String, String> references = new HashMap<>();
            for (JsonNode configuration : model.get("configurations")) {
                String name = text(configuration.get("name"), true);
                require(configurations.add(name), "duplicate build configuration");
                JsonNode targets = configuration.get("targets");
                require(targets == null || targets.isArray(), "invalid configured target references");
                if (targets != null) for (JsonNode ref : targets) {
                    String key = name + "\0" + text(ref.get("id"), false);
                    require(references.putIfAbsent(key, text(ref.get("name"), false)) == null,
                            "duplicate configured target reference");
                }
            }
            Set<String> objects = new HashSet<>();
            Set<Path> sources = new HashSet<>();
            for (JsonNode row : root.get("targets")) {
                require(row.isObject() && row.size() == 3, "invalid configured target envelope");
                String configuration = text(row.get("configuration"), true);
                String id = text(row.get("id"), false);
                String key = configuration + "\0" + id;
                JsonNode target = row.path("object");
                require(target.isObject() && configurations.contains(configuration)
                        && id.equals(text(target.get("id"), false))
                        && text(target.get("name"), false).equals(references.get(key))
                        && objects.add(key), "configured target reference/object mismatch");
                JsonNode sourceRows = target.get("sources");
                require(sourceRows == null || sourceRows.isArray(), "invalid configured source rows");
                if (sourceRows != null) for (JsonNode entry : sourceRows)
                    sources.add(source.resolve(text(entry.get("path"), false)).normalize());
            }
            require(objects.equals(references.keySet()), "configured target object population incomplete");
            return new CppConfiguredBuildModel(json, source, build, sources);
        } catch (IOException failure) {
            throw new IllegalArgumentException("invalid configured build model JSON", failure);
        }
    }

    private static String text(JsonNode value, boolean emptyAllowed) {
        require(value != null && value.isTextual(), "configured model field must be textual");
        String result = value.asText();
        require((emptyAllowed || !result.isBlank()) && result.indexOf('\0') < 0,
                "invalid configured model text");
        return result;
    }

    private static Path directory(JsonNode node) {
        String value = text(node, false); Path path = Path.of(value);
        require(path.isAbsolute() && path.normalize().toString().equals(value),
                "configured model directory must be canonical and absolute");
        return path;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
