package metamodel.conformance.pipeline.adapter.cpp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.adapter.SourceObserver;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;
import metamodel.conformance.pipeline.model.SourceUnit;
import metamodel.conformance.pipeline.model.UnresolvedParent;
import metamodel.conformance.pipeline.util.Hashing;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Conservative compiler-backed C++ observer for class/struct direct inheritance.
 *
 * <p>The observer invokes Clang under a declared C++17 host profile. Unlike the initial
 * root-only profile, standard/platform headers are available so real source can be parsed.
 * Every external header actually consumed by a successful translation unit, together with
 * the concrete Clang executable, is fingerprinted into the canonical source set. The JSON
 * AST is consumed as a stream so large standard-library ASTs do not become an in-memory or
 * fixed-byte observation boundary.</p>
 *
 * <p>This is still not a project-build observer. Command-line macros, generated include
 * directories, non-default language flags, and compilation-database entries are not guessed.
 * Non-guard conditional preprocessing in project source, compiler failures under the declared
 * profile, dependent/template bases that cannot be resolved internally, and ambiguous source
 * identities therefore keep hierarchy evidence incomplete.</p>
 *
 * <p>The first C++ semantic slice emits class/struct classifiers and direct parent edges only.
 * Declaration ownership, signatures, visibility, inheritability, overrides, and independently
 * observed inherited membership remain incomplete.</p>
 */
public final class ClangCppObserver implements SourceObserver {
    public static final String ADAPTER_ID = "clang-cpp";
    public static final String ADAPTER_VERSION = "0.2.0";
    private static final String PROFILE_ID = "cxx17-host-fingerprinted";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(".cpp", ".cc", ".cxx", ".c++");
    private static final Set<String> HEADER_EXTENSIONS = Set.of(".hpp", ".hh", ".hxx", ".h", ".ipp", ".tpp");
    private static final Set<String> EXTENSIONS;
    private static final Pattern CLANG_VERSION = Pattern.compile(
            "(?i)(?:clang version|version)\\s+([0-9]+(?:\\.[0-9]+){0,2})");
    private static final Pattern DIRECTIVE = Pattern.compile(
            "(?m)^[ \\t]*#[ \\t]*(if|ifdef|ifndef|elif|define|endif)\\b([^\\r\\n]*)");
    private static final Pattern IF_NOT_DEFINED = Pattern.compile(
            "^!\\s*defined\\s*(?:\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)|\\s+([A-Za-z_][A-Za-z0-9_]*))\\s*$");
    private final String clangExecutable;

    static {
        Set<String> extensions = new HashSet<>(SOURCE_EXTENSIONS);
        extensions.addAll(HEADER_EXTENSIONS);
        EXTENSIONS = Set.copyOf(extensions);
    }

    public ClangCppObserver() {
        this(System.getProperty("metamodel.conformance.clang", "clang++"));
    }

    public ClangCppObserver(String clangExecutable) {
        if (clangExecutable == null || clangExecutable.isBlank()) {
            throw new IllegalArgumentException("clang executable must not be blank");
        }
        this.clangExecutable = clangExecutable;
    }

