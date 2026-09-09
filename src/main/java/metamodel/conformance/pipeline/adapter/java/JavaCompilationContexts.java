package metamodel.conformance.pipeline.adapter.java;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Assigns repository source files to explicit build-observed compilation contexts. */
final class JavaCompilationContexts {
    private JavaCompilationContexts() {
    }

    static Grouping group(Path root, List<Path> files, JavaDependencyInputs inputs) {
        JavaDependencyInputs actual = inputs == null ? JavaDependencyInputs.none() : inputs;
        List<JavaCompilationContext> contexts = actual.contexts();
        if (contexts.isEmpty()) {
            contexts = List.of(sourceOnlyContext());
        }
        LinkedHashMap<String, List<Path>> grouped = new LinkedHashMap<>();
        contexts.stream().sorted(Comparator.comparing(JavaCompilationContext::id))
                .forEach(context -> grouped.put(context.id(), new ArrayList<>()));
        ArrayList<Path> unowned = new ArrayList<>();
        for (Path file : files) {
            String relative = relativePath(root, file);
            List<JavaCompilationContext> owners = contexts.stream()
                    .filter(context -> context.ownsSourcePath(relative)).toList();
            if (owners.isEmpty()) {
                unowned.add(file);
                continue;
            }
            owners.forEach(context -> grouped.get(context.id()).add(file));
        }
        LinkedHashMap<String, List<Path>> immutable = new LinkedHashMap<>();
        grouped.forEach((id, owned) -> {
            if (!owned.isEmpty()) {
                immutable.put(id, List.copyOf(owned));
            }
        });
        return new Grouping(List.copyOf(contexts), Map.copyOf(immutable), List.copyOf(unowned));
    }

    static JavaCompilationContext sourceOnlyContext() {
        int release = Runtime.version().feature();
        return new JavaCompilationContext(
                "source-only-runtime",
                ".",
                List.of("."),
                List.of(),
                List.of(),
                List.of(),
                Set.of(),
                new JavaCompilerSemantics(null, null, release, false, "runtime-jdk-" + release));
    }

    static List<JavaCompilationContext> owners(JavaDependencyInputs inputs, String sourcePath) {
        if (inputs == null || inputs.contexts().isEmpty()) {
            return List.of(sourceOnlyContext());
        }
        return inputs.contextsForSourcePath(sourcePath);
    }

    static boolean belongsTo(JavaCompilationContext context, String sourcePath) {
        return context.ownsSourcePath(sourcePath);
    }

    static String relativePath(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    record Grouping(
            List<JavaCompilationContext> contexts,
            Map<String, List<Path>> filesByContext,
            List<Path> unownedFiles) {
        JavaCompilationContext context(String id) {
            return contexts.stream().filter(context -> context.id().equals(id)).findFirst().orElse(null);
        }
    }
}
