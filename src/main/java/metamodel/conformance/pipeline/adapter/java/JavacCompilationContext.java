package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reconstructs one javac compilation context from build-observed semantics. */
final class JavacCompilationContext implements AutoCloseable {
    private final boolean complete;
    private final JavaCompilationContext context;
    private final String classpath;
    private final List<String> options;
    private final List<ObservationDiagnostic> diagnostics;
    private final Path temporaryDirectory;

    private JavacCompilationContext(
            boolean complete,
            JavaCompilationContext context,
            String classpath,
            List<String> options,
            List<ObservationDiagnostic> diagnostics,
            Path temporaryDirectory) {
        this.complete = complete;
        this.context = context;
        this.classpath = classpath;
        this.options = List.copyOf(options);
        this.diagnostics = List.copyOf(diagnostics);
        this.temporaryDirectory = temporaryDirectory;
    }

    static JavacCompilationContext prepare(
            Path root,
            JavaCompilationContext context,
            Map<String, List<Path>> filesByContext,
            JavaDependencyInputs inputs) throws IOException {
        JavaDependencyInputs actual = inputs == null ? JavaDependencyInputs.none() : inputs;
        Path temporary = Files.createTempDirectory("metamodel-conformance-javac-context-");
        Path emptyClasspath = Files.createDirectory(temporary.resolve("empty-classpath"));
        Map<String, CompiledOutput> compiled = new LinkedHashMap<>();
        List<JavaCompilationContext> upstreamContexts = actual.context(context.id()) == null
                ? List.of()
                : actual.upstreamTopological(context.id()).stream()
                        .map(actual::context)
                        .filter(java.util.Objects::nonNull)
                        .toList();

        for (JavaCompilationContext upstream : upstreamContexts) {
            List<Path> upstreamFiles = filesByContext.getOrDefault(upstream.id(), List.of());
            if (upstreamFiles.isEmpty()) {
                continue;
            }
            Path output = temporary.resolve("context-" + metamodel.conformance.pipeline.util.Hashing.sha256(upstream.id()));
            Files.createDirectories(output);
            CompileResult result = compile(
                    root,
                    upstream,
                    upstreamFiles,
                    visibleOutputs(actual, upstream.id(), compiled),
                    output);
            if (!result.complete()) {
                return new JavacCompilationContext(
                        false,
                        context,
                        "",
                        List.of(),
                        result.diagnostics(),
                        temporary);
            }
            compiled.put(upstream.id(), new CompiledOutput(upstream, output));
        }

        List<CompiledOutput> visible = visibleOutputs(actual, context.id(), compiled);
        String classpath;
        List<String> options;
        try {
            classpath = classpath(context, visible, emptyClasspath);
            options = compilerOptions(context, classpath, visible);
        } catch (IllegalArgumentException failure) {
            return new JavacCompilationContext(
                    false,
                    context,
                    "",
                    List.of(),
                    List.of(new ObservationDiagnostic(
                            DiagnosticKind.EVIDENCE_INCOMPLETE,
                            context.allSourceRoots().get(0),
                            0,
                            failure.getMessage())),
                    temporary);
        }
        return new JavacCompilationContext(true, context, classpath, options, List.of(), temporary);
    }

    boolean complete() {
        return complete;
    }

    JavaCompilationContext context() {
        return context;
    }

    String classpath() {
        return classpath;
    }

    List<String> options() {
        return options;
    }

    List<ObservationDiagnostic> diagnostics() {
        return diagnostics;
    }

