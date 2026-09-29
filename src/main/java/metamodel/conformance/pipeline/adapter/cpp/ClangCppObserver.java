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
import metamodel.conformance.pipeline.model.Inheritability;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.MemberScope;
import metamodel.conformance.pipeline.model.MemberVisibility;
import metamodel.conformance.pipeline.model.MethodAbstraction;
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
    public static final String ADAPTER_VERSION = "0.3.0";
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

            List<MemberObservation> members = new ArrayList<>();
            Map<String, List<String>> memberKeysByOwner = new HashMap<>();
            Set<String> memberTechnicalKeys = new HashSet<>();
            boolean declarationIncomplete = false;
            boolean signaturesIncomplete = false;
            boolean inheritabilityIncomplete = false;
            boolean abstractionIncomplete = false;
            boolean scopeIncomplete = false;
            int methodCount = 0;
            int unsupportedMethodCount = 0;
            for (Draft draft : drafts) {
                List<String> keys = new ArrayList<>();
                for (MemberDraft source : draft.members()) {
                    if (source.name().isBlank() || source.line() < 1 || source.endLine() < source.line()) {
                        declarationIncomplete = true;
                        diagnostics.add(new ObservationDiagnostic(
                                DiagnosticKind.EVIDENCE_INCOMPLETE,
                                draft.path(), Math.max(0, source.line()),
                                "invalid C++ source-member declaration identity"));
                        continue;
                    }
                    String technicalKey = "mem_" + Hashing.sha256(
                            "cpp\\0member\\0" + draft.path() + "\\0" + source.definitionKey());
                    if (!memberTechnicalKeys.add(technicalKey)) {
                        declarationIncomplete = true;
                        diagnostics.add(new ObservationDiagnostic(
                                DiagnosticKind.EVIDENCE_INCOMPLETE,
                                draft.path(), source.line(),
                                "duplicate C++ source-member definition identity"));
                        continue;
                    }
                    boolean method = "METHOD".equals(source.kind());
                    if (method) {
                        methodCount++;
                        if (!source.signatureSupported()) {
                            signaturesIncomplete = true;
                            unsupportedMethodCount++;
                        }
                        if ("UNKNOWN".equals(source.abstraction())) {
                            abstractionIncomplete = true;
                        }
                        if ("UNKNOWN".equals(source.scope())) {
                            scopeIncomplete = true;
                        }
                    }
                    if ("UNKNOWN".equals(source.inheritability())) {
                        inheritabilityIncomplete = true;
                    }
                    members.add(new MemberObservation(
                            technicalKey,
                            null,
                            MemberKind.valueOf(source.kind()),
                            Inheritability.valueOf(source.inheritability()),
                            MemberVisibility.valueOf(source.visibility()),
                            source.name(),
                            draft.path(),
                            source.line(),
                            source.endLine(),
                            source.parameterTypes(),
                            method ? MethodAbstraction.valueOf(source.abstraction())
                                    : MethodAbstraction.UNKNOWN,
                            method ? MemberScope.valueOf(source.scope()) : MemberScope.UNKNOWN));
                    keys.add(technicalKey);
                }
                memberKeysByOwner.put(draft.id(), List.copyOf(keys));
            }

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
                        memberKeysByOwner.getOrDefault(draft.id(), List.of())));
            }

            if (!unresolved.isEmpty()) {
                hierarchyIncomplete = true;
            }

            String evidencePath = relativePath(root, files.get(0));
            diagnostics.add(new ObservationDiagnostic(
                    DiagnosticKind.EVIDENCE_INCOMPLETE,
                    evidencePath,
                    0,
                    "C++ adapter observes class/struct direct hierarchy and source-declared member ownership, "
                            + "signatures, visibility, inheritability, abstraction and scope under the declared "
                            + PROFILE_ID + " profile with compiler/include fingerprints; overrides and "
                            + "inherited-member evidence remain incomplete."));
            if (signaturesIncomplete) {
                diagnostics.add(new ObservationDiagnostic(
                        DiagnosticKind.EVIDENCE_INCOMPLETE,
                        evidencePath, 0,
                        "C++ local signatures remain incomplete: " + unsupportedMethodCount + " of "
                                + methodCount + " methods are outside the signature subset (templates, "
                                + "variadic parameters or unresolvable parameter types)"));
            }
            if (declarationIncomplete) {
                diagnostics.add(new ObservationDiagnostic(
                        DiagnosticKind.EVIDENCE_INCOMPLETE,
                        evidencePath, 0,
                        "C++ declaration ownership remains incomplete: at least one member declaration "
                                + "identity is invalid or duplicated"));
            }
            if (inheritabilityIncomplete || abstractionIncomplete || scopeIncomplete) {
                diagnostics.add(new ObservationDiagnostic(
                        DiagnosticKind.EVIDENCE_INCOMPLETE,
                        evidencePath, 0,
                        "C++ member inheritability, abstraction or scope evidence remains incomplete"));
            }

            verifyPhysicalEvidenceUnchanged(physicalHashes);

            EnumSet<EvidenceKind> completeEvidence = EnumSet.noneOf(EvidenceKind.class);
            if (!hierarchyIncomplete && unresolved.isEmpty()) {
                completeEvidence.add(EvidenceKind.HIERARCHY);
            }
            if (!hierarchyIncomplete) {
                if (!declarationIncomplete) {
                    completeEvidence.add(EvidenceKind.DECLARATION_OWNERSHIP);
                }
                if (!signaturesIncomplete) {
                    completeEvidence.add(EvidenceKind.LOCAL_SIGNATURES);
                }
                if (!inheritabilityIncomplete) {
                    completeEvidence.add(EvidenceKind.INHERITABILITY);
                }
                if (!abstractionIncomplete) {
                    completeEvidence.add(EvidenceKind.METHOD_ABSTRACTION);
                }
                if (!scopeIncomplete) {
                    completeEvidence.add(EvidenceKind.METHOD_SCOPE);
                }
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
                    members,
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
        private MemberSink activeSink;
        private MemberBuilder pendingMember;

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
            String storageClass = "";
            String access = "";
            boolean pure = false;
            boolean variadic = false;
            boolean completeDefinition = false;
            boolean implicit = false;
            JsonNode location = null;
            JsonNode range = null;
            JsonNode bases = null;
            JsonNode type = null;
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
                    case "storageClass" -> storageClass = parser.getValueAsString("");
                    case "access" -> access = parser.getValueAsString("");
                    case "pure" -> pure = parser.getValueAsBoolean(false);
                    case "variadic" -> variadic = parser.getValueAsBoolean(false);
                    case "completeDefinition" -> completeDefinition = parser.getValueAsBoolean(false);
                    case "isImplicit" -> implicit = parser.getValueAsBoolean(false);
                    case "loc" -> {
                        location = JSON.readTree(parser);
                        file = sourceFile(location, inheritedFile);
                    }
                    case "range" -> range = JSON.readTree(parser);
                    case "type" -> {
                        if ("ParmVarDecl".equals(kind) || isMethodKind(kind)) {
                            type = JSON.readTree(parser);
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "bases" -> {
                        if (file == null && inheritedFile == null) {
                            parser.skipChildren();
                        } else {
                            bases = JSON.readTree(parser);
                        }
                    }
                    case "inner" -> {
                        NodeResult node = processNode(
                                kind, name, tag, storageClass, access, pure, variadic,
                                completeDefinition, implicit,
                                location, range, bases, type, scope, file, templateContext);
                        processed = true;
                        childScope = node.childScope();
                        childTemplateContext = node.templateContext();

                        if (valueToken == JsonToken.START_ARRAY
                                && shouldTraverseChildren(kind, file)) {
                            MemberSink previousSink = activeSink;
                            MemberBuilder previousPending = pendingMember;
                            if (node.recordDraft() != null) {
                                activeSink = new MemberSink(node.recordDraft(), childScope, node.defaultAccess());
                                pendingMember = null;
                            } else if (node.pendingMember() != null) {
                                pendingMember = node.pendingMember();
                            }
                            while (parser.nextToken() != JsonToken.END_ARRAY) {
                                if (parser.currentToken() == JsonToken.START_OBJECT) {
                                    walk(parser, childScope, file, childTemplateContext);
                                } else {
                                    parser.skipChildren();
                                }
                            }
                            activeSink = previousSink;
                            pendingMember = previousPending;
                        } else {
                            parser.skipChildren();
                        }
                        if (node.pendingMember() != null) {
                            node.pendingMember().finish();
                        }
                    }
                    default -> parser.skipChildren();
                }
            }

            if (!processed) {
                NodeResult node = processNode(
                        kind, name, tag, storageClass, access, pure, variadic,
                        completeDefinition, implicit,
                        location, range, bases, type, scope, file, templateContext);
                if (node.pendingMember() != null) {
                    node.pendingMember().finish();
                }
            }
        }

        private NodeResult processNode(
                String kind,
                String name,
                String tag,
                String storageClass,
                String access,
                boolean pure,
                boolean variadic,
                boolean completeDefinition,
                boolean implicit,
                JsonNode location,
                JsonNode range,
                JsonNode basesNode,
                JsonNode typeNode,
                List<String> scope,
                String file,
                boolean templateContext) throws IOException {
            List<String> childScope = scope;
            boolean childTemplateContext = templateContext
                    || "ClassTemplateDecl".equals(kind)
                    || "FunctionTemplateDecl".equals(kind);
            Draft recordDraft = null;
            String defaultAccess = null;
            MemberBuilder memberBuilder = null;

            if (file != null && "ParmVarDecl".equals(kind)
                    && pendingMember != null && pendingMember.parameterScope().equals(scope)) {
                pendingMember.addParameter(parameterToken(typeNode));
            }
            if (file != null && pendingMember != null
                    && pendingMember.parameterScope().equals(scope)
                    && "CompoundStmt".equals(kind)) {
                pendingMember.closeParameters();
            }
            if (file != null && "AccessSpecDecl".equals(kind)
                    && activeSink != null && activeSink.scope().equals(scope) && !access.isBlank()) {
                activeSink.access(access);
            }

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
                    childScope = append(scope, "<unnamed@" + Math.max(1, line) + ">");
                } else if (line < 1) {
                    incomplete = true;
                    diagnostics.add(new ObservationDiagnostic(
                            DiagnosticKind.EVIDENCE_INCOMPLETE,
                            file,
                            0,
                            "Clang declaration lacks a stable source line: " + name));
                    childScope = append(scope, "<" + name + "@1>");
                } else {
                    String qualified = qualify(scope, name);
                    int endLine = sourceLine(range == null ? null : range.path("end"), file);
                    if (endLine < line) {
                        endLine = line;
                    }
                    List<BaseDraft> bases = bases(basesNode, file, line, templateContext);
                    String definitionKey = file + "\\0" + line + "\\0" + qualified;
                    recordDraft = new Draft(
                            definitionKey,
                            stableId(file, line, qualified),
                            qualified,
                            namespaceOf(qualified),
                            file,
                            line,
                            endLine,
                            bases,
                            new ArrayList<>());
                    drafts.add(recordDraft);
                    childScope = append(scope, name);
                    defaultAccess = "struct".equals(tag) ? "public" : "private";
                }
            } else if (file != null && "CXXRecordDecl".equals(kind)) {
                // A record outside the current identity boundary (union, incomplete,
                // implicit, template or lambda record) must not leak its member
                // declarations into the enclosing record.
                int line = sourceLine(location, file);
                childScope = append(scope, "<undrafted@" + Math.max(1, line) + ">");
            }

            if (file != null && activeSink != null && activeSink.scope().equals(scope)
                    && isMemberKind(kind) && !implicit
                    && (!"VarDecl".equals(kind) || "static".equals(storageClass))) {
                int line = sourceLine(location, file);
                if (line < 1) {
                    incomplete = true;
                    diagnostics.add(new ObservationDiagnostic(
                            DiagnosticKind.EVIDENCE_INCOMPLETE,
                            file,
                            0,
                            "Clang member declaration lacks a stable source line: " + name));
                } else {
                    int endLine = sourceLine(range == null ? null : range.path("end"), file);
                    if (endLine < line) {
                        endLine = line;
                    }
                    boolean method = isMethodKind(kind);
                    String visibility = "public".equals(activeSink.access()) ? "PUBLIC"
                            : "protected".equals(activeSink.access()) ? "PROTECTED" : "PRIVATE";
                    String inheritability = "PRIVATE".equals(visibility)
                            ? "NOT_INHERITABLE" : "INHERITABLE";
                    String memberScope = method
                            ? ("static".equals(storageClass) ? "STATIC" : "INSTANCE")
                            : "UNKNOWN";
                    String abstraction = method ? (pure ? "ABSTRACT" : "CONCRETE") : "UNKNOWN";
                    String definitionKey = file + "\\0" + line + "\\0" + qualify(scope, name)
                            + "\\0" + kind + "\\0" + name;
                    memberBuilder = new MemberBuilder(
                            activeSink.draft(), definitionKey, method ? "METHOD" : "ATTRIBUTE",
                            name, line, endLine, visibility, memberScope, abstraction, inheritability,
                            childScope);
                    if (!method) {
                        memberBuilder.closeParameters();
                    } else {
                        if (variadic || "FunctionTemplateDecl".equals(kind)) {
                            memberBuilder.markUnsupportedSignature();
                        }
                        List<String> qualifiers = methodQualifierTokens(typeNode);
                        if (qualifiers == null) {
                            memberBuilder.markUnsupportedSignature();
                        } else {
                            for (String qualifier : qualifiers) {
                                memberBuilder.addQualifier(qualifier);
                            }
                        }
                    }
                }
            }
            return new NodeResult(childScope, childTemplateContext, recordDraft, defaultAccess, memberBuilder);
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
                    || "CXXDestructorDecl".equals(kind)
                    || "CXXConversionDecl".equals(kind);
        }

        private static boolean isMemberKind(String kind) {
            return "FieldDecl".equals(kind)
                    || "VarDecl".equals(kind)
                    || isMethodKind(kind);
        }

        private static boolean isMethodKind(String kind) {
            return "CXXMethodDecl".equals(kind)
                    || "CXXConstructorDecl".equals(kind)
                    || "CXXDestructorDecl".equals(kind)
                    || "CXXConversionDecl".equals(kind)
                    || "FunctionTemplateDecl".equals(kind);
        }

        private static String parameterToken(JsonNode typeNode) {
            if (typeNode == null || !typeNode.isObject()) {
                return "";
            }
            String token = typeNode.path("desugaredQualType").asText("");
            if (token.isBlank()) {
                token = typeNode.path("qualType").asText("");
            }
            return token.strip();
        }

        /**
         * Trailing member-function qualifiers (cv-qualifiers, ref-qualifiers and
         * noexcept groups) from the method's printed type, for example
         * {@code "int () const"} or {@code "void () & noexcept(false)"}. They are
         * canonical signature tokens: {@code f(int)} and {@code f(int) const}
         * are distinct C++ declarations and must not collapse onto one key.
         * Returns null when the printed type cannot be split unambiguously, in
         * which case the method stays outside the signature subset.
         */
        private static List<String> methodQualifierTokens(JsonNode typeNode) {
            if (typeNode == null || !typeNode.isObject()) {
                return null;
            }
            String text = typeNode.path("qualType").asText("");
            if (text.isBlank()) {
                text = typeNode.path("desugaredQualType").asText("");
            }
            if (text.isBlank()) {
                return null;
            }
            List<String> tokens = new ArrayList<>();
            String remaining = text.stripTrailing();
            while (true) {
                remaining = remaining.stripTrailing();
                if (remaining.endsWith(")")) {
                    int close = remaining.length() - 1;
                    int depth = 0;
                    int open = -1;
                    for (int index = close; index >= 0; index--) {
                        char character = remaining.charAt(index);
                        if (character == ')') {
                            depth++;
                        } else if (character == '(') {
                            depth--;
                            if (depth == 0) {
                                open = index;
                                break;
                            }
                        }
                    }
                    if (open < 0) {
                        return null;
                    }
                    String group = remaining.substring(open, close + 1);
                    if (group.startsWith("noexcept")) {
                        tokens.add(0, group);
                        remaining = remaining.substring(0, open);
                        continue;
                    }
                    return remaining.substring(close + 1).isBlank() ? tokens : null;
                }
                int space = remaining.lastIndexOf(' ');
                if (space < 0) {
                    return null;
                }
                String token = remaining.substring(space + 1);
                if (!token.equals("const") && !token.equals("volatile")
                        && !token.equals("&") && !token.equals("&&")
                        && !token.equals("noexcept")) {
                    return null;
                }
                tokens.add(0, token);
                remaining = remaining.substring(0, space);
            }
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

    private record NodeResult(
            List<String> childScope,
            boolean templateContext,
            Draft recordDraft,
            String defaultAccess,
            MemberBuilder pendingMember) {
    }

    private record BaseDraft(String targetName, int line, boolean templateContext) {
    }

    private record MemberDraft(
            String definitionKey,
            String kind,
            String name,
            int line,
            int endLine,
            String visibility,
            String scope,
            String abstraction,
            String inheritability,
            List<String> parameterTypes,
            boolean signatureSupported) {
    }

    private static final class MemberSink {
        private final Draft draft;
        private final List<String> scope;
        private String access;

        private MemberSink(Draft draft, List<String> scope, String defaultAccess) {
            this.draft = draft;
            this.scope = scope;
            this.access = defaultAccess == null || defaultAccess.isBlank() ? "private" : defaultAccess;
        }

        Draft draft() {
            return draft;
        }

        List<String> scope() {
            return scope;
        }

        String access() {
            return access;
        }

        void access(String value) {
            this.access = value;
        }
    }

    private static final class MemberBuilder {
        private final Draft owner;
        private final String definitionKey;
        private final String kind;
        private final String name;
        private final int line;
        private final int endLine;
        private final String visibility;
        private final String scope;
        private final String abstraction;
        private final String inheritability;
        private final List<String> parameterScope;
        private final List<String> parameterTypes = new ArrayList<>();
        private final List<String> qualifiers = new ArrayList<>();
        private boolean signatureSupported = true;
        private boolean parametersClosed;
        private boolean finalized;

        private MemberBuilder(
                Draft owner, String definitionKey, String kind, String name,
                int line, int endLine, String visibility, String scope,
                String abstraction, String inheritability, List<String> parameterScope) {
            this.owner = owner;
            this.definitionKey = definitionKey;
            this.kind = kind;
            this.name = name;
            this.line = line;
            this.endLine = endLine;
            this.visibility = visibility;
            this.scope = scope;
            this.abstraction = abstraction;
            this.inheritability = inheritability;
            this.parameterScope = List.copyOf(parameterScope);
        }

        List<String> parameterScope() {
            return parameterScope;
        }

        void addParameter(String token) {
            if (parametersClosed || !signatureSupported) {
                return;
            }
            if (token == null || token.isBlank()
                    || token.contains("<") || token.contains("decltype") || token.contains("auto")) {
                markUnsupportedSignature();
                return;
            }
            parameterTypes.add(token);
        }

        void addQualifier(String token) {
            if (!parametersClosed && signatureSupported) {
                qualifiers.add(token);
            }
        }

        void closeParameters() {
            parametersClosed = true;
        }

        void markUnsupportedSignature() {
            signatureSupported = false;
            parameterTypes.clear();
            qualifiers.clear();
        }

        void finish() {
            if (finalized) {
                return;
            }
            finalized = true;
            if (signatureSupported) {
                parameterTypes.addAll(qualifiers);
            }
            owner.members().add(new MemberDraft(
                    definitionKey, kind, name, line, endLine, visibility, scope,
                    abstraction, inheritability, List.copyOf(parameterTypes), signatureSupported));
        }
    }

    private record Draft(
            String definitionKey,
            String id,
            String qualifiedName,
            String namespaceName,
            String path,
            int line,
            int endLine,
            List<BaseDraft> bases,
            List<MemberDraft> members) {
        boolean sameSemanticShape(Draft other) {
            return qualifiedName.equals(other.qualifiedName)
                    && namespaceName.equals(other.namespaceName)
                    && path.equals(other.path)
                    && line == other.line
                    && endLine == other.endLine
                    && bases.equals(other.bases)
                    && members.equals(other.members);
        }
    }

    private static final class UncheckedIo extends RuntimeException {
        private UncheckedIo(IOException cause) {
            super(cause);
        }
    }
}