    @Override
    public Observation observe(Path sourceRoot, Set<String> externalParents) throws ObservationException {
        try {
            Path root = validateRoot(sourceRoot);
            List<Path> files = discoverCppFiles(root);
            if (files.isEmpty()) {
                throw new ObservationException("source root contains no supported C++ files: " + sourceRoot);
            }

            Map<String, SourceUnit> unitsByPath = new HashMap<>();
            Map<Path, String> physicalHashes = new HashMap<>();
            for (Path file : files) {
                addRootEvidence(root, file, unitsByPath, physicalHashes);
            }

            ClangIdentity clang = clangIdentity();
            String clangHash = recordPhysicalHash(clang.executable(), physicalHashes);
            unitsByPath.put("cpp-toolchain/clang-executable",
                    new SourceUnit(Language.CPP, "cpp-toolchain/clang-executable", clangHash));

            List<ObservationDiagnostic> diagnostics = new ArrayList<>();
            boolean hierarchyIncomplete = conditionalCompilationDiagnostics(root, files, diagnostics);
            Map<String, Draft> byDefinitionKey = new HashMap<>();
            Set<Path> coveredProjectFiles = new HashSet<>();

            List<Path> translationUnits = files.stream()
                    .filter(path -> isSourcePath(path.getFileName().toString()))
                    .toList();
            List<Path> initialUnits = translationUnits.isEmpty() ? files : translationUnits;

            for (Path file : initialUnits) {
                AnalysisResult result = analyzeUnit(root, file);
                hierarchyIncomplete |= incorporateResult(
                        root, file, result, byDefinitionKey, diagnostics,
                        unitsByPath, physicalHashes, coveredProjectFiles);
            }

            for (Path header : files.stream()
                    .filter(path -> !isSourcePath(path.getFileName().toString()))
                    .filter(path -> !coveredProjectFiles.contains(path))
                    .toList()) {
                AnalysisResult result = analyzeUnit(root, header);
                hierarchyIncomplete |= incorporateResult(
                        root, header, result, byDefinitionKey, diagnostics,
                        unitsByPath, physicalHashes, coveredProjectFiles);
            }

            List<Draft> drafts = byDefinitionKey.values().stream()
                    .sorted(Comparator.comparing(Draft::id)).toList();
            Map<String, List<Draft>> byQualifiedName = new HashMap<>();
            for (Draft draft : drafts) {
                byQualifiedName.computeIfAbsent(draft.qualifiedName(), ignored -> new ArrayList<>()).add(draft);
                ensureObservedSourceUnit(root, draft.path(), unitsByPath, physicalHashes);
            }

            Set<String> allowed = externalParents == null ? Set.of() : Set.copyOf(externalParents);
            List<ClassifierObservation> classifiers = new ArrayList<>();
            List<UnresolvedParent> unresolved = new ArrayList<>();
            for (Draft draft : drafts) {
                LinkedHashSet<String> parentIds = new LinkedHashSet<>();
                for (BaseDraft base : draft.bases()) {
                    List<Draft> internal = internalBaseCandidates(draft, base.targetName(), byQualifiedName);
                    if (internal.size() == 1) {
                        parentIds.add(internal.get(0).id());
                    } else if (internal.size() > 1) {
                        unresolved.add(new UnresolvedParent(
                                draft.id(), base.targetName(), draft.path(), positiveLine(base.line(), draft.line())));
                    } else if (base.templateContext()) {
                        unresolved.add(new UnresolvedParent(
                                draft.id(), base.targetName(), draft.path(), positiveLine(base.line(), draft.line())));
                        diagnostics.add(new ObservationDiagnostic(
                                DiagnosticKind.EVIDENCE_INCOMPLETE,
                                draft.path(), positiveLine(base.line(), draft.line()),
                                "C++ template-context base cannot be treated as an external root without "
                                        + "independent resolution: " + base.targetName()));
                    } else if (!allowedExternal(base.targetName(), allowed)) {
                        unresolved.add(new UnresolvedParent(
                                draft.id(), base.targetName(), draft.path(), positiveLine(base.line(), draft.line())));
                    }
                }
                classifiers.add(new ClassifierObservation(
                        draft.id(),
                        draft.qualifiedName(),
                        draft.namespaceName(),
                        ClassifierKind.CLASS,
                        draft.path(),
                        draft.line(),
                        draft.endLine(),
                        List.copyOf(parentIds),
                        List.of()));
            }

            if (!unresolved.isEmpty()) {
                hierarchyIncomplete = true;
            }

            String evidencePath = relativePath(root, files.get(0));
            diagnostics.add(new ObservationDiagnostic(
                    DiagnosticKind.EVIDENCE_INCOMPLETE,
                    evidencePath,
                    0,
                    "C++ adapter observes class/struct direct hierarchy under the declared " + PROFILE_ID
                            + " profile with compiler/include fingerprints; declaration ownership, signatures, "
                            + "visibility, inheritability, overrides, and inherited-member evidence remain incomplete."));

            verifyPhysicalEvidenceUnchanged(physicalHashes);

            EnumSet<EvidenceKind> completeEvidence = EnumSet.noneOf(EvidenceKind.class);
            if (!hierarchyIncomplete && unresolved.isEmpty()) {
                completeEvidence.add(EvidenceKind.HIERARCHY);
            }

            String adapterVersion = ADAPTER_VERSION
                    + "/clang-" + canonicalToken(clang.version())
                    + "/" + canonicalToken(clang.target())
                    + "/" + PROFILE_ID;
            return new Observation(
                    "8",
                    ADAPTER_ID,
                    adapterVersion,
                    List.copyOf(allowed),
                    completeEvidence,
                    new ArrayList<>(unitsByPath.values()),
                    classifiers,
                    List.of(),
                    unresolved,
                    diagnostics);
        } catch (ObservationException exception) {
            throw exception;
        } catch (IOException | RuntimeException failure) {
            throw new ObservationException("C++ observation failed: " + failure.getMessage(), failure);
        }
    }