    @Override
    public void close() throws IOException {
        if (!Files.exists(temporaryDirectory)) {
            return;
        }
        try (var paths = Files.walk(temporaryDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static CompileResult compile(
            Path root,
            JavaCompilationContext context,
            List<Path> files,
            List<CompiledOutput> upstreamOutputs,
            Path output) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new CompileResult(false, List.of(new ObservationDiagnostic(
                    DiagnosticKind.EVIDENCE_INCOMPLETE,
                    relativePath(root, files.get(0)),
                    0,
                    "JDK compiler is unavailable; upstream compilation context was not observed")));
        }
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                collector, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> sources = fileManager.getJavaFileObjectsFromPaths(files);
            String classpath = classpath(context, upstreamOutputs, output);
            ArrayList<String> options = new ArrayList<>(compilerOptions(context, classpath, upstreamOutputs));
            options.add("-d");
            options.add(output.toString());
            Boolean success = compiler.getTask(null, fileManager, collector, options, null, sources).call();
            boolean errors = collector.getDiagnostics().stream()
                    .anyMatch(item -> item.getKind() == Diagnostic.Kind.ERROR);
            if (!Boolean.TRUE.equals(success) || errors) {
                return new CompileResult(false, evidenceDiagnostics(
                        root, files, collector,
                        "javac could not compile an upstream compilation context"));
            }
            return new CompileResult(true, List.of());
        } catch (IllegalArgumentException failure) {
            String message = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            return new CompileResult(false, List.of(new ObservationDiagnostic(
                    DiagnosticKind.EVIDENCE_INCOMPLETE, relativePath(root, files.get(0)), 0,
                    "javac rejected compiler options for upstream context " + context.id() + ": "
                            + message.replace(root.toAbsolutePath().normalize().toString(), "."))));
        }
    }

    private static List<String> compilerOptions(JavaCompilationContext context, String classpath,
            List<CompiledOutput> upstreamOutputs) {
        ArrayList<String> options = new ArrayList<>(List.of("-proc:none", "-implicit:none", "-Xlint:none"));
        JavaCompilerSemantics semantics = context.compilerSemantics();
        if (semantics.releaseLevel() != null) {
            options.add("--release");
            options.add(Integer.toString(semantics.releaseLevel()));
        } else {
            if (semantics.sourceLevel() != null) {
                options.add("-source");
                options.add(Integer.toString(semantics.sourceLevel()));
            }
            if (semantics.targetLevel() != null) {
                options.add("-target");
                options.add(Integer.toString(semantics.targetLevel()));
            }
        }
        if (semantics.previewEnabled()) {
            options.add("--enable-preview");
        }
        if (!classpath.isBlank()) {
            options.add("-classpath");
            options.add(classpath);
        }
        appendPath(options, "--module-path", resolvedEntries(context, JavaResolutionPathRole.MODULE_PATH, upstreamOutputs));
        appendPath(options, "--upgrade-module-path",
                resolvedEntries(context, JavaResolutionPathRole.UPGRADE_MODULE_PATH, upstreamOutputs));
        appendPath(options, "-processorpath", resolvedEntries(context, JavaResolutionPathRole.PROCESSOR_PATH, upstreamOutputs));
        List<Path> platform = context.resolutionEntries(JavaResolutionPathRole.PLATFORM_PATH);
        if (platform.size() > 1) {
            throw new IllegalArgumentException("multiple PLATFORM_PATH entries are not representable by javac --system");
        }
        if (semantics.releaseLevel() != null && !platform.isEmpty()) {
            throw new IllegalArgumentException("--release cannot be combined with an explicit PLATFORM_PATH");
        }
        if (platform.size() == 1) {
            options.add("--system");
            options.add(platform.get(0).toString());
        }
        for (JavaResolutionPath path : context.resolutionPaths()) {
            if (path.role() == JavaResolutionPathRole.PATCH_MODULE && !path.entries().isEmpty()) {
                options.add("--patch-module");
                options.add(path.qualifier() + "=" + join(replaceOutputs(path.entries(), upstreamOutputs)));
            }
        }
        return List.copyOf(options);
    }

    private static void appendPath(List<String> options, String option, List<Path> entries) {
        if (!entries.isEmpty()) {
            options.add(option);
            options.add(join(entries));
        }
    }

