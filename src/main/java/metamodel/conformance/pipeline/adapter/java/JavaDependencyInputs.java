package metamodel.conformance.pipeline.adapter.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable build-observed Java compilation contexts.
 *
 * <p>The {@link List} view is retained only for existing archive-fingerprinting APIs. Semantic
 * compilation consumes {@link #contexts()} and never infers build meaning from repository paths.</p>
 */
public final class JavaDependencyInputs extends AbstractList<Path> implements RandomAccess {
    private static final String GLOBAL_CONTEXT = "global";

    private final List<JavaCompilationContext> contexts;
    private final Map<String, JavaCompilationContext> byId;
    private final List<Path> allArchives;
    private final Map<String, Set<String>> excludedByContext;
    private final Set<String> noncompiledSources;

    private JavaDependencyInputs(List<JavaCompilationContext> contexts) {
        this(contexts, Map.of(), Set.of());
    }

    private JavaDependencyInputs(List<JavaCompilationContext> contexts,
            Map<String, Set<String>> excludedByContext, Set<String> noncompiledSources) {
        ArrayList<JavaCompilationContext> ordered = new ArrayList<>(contexts == null ? List.of() : contexts);
        ordered.sort(Comparator.comparing(JavaCompilationContext::id));
        LinkedHashMap<String, JavaCompilationContext> indexed = new LinkedHashMap<>();
        for (JavaCompilationContext context : ordered) {
            if (indexed.put(context.id(), context) != null) {
                throw new IllegalArgumentException("duplicate compilation context id: " + context.id());
            }
        }
        for (JavaCompilationContext context : ordered) {
            for (String upstream : context.upstreamContextIds()) {
                if (!indexed.containsKey(upstream)) {
                    throw new IllegalArgumentException(
                            "unknown upstream compilation context " + upstream + " for " + context.id());
                }
            }
        }
        this.contexts = List.copyOf(ordered);
        this.byId = Map.copyOf(indexed);
        LinkedHashSet<Path> archives = new LinkedHashSet<>();
        for (JavaCompilationContext context : ordered) {
            for (JavaResolutionPath path : context.resolutionPaths()) {
                if (path.role() == JavaResolutionPathRole.PLATFORM_PATH) {
                    continue;
                }
                path.entries().stream()
                        .filter(entry -> entry.getFileName() != null
                                && entry.getFileName().toString().endsWith(".jar"))
                        .forEach(archives::add);
            }
        }
        this.allArchives = List.copyOf(archives);
        this.excludedByContext = Map.copyOf(excludedByContext == null ? Map.of() : excludedByContext);
        this.noncompiledSources = Set.copyOf(noncompiledSources == null ? Set.of() : noncompiledSources);
    }

    public static JavaDependencyInputs none() {
        return new JavaDependencyInputs(List.of());
    }

    public static JavaDependencyInputs global(List<Path> archives) {
        if (archives instanceof JavaDependencyInputs inputs) {
            return inputs;
        }
        List<Path> canonical = canonicalPaths(archives == null ? List.of() : archives);
        if (canonical.isEmpty()) {
            return none();
        }
        return new JavaDependencyInputs(List.of(new JavaCompilationContext(
                GLOBAL_CONTEXT,
                ".",
                List.of("."),
                List.of(),
                List.of(new JavaResolutionPath(JavaResolutionPathRole.CLASS_PATH, "", canonical)),
                List.of(),
                Set.of(),
                JavaCompilerSemantics.runtime())));
    }

    public static JavaDependencyInputs fromManifest(Path manifest) throws IOException {
        if (manifest == null || Files.isSymbolicLink(manifest)
                || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("dependency manifest is not a regular file: " + manifest);
        }
        List<String> lines = Files.readAllLines(manifest);
        boolean typed = lines.stream().filter(line -> !line.isBlank())
                .map(line -> line.split("\\t", -1)[0])
                .anyMatch(kind -> Set.of("context", "source", "generated-source", "classpath",
                        "module-path", "processor-path", "upgrade-module-path", "platform-path",
                        "patch-module", "output", "upstream", "exclude", "noncompiled").contains(kind));
        return typed ? parseTyped(lines) : parseLegacy(lines);
    }

    public List<JavaCompilationContext> contexts() {
        return contexts;
    }

    public JavaCompilationContext context(String id) {
        return byId.get(id);
    }

    /**
     * Java paths that build facts declare as inputs no observed compilation
     * consumes (for example templating-plugin template trees). They stay inside
     * the observation boundary without an ownership claim.
     */
    public Set<String> noncompiledSources() {
        return noncompiledSources;
    }

    /** Java paths one context's build configuration excludes from compilation. */
    public Set<String> excludedSources(String contextId) {
        return excludedByContext.getOrDefault(contextId, Set.of());
    }

    public List<JavaCompilationContext> contextsForSourcePath(String sourcePath) {
        String canonical;
        try {
            canonical = JavaCompilationContext.canonicalRelativePath(sourcePath, "source path");
        } catch (IllegalArgumentException failure) {
            return List.of();
        }
        List<JavaCompilationContext> owners = contexts.stream()
                .filter(context -> context.ownsSourcePath(canonical))
                .toList();
        if (!owners.isEmpty()) {
            return owners;
        }
        if (contexts.size() == 1 && GLOBAL_CONTEXT.equals(contexts.get(0).id())) {
            return contexts;
        }
        return List.of();
    }

    public List<Path> pathsForContext(String contextId) {
        JavaCompilationContext context = byId.get(contextId);
        if (context == null) {
            return List.of();
        }
        return context.resolutionEntries(JavaResolutionPathRole.CLASS_PATH);
    }

    public List<Path> archivePathsForContext(String contextId) {
        JavaCompilationContext context = byId.get(contextId);
        if (context == null) return List.of();
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        for (JavaResolutionPath path : context.resolutionPaths()) {
            if (path.role() == JavaResolutionPathRole.PLATFORM_PATH) continue;
            path.entries().stream()
                    .filter(entry -> entry.getFileName() != null
                            && entry.getFileName().toString().endsWith(".jar"))
                    .forEach(result::add);
        }
        return List.copyOf(result);
    }

    /** Compatibility alias. The argument is now a compilation-context id, not a path-derived source-set id. */
    public List<Path> pathsForSourceSet(String contextId) {
        JavaCompilationContext exact = byId.get(contextId);
        if (exact != null) {
            return exact.resolutionEntries(JavaResolutionPathRole.CLASS_PATH);
        }
        List<JavaCompilationContext> owners = contextsForSourcePath(contextId);
        return owners.size() == 1
                ? owners.get(0).resolutionEntries(JavaResolutionPathRole.CLASS_PATH)
                : List.of();
    }

    public Set<String> upstreamClosure(String contextId) {
        return Set.copyOf(upstreamTopological(contextId));
    }

    /** Dependency-first deterministic ordering of all transitive upstream compilation contexts. */
    public List<String> upstreamTopological(String contextId) {
        if (!byId.containsKey(contextId)) {
            return List.of();
        }
        ArrayList<String> ordered = new ArrayList<>();
        collectUpstreamTopological(contextId, ordered, new LinkedHashSet<>(), new LinkedHashSet<>());
        return List.copyOf(ordered);
    }

    private void collectUpstreamTopological(
            String id, List<String> ordered, Set<String> completed, Set<String> visiting) {
        if (!visiting.add(id)) {
            throw new IllegalArgumentException("cycle in compilation-context upstream graph at " + id);
        }
        JavaCompilationContext context = byId.get(id);
        if (context != null) {
            for (String upstream : new TreeSet<>(context.upstreamContextIds())) {
                if (!completed.contains(upstream)) {
                    collectUpstreamTopological(upstream, ordered, completed, visiting);
                    completed.add(upstream);
                    ordered.add(upstream);
                }
            }
        }
        visiting.remove(id);
    }

    /** Deterministic union used only for module-level support materialization/provenance. */
    public List<Path> pathsForModule(String moduleKey) {
        String canonical = JavaCompilationContext.canonicalModuleKey(moduleKey);
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        contexts.stream().filter(context -> context.moduleKey().equals(canonical))
                .forEach(context -> result.addAll(context.resolutionEntries(JavaResolutionPathRole.CLASS_PATH)));
        return List.copyOf(result);
    }

    public List<Path> allPaths() {
        return allArchives;
    }

    public boolean scoped() {
        return !contexts.isEmpty() && !(contexts.size() == 1 && GLOBAL_CONTEXT.equals(contexts.get(0).id()));
    }

    @Override
    public boolean isEmpty() {
        return allArchives.isEmpty();
    }

    public Set<String> sourceSetKeys() {
        return contextIds();
    }

    public Set<String> contextIds() {
        return Set.copyOf(byId.keySet());
    }

    public Set<String> moduleKeys() {
        TreeSet<String> modules = new TreeSet<>();
        contexts.forEach(context -> modules.add(context.moduleKey()));
        return Set.copyOf(modules);
    }

    @Override
    public Path get(int index) {
        return allArchives.get(index);
    }

    @Override
    public int size() {
        return allArchives.size();
    }

    private static JavaDependencyInputs parseLegacy(List<String> lines) throws IOException {
        LinkedHashMap<String, List<Path>> byRoot = new LinkedHashMap<>();
        int lineNumber = 0;
        for (String line : lines) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split("\\t", -1);
            if (fields.length != 2 || fields[0].isBlank() || fields[1].isBlank()) {
                throw new IOException("invalid legacy dependency manifest line " + lineNumber);
            }
            String root;
            try {
                root = JavaCompilationContext.canonicalRelativePath(fields[0], "dependency source root");
            } catch (IllegalArgumentException failure) {
                throw new IOException("invalid legacy dependency manifest line " + lineNumber, failure);
            }
            byRoot.computeIfAbsent(root, ignored -> new ArrayList<>()).add(Path.of(fields[1]));
        }
        ArrayList<JavaCompilationContext> contexts = new ArrayList<>();
        int index = 0;
        for (Map.Entry<String, List<Path>> entry : byRoot.entrySet()) {
            contexts.add(new JavaCompilationContext(
                    "legacy-" + index++, ".", List.of(entry.getKey()), List.of(),
                    List.of(new JavaResolutionPath(
                            JavaResolutionPathRole.CLASS_PATH, "", canonicalPaths(entry.getValue()))),
                    List.of(), Set.of(), JavaCompilerSemantics.unknown()));
        }
        return new JavaDependencyInputs(contexts);
    }

    private static JavaDependencyInputs parseTyped(List<String> lines) throws IOException {
        LinkedHashMap<String, MutableContext> contexts = new LinkedHashMap<>();
        Set<String> noncompiledSources = new LinkedHashSet<>();
        int lineNumber = 0;
        for (String line : lines) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split("\\t", -1);
            try {
                switch (fields[0]) {
                    case "context" -> {
                        if (fields.length != 8) {
                            throw new IllegalArgumentException("context row requires 8 fields");
                        }
                        String id = token(fields[1], "context id");
                        if (contexts.containsKey(id)) {
                            throw new IllegalArgumentException("duplicate context row: " + id);
                        }
                        contexts.put(id, new MutableContext(
                                id,
                                JavaCompilationContext.canonicalModuleKey(fields[2]),
                                integer(fields[3]), integer(fields[4]), integer(fields[5]),
                                booleanValue(fields[6]), fields[7].trim()));
                    }
                    case "source", "generated-source" -> {
                        requireFields(fields, 3);
                        MutableContext context = requireContext(contexts, fields[1]);
                        String root = JavaCompilationContext.canonicalRelativePath(fields[2], fields[0]);
                        ("source".equals(fields[0]) ? context.sources : context.generatedSources).add(root);
                    }
                    case "classpath", "module-path", "processor-path", "upgrade-module-path",
                            "platform-path" -> {
                        requireFields(fields, 3);
                        MutableContext context = requireContext(contexts, fields[1]);
                        context.path(pathRole(fields[0]), "").add(Path.of(fields[2]));
                    }
                    case "patch-module" -> {
                        requireFields(fields, 4);
                        MutableContext context = requireContext(contexts, fields[1]);
                        context.path(JavaResolutionPathRole.PATCH_MODULE, token(fields[2], "patch module"))
                                .add(Path.of(fields[3]));
                    }
                    case "output" -> {
                        requireFields(fields, 3);
                        requireContext(contexts, fields[1]).outputs.add(Path.of(fields[2]));
                    }
                    case "upstream" -> {
                        requireFields(fields, 3);
                        requireContext(contexts, fields[1]).upstream.add(token(fields[2], "upstream id"));
                    }
                    case "exclude" -> {
                        requireFields(fields, 3);
                        requireContext(contexts, fields[1]).excludedSources.add(
                                JavaCompilationContext.canonicalRelativePath(fields[2], "excluded source"));
                    }
                    case "noncompiled" -> {
                        requireFields(fields, 2);
                        noncompiledSources.add(
                                JavaCompilationContext.canonicalRelativePath(fields[1], "noncompiled source"));
                    }
                    default -> throw new IllegalArgumentException("unknown manifest row kind: " + fields[0]);
                }
            } catch (IllegalArgumentException failure) {
                throw new IOException("invalid dependency manifest line " + lineNumber + ": " + failure.getMessage(), failure);
            }
        }
        ArrayList<JavaCompilationContext> built = new ArrayList<>();
        LinkedHashMap<String, Set<String>> excluded = new LinkedHashMap<>();
        for (MutableContext context : contexts.values()) {
            built.add(context.build());
            if (!context.excludedSources.isEmpty()) {
                excluded.put(context.id, Set.copyOf(context.excludedSources));
            }
        }
        try {
            return new JavaDependencyInputs(built, excluded, noncompiledSources);
        } catch (IllegalArgumentException failure) {
            throw new IOException("invalid dependency manifest graph: " + failure.getMessage(), failure);
        }
    }

    private static List<Path> canonicalPaths(List<Path> paths) {
        LinkedHashSet<Path> canonical = new LinkedHashSet<>();
        for (Path path : paths == null ? List.<Path>of() : paths) {
            if (path == null) {
                throw new IllegalArgumentException("dependency path must not be null");
            }
            canonical.add(path.toAbsolutePath().normalize());
        }
        return List.copyOf(canonical);
    }

    private static void requireFields(String[] fields, int count) {
        if (fields.length != count) {
            throw new IllegalArgumentException(fields[0] + " row requires " + count + " fields");
        }
    }

    private static MutableContext requireContext(Map<String, MutableContext> contexts, String id) {
        MutableContext context = contexts.get(token(id, "context id"));
        if (context == null) {
            throw new IllegalArgumentException("row refers to context before declaration: " + id);
        }
        return context;
    }

    private static String token(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }

    private static Integer integer(String value) {
        return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
    }

    private static boolean booleanValue(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException("boolean field must be true or false");
    }

    private static JavaResolutionPathRole pathRole(String kind) {
        return switch (kind) {
            case "classpath" -> JavaResolutionPathRole.CLASS_PATH;
            case "module-path" -> JavaResolutionPathRole.MODULE_PATH;
            case "processor-path" -> JavaResolutionPathRole.PROCESSOR_PATH;
            case "upgrade-module-path" -> JavaResolutionPathRole.UPGRADE_MODULE_PATH;
            case "platform-path" -> JavaResolutionPathRole.PLATFORM_PATH;
            default -> throw new IllegalArgumentException("unknown path role: " + kind);
        };
    }

    private static final class MutableContext {
        private final String id;
        private final String module;
        private final Integer source;
        private final Integer target;
        private final Integer release;
        private final boolean preview;
        private final String platform;
        private final List<String> sources = new ArrayList<>();
        private final List<String> generatedSources = new ArrayList<>();
        private final Set<String> excludedSources = new LinkedHashSet<>();
        private final Map<PathKey, List<Path>> paths = new LinkedHashMap<>();
        private final List<Path> outputs = new ArrayList<>();
        private final Set<String> upstream = new LinkedHashSet<>();

        private MutableContext(
                String id, String module, Integer source, Integer target, Integer release,
                boolean preview, String platform) {
            this.id = id;
            this.module = module;
            this.source = source;
            this.target = target;
            this.release = release;
            this.preview = preview;
            this.platform = platform;
        }

        private List<Path> path(JavaResolutionPathRole role, String qualifier) {
            return paths.computeIfAbsent(new PathKey(role, qualifier), ignored -> new ArrayList<>());
        }

        private JavaCompilationContext build() {
            List<JavaResolutionPath> resolutionPaths = paths.entrySet().stream()
                    .map(entry -> new JavaResolutionPath(
                            entry.getKey().role(), entry.getKey().qualifier(), entry.getValue()))
                    .toList();
            return new JavaCompilationContext(
                    id, module, sources, generatedSources, resolutionPaths, outputs, upstream,
                    new JavaCompilerSemantics(source, target, release, preview, platform));
        }
    }

    private record PathKey(JavaResolutionPathRole role, String qualifier) {
    }
}