    private boolean incorporateResult(
            Path root,
            Path source,
            AnalysisResult result,
            Map<String, Draft> byDefinitionKey,
            List<ObservationDiagnostic> diagnostics,
            Map<String, SourceUnit> unitsByPath,
            Map<Path, String> physicalHashes,
            Set<Path> coveredProjectFiles) throws IOException, ObservationException {
        String relative = relativePath(root, source);
        if (result.exitCode() != 0) {
            diagnostics.add(new ObservationDiagnostic(
                    DiagnosticKind.EVIDENCE_INCOMPLETE,
                    relative,
                    0,
                    "Clang could not analyze this unit under the declared " + PROFILE_ID
                            + " profile: " + normalizeDiagnostic(root, result.stderr())));
            return true;
        }
        if (result.astFailure() != null) {
            throw new ObservationException(
                    "Clang returned malformed JSON AST for " + relative, result.astFailure());
        }

        boolean incomplete = result.collector().incomplete();
        diagnostics.addAll(result.collector().diagnostics());
        if (result.dependencyError() != null) {
            incomplete = true;
            diagnostics.add(new ObservationDiagnostic(
                    DiagnosticKind.EVIDENCE_INCOMPLETE,
                    relative,
                    0,
                    "C++ include dependency set could not be fingerprinted: " + result.dependencyError()));
        } else {
            for (Path dependency : result.dependencies()) {
                if (dependency.startsWith(root)) {
                    coveredProjectFiles.add(dependency);
                    addRootEvidence(root, dependency, unitsByPath, physicalHashes);
                } else {
                    addExternalDependencyEvidence(dependency, unitsByPath, physicalHashes);
                }
            }
        }
        coveredProjectFiles.add(source);

        for (Draft draft : result.collector().drafts()) {
            Draft previous = byDefinitionKey.putIfAbsent(draft.definitionKey(), draft);
            if (previous != null && !previous.sameSemanticShape(draft)) {
                incomplete = true;
                diagnostics.add(new ObservationDiagnostic(
                        DiagnosticKind.EVIDENCE_INCOMPLETE,
                        draft.path(),
                        draft.line(),
                        "C++ declaration has translation-unit-dependent hierarchy under the declared profile: "
                                + draft.qualifiedName()));
            }
        }
        return incomplete;
    }

    private AnalysisResult analyzeUnit(Path root, Path source) throws ObservationException, IOException {
        Path stderr = Files.createTempFile("metamodel-clang-observer-", ".stderr");
        Path dependencies = Files.createTempFile("metamodel-clang-observer-", ".d");
        List<ObservationDiagnostic> localDiagnostics = new ArrayList<>();
        AstCollector collector = new AstCollector(root, localDiagnostics);
        Exception astFailure = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    clangExecutable,
                    "-std=c++17",
                    "-fsyntax-only",
                    "-fno-color-diagnostics",
                    "-Wno-pragma-once-outside-header",
                    "-I", root.toString(),
                    "-x", "c++",
                    "-MD", "-MF", dependencies.toString(), "-MT", "observation",
                    "-Xclang", "-ast-dump=json",
                    source.toString());
            builder.directory(root.toFile());
            builder.redirectError(stderr.toFile());
            Process process;
            try {
                process = builder.start();
            } catch (IOException failure) {
                throw new ObservationException(
                        "cannot start Clang executable '" + clangExecutable + "': " + failure.getMessage(), failure);
            }

            try (InputStream ast = process.getInputStream();
                 JsonParser parser = JSON.getFactory().createParser(ast)) {
                JsonToken token = parser.nextToken();
                if (token == JsonToken.START_OBJECT) {
                    collector.walk(parser, List.of(), null, false);
                    if (parser.nextToken() != null) {
                        astFailure = new IOException("multiple top-level JSON values in Clang AST dump");
                    }
                } else if (token != null) {
                    astFailure = new IOException("Clang AST dump does not begin with a JSON object");
                }
            } catch (Exception malformed) {
                astFailure = malformed;
                process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            }

            int exit;
            try {
                exit = process.waitFor();
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new ObservationException("C++ observation interrupted", interrupted);
            }
            String error = Files.readString(stderr, StandardCharsets.UTF_8);
            if (exit != 0) {
                return new AnalysisResult(exit, error, collector, List.of(), null, astFailure);
            }