    private static String classpath(JavaCompilationContext context, List<CompiledOutput> upstreamOutputs,
            Path emptyClasspath) {
        LinkedHashSet<Path> entries = new LinkedHashSet<>();
        // An upstream edge without an explicit path retains the compatibility
        // classpath role. When a role/position is observed, preserve it exactly.
        Set<Path> explicit = context.resolutionPaths().stream().flatMap(path -> path.entries().stream())
                .collect(java.util.stream.Collectors.toSet());
        for (CompiledOutput output : upstreamOutputs) {
            if (output.context().outputs().stream().noneMatch(explicit::contains)) {
                entries.add(output.directory());
            }
        }
        entries.addAll(resolvedEntries(context, JavaResolutionPathRole.CLASS_PATH, upstreamOutputs));
        // Never fall back to the analyzer process's ambient classpath.
        if (entries.isEmpty()) entries.add(emptyClasspath);
        return join(List.copyOf(entries));
    }

    private static List<CompiledOutput> visibleOutputs(JavaDependencyInputs inputs, String contextId,
            Map<String, CompiledOutput> compiled) {
        return inputs.upstreamTopological(contextId).stream().map(compiled::get)
                .filter(java.util.Objects::nonNull).toList();
    }

    private static List<Path> resolvedEntries(JavaCompilationContext context, JavaResolutionPathRole role,
            List<CompiledOutput> outputs) {
        return replaceOutputs(context.resolutionEntries(role), outputs);
    }

    private static List<Path> replaceOutputs(List<Path> entries, List<CompiledOutput> outputs) {
        Map<Path, Path> replacements = new LinkedHashMap<>();
        for (CompiledOutput output : outputs) {
            for (Path declared : output.context().outputs()) {
                Path previous = replacements.putIfAbsent(declared, output.directory());
                if (previous != null && !previous.equals(output.directory())) {
                    throw new IllegalArgumentException("ambiguous upstream output ownership: " + declared);
                }
            }
        }
        return entries.stream().map(path -> replacements.getOrDefault(path, path)).toList();
    }

    private record CompiledOutput(JavaCompilationContext context, Path directory) {}

    private static String join(List<Path> entries) {
        return entries.stream().map(Path::toString)
                .collect(java.util.stream.Collectors.joining(File.pathSeparator));
    }

    private static List<ObservationDiagnostic> evidenceDiagnostics(
            Path root,
            List<Path> files,
            DiagnosticCollector<JavaFileObject> collector,
            String fallbackMessage) {
        String fallbackPath = relativePath(root, files.get(0));
        List<ObservationDiagnostic> diagnostics = collector.getDiagnostics().stream()
                .filter(item -> item.getKind() == Diagnostic.Kind.ERROR)
                .map(item -> new ObservationDiagnostic(
                        DiagnosticKind.EVIDENCE_INCOMPLETE,
                        diagnosticPath(root, item.getSource(), fallbackPath),
                        item.getLineNumber() < 0 ? 0 : Math.toIntExact(item.getLineNumber()),
                        normalizedMessage(root, item.getMessage(Locale.ROOT), fallbackMessage)))
                .distinct()
                .sorted(Comparator.comparing(ObservationDiagnostic::sourcePath)
                        .thenComparingInt(ObservationDiagnostic::line)
                        .thenComparing(ObservationDiagnostic::message))
                .toList();
        return diagnostics.isEmpty()
                ? List.of(new ObservationDiagnostic(DiagnosticKind.EVIDENCE_INCOMPLETE, fallbackPath, 0, fallbackMessage))
                : diagnostics;
    }

    private static String diagnosticPath(Path root, JavaFileObject source, String fallback) {
        if (source == null) {
            return fallback;
        }
        try {
            Path path = Path.of(source.toUri()).toRealPath(LinkOption.NOFOLLOW_LINKS);
            return path.startsWith(root) ? relativePath(root, path) : fallback;
        } catch (IOException | RuntimeException ignored) {
            return fallback;
        }
    }

    private static String normalizedMessage(Path root, String message, String fallback) {
        String text = message == null || message.isBlank() ? fallback : message;
        return text.replace(root.toAbsolutePath().normalize().toString(), ".")
                .replace('\r', ' ').trim();
    }

    private static String relativePath(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private record CompileResult(boolean complete, List<ObservationDiagnostic> diagnostics) {
    }
}
