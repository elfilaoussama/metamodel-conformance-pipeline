package metamodel.conformance.pipeline.adapter.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.adapter.SourceObserver;
import metamodel.conformance.pipeline.adapter.SourceInputSnapshot;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Inheritability;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.MemberVisibility;
import metamodel.conformance.pipeline.model.MethodAbstraction;
import metamodel.conformance.pipeline.model.MemberScope;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.NativeEvidence;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;
import metamodel.conformance.pipeline.model.SourceUnit;
import metamodel.conformance.pipeline.model.UnresolvedParent;
import metamodel.conformance.pipeline.util.ArtifactLimits;
import metamodel.conformance.pipeline.util.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Conservative source-only Python observer for classifier hierarchy and declaration ownership.
 *
 * <p>The bridge uses CPython's standard {@code ast} module and never imports or executes
 * the observed source. Source declarations have independent definition identities; runtime
 * name bindings are tracked separately so aliases and straight-line redefinitions can be
 * resolved without collapsing distinct declarations. Source-declared methods and assignment/
 * annotation attributes are mapped to canonical members with unknown visibility. Method
 * inheritability is INHERITABLE per C3 namespace lookup, including double-underscore
 * methods, whose effective attribute name is the CPython-mangled form ({@code A.__run}
 * becomes {@code _A__run}); attribute inheritability remains unknown. Local signatures are
 * observed only for the annotation-based subset: exclude the implicit receiver of a known
 * instance/class descriptor, then fully-annotated plain positional parameters; names are never used as type
 * tokens, and unsupported parameter shapes (defaults, positional-only, keyword-only,
 * variadic) stay outside the subset so local-signature evidence remains incomplete for
 * the observation. Inherited-member, language-specific visibility, and attribute
 * inheritability evidence deliberately remain incomplete. Supported known decorator identities
 * provide method abstraction and instance/static scope; opaque transformations and the unresolved
 * class-bound common-scope mapping remain explicit unknowns.</p>
 */
public final class PythonAstObserver implements SourceObserver {
    public static final String ADAPTER_ID = "python-ast";
    public static final String ADAPTER_VERSION = "0.13.0";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BRIDGE_BYTES = (int) ArtifactLimits.MAX_XMI_BYTES;
    private static final String BRIDGE_SCRIPT = loadBridgeScript();
    private final String pythonExecutable;
    private final PythonDependencyInputs dependencyInputs;

    public PythonAstObserver() {
        this(System.getProperty("metamodel.conformance.python", "python3"));
    }

    public PythonAstObserver(String pythonExecutable) {
        this(pythonExecutable, PythonDependencyInputs.none());
    }

    public PythonAstObserver(PythonDependencyInputs dependencyInputs) {
        this(System.getProperty("metamodel.conformance.python", "python3"), dependencyInputs);
    }

    public PythonAstObserver(String pythonExecutable, PythonDependencyInputs dependencyInputs) {
        if (pythonExecutable == null || pythonExecutable.isBlank()) {
            throw new IllegalArgumentException("python executable must not be blank");
        }
        this.pythonExecutable = pythonExecutable;
        this.dependencyInputs = dependencyInputs == null
                ? PythonDependencyInputs.none() : dependencyInputs;
    }

