package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.adapter.SourceObserver;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Inheritability;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.MemberVisibility;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;
import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.SourceUnit;
import metamodel.conformance.pipeline.model.UnresolvedParent;
import metamodel.conformance.pipeline.util.Hashing;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtAnnotationType;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtInterface;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtModifiable;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeMember;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.reference.CtTypeReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class SpoonJavaObserver implements SourceObserver {
    public static final String ADAPTER_ID = "spoon-java";
    public static final String ADAPTER_VERSION = "0.12.0";
    private static final Set<String> PLATFORM_ROOTS = Set.of(
            "java.lang.Object",
            "java.lang.Record",
            "java.lang.Enum",
            "java.lang.annotation.Annotation");
    private static final Pattern DIAGNOSTIC_LINE = Pattern.compile("(?i)\\bline\\s+(\\d+)\\b");
    private static final int MAX_DIAGNOSTIC_MESSAGE = 4096;
    private final JavaDependencyInputs dependencyInputs;

    public SpoonJavaObserver() {
        this(List.of());
    }

    public SpoonJavaObserver(List<Path> dependencyArchives) {
        this.dependencyInputs = JavaDependencyInputs.global(dependencyArchives);
    }

    @Override
    public Observation observe(Path sourceRoot, Set<String> externalParents) throws ObservationException {
        try {
            Path root = validateRoot(sourceRoot);
            List<Path> files = discoverJavaFiles(root);
            if (files.isEmpty()) {
                throw new ObservationException("source root contains no .java files: " + sourceRoot);
            }

            List<SourceUnit> units = new ArrayList<>();
            for (Path file : files) {
                units.add(new SourceUnit(
                        Language.JAVA, relativePath(root, file), Hashing.sha256(file)));
            }
            JavaDependencyInputs compilationInputs = dependencyInputs;
            JavaDependencyClasspath.Result dependencies = JavaDependencyClasspath.resolve(compilationInputs);
            units.addAll(dependencies.units());

            BuildResult build = buildTypes(root, files, compilationInputs);
            List<CtType<?>> types = build.types();
            Map<String, TypeDraft> draftsById = new HashMap<>();
            Map<String, List<TypeDraft>> draftsByQualifiedName = new HashMap<>();
            for (CtType<?> type : types) {
                String path = sourcePath(root, type);
                ClassifierKind kind = kindOf(type);
                String id = stableId(path, kind, type.getQualifiedName());
                SourcePosition position = type.getPosition();
                TypeDraft draft = new TypeDraft(
                        type, id, path, contextIdsForSourcePath(compilationInputs, path),
                        packageName(type), kind, position.getLine(), position.getEndLine());
                draftsById.put(id, draft);
                draftsByQualifiedName.computeIfAbsent(type.getQualifiedName(), ignored -> new ArrayList<>())
                        .add(draft);
            }

            Set<String> allowed = externalParents == null ? Set.of() : Set.copyOf(externalParents);
            List<MemberObservation> members = new ArrayList<>();
            Map<String, List<String>> memberKeysByOwner = new HashMap<>();
            boolean localSignaturesComplete = true;
            boolean inheritabilityComplete = true;
            for (TypeDraft draft : draftsById.values().stream().sorted(Comparator.comparing(TypeDraft::id)).toList()) {
                List<String> memberKeys = new ArrayList<>();
                for (CtTypeMember typeMember : draft.type().getTypeMembers()) {
                    if (!typeMember.getPosition().isValidPosition()) {
                        continue;
                    }
                    if (typeMember instanceof CtMethod<?> method) {
                        List<String> parameterTypes = new ArrayList<>();
                        for (var parameter : method.getParameters()) {
                            String parameterType = parameter.getType().getQualifiedName();
                            if (parameterType == null || parameterType.isBlank()) {
                                parameterType = "<unknown>";
                                localSignaturesComplete = false;
                            }
                            parameterTypes.add(parameterType);
                        }
                        MemberObservation member = memberObservation(
                                root, draft, MemberKind.METHOD, method.getSimpleName(),
                                inheritability(draft.type(), method),
                                visibility(draft.type(), method),
                                method.getPosition(), parameterTypes);
                        inheritabilityComplete &= member.inheritability() != Inheritability.UNKNOWN;
                        members.add(member);
                        memberKeys.add(member.technicalKey());
                    } else if (typeMember instanceof CtField<?> field) {
                        MemberObservation member = memberObservation(
                                root, draft, MemberKind.ATTRIBUTE, field.getSimpleName(),
                                inheritability(draft.type(), field),
                                visibility(draft.type(), field),
                                field.getPosition(), List.of());
                        inheritabilityComplete &= member.inheritability() != Inheritability.UNKNOWN;
                        members.add(member);
                        memberKeys.add(member.technicalKey());
                    }
                }
                memberKeysByOwner.put(draft.id(), memberKeys);
            }

            List<ClassifierObservation> classifiers = new ArrayList<>();
            List<UnresolvedParent> unresolved = new ArrayList<>();
            for (TypeDraft draft : draftsById.values().stream().sorted(Comparator.comparing(TypeDraft::id)).toList()) {
                LinkedHashSet<String> parentIds = new LinkedHashSet<>();
                for (CtTypeReference<?> parent : directParents(draft.type())) {
                    String parentName = parent.getQualifiedName();
                    List<TypeDraft> internalCandidates = scopedCandidates(
                            draft, parentName, draftsByQualifiedName, compilationInputs);
                    if (internalCandidates != null && internalCandidates.size() == 1) {
                        parentIds.add(internalCandidates.get(0).id());
                    } else if (internalCandidates != null && !internalCandidates.isEmpty()) {
                        unresolved.add(new UnresolvedParent(
                                draft.id(), parentName, draft.path(), referenceLine(parent, draft.startLine())));
                    } else if (!PLATFORM_ROOTS.contains(parentName) && !allowed.contains(parentName)) {
                        unresolved.add(new UnresolvedParent(
                                draft.id(), parentName, draft.path(), referenceLine(parent, draft.startLine())));
                    }
                }
                classifiers.add(new ClassifierObservation(
                        draft.id(), draft.type().getQualifiedName(), draft.packageName(), draft.kind(), draft.path(),
                        draft.startLine(), draft.endLine(), List.copyOf(parentIds),
                        memberKeysByOwner.getOrDefault(draft.id(), List.of())));
            }
            JavacInheritedMemberObserver.Result inherited = build.diagnostics().isEmpty()
                    ? new JavacInheritedMemberObserver().observe(
                            root, files, classifiers, members, dependencies.paths())
                    : JavacInheritedMemberObserver.Result.incomplete();
            classifiers = classifiers.stream().map(classifier -> new ClassifierObservation(
                    classifier.id(), classifier.qualifiedName(), classifier.packageName(), classifier.kind(),
                    classifier.sourcePath(), classifier.startLine(), classifier.endLine(),
                    classifier.parentIds(), classifier.declaredMemberKeys(),
                    inherited.inheritedByClassifier().getOrDefault(classifier.id(), List.of())))
                    .toList();
            EnumSet<EvidenceKind> completeEvidence = EnumSet.noneOf(EvidenceKind.class);
            if (build.diagnostics().isEmpty()) {
                completeEvidence.add(EvidenceKind.DECLARATION_OWNERSHIP);
                if (inheritabilityComplete) {
                    completeEvidence.add(EvidenceKind.INHERITABILITY);
                }
                if (unresolved.isEmpty()) {
                    completeEvidence.add(EvidenceKind.HIERARCHY);
                }
                if (localSignaturesComplete) {
                    completeEvidence.add(EvidenceKind.LOCAL_SIGNATURES);
                }
                if (inherited.complete() && unresolved.isEmpty()) {
                    completeEvidence.add(EvidenceKind.INHERITED_MEMBERS);
                }
            }
            List<ObservationDiagnostic> diagnostics = new ArrayList<>(build.diagnostics());
            diagnostics.addAll(inherited.diagnostics());
            dependencies.verifyUnchanged();
            return new Observation(
                    "8", ADAPTER_ID, ADAPTER_VERSION, List.copyOf(allowed), completeEvidence,
                    units, classifiers, members, unresolved, diagnostics);
        } catch (ObservationException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw new ObservationException("Java observation failed: " + exception.getMessage(), exception);
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

    private static BuildResult buildTypes(Path root, List<Path> files, JavaDependencyInputs inputs) {
        JavaCompilationContexts.Grouping grouping = JavaCompilationContexts.group(root, files, inputs);
        Map<SourceTypeLocation, CtType<?>> observed = new LinkedHashMap<>();
        Set<SourceTypeLocation> conflicts = new LinkedHashSet<>();
        List<ObservationDiagnostic> diagnostics = new ArrayList<>();
        for (JavaCompilationContext context : grouping.contexts().stream()
                .sorted(Comparator.comparing(JavaCompilationContext::id)).toList()) {
            List<Path> owned = grouping.filesByContext().getOrDefault(context.id(), List.of());
            if (owned.isEmpty()) continue;
            BuildResult result = buildContextTypes(root, owned, context);
            diagnostics.addAll(result.diagnostics());
            mergeContextTypes(root, result.types(), observed, conflicts, diagnostics);
        }
        if (!grouping.unownedFiles().isEmpty()) {
            BuildResult unowned = buildContextTypes(root, grouping.unownedFiles(),
                    JavaCompilationContexts.sourceOnlyContext());
            diagnostics.addAll(unowned.diagnostics());
            mergeContextTypes(root, unowned.types(), observed, conflicts, diagnostics);
            for (Path file : grouping.unownedFiles()) {
                diagnostics.add(new ObservationDiagnostic(DiagnosticKind.EVIDENCE_INCOMPLETE,
                        relativePath(root, file), 0,
                        "Java source is not owned by an observed compilation context; only lexical recovery was attempted"));
            }
        }
        return new BuildResult(List.copyOf(observed.values()), diagnostics);
    }

    private static void mergeContextTypes(Path root, List<CtType<?>> types,
            Map<SourceTypeLocation, CtType<?>> observed, Set<SourceTypeLocation> conflicts,
            List<ObservationDiagnostic> diagnostics) {
        for (CtType<?> type : types) {
            SourcePosition position = type.getPosition();
            Path file = position.getFile().toPath().toAbsolutePath().normalize();
            SourceTypeLocation location = new SourceTypeLocation(file,
                    position.getSourceStart(), position.getSourceEnd());
            if (conflicts.contains(location)) continue;
            CtType<?> previous = observed.putIfAbsent(location, type);
            if (previous != null && !previous.equals(type)) {
                observed.remove(location);
                conflicts.add(location);
                diagnostics.add(new ObservationDiagnostic(DiagnosticKind.EVIDENCE_INCOMPLETE,
                        relativePath(root, file), position.getLine(),
                        "Conflicting structural declaration observations across compilation contexts"));
            }
        }
    }

    private record SourceTypeLocation(Path file, int start, int end) {}

    private static BuildResult buildContextTypes(Path root, List<Path> files, JavaCompilationContext context) {
        int requestedLevel = complianceLevel(context);
        int supportedLevel = Integer.parseInt(org.eclipse.jdt.internal.compiler.impl.CompilerOptions.getLatestVersion());
        if (requestedLevel > supportedLevel) {
            return new BuildResult(List.of(), files.stream().map(file -> new ObservationDiagnostic(
                    DiagnosticKind.EVIDENCE_INCOMPLETE, relativePath(root, file), 0,
                    "Java parser does not support requested source level " + requestedLevel
                            + "; maximum supported level is " + supportedLevel
                            + ". Build-observed compiler semantics were not changed.")).toList());
        }
        try {
            return new BuildResult(modelTypes(buildModel(files, context)), List.of());
        } catch (RuntimeException failure) {
            List<CtType<?>> isolated = new ArrayList<>();
            List<ObservationDiagnostic> diagnostics = new ArrayList<>();
            List<Path> parsedFiles = new ArrayList<>();
            Map<Path, RuntimeException> failures = new LinkedHashMap<>();
            for (Path file : files) {
                try {
                    isolated.addAll(modelTypes(buildModel(List.of(file), context)));
                    parsedFiles.add(file);
                } catch (RuntimeException isolatedFailure) {
                    failures.put(file, isolatedFailure);
                }
            }
            // Isolation can remove declarations needed by the frontend itself.
            // Retry failed units with known parseable peers, without dropping a
            // remaining failure or duplicating the peers' observed declarations.
            for (var entry : failures.entrySet()) {
                Path file = entry.getKey();
                if (!parsedFiles.isEmpty()) {
                    List<Path> recoveryFiles = new ArrayList<>(parsedFiles);
                    recoveryFiles.add(file);
                    recoveryFiles.sort(Comparator.naturalOrder());
                    try {
                        List<CtType<?>> recovered = modelTypes(buildModel(recoveryFiles, context));
                        isolated.addAll(recovered.stream().filter(type -> type.getPosition().getFile()
                                .toPath().toAbsolutePath().normalize().equals(file)).toList());
                        continue;
                    } catch (RuntimeException recoveryFailure) {
                        // Retain the original unit-specific failure. A combined
                        // model can also fail because its peers conflict.
                    }
                }
                diagnostics.add(parseDiagnostic(root, file, entry.getValue()));
            }
            List<CtType<?>> sorted = isolated.stream()
                    .sorted(Comparator.comparing((CtType<?> type) -> type.getQualifiedName())
                            .thenComparing(type -> type.getPosition().getFile().getPath()))
                    .toList();
            return new BuildResult(sorted, diagnostics);
        }
    }

    private static ObservationDiagnostic parseDiagnostic(
            Path root,
            Path file,
            RuntimeException failure) {
        String relative = relativePath(root, file);
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        message = message.replace(file.toAbsolutePath().normalize().toString(), relative)
                .replace(root.toAbsolutePath().normalize().toString(), ".")
                .replace('\r', ' ');
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < message.length() && normalized.length() < MAX_DIAGNOSTIC_MESSAGE; index++) {
            char character = message.charAt(index);
            normalized.append(Character.isISOControl(character) && character != '\n' && character != '\t'
                    ? ' ' : character);
        }
        String text = normalized.toString().trim();
        if (text.isEmpty()) {
            text = "Java parser rejected source unit";
        }
        Matcher line = DIAGNOSTIC_LINE.matcher(text);
        return new ObservationDiagnostic(
                DiagnosticKind.PARSE_ERROR,
                relative,
                line.find() ? Integer.parseInt(line.group(1)) : 0,
                text);
    }

    private static CtModel buildModel(List<Path> files, JavaCompilationContext context) {
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setComplianceLevel(complianceLevel(context));
        launcher.getEnvironment().setPreviewFeaturesEnabled(context.compilerSemantics().previewEnabled());
        launcher.getEnvironment().setCommentEnabled(false);
        files.forEach(file -> launcher.addInputResource(file.toString()));
        return launcher.buildModel();
    }

    private static int complianceLevel(JavaCompilationContext context) {
        JavaCompilerSemantics semantics = context.compilerSemantics();
        return semantics.releaseLevel() != null ? semantics.releaseLevel()
                : semantics.sourceLevel() != null ? semantics.sourceLevel() : Runtime.version().feature();
    }

    private static List<CtType<?>> modelTypes(CtModel model) {
        return SpoonSourceTypes.declarations(model).stream()
                .sorted(Comparator.comparing(CtType::getQualifiedName))
                .toList();
    }

    private static List<Path> discoverJavaFiles(Path root) throws IOException, ObservationException {
        List<Path> files;
        try (Stream<Path> paths = Files.walk(root)) {
            files = paths.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(path -> relativePath(root, path)))
                    .toList();
        }
        for (Path file : files) {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new ObservationException("non-regular or symbolic-link Java source rejected: " + file);
            }
            Path real = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.startsWith(root)) {
                throw new ObservationException("source escapes declared root: " + file);
            }
        }
        return files;
    }

    private static List<CtTypeReference<?>> directParents(CtType<?> type) {
        List<CtTypeReference<?>> parents = new ArrayList<>();
        if (type instanceof CtClass<?> ctClass && ctClass.getSuperclass() != null) {
            parents.add(ctClass.getSuperclass());
        }
        parents.addAll(type.getSuperInterfaces());
        return parents.stream().sorted(Comparator.comparing(CtTypeReference::getQualifiedName)).toList();
    }

    private static ClassifierKind kindOf(CtType<?> type) throws ObservationException {
        if (type instanceof CtAnnotationType<?>) {
            return ClassifierKind.ANNOTATION;
        }
        if (type instanceof CtEnum<?>) {
            return ClassifierKind.ENUM;
        }
        if (type instanceof CtInterface<?>) {
            return ClassifierKind.INTERFACE;
        }
        if (type instanceof CtClass<?>) {
            return ClassifierKind.CLASS;
        }
        throw new ObservationException("unsupported Java classifier: " + type.getQualifiedName());
    }

    private static String sourcePath(Path root, CtType<?> type) throws IOException, ObservationException {
        Path source = type.getPosition().getFile().toPath().toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!source.startsWith(root)) {
            throw new ObservationException("Spoon reported a source outside the declared root: " + source);
        }
        return relativePath(root, source);
    }

    private static String packageName(CtType<?> type) {
        String value = type.getPackage() == null ? null : type.getPackage().getQualifiedName();
        return value == null || value.isBlank() ? "<default>" : value;
    }

    private static List<TypeDraft> scopedCandidates(
            TypeDraft owner,
            String qualifiedName,
            Map<String, List<TypeDraft>> global,
            JavaDependencyInputs inputs) {
        List<TypeDraft> candidates = global.get(qualifiedName);
        if (candidates == null || candidates.isEmpty() || inputs.contexts().isEmpty()) {
            return candidates;
        }
        Set<String> visible = new LinkedHashSet<>(owner.contextIds());
        for (String contextId : owner.contextIds()) {
            if (inputs.context(contextId) != null) {
                visible.addAll(inputs.upstreamClosure(contextId));
            }
        }
        List<TypeDraft> scoped = candidates.stream()
                .filter(candidate -> candidate.contextIds().stream().anyMatch(visible::contains))
                .toList();
        return scoped.isEmpty() ? null : scoped;
    }

    private static Set<String> contextIdsForSourcePath(JavaDependencyInputs inputs, String sourcePath) {
        if (inputs.contexts().isEmpty()) {
            return Set.of(JavaCompilationContexts.sourceOnlyContext().id());
        }
        return inputs.contextsForSourcePath(sourcePath).stream()
                .map(JavaCompilationContext::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static int referenceLine(CtTypeReference<?> reference, int fallback) {
        return reference.getPosition().isValidPosition() ? reference.getPosition().getLine() : fallback;
    }

    private static String relativePath(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String stableId(String path, ClassifierKind kind, String qualifiedName) {
        return "cls_" + Hashing.sha256("java\0" + path + "\0" + kind + "\0" + qualifiedName);
    }

    private static MemberObservation memberObservation(
            Path root,
            TypeDraft owner,
            MemberKind kind,
            String memberName,
            Inheritability inheritability,
            MemberVisibility visibility,
            SourcePosition position,
            List<String> parameterTypes) throws IOException, ObservationException {
        Path source = position.getFile().toPath().toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!source.startsWith(root)) {
            throw new ObservationException("Spoon reported a member outside the declared root: " + source);
        }
        String path = relativePath(root, source);
        String canonical = "java\0" + path + "\0" + owner.type().getQualifiedName() + "\0"
                + kind + "\0" + memberName + "\0" + String.join("\0", parameterTypes)
                + "\0" + position.getLine();
        return new MemberObservation(
                "mem_" + Hashing.sha256(canonical),
                null,
                kind,
                inheritability,
                visibility,
                memberName,
                path,
                position.getLine(),
                position.getEndLine(),
                parameterTypes);
    }

    private static Inheritability inheritability(CtType<?> owner, CtModifiable member) {
        if (member.hasModifier(ModifierKind.PRIVATE)) {
            return Inheritability.NOT_INHERITABLE;
        }
        if (owner instanceof CtInterface<?> && member.hasModifier(ModifierKind.STATIC)) {
            return Inheritability.NOT_INHERITABLE;
        }
        return Inheritability.INHERITABLE;
    }

    private static MemberVisibility visibility(CtType<?> owner, CtModifiable member) {
        if (member.hasModifier(ModifierKind.PRIVATE)) {
            return MemberVisibility.PRIVATE;
        }
        if (member.hasModifier(ModifierKind.PROTECTED)) {
            return MemberVisibility.PROTECTED;
        }
        if (owner instanceof CtInterface<?> || member.hasModifier(ModifierKind.PUBLIC)) {
            return MemberVisibility.PUBLIC;
        }
        return MemberVisibility.PACKAGE;
    }

    private record TypeDraft(
            CtType<?> type, String id, String path, Set<String> contextIds, String packageName,
            ClassifierKind kind, int startLine, int endLine) {
        private TypeDraft {
            contextIds = Set.copyOf(contextIds);
        }
    }

    private record BuildResult(List<CtType<?>> types, List<ObservationDiagnostic> diagnostics) {
        private BuildResult {
            types = List.copyOf(types);
            diagnostics = diagnostics.stream()
                    .sorted(Comparator.comparing(ObservationDiagnostic::sourcePath)
                            .thenComparingInt(ObservationDiagnostic::line)
                            .thenComparing(ObservationDiagnostic::message))
                    .toList();
        }
    }
}