            List<Path> dependencyPaths;
            String dependencyError = null;
            try {
                dependencyPaths = parseDependencyFile(root, dependencies);
            } catch (IOException | RuntimeException failure) {
                dependencyPaths = List.of();
                dependencyError = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
            }
            return new AnalysisResult(
                    exit, error, collector, dependencyPaths, dependencyError, astFailure);
        } finally {
            Files.deleteIfExists(stderr);
            Files.deleteIfExists(dependencies);
        }
    }

    private ClangIdentity clangIdentity() throws ObservationException, IOException {
        String versionOutput = commandOutput("--version");
        Matcher matcher = CLANG_VERSION.matcher(versionOutput);
        String version = matcher.find()
                ? matcher.group(1)
                : "unknown-" + Hashing.sha256(versionOutput).substring(0, 12);
        String target = commandOutput("-dumpmachine").strip();
        if (target.isBlank()) {
            throw new ObservationException("Clang returned a blank target triple");
        }
        return new ClangIdentity(version, target, resolveClangExecutable());
    }

    private String commandOutput(String argument) throws ObservationException, IOException {
        Process process;
        try {
            process = new ProcessBuilder(clangExecutable, argument)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException failure) {
            throw new ObservationException(
                    "cannot start Clang executable '" + clangExecutable + "': " + failure.getMessage(), failure);
        }
        byte[] output = process.getInputStream().readAllBytes();
        int exit;
        try {
            exit = process.waitFor();
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ObservationException("Clang identity probe interrupted", interrupted);
        }
        if (exit != 0) {
            throw new ObservationException("Clang identity probe '" + argument + "' exited " + exit);
        }
        return new String(output, StandardCharsets.UTF_8);
    }

    private Path resolveClangExecutable() throws IOException, ObservationException {
        Path direct = Path.of(clangExecutable);
        if (direct.isAbsolute() || clangExecutable.contains("/") || clangExecutable.contains("\\")) {
            if (Files.isRegularFile(direct) && Files.isExecutable(direct)) {
                return direct.toRealPath();
            }
            throw new ObservationException("Clang executable is not a regular executable file: " + clangExecutable);
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(Pattern.quote(File.pathSeparator))) {
                if (directory.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(directory).resolve(clangExecutable);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toRealPath();
                }
            }
        }
        throw new ObservationException("cannot resolve Clang executable on PATH: " + clangExecutable);
    }

    private static Path validateRoot(Path sourceRoot) throws IOException, ObservationException {
        if (sourceRoot == null || !Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new ObservationException("source root is not a directory: " + sourceRoot);
        }
        if (Files.isSymbolicLink(sourceRoot)) {
            throw new ObservationException("symbolic-link source roots are not accepted: " + sourceRoot);
        }
        return sourceRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static List<Path> discoverCppFiles(Path root) throws IOException, ObservationException {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream.filter(path -> isCppPath(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> relativePath(root, path)))
                    .toList();
        }
        for (Path file : files) {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new ObservationException("C++ source is not a regular non-symbolic-link file: " + file);
            }
            Path real = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.startsWith(root)) {
                throw new ObservationException("C++ source escapes declared root: " + file);
            }
        }
        return files;
    }

    private static boolean isCppPath(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private static boolean isSourcePath(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return SOURCE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private static boolean conditionalCompilationDiagnostics(
            Path root,
            List<Path> files,
            List<ObservationDiagnostic> diagnostics) throws IOException {
        boolean incomplete = false;
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            List<Directive> directives = directives(text);
            int guardStart = includeGuardStart(text, directives);
            for (int index = 0; index < directives.size(); index++) {
                Directive directive = directives.get(index);
                if (index == guardStart) {
                    continue;
                }
                if (isConditionalStart(directive.kind())) {
                    incomplete = true;
                    diagnostics.add(new ObservationDiagnostic(
                            DiagnosticKind.EVIDENCE_INCOMPLETE,
                            relativePath(root, file),
                            directive.line(),
                            "conditional preprocessing prevents a configuration-independent hierarchy claim "
                                    + "under the current source-only C++ profile"));
                    break;
                }
            }
        }
        return incomplete;
    }

    private static List<Directive> directives(String text) {
        List<Directive> result = new ArrayList<>();
        Matcher matcher = DIRECTIVE.matcher(text);
        while (matcher.find()) {
            int line = 1;
            for (int index = 0; index < matcher.start(); index++) {
                if (text.charAt(index) == '\n') {
                    line++;
                }
            }
            result.add(new Directive(
                    matcher.group(1), matcher.group(2).trim(), matcher.start(), matcher.end(), line));
        }
        return result;
    }

    private static int includeGuardStart(String text, List<Directive> directives) {
        if (directives.size() < 3 || !onlyCommentsAndWhitespace(text.substring(0, directives.get(0).start()))) {
            return -1;
        }
        Directive first = directives.get(0);
        String macro = guardMacro(first);
        if (macro == null) {
            return -1;
        }
        Directive second = directives.get(1);
        if (!"define".equals(second.kind()) || !macro.equals(firstIdentifier(second.arguments()))) {
            return -1;
        }

        int depth = 0;
        int closing = -1;
        for (int index = 0; index < directives.size(); index++) {
            String kind = directives.get(index).kind();
            if ("if".equals(kind) || "ifdef".equals(kind) || "ifndef".equals(kind)) {
                depth++;
            } else if ("endif".equals(kind)) {
                depth--;
                if (depth == 0) {
                    closing = index;
                    break;
                }
            }
        }
        if (closing < 0 || !onlyCommentsAndWhitespace(text.substring(directives.get(closing).end()))) {
            return -1;
        }
        return 0;
    }

    private static String guardMacro(Directive directive) {
        if ("ifndef".equals(directive.kind())) {
            return firstIdentifier(directive.arguments());
        }
        if ("if".equals(directive.kind())) {
            Matcher matcher = IF_NOT_DEFINED.matcher(directive.arguments());
            if (matcher.matches()) {
                return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            }
        }
        return null;
    }

    private static String firstIdentifier(String value) {
        Matcher matcher = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\b").matcher(value == null ? "" : value.trim());
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean onlyCommentsAndWhitespace(String value) {
        String withoutBlock = value.replaceAll("(?s)/\\*.*?\\*/", " ");
        String withoutLine = withoutBlock.replaceAll("(?m)//.*$", " ");
        return withoutLine.isBlank();
    }

    private static boolean isConditionalStart(String kind) {
        return "if".equals(kind) || "ifdef".equals(kind) || "ifndef".equals(kind) || "elif".equals(kind);
    }

    private static List<Path> parseDependencyFile(Path root, Path dependencyFile) throws IOException {
        if (!Files.isRegularFile(dependencyFile)) {
            throw new IOException("Clang did not emit a dependency file");
        }
        String text = Files.readString(dependencyFile, StandardCharsets.UTF_8)
                .replace("\\\r\n", "")
                .replace("\\\n", "");
        int separator = text.indexOf(':');
        if (separator < 0) {
            throw new IOException("malformed Clang dependency file");
        }
        List<String> tokens = splitMakefileWords(text.substring(separator + 1));
        List<Path> result = new ArrayList<>();
        for (String token : tokens) {
            Path path = Path.of(token);
            if (!path.isAbsolute()) {
                path = root.resolve(path);
            }
            if (!Files.exists(path)) {
                throw new IOException("dependency disappeared during observation");
            }
            Path real = path.toRealPath();
            if (Files.isRegularFile(real)) {
                result.add(real);
            }
        }
        return result.stream().distinct().sorted(Comparator.comparing(Path::toString)).toList();
    }

    private static List<String> splitMakefileWords(String value) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (escaped) {
                current.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (Character.isWhitespace(character)) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }
        if (escaped) {
            current.append('\\');
        }
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }

    private static void addRootEvidence(
            Path root,
            Path file,
            Map<String, SourceUnit> unitsByPath,
            Map<Path, String> physicalHashes) throws IOException, ObservationException {
        Path real = file.toRealPath();
        if (!real.startsWith(root) || !Files.isRegularFile(real)) {
            throw new ObservationException("C++ project dependency escapes the source root as source evidence: " + file);
        }
        String relative = relativePath(root, real);
        String hash = recordPhysicalHash(real, physicalHashes);
        putUnit(unitsByPath, new SourceUnit(Language.CPP, relative, hash));
    }

    private static void ensureObservedSourceUnit(
            Path root,
            String relative,
            Map<String, SourceUnit> unitsByPath,
            Map<Path, String> physicalHashes) throws IOException, ObservationException {
        if (!unitsByPath.containsKey(relative)) {
            addRootEvidence(root, root.resolve(relative), unitsByPath, physicalHashes);
        }
    }

    private static void addExternalDependencyEvidence(
            Path dependency,
            Map<String, SourceUnit> unitsByPath,
            Map<Path, String> physicalHashes) throws IOException, ObservationException {
        Path real = dependency.toRealPath();
        if (!Files.isRegularFile(real)) {
            throw new ObservationException("C++ external dependency is not a regular file: " + dependency);
        }
        String hash = recordPhysicalHash(real, physicalHashes);
        String virtualPath = "cpp-dependency/" + Hashing.sha256(real.toString().replace('\\', '/'));
        putUnit(unitsByPath, new SourceUnit(Language.CPP, virtualPath, hash));
    }

    private static String recordPhysicalHash(Path file, Map<Path, String> physicalHashes) throws IOException {
        Path real = file.toRealPath();
        String existing = physicalHashes.get(real);
        if (existing != null) {
            return existing;
        }
        String hash = Hashing.sha256(real);
        physicalHashes.put(real, hash);
        return hash;
    }

    private static void putUnit(Map<String, SourceUnit> unitsByPath, SourceUnit unit) throws ObservationException {
        SourceUnit previous = unitsByPath.putIfAbsent(unit.path(), unit);
        if (previous != null && !previous.equals(unit)) {
            throw new ObservationException("conflicting canonical C++ evidence path: " + unit.path());
        }
    }

    private static void verifyPhysicalEvidenceUnchanged(Map<Path, String> physicalHashes)
            throws IOException, ObservationException {
        for (Map.Entry<Path, String> entry : physicalHashes.entrySet()) {
            if (!Files.isRegularFile(entry.getKey())
                    || !entry.getValue().equals(Hashing.sha256(entry.getKey()))) {
                throw new ObservationException("C++ source/toolchain evidence changed during observation");
            }
        }
    }

    private static List<Draft> internalBaseCandidates(
            Draft owner,
            String rawTarget,
            Map<String, List<Draft>> byQualifiedName) {
        String target = normalizeBaseName(rawTarget);
        if (target == null) {
            return List.of();
        }
        if (target.startsWith("::")) {
            return List.copyOf(byQualifiedName.getOrDefault(target.substring(2), List.of()));
        }
        List<Draft> exact = byQualifiedName.get(target);
        if (exact != null && !exact.isEmpty()) {
            return List.copyOf(exact);
        }
        String[] ownerParts = owner.qualifiedName().split("::");
        for (int length = ownerParts.length - 1; length >= 1; length--) {
            String prefix = String.join("::", Arrays.copyOf(ownerParts, length));
            List<Draft> scoped = byQualifiedName.get(prefix + "::" + target);
            if (scoped != null && !scoped.isEmpty()) {
                return List.copyOf(scoped);
            }
        }
        return List.of();
    }

    private static boolean allowedExternal(String raw, Set<String> allowed) {
        String normalized = normalizeBaseName(raw);
        return allowed.contains(raw)
                || (normalized != null && (allowed.contains(normalized)
                || (normalized.startsWith("::") && allowed.contains(normalized.substring(2)))));
    }

    private static String normalizeBaseName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim()
                .replaceFirst("^(class|struct)\\s+", "")
                .replaceFirst("^typename\\s+", "")
                .trim();
        if (value.contains("<") || value.contains("decltype(") || value.contains("auto")) {
            return null;
        }
        return value.isBlank() ? null : value;
    }

    private static String normalizeDiagnostic(Path root, String stderr) {
        String text = stderr == null ? "" : stderr.strip();
        if (text.isEmpty()) {
            return "compiler exited without diagnostics";
        }
        text = text.replace(root.toString(), ".");
        if (text.length() > 4096) {
            text = text.substring(0, 4096);
        }
        return text.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").strip();
    }

    private static String relativePath(Path root, Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String stableId(String path, int line, String qualifiedName) {
        return "cls_" + Hashing.sha256("cpp\\0" + path + "\\0" + line + "\\0" + qualifiedName);
    }

    private static int positiveLine(int value, int fallback) {
        return value > 0 ? value : Math.max(1, fallback);
    }

    private static String canonicalToken(String value) {
        return value == null || value.isBlank()
                ? "unknown"
                : value.trim().replaceAll("[^A-Za-z0-9_.+-]", "_");
    }

    private static final class AstCollector {
        private final Path root;
        private final List<ObservationDiagnostic> diagnostics;
        private final List<Draft> drafts = new ArrayList<>();
        private final Map<Path, byte[]> bytes = new HashMap<>();
        private String lastLocationFile;
        private boolean incomplete;

        private AstCollector(Path root, List<ObservationDiagnostic> diagnostics) {
            this.root = root;
            this.diagnostics = diagnostics;
        }

        void walk(
                JsonParser parser,
                List<String> scope,
                String inheritedFile,
                boolean templateContext) throws IOException {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                throw new IOException("expected Clang AST object");
            }

            String kind = "";
            String name = "";
            String tag = "";
            boolean completeDefinition = false;
            boolean implicit = false;
            JsonNode location = null;
            JsonNode range = null;
            JsonNode bases = null;
            boolean processed = false;
            String file = inheritedFile;
            List<String> childScope = scope;
            boolean childTemplateContext = templateContext;

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    throw new IOException("malformed Clang AST object");
                }
                String field = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                switch (field) {
                    case "kind" -> kind = parser.getValueAsString("");
                    case "name" -> name = parser.getValueAsString("");
                    case "tagUsed" -> tag = parser.getValueAsString("");
                    case "completeDefinition" -> completeDefinition = parser.getValueAsBoolean(false);
                    case "isImplicit" -> implicit = parser.getValueAsBoolean(false);
                    case "loc" -> {
                        location = JSON.readTree(parser);
                        file = sourceFile(location, inheritedFile);
                    }
                    case "range" -> range = JSON.readTree(parser);
                    case "bases" -> {
                        if (file == null && inheritedFile == null) {
                            parser.skipChildren();
                        } else {
                            bases = JSON.readTree(parser);
                        }
                    }
                    case "inner" -> {
                        NodeResult node = processNode(
                                kind, name, tag, completeDefinition, implicit,
                                location, range, bases, scope, file, templateContext);
                        processed = true;
                        childScope = node.childScope();
                        childTemplateContext = node.templateContext();

                        if (valueToken == JsonToken.START_ARRAY
                                && shouldTraverseChildren(kind, file)) {
                            while (parser.nextToken() != JsonToken.END_ARRAY) {
                                if (parser.currentToken() == JsonToken.START_OBJECT) {
                                    walk(parser, childScope, file, childTemplateContext);
                                } else {
                                    parser.skipChildren();
                                }
                            }
                        } else {
                            parser.skipChildren();
                        }
                    }
                    default -> parser.skipChildren();
                }
            }

            if (!processed) {
                processNode(
                        kind, name, tag, completeDefinition, implicit,
                        location, range, bases, scope, file, templateContext);
            }
        }

        private NodeResult processNode(
                String kind,
                String name,
                String tag,
                boolean completeDefinition,
                boolean implicit,
                JsonNode location,
                JsonNode range,
                JsonNode basesNode,
                List<String> scope,
                String file,
                boolean templateContext) throws IOException {
            List<String> childScope = scope;
            boolean childTemplateContext = templateContext
                    || "ClassTemplateDecl".equals(kind)
                    || "FunctionTemplateDecl".equals(kind);

            if (file != null && "NamespaceDecl".equals(kind) && !name.isBlank()) {
                childScope = append(scope, name);
            } else if (file != null && isFunctionLike(kind) && !name.isBlank()) {
                int line = sourceLine(location, file);
                childScope = append(scope, "<" + name + "@" + Math.max(1, line) + ">");
            } else if (file != null
                    && "CXXRecordDecl".equals(kind)
                    && completeDefinition
                    && !implicit
                    && ("class".equals(tag) || "struct".equals(tag))) {
                int line = sourceLine(location, file);
                if (name.isBlank()) {
                    incomplete = true;
                    diagnostics.add(new ObservationDiagnostic(
                            DiagnosticKind.EVIDENCE_INCOMPLETE,
                            file,
                            Math.max(0, line),
                            "unnamed C++ class/struct definition is outside the current canonical identity boundary"));
                } else if (line < 1) {
                    incomplete = true;
                    diagnostics.add(new ObservationDiagnostic(
                            DiagnosticKind.EVIDENCE_INCOMPLETE,
                            file,
                            0,
                            "Clang declaration lacks a stable source line: " + name));
                } else {
                    String qualified = qualify(scope, name);
                    int endLine = sourceLine(range == null ? null : range.path("end"), file);
                    if (endLine < line) {
                        endLine = line;
                    }
                    List<BaseDraft> bases = bases(basesNode, file, line, templateContext);
                    String definitionKey = file + "\\0" + line + "\\0" + qualified;
                    drafts.add(new Draft(
                            definitionKey,
                            stableId(file, line, qualified),
                            qualified,
                            namespaceOf(qualified),
                            file,
                            line,
                            endLine,
                            bases));
                    childScope = append(scope, name);
                }
            }
            return new NodeResult(childScope, childTemplateContext);
        }

        private List<BaseDraft> bases(
                JsonNode basesNode,
                String file,
                int fallbackLine,
                boolean templateContext) throws IOException {
            if (basesNode == null || !basesNode.isArray()) {
                return List.of();
            }
            List<BaseDraft> result = new ArrayList<>();
            for (JsonNode base : basesNode) {
                String target = base.path("type").path("desugaredQualType").asText("");
                if (target.isBlank()) {
                    target = base.path("type").path("qualType").asText("");
                }
                String normalized = normalizeBaseName(target);
                int line = sourceLine(base.path("range").path("begin"), file);
                if (normalized == null) {
                    incomplete = true;
                    diagnostics.add(new ObservationDiagnostic(
                            DiagnosticKind.EVIDENCE_INCOMPLETE,
                            file,
                            positiveLine(line, fallbackLine),
                            "dependent or unsupported C++ base prevents complete hierarchy resolution: "
                                    + (target.isBlank() ? "<unknown>" : target)));
                } else {
                    result.add(new BaseDraft(normalized, positiveLine(line, fallbackLine), templateContext));
                }
            }
            return result.stream()
                    .sorted(Comparator.comparing(BaseDraft::targetName)
                            .thenComparingInt(BaseDraft::line)
                            .thenComparing(BaseDraft::templateContext))
                    .distinct().toList();
        }

        private boolean shouldTraverseChildren(String kind, String file) {
            return "TranslationUnitDecl".equals(kind) || file != null;
        }

        private String sourceFile(JsonNode location, String inheritedFile) throws IOException {
            String raw = locationFile(location);
            if (raw == null || raw.isBlank()) {
                if (inheritedFile != null) {
                    return inheritedFile;
                }
                raw = lastLocationFile;
            } else {
                lastLocationFile = raw;
            }
            if (raw == null || raw.isBlank()) {
                return null;
            }
            if (raw.startsWith("<") && raw.endsWith(">")) {
                return null;
            }
            Path candidate = Path.of(raw);
            if (!candidate.isAbsolute()) {
                candidate = root.resolve(candidate);
            }
            if (!Files.exists(candidate)) {
                return null;
            }
            Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.startsWith(root) || !Files.isRegularFile(real)) {
                return null;
            }
            return relativePath(root, real);
        }

        private static String locationFile(JsonNode location) {
            if (location == null || location.isMissingNode() || location.isNull()) {
                return null;
            }
            String direct = location.path("file").asText(null);
            if (direct != null) {
                return direct;
            }
            String expansion = location.path("expansionLoc").path("file").asText(null);
            if (expansion != null) {
                return expansion;
            }
            return location.path("spellingLoc").path("file").asText(null);
        }

        private int sourceLine(JsonNode location, String relativeFile) throws IOException {
            if (location == null || location.isMissingNode() || location.isNull()) {
                return 0;
            }
            int line = location.path("line").asInt(0);
            if (line > 0) {
                return line;
            }
            if (location.has("expansionLoc")) {
                line = sourceLine(location.path("expansionLoc"), relativeFile);
                if (line > 0) {
                    return line;
                }
            }
            if (location.has("spellingLoc")) {
                line = sourceLine(location.path("spellingLoc"), relativeFile);
                if (line > 0) {
                    return line;
                }
            }
            int offset = location.path("offset").asInt(-1);
            if (offset < 0 || relativeFile == null) {
                return 0;
            }
            Path file = root.resolve(relativeFile).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                return 0;
            }
            byte[] content;
            try {
                content = bytes.computeIfAbsent(file, path -> {
                    try {
                        return Files.readAllBytes(path);
                    } catch (IOException failure) {
                        throw new UncheckedIo(failure);
                    }
                });
            } catch (UncheckedIo failure) {
                throw (IOException) failure.getCause();
            }
            int bounded = Math.min(offset, content.length);
            int result = 1;
            for (int index = 0; index < bounded; index++) {
                if (content[index] == '\n') {
                    result++;
                }
            }
            return result;
        }

        List<Draft> drafts() {
            return List.copyOf(drafts);
        }

        List<ObservationDiagnostic> diagnostics() {
            return List.copyOf(diagnostics);
        }

        boolean incomplete() {
            return incomplete;
        }

        private static boolean isFunctionLike(String kind) {
            return "FunctionDecl".equals(kind)
                    || "FunctionTemplateDecl".equals(kind)
                    || "CXXMethodDecl".equals(kind)
                    || "CXXConstructorDecl".equals(kind)
                    || "CXXConversionDecl".equals(kind);
        }

        private static List<String> append(List<String> scope, String value) {
            List<String> result = new ArrayList<>(scope);
            result.add(value);
            return List.copyOf(result);
        }

        private static String qualify(List<String> scope, String name) {
            return scope.isEmpty() ? name : String.join("::", scope) + "::" + name;
        }

        private static String namespaceOf(String qualified) {
            int separator = qualified.lastIndexOf("::");
            return separator < 0 ? "<global>" : qualified.substring(0, separator);
        }
    }

    private record AnalysisResult(
            int exitCode,
            String stderr,
            AstCollector collector,
            List<Path> dependencies,
            String dependencyError,
            Exception astFailure) {
    }

    private record ClangIdentity(String version, String target, Path executable) {
    }

    private record Directive(String kind, String arguments, int start, int end, int line) {
    }

    private record NodeResult(List<String> childScope, boolean templateContext) {
    }

    private record BaseDraft(String targetName, int line, boolean templateContext) {
    }

    private record Draft(
            String definitionKey,
            String id,
            String qualifiedName,
            String namespaceName,
            String path,
            int line,
            int endLine,
            List<BaseDraft> bases) {
        boolean sameSemanticShape(Draft other) {
            return qualifiedName.equals(other.qualifiedName)
                    && namespaceName.equals(other.namespaceName)
                    && path.equals(other.path)
                    && line == other.line
                    && endLine == other.endLine
                    && bases.equals(other.bases);
        }
    }

    private static final class UncheckedIo extends RuntimeException {
        private UncheckedIo(IOException cause) {
            super(cause);
        }
    }
}