    @Override
    public Observation observe(Path sourceRoot, Set<String> externalParents) throws ObservationException {
        try {
            Path root = validateRoot(sourceRoot);
            List<Path> files = discoverPythonFiles(root);
            if (files.isEmpty()) {
                throw new ObservationException("source root contains no .py files: " + sourceRoot);
            }

            try (SourceInputSnapshot snapshot = SourceInputSnapshot.capture(root, files)) {
            List<SourceUnit> units = new ArrayList<>(snapshot.units(Language.PYTHON));
            List<String> paths = new ArrayList<>();
            for (Path file : files) {
                String relative = relativePath(root, file);
                paths.add(relative);
            }
            // Locked distributions are declared environment evidence, the
            // classpath analogue: fingerprinted units, never source classifiers.
            for (PythonDependencyInputs.Distribution distribution
                    : dependencyInputs.distributions().stream()
                            .sorted(java.util.Comparator.comparing(PythonDependencyInputs.Distribution::name))
                            .toList()) {
                units.add(new SourceUnit(
                        Language.PYTHON,
                        "python-dependency/" + distribution.name(),
                        distribution.digest()));
            }

            BridgeResult bridge = runBridge(snapshot.root(), paths);
            if (bridge.runtimeExecutable() == null || bridge.runtimeContext() == null) {
                throw new ObservationException("Python AST bridge omitted its runtime receipts");
            }
            bridge.runtimeExecutable().verify();
            bridge.runtimeContext().verify();
            dependencyInputs.requireInterpreterVersion(bridge.pythonVersion());
            String configuration = JSON.writeValueAsString(new MeasurementContextIdentity(
                    "3", Hashing.sha256(BRIDGE_SCRIPT), bridge.pythonVersion(), bridge.runtimeExecutable(), bridge.runtimeContext(), dependencyInputs.contextIdentity(),
                    externalParents == null ? List.of() : externalParents.stream().sorted().toList()));
            String contextHash = Hashing.sha256(configuration);
            String contextId = "python-ast-domain-" + contextHash;
            units.add(new SourceUnit(Language.PYTHON, "python-context/ast-declarations", contextHash));
            List<ObservationDiagnostic> diagnostics = new ArrayList<>();
            for (BridgeDiagnostic diagnostic : safe(bridge.diagnostics())) {
                diagnostics.add(new ObservationDiagnostic(
                        DiagnosticKind.valueOf(diagnostic.kind()),
                        diagnostic.path(), diagnostic.line(), diagnostic.message()));
            }

            Map<String, TypeDraft> byDefinitionKey = new HashMap<>();
            Map<String, List<TypeDraft>> byQualifiedName = new HashMap<>();
            List<TypeDraft> drafts = new ArrayList<>();
            boolean hierarchyIncomplete = bridge.hierarchyIncomplete();
            for (BridgeClass item : safe(bridge.classes())) {
                String id = stableId(contextId, item.path(), item.line(), item.qualifiedName());
                TypeDraft draft = new TypeDraft(
                        item.definitionKey(),
                        id,
                        item.qualifiedName(),
                        item.moduleName() == null || item.moduleName().isBlank()
                                || "<root>".equals(item.moduleName()) ? "<default>" : item.moduleName(),
                        item.path(),
                        item.line(),
                        Math.max(item.line(), item.endLine()),
                        safe(item.bases()),
                        safe(item.members()));
                drafts.add(draft);
                if (draft.definitionKey() == null || draft.definitionKey().isBlank()
                        || byDefinitionKey.putIfAbsent(draft.definitionKey(), draft) != null) {
                    hierarchyIncomplete = true;
                    diagnostics.add(new ObservationDiagnostic(
                            DiagnosticKind.EVIDENCE_INCOMPLETE,
                            draft.path(), draft.line(),
                            "duplicate or missing Python source-definition identity"));
                }
                byQualifiedName.computeIfAbsent(draft.qualifiedName(), ignored -> new ArrayList<>()).add(draft);
            }

            List<MemberObservation> members = new ArrayList<>();
            List<NativeEvidence.Signature> nativeSignatures = new ArrayList<>();
            List<NativeEvidence.Bases> nativeBases = new ArrayList<>();
            Map<String, List<String>> memberKeysByOwner = new HashMap<>();
            Set<String> memberTechnicalKeys = new HashSet<>();
            boolean declarationIncomplete = false;
            boolean signaturesIncomplete = false;
            boolean abstractionsIncomplete = false;
            boolean scopesIncomplete = false;
            int methodCount = 0;
            int unsupportedMethodCount = 0;
            for (TypeDraft draft : drafts.stream().sorted(Comparator.comparing(TypeDraft::id)).toList()) {
                List<String> keys = new ArrayList<>();
                for (BridgeMember source : draft.members()) {
                    if (source.definitionKey() == null || source.definitionKey().isBlank()
                            || source.name() == null || source.name().isBlank()
                            || source.line() < 1 || source.endLine() < source.line()) {
                        declarationIncomplete = true;
                        diagnostics.add(new ObservationDiagnostic(
                                DiagnosticKind.EVIDENCE_INCOMPLETE,
                                draft.path(), Math.max(0, source.line()),
                                "invalid Python source-member declaration identity"));
                        continue;
                    }
                    MemberKind kind;
                    try {
                        kind = MemberKind.valueOf(source.kind());
                    } catch (IllegalArgumentException invalidKind) {
                        declarationIncomplete = true;
                        diagnostics.add(new ObservationDiagnostic(
                                DiagnosticKind.EVIDENCE_INCOMPLETE,
                                draft.path(), source.line(),
                                "unsupported Python source-member kind: " + source.kind()));
                        continue;
                    }
                    MethodAbstraction abstraction = kind == MemberKind.METHOD && source.abstraction() != null
                            ? MethodAbstraction.valueOf(source.abstraction()) : MethodAbstraction.UNKNOWN;
                    MemberScope scope = kind == MemberKind.METHOD && source.scope() != null
                            ? MemberScope.valueOf(source.scope()) : MemberScope.UNKNOWN;
                    if (kind == MemberKind.METHOD) {
                        methodCount++;
                        abstractionsIncomplete |= abstraction == MethodAbstraction.UNKNOWN;
                        scopesIncomplete |= scope == MemberScope.UNKNOWN;
                        if (!source.signatureSupported()) {
                            signaturesIncomplete = true;
                            unsupportedMethodCount++;
                        }
                    }
                    String technicalKey = stableMemberKey(contextId, draft.path(), source.definitionKey());
                    if (!memberTechnicalKeys.add(technicalKey)) {
                        declarationIncomplete = true;
                        diagnostics.add(new ObservationDiagnostic(
                                DiagnosticKind.EVIDENCE_INCOMPLETE,
                                draft.path(), source.line(),
                                "duplicate Python source-member definition identity"));
                        continue;
                    }
                    MemberObservation member = new MemberObservation(
                            technicalKey,
                            null,
                            kind,
                            kind == MemberKind.METHOD
                                    ? Inheritability.INHERITABLE : Inheritability.UNKNOWN,
                            MemberVisibility.UNKNOWN,
                            source.name(),
                            draft.path(),
                            source.line(),
                            source.endLine(),
                            kind == MemberKind.METHOD ? safe(source.parameters()) : List.of(),
                            abstraction, scope);
                    members.add(member);
                    keys.add(member.technicalKey());
                    if (kind == MemberKind.METHOD) {
                        var parameters = source.signatureSupported()
                                ? NativeEvidence.Evidence.known(safe(source.parameters()).stream().map(token ->
                                    new NativeEvidence.Parameter(token, NativeEvidence.TypeMeaning.SOURCE_ANNOTATION)).toList())
                                : NativeEvidence.Evidence.<NativeEvidence.Parameter>unavailable(
                                    NativeEvidence.Knowledge.UNSUPPORTED, "outside the supported annotated descriptor/parameter subset");
                        var returnType = source.returnAnnotation() == null
                                ? NativeEvidence.Evidence.<NativeEvidence.TypeToken>unavailable(
                                    NativeEvidence.Knowledge.UNKNOWN, "no declared return annotation")
                                : NativeEvidence.Evidence.known(List.of(new NativeEvidence.TypeToken(
                                    source.returnAnnotation(), NativeEvidence.TypeMeaning.SOURCE_ANNOTATION)));
                        nativeSignatures.add(new NativeEvidence.Signature(contextId, technicalKey,
                                parameters, NativeEvidence.Evidence.known(List.of()), returnType));
                    }
                }
                memberKeysByOwner.put(draft.id(), List.copyOf(keys));
            }

            Set<String> allowed = externalParents == null ? Set.of() : Set.copyOf(externalParents);
            List<ClassifierObservation> classifiers = new ArrayList<>();
            List<UnresolvedParent> unresolved = new ArrayList<>();
            int opaqueExternalBases = 0;
            for (TypeDraft draft : drafts.stream()
                    .sorted(Comparator.comparing(TypeDraft::id)).toList()) {
                LinkedHashSet<String> parentIds = new LinkedHashSet<>();
                List<NativeEvidence.DirectBase> directBases = new ArrayList<>();
                for (BridgeBase base : draft.bases()) {
                    directBases.add(new NativeEvidence.DirectBase(base.raw(),
                            nativeBaseTarget(base, byDefinitionKey, byQualifiedName),
                            NativeEvidence.Access.NOT_APPLICABLE, NativeEvidence.Virtuality.NOT_APPLICABLE));
                    if (base.definitionCandidate() != null) {
                        TypeDraft internal = byDefinitionKey.get(base.definitionCandidate());
                        if (internal != null) {
                            parentIds.add(internal.id());
                        } else {
                            unresolved.add(new UnresolvedParent(
                                    draft.id(), targetName(base), draft.path(),
                                    positiveLine(base.line(), draft.line())));
                        }
                        continue;
                    }

                    List<TypeDraft> internal = base.qualifiedCandidate() == null
                            ? List.of() : byQualifiedName.getOrDefault(base.qualifiedCandidate(), List.of());
                    if (internal.size() == 1) {
                        parentIds.add(internal.get(0).id());
                    } else if (internal.size() > 1) {
                        // An external allowlist must never hide an ambiguous internal runtime binding.
                        unresolved.add(new UnresolvedParent(
                                draft.id(), targetName(base), draft.path(),
                                positiveLine(base.line(), draft.line())));
                    } else if (base.builtinCandidate() != null) {
                        // CPython built-in types are platform roots, analogous to Java platform roots.
                    } else if (allowedExternal(base, allowed) || declaredDependencyRoot(base)) {
                        // Boundary attribution is not observation of the type or its ancestry.
                        // Keep the cutoff metadata, but never promote it to complete hierarchy.
                        hierarchyIncomplete = true;
                        opaqueExternalBases++;
                    } else {
                        unresolved.add(new UnresolvedParent(
                                draft.id(), targetName(base), draft.path(),
                                positiveLine(base.line(), draft.line())));
                    }
                }
                nativeBases.add(new NativeEvidence.Bases(contextId, draft.id(), NativeEvidence.Evidence.known(directBases)));
                classifiers.add(new ClassifierObservation(
                        draft.id(), draft.qualifiedName(), draft.packageName(), ClassifierKind.CLASS,
                        draft.path(), draft.line(), draft.endLine(), List.copyOf(parentIds),
                        memberKeysByOwner.getOrDefault(draft.id(), List.of())));
            }

            String evidencePath = units.get(0).path();
            if (opaqueExternalBases > 0) {
                diagnostics.add(new ObservationDiagnostic(
                        DiagnosticKind.EVIDENCE_INCOMPLETE, evidencePath, 0,
                        "Unobserved external Python base types/ancestry: " + opaqueExternalBases
                                + "; boundary attribution does not establish complete hierarchy evidence."));
            }
            diagnostics.add(new ObservationDiagnostic(
                    DiagnosticKind.EVIDENCE_INCOMPLETE,
                    evidencePath, 0,
                    "Python adapter observes classifier hierarchy, source-declared method/attribute "
                            + "ownership, annotation-based method signatures, and supported decorator-derived "
                            + "method abstraction/scope; language-specific "
                            + "visibility, attribute inheritability, and inherited-member "
                            + "evidence remain incomplete."));
            if (signaturesIncomplete) {
                diagnostics.add(new ObservationDiagnostic(
                        DiagnosticKind.EVIDENCE_INCOMPLETE,
                        evidencePath, 0,
                        "Python local signatures remain incomplete: " + unsupportedMethodCount
                                + " of " + methodCount + " methods are outside the annotation-based "
                                + "signature subset (unannotated parameters or unsupported parameter "
                                + "shapes or unresolved descriptor binding)"));
            }

            boolean parseError = diagnostics.stream()
                    .anyMatch(item -> item.kind() == DiagnosticKind.PARSE_ERROR);
            EnumSet<EvidenceKind> completeEvidence = EnumSet.noneOf(EvidenceKind.class);
            if (!parseError && !declarationIncomplete) {
                completeEvidence.add(EvidenceKind.DECLARATION_OWNERSHIP);
                if (!signaturesIncomplete) {
                    completeEvidence.add(EvidenceKind.LOCAL_SIGNATURES);
                }
                if (!abstractionsIncomplete) {
                    completeEvidence.add(EvidenceKind.METHOD_ABSTRACTION);
                }
                if (!scopesIncomplete) {
                    completeEvidence.add(EvidenceKind.METHOD_SCOPE);
                }
            }

            if (abstractionsIncomplete || scopesIncomplete) {
                diagnostics.add(new ObservationDiagnostic(DiagnosticKind.EVIDENCE_INCOMPLETE,
                        evidencePath, 0, "Python method decorator/descriptor evidence is incomplete: "
                        + "opaque transformations or unresolved common-scope mapping prevent complete "
                        + "method abstraction/scope evidence."));
            }
            if (!parseError && !hierarchyIncomplete && unresolved.isEmpty()) {
                completeEvidence.add(EvidenceKind.HIERARCHY);
            }

            snapshot.verifyOriginalsUnchanged(discoverPythonFiles(root));
            bridge.runtimeExecutable().verify();
            bridge.runtimeContext().verify();
            var domain = new NativeEvidence.Domain(contextId, Language.PYTHON,
                    NativeEvidence.MeasurementMode.SOURCE_ONLY, NativeEvidence.View.SOURCE_DECLARATIONS,
                    Hashing.sha256(contextHash + "\0" + Hashing.sourceSetDigest(units)),
                    "Captured source-only AST declarations; declared lock metadata, actual executable and reported module backing files/configuration fingerprinted; consumed bytecode/shared-image/transient-loader/environment/input/role closure unverified",
                    classifiers.stream().map(ClassifierObservation::id).toList(),
                    members.stream().map(MemberObservation::technicalKey).toList());
            return new Observation(
                    "15",
                    ADAPTER_ID,
                    ADAPTER_VERSION + "/python-" + bridge.pythonVersion(),
                    List.copyOf(allowed),
                    completeEvidence,
                    units,
                    classifiers,
                    members,
                    List.of(),
                    List.of(),
                    unresolved,
                    diagnostics,
                    List.of(),
                    new NativeEvidence.Bundle(List.of(domain), List.of(), List.of(), nativeBases,
                            nativeSignatures, List.of(), List.of(), List.of(bridge.runtimeContext().reportedInputs(contextId))));
            }
        } catch (ObservationException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw new ObservationException("Python observation failed: " + exception.getMessage(), exception);
        }
    }

