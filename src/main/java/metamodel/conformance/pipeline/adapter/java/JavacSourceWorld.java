package metamodel.conformance.pipeline.adapter.java;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import metamodel.conformance.pipeline.util.Hashing;
import javax.tools.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** One request-owned attributed source world; never a cross-observation cache. */
final class JavacSourceWorld implements AutoCloseable {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private final Thread owner = Thread.currentThread();
    private final JavacBinaryInputs manager;
    private final JavacTask task;
    private final DiagnosticCollector<JavaFileObject> diagnostics;
    private final List<CompilationUnitTree> parsed;
    private final JavacMethodBodyCensus census;
    private final boolean retained;
    private boolean analyzed;
    private boolean disposed;
    private Throwable analysisFailure;

    private JavacSourceWorld(Path root, List<Path> files, List<String> options,
            JavaCompiler compiler, boolean retained) throws IOException {
        this.retained = retained;
        diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager standard = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        manager = new JavacBinaryInputs(standard);
        try {
            task = (JavacTask) compiler.getTask(null, manager, diagnostics, options, null,
                    standard.getJavaFileObjectsFromPaths(files));
            List<CompilationUnitTree> units = new ArrayList<>();
            task.parse().forEach(units::add);
            parsed = List.copyOf(units);
            // Attribution inserts synthetic declarations: the syntax census must precede it.
            census = JavacMethodBodyCensus.capture(root, files, parsed, Trees.instance(task),
                    diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR));
        } catch (IOException | RuntimeException | Error failure) {
            try { manager.close(); } catch (IOException | RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    static Scope scope() { return new Scope(); }

    static JavacCompilationContext prepare(Path root, JavaCompilationContext context,
            Map<String,List<Path>> files, JavaDependencyInputs inputs,
            Preparation fresh) throws IOException {
        Scope scope = CURRENT.get();
        if (scope == null) return fresh.create();
        // Raw provider arguments can add filesystem lookup boundaries outside the
        // typed path inventory. Preserve fresh compilation until those are captured.
        if (!context.compilerSemantics().compilerArgs().isEmpty()
                || inputs.contexts().stream().anyMatch(c -> !c.compilerSemantics().compilerArgs().isEmpty()))
            return fresh.create();
        Map<String,List<Path>> copiedFiles = new LinkedHashMap<>();
        files.forEach((id, paths) -> copiedFiles.put(id, List.copyOf(paths)));
        Map<String,List<Path>> immutableFiles = Map.copyOf(copiedFiles);
        String fingerprint = fingerprint(immutableFiles, inputs);
        // Unfingerprintable boundaries use the existing fresh-context behavior.
        if (fingerprint == null) return fresh.create();
        PreparationKey key = new PreparationKey(root.toRealPath(), context, new Identity(inputs),
                immutableFiles, fingerprint);
        JavacCompilationContext result = scope.contexts.get(key);
        if (result == null) {
            result = fresh.create();
            result.retainForScope();
            scope.contexts.put(key, result);
            scope.inputs.put(key, inputs);
        }
        return result;
    }

    static JavacSourceWorld open(Path root, List<Path> files, JavacCompilationContext context,
            JavaCompiler compiler) throws IOException {
        List<String> options = context.options();
        Scope scope = CURRENT.get();
        boolean managed = scope != null && scope.contexts.values().stream()
                .anyMatch(c -> c == context && c.complete());
        if (!managed) return new JavacSourceWorld(root, files, options, compiler, false);
        WorldKey key = new WorldKey(root.toRealPath(), List.copyOf(files), context);
        JavacSourceWorld world = scope.worlds.get(key);
        if (world == null) {
            world = new JavacSourceWorld(root, files, options, compiler, true);
            scope.worlds.put(key, world);
        } else {
            world.checkThread();
            world.manager.verifyUnchanged();
        }
        return world;
    }

    JavacTask task() { checkThread(); return task; }
    List<CompilationUnitTree> parsed() { checkThread(); return parsed; }
    DiagnosticCollector<JavaFileObject> diagnostics() { checkThread(); return diagnostics; }
    JavacMethodBodyCensus census() { checkThread(); return census; }

    void analyze() throws IOException {
        checkThread();
        if (analysisFailure != null) rethrow(analysisFailure);
        if (!analyzed) {
            try {
                task.analyze();
                manager.verifyUnchanged();
                analyzed = true;
            } catch (IOException | RuntimeException | Error failure) {
                analysisFailure = failure;
                throw failure;
            }
        }
    }

    private static void rethrow(Throwable failure) throws IOException {
        if (failure instanceof IOException io) throw io;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new IllegalStateException(failure);
    }
    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("javac world used by another thread");
        if (disposed) throw new IllegalStateException("javac world used after its observation closed");
    }
    @Override public void close() throws IOException { checkThread(); if (!retained) dispose(); }
    private void dispose() throws IOException {
        if (disposed) return;
        disposed = true;
        Throwable failure = null;
        try { manager.verifyUnchanged(); } catch (IOException | RuntimeException | Error error) { failure = error; }
        try { manager.close(); } catch (IOException | RuntimeException | Error error) {
            if (failure == null) failure = error; else failure.addSuppressed(error);
        }
        if (failure != null) rethrow(failure);
    }

    private static String fingerprint(Map<String,List<Path>> files, JavaDependencyInputs inputs) throws IOException {
        TreeSet<Path> paths = new TreeSet<>();
        files.values().forEach(paths::addAll);
        for (JavaCompilationContext context : inputs.contexts())
            for (JavaResolutionPath path : context.resolutionPaths()) paths.addAll(path.entries());
        StringBuilder identity = new StringBuilder();
        for (Path path : paths) {
            Path absolute = path.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(absolute)) return null;
            if (Files.notExists(absolute, LinkOption.NOFOLLOW_LINKS)) {
                // A declared upstream output may be absent and reconstructed privately.
                // Its appearance during this observation must still invalidate reuse.
                identity.append(absolute).append('\0').append("ABSENT").append('\n');
                continue;
            }
            if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) return null;
            List<Path> entries;
            if (Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
                try (var walk = Files.walk(absolute)) { entries = walk.sorted().toList(); }
            } else entries = List.of(absolute);
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry)) return null;
                identity.append(entry).append('\0');
                if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    // javac may choose source versus class according to modification time.
                    identity.append(Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS))
                            .append('\0').append(Hashing.sha256(entry));
                } else if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) return null;
                identity.append('\n');
            }
        }
        return Hashing.sha256(identity.toString());
    }

    @FunctionalInterface interface Preparation { JavacCompilationContext create() throws IOException; }
    private record Identity(JavaDependencyInputs value) {
        @Override public boolean equals(Object other) { return other instanceof Identity id && value == id.value; }
        @Override public int hashCode() { return System.identityHashCode(value); }
    }
    private record PreparationKey(Path root, JavaCompilationContext context, Identity inputs,
                                  Map<String,List<Path>> files, String fingerprint) { }
    private record WorldKey(Path root, List<Path> files, JavacCompilationContext context) { }

    static final class Scope implements AutoCloseable {
        private final Scope previous = CURRENT.get();
        private final Thread owner = Thread.currentThread();
        private final Map<PreparationKey,JavacCompilationContext> contexts = new LinkedHashMap<>();
        private final Map<PreparationKey,JavaDependencyInputs> inputs = new LinkedHashMap<>();
        private final Map<WorldKey,JavacSourceWorld> worlds = new LinkedHashMap<>();
        private boolean closed;
        private Scope() { CURRENT.set(this); }
        @Override public void close() throws IOException {
            if (closed) return;
            if (Thread.currentThread() != owner || CURRENT.get() != this)
                throw new IllegalStateException("javac scope closed out of order or on another thread");
            Throwable failure = null;
            try {
                for (PreparationKey key : contexts.keySet()) {
                    try {
                        if (!key.fingerprint.equals(fingerprint(key.files, inputs.get(key))))
                            throw new IOException("Java compilation-world inputs changed during observation");
                    } catch (IOException | RuntimeException | Error error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
                }
                for (JavacSourceWorld world : worlds.values()) {
                    try { world.dispose(); } catch (IOException | RuntimeException | Error error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
                }
                for (JavacCompilationContext context : contexts.values()) {
                    try { context.dispose(); } catch (IOException | RuntimeException | Error error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
                }
            } finally {
                closed = true;
                if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            }
            if (failure != null) rethrow(failure);
        }
    }
}
