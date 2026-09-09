package metamodel.conformance.pipeline.adapter.java;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Build-system-neutral Java compilation context. */
record JavaCompilationContext(
        String id,
        String moduleKey,
        List<String> sourceRoots,
        List<String> generatedSourceRoots,
        List<JavaResolutionPath> resolutionPaths,
        List<Path> outputs,
        Set<String> upstreamContextIds,
        JavaCompilerSemantics compilerSemantics) {
    JavaCompilationContext {
        id = requireToken(id, "compilation context id");
        moduleKey = canonicalModuleKey(moduleKey);
        sourceRoots = canonicalRoots(sourceRoots, "source root");
        generatedSourceRoots = canonicalRoots(generatedSourceRoots, "generated source root");
        if (sourceRoots.isEmpty() && generatedSourceRoots.isEmpty()) {
            throw new IllegalArgumentException("compilation context must expose at least one source root");
        }
        List<JavaResolutionPath> canonicalPaths = new ArrayList<>(
                resolutionPaths == null ? List.of() : resolutionPaths);
        canonicalPaths.sort(Comparator.comparing((JavaResolutionPath path) -> path.role().name())
                .thenComparing(JavaResolutionPath::qualifier));
        for (int i = 1; i < canonicalPaths.size(); i++) {
            JavaResolutionPath previous = canonicalPaths.get(i - 1);
            JavaResolutionPath current = canonicalPaths.get(i);
            if (previous.role() == current.role() && previous.qualifier().equals(current.qualifier())) {
                throw new IllegalArgumentException("duplicate resolution path role/qualifier in context " + id);
            }
        }
        resolutionPaths = List.copyOf(canonicalPaths);
        LinkedHashSet<Path> canonicalOutputs = new LinkedHashSet<>();
        for (Path output : outputs == null ? List.<Path>of() : outputs) {
            if (output == null) {
                throw new IllegalArgumentException("compilation output must not be null");
            }
            canonicalOutputs.add(output.toAbsolutePath().normalize());
        }
        outputs = List.copyOf(canonicalOutputs);
        LinkedHashSet<String> upstream = new LinkedHashSet<>();
        for (String candidate : upstreamContextIds == null ? Set.<String>of() : upstreamContextIds) {
            String canonical = requireToken(candidate, "upstream context id");
            if (canonical.equals(id)) {
                throw new IllegalArgumentException("compilation context cannot depend on itself: " + id);
            }
            upstream.add(canonical);
        }
        upstreamContextIds = Set.copyOf(upstream);
        compilerSemantics = compilerSemantics == null ? JavaCompilerSemantics.unknown() : compilerSemantics;
    }

    List<String> allSourceRoots() {
        ArrayList<String> roots = new ArrayList<>(sourceRoots);
        roots.addAll(generatedSourceRoots);
        return List.copyOf(roots);
    }

    List<Path> resolutionEntries(JavaResolutionPathRole role) {
        return resolutionPaths.stream()
                .filter(path -> path.role() == role)
                .flatMap(path -> path.entries().stream())
                .toList();
    }

    boolean ownsSourcePath(String sourcePath) {
        String canonical = canonicalRelativePath(sourcePath, "source path");
        return allSourceRoots().stream().anyMatch(root -> contains(root, canonical));
    }

    private static boolean contains(String root, String path) {
        return ".".equals(root) || path.equals(root) || path.startsWith(root + "/");
    }

    private static List<String> canonicalRoots(List<String> roots, String label) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String root : roots == null ? List.<String>of() : roots) {
            result.add(canonicalRelativePath(root, label));
        }
        return List.copyOf(result);
    }

    static String canonicalModuleKey(String value) {
        if (value == null || value.isBlank() || ".".equals(value.trim())) {
            return ".";
        }
        return canonicalRelativePath(value, "module key");
    }

    static String canonicalRelativePath(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        String path = value.trim().replace('\\', '/');
        if (".".equals(path)) {
            return ".";
        }
        if (path.startsWith("/") || path.endsWith("/") || path.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException(label + " must be repository-relative: " + value);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(label + " must be canonical: " + value);
            }
        }
        return path;
    }

    private static String requireToken(String value, String label) {
        if (value == null || value.isBlank() || value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(label + " must not be blank or contain control separators");
        }
        return value.trim();
    }
}