    private BridgeResult runBridge(Path root, List<String> relativePaths) throws ObservationException, IOException {
        Path stderr = Files.createTempFile("metamodel-python-observer-", ".stderr");
        try {
            ProcessBuilder builder = new ProcessBuilder(pythonExecutable, "-c", BRIDGE_SCRIPT);
            builder.redirectError(stderr.toFile());
            Process process;
            try {
                process = builder.start();
            } catch (IOException failure) {
                throw new ObservationException(
                        "cannot start Python interpreter '" + pythonExecutable + "': " + failure.getMessage(), failure);
            }

            byte[] manifest = JSON.writeValueAsBytes(Map.of(
                    "root", root.toString(),
                    "paths", relativePaths));
            try (OutputStream input = process.getOutputStream()) {
                input.write(manifest);
            }

            byte[] output;
            try {
                output = process.getInputStream().readNBytes(MAX_BRIDGE_BYTES + 1);
            } catch (IOException failure) {
                process.destroyForcibly();
                throw failure;
            }
            if (output.length > MAX_BRIDGE_BYTES) {
                process.destroyForcibly();
                throw new ObservationException("Python observer output exceeds canonical artifact boundary");
            }

            int exit;
            try {
                exit = process.waitFor();
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new ObservationException("Python observation interrupted", interrupted);
            }
            if (exit != 0) {
                String error = Files.readString(stderr, StandardCharsets.UTF_8);
                if (error.length() > 4096) {
                    error = error.substring(0, 4096);
                }
                throw new ObservationException(
                        "Python AST bridge exited " + exit + (error.isBlank() ? "" : ": " + error.strip()));
            }
            try {
                return JSON.readValue(output, BridgeResult.class);
            } catch (IOException malformed) {
                throw new ObservationException("Python AST bridge returned invalid JSON", malformed);
            }
        } finally {
            Files.deleteIfExists(stderr);
        }
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

    private static List<Path> discoverPythonFiles(Path root) throws IOException, ObservationException {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream.filter(path -> path.toString().endsWith(".py"))
                    .sorted(Comparator.comparing(path -> relativePath(root, path)))
                    .toList();
        }
        for (Path file : files) {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new ObservationException("Python source is not a regular non-symbolic-link file: " + file);
            }
        }
        return files;
    }

    private static String relativePath(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String stableId(String contextId, String path, int line, String qualifiedName) {
        return "cls_" + Hashing.sha256("python\0" + contextId + "\0" + path + "\0" + line + "\0" + qualifiedName);
    }

    private static String stableMemberKey(String contextId, String path, String definitionKey) {
        return "mem_" + Hashing.sha256("python\0member\0" + contextId + "\0" + path + "\0" + definitionKey);
    }

    private static NativeEvidence.Evidence<String> nativeBaseTarget(BridgeBase base,
            Map<String, TypeDraft> byDefinitionKey, Map<String, List<TypeDraft>> byQualifiedName) {
        if (base.definitionCandidate() != null) {
            TypeDraft target = byDefinitionKey.get(base.definitionCandidate());
            return target == null ? NativeEvidence.Evidence.unavailable(NativeEvidence.Knowledge.UNKNOWN,
                    "source binding target is absent from the canonical population")
                    : NativeEvidence.Evidence.known(List.of(target.id()));
        }
        List<TypeDraft> targets = base.qualifiedCandidate() == null ? List.of()
                : byQualifiedName.getOrDefault(base.qualifiedCandidate(), List.of());
        if (targets.size() == 1) return NativeEvidence.Evidence.known(List.of(targets.get(0).id()));
        return NativeEvidence.Evidence.unavailable(targets.size() > 1
                ? NativeEvidence.Knowledge.AMBIGUOUS : NativeEvidence.Knowledge.UNKNOWN,
                base.builtinCandidate() != null ? "builtin root is not a canonical source classifier"
                        : "base has no unique observed canonical target");
    }

    private static boolean allowedExternal(BridgeBase base, Set<String> allowed) {
        return (base.qualifiedCandidate() != null && allowed.contains(base.qualifiedCandidate()))
                || (base.raw() != null && allowed.contains(base.raw()));
    }

    /**
     * A base has provider-declared boundary attribution when a dotted reference's
     * first segment names a top-level module of a locked distribution. Bare
     * single-segment names are never attributed: without import tracking that
     * would guess which distribution owns them.
     */
    private boolean declaredDependencyRoot(BridgeBase base) {
        if (dependencyInputs.isEmpty()) {
            return false;
        }
        for (String name : new String[] {base.qualifiedCandidate(), base.raw()}) {
            if (name == null || name.isBlank() || name.indexOf('.') < 0) {
                continue;
            }
            String firstSegment = name.substring(0, name.indexOf('.'));
            if (!firstSegment.isBlank()
                    && dependencyInputs.distributionForTopLevelModule(firstSegment) != null) {
                return true;
            }
        }
        return false;
    }

    private static String targetName(BridgeBase base) {
        if (base.qualifiedCandidate() != null && !base.qualifiedCandidate().isBlank()) {
            return base.qualifiedCandidate();
        }
        if (base.raw() != null && !base.raw().isBlank()) {
            return base.raw();
        }
        return "<dynamic-python-base>";
    }

    private static int positiveLine(int value, int fallback) {
        return value > 0 ? value : Math.max(1, fallback);
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String loadBridgeScript() {
        try (InputStream input = PythonAstObserver.class
                .getResourceAsStream("/python/ast_hierarchy_bridge.py")) {
            if (input == null) {
                throw new IllegalStateException("bundled Python AST bridge is missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot load bundled Python AST bridge", failure);
        }
    }

    private record MeasurementContextIdentity(String schemaVersion, String bridgeScriptSha256,
            String reportedInterpreterVersion, PythonRuntimeExecutable runtimeExecutable,
            PythonRuntimeContext runtimeContext,
            PythonDependencyInputs.ContextIdentity declaredEnvironment,
            List<String> boundaryHints) { }

    private record TypeDraft(
            String definitionKey,
            String id,
            String qualifiedName,
            String packageName,
            String path,
            int line,
            int endLine,
            List<BridgeBase> bases,
            List<BridgeMember> members) {
    }

    private record BridgeResult(
            String pythonVersion,
            PythonRuntimeExecutable runtimeExecutable,
            PythonRuntimeContext runtimeContext,
            boolean hierarchyIncomplete,
            List<BridgeClass> classes,
            List<BridgeDiagnostic> diagnostics) {
    }

    private record BridgeClass(
            String definitionKey,
            String path,
            String moduleName,
            String qualifiedName,
            int line,
            int endLine,
            List<BridgeBase> bases,
            List<BridgeMember> members) {
    }

    private record BridgeMember(
            String definitionKey,
            String kind,
            String name,
            int line,
            int endLine,
            List<String> parameters,
            boolean signatureSupported,
            String returnAnnotation,
            String abstraction,
            String scope) {
    }

    private record BridgeBase(
            String raw,
            String definitionCandidate,
            String qualifiedCandidate,
            String builtinCandidate,
            int line) {
    }

    private record BridgeDiagnostic(
            String kind,
            String path,
            int line,
            String message) {
    }
}
