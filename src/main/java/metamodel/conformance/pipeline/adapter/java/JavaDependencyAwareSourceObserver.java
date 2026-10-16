package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.adapter.SourceObserver;
import metamodel.conformance.pipeline.adapter.SourceInputSnapshot;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;
import metamodel.conformance.pipeline.model.PlatformEvidence;
import metamodel.conformance.pipeline.model.SourceUnit;
import metamodel.conformance.pipeline.model.UnresolvedParent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Adds dependency bytecode evidence under exact build-observed compilation contexts. */
public final class JavaDependencyAwareSourceObserver implements SourceObserver {
    public static final String ADAPTER_ID = JavaImplementationSourceObserver.ADAPTER_ID;
    public static final String ADAPTER_VERSION = "1.28.0";

    private final JavaDependencyInputs dependencyInputs;
    private final SourceObserver delegate;
    private final boolean decodeContextualFrames;

    public JavaDependencyAwareSourceObserver(List<Path> dependencyArchives) {
        this(JavaDependencyInputs.global(dependencyArchives));
    }

    public JavaDependencyAwareSourceObserver(JavaDependencyInputs dependencyInputs) {
        this(dependencyInputs, new JavaImplementationSourceObserver(dependencyInputs), true);
    }

    JavaDependencyAwareSourceObserver(
            JavaDependencyInputs dependencyInputs,
            SourceObserver delegate) {
        this(dependencyInputs, delegate, false);
    }

    JavaDependencyAwareSourceObserver(JavaDependencyInputs dependencyInputs,
            SourceObserver delegate, boolean decodeContextualFrames) {
        this.dependencyInputs = dependencyInputs == null
                ? JavaDependencyInputs.none() : dependencyInputs;
        this.delegate = delegate;
        this.decodeContextualFrames = decodeContextualFrames;
    }

    @Override
    public Observation observe(Path sourceRoot, Set<String> externalParents) throws ObservationException {
        // Injected delegates supply their own observed frame; the production decoder
        // captures once so every compiler world consumes the same source population.
        if (!decodeContextualFrames) {
            return observeCaptured(sourceRoot, externalParents);
        }
        try {
            Path original = SpoonJavaObserver.validateRoot(sourceRoot);
            try (SourceInputSnapshot snapshot = SourceInputSnapshot.capture(
                    original, SpoonJavaObserver.discoverJavaFiles(original))) {
                Observation observed = observeCaptured(snapshot.root(), externalParents);
                Set<SourceUnit> actual = observed.units().stream()
                        .filter(unit -> unit.language() == Language.JAVA)
                        .collect(java.util.stream.Collectors.toSet());
                if (!actual.equals(Set.copyOf(snapshot.units(Language.JAVA)))) {
                    throw new ObservationException("Java observation source identities differ from captured inputs");
                }
                snapshot.verifyOriginalsUnchanged(SpoonJavaObserver.discoverJavaFiles(original));
                return observed;
            }
        } catch (IOException failure) {
            throw new ObservationException("Java source capture failed", failure);
        }
    }

    private Observation observeCaptured(Path sourceRoot, Set<String> externalParents) throws ObservationException {
        if (!decodeContextualFrames) return decodeFrame(sourceRoot, externalParents).observation();
        boolean sourceOnly = dependencyInputs.contexts().isEmpty();
        List<JavaCompilationContext> contexts = sourceOnly ? List.of(JavaCompilationContexts.sourceOnlyContext())
                : dependencyInputs.observationContexts();
        Map<String, Observation> frames = new TreeMap<>();
        for (JavaCompilationContext context : contexts) {
            JavaDependencyInputs selected = sourceOnly ? dependencyInputs : dependencyInputs.forObservationContext(context.id());
            JavaDependencyAwareSourceObserver decoder = contexts.size() == 1 ? this : new JavaDependencyAwareSourceObserver(selected);
            PlatformEvidence runtimePlatform = sourceOnly ? JavaPlatformProvenance.capture(context).evidence() : null;
            DecodedFrame decoded = decoder.decodeFrame(sourceRoot, externalParents);
            if (sourceOnly && !runtimePlatform.equals(JavaPlatformProvenance.capture(context).evidence())) {
                throw new ObservationException("source-only Java platform/compiler image changed during observation");
            }
            frames.put(context.id(), JavaNativeDeclarations.populate(context, selected, decoded.observation(),
                    decoded.declarations(), domainIdentity(context, selected, decoded.observation(), sourceOnly, runtimePlatform), sourceOnly));
        }
        try {
            return JavaContextualObservation.mergeQualifiedFrames(frames);
        } catch (IllegalArgumentException inconsistentFrames) {
            throw new ObservationException("Java contextual frame aggregation failed: "
                    + inconsistentFrames.getMessage(), inconsistentFrames);
        }
    }

    private record DecodedFrame(Observation observation, JavacDirectParentNames.Result declarations) { }

    private DecodedFrame decodeFrame(Path sourceRoot, Set<String> externalParents) throws ObservationException {
        Observation base = delegate.observe(sourceRoot, externalParents);
        try {
            Path root = sourceRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
            List<Path> files = observedJavaFiles(root, base.units());
            boolean parseFailed = base.diagnostics().stream().anyMatch(item -> item.kind() == DiagnosticKind.PARSE_ERROR);
            var declarations = decodeContextualFrames && !parseFailed
                    ? JavacDirectParentNames.measure(root, files, dependencyInputs, base) : JavacDirectParentNames.Result.empty();
            if (dependencyInputs.contexts().isEmpty() || parseFailed
                    || (base.classifiers().isEmpty() && !base.completeEvidence().contains(EvidenceKind.DECLARATION_OWNERSHIP))) {
                return new DecodedFrame(base, declarations);
            }
            if (decodeContextualFrames && !base.unresolvedParents().isEmpty()) {
                var names = declarations.parentNames();
                if (!names.isEmpty()) {
                    base = new Observation(base.schemaVersion(), base.adapterId(), base.adapterVersion(),
                            base.externalParents(), base.completeEvidence(), base.units(), base.classifiers(), base.members(),
                            base.methodBodies(), base.implementationBindings(), base.unresolvedParents().stream()
                            .map(p -> new UnresolvedParent(p.ownerId(), names.getOrDefault(p, p.targetName()), p.sourcePath(), p.line()))
                            .toList(), base.diagnostics(), base.platformEvidence(), base.nativeEvidence());
                }
            }
            JavaCompilationContexts.Grouping grouping = JavaCompilationContexts.group(root, files, dependencyInputs);
            Map<String, ClassifierObservation> sourceById = new HashMap<>();
            base.classifiers().forEach(classifier -> sourceById.put(classifier.id(), classifier));

            Map<String, List<UnresolvedParent>> unresolvedByContext = new TreeMap<>();
            boolean dependencyEvidenceComplete = grouping.unownedFiles().isEmpty();
            List<ObservationDiagnostic> diagnostics = new ArrayList<>();
            for (UnresolvedParent unresolved : base.unresolvedParents()) {
                ClassifierObservation owner = sourceById.get(unresolved.ownerId());
                if (owner == null) {
                    throw new ObservationException(
                            "unresolved parent references an unavailable source classifier: "
                                    + unresolved.ownerId());
                }
                List<JavaCompilationContext> owners = dependencyInputs.contextsForSourcePath(owner.sourcePath());
                if (owners.isEmpty()) {
                    dependencyEvidenceComplete = false;
                    diagnostics.add(new ObservationDiagnostic(
                            DiagnosticKind.EVIDENCE_INCOMPLETE, owner.sourcePath(), 0,
                            "unresolved parent owner is not assigned to an observed compilation context"));
                    continue;
                }
                for (JavaCompilationContext context : owners) {
                    unresolvedByContext.computeIfAbsent(context.id(), ignored -> new ArrayList<>()).add(unresolved);
                }
            }

            Map<String, String> supportParentByUnresolvedKey = new HashMap<>();
            Map<String, ClassifierObservation> supportClassifiersById = new TreeMap<>();
            Map<String, MemberObservation> supportMembersByKey = new TreeMap<>();
            Map<String, SourceUnit> supportUnitsByPath = new TreeMap<>();
            Map<String, PlatformEvidence> platformEvidenceByContext = new TreeMap<>();
            Map<String, List<String>> inheritedByClassifier = new HashMap<>();
            Map<String, List<String>> overridesByMember = new HashMap<>();
            Map<String, String> returnTypesByMember = new HashMap<>();
            Set<String> parentConflicts = new TreeSet<>();
            Set<String> inheritedConflicts = new TreeSet<>();
            Set<String> overrideConflicts = new TreeSet<>();
            Set<String> returnConflicts = new TreeSet<>();

            for (Map.Entry<String, List<Path>> entry : grouping.filesByContext().entrySet()) {
                JavaCompilationContext context = grouping.context(entry.getKey());
                if (context == null) {
                    dependencyEvidenceComplete = false;
                    continue;
                }
                Set<String> evidenceContextIds = new TreeSet<>();
                evidenceContextIds.add(context.id());
                evidenceContextIds.addAll(dependencyInputs.upstreamClosure(context.id()));

                Map<String, ClassifierObservation> contextSupportClassifiers = new TreeMap<>();
                Map<String, MemberObservation> contextSupportMembers = new TreeMap<>();
                Set<String> resolvedDirectRoots = new TreeSet<>();
                // The active context's own support resolution ids, used to
                // disambiguate support types that several evidence contexts
                // materialize under different platform releases.
                Map<String, String> activeSupportIdsByQualifiedName = Map.of();

                JavaDependencyClasspath.Result activeClasspath = JavaDependencyClasspath.resolve(
                        dependencyInputs.archivePathsForContext(context.id()));
                for (String evidenceContextId : evidenceContextIds) {
                    List<UnresolvedParent> unresolved = unresolvedByContext.getOrDefault(
                            evidenceContextId, List.of());
                    JavaDependencyClasspath.Result evidenceClasspath = JavaDependencyClasspath.resolve(
                            dependencyInputs.archivePathsForContext(evidenceContextId));
                    Set<String> roots = unresolved.stream().map(UnresolvedParent::targetName)
                            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
                    if (!evidenceContextId.equals(context.id())) {
                        for (String target : roots) {
                            JavaDependencyClasspath.Entry upstreamOwner = evidenceClasspath.ownerOfType(target);
                            JavaDependencyClasspath.Entry activeOwner = activeClasspath.ownerOfType(target);
                            if (activeOwner != null && upstreamOwner != null
                                    && !activeOwner.unit().sha256().equals(upstreamOwner.unit().sha256())) {
                                dependencyEvidenceComplete = false;
                                diagnostics.add(incompleteDiagnostic(
                                        base, context,
                                        "compilation contexts resolve different bytecode for inherited dependency "
                                                + target));
                            }
                        }
                    }
                    // javac's effective member view of a source type reaches
                    // implicit platform supertypes that neither source observation
                    // nor unresolved-parent evidence reports. Materialize them for
                    // the contexts that actually declare such a type so
                    // inherited-member correspondence has an observed platform
                    // owner.
                    Set<String> evidencePaths = grouping.filesByContext()
                            .getOrDefault(evidenceContextId, List.of()).stream()
                            .map(path -> relativePath(root, path))
                            .collect(java.util.stream.Collectors.toSet());
                    Set<String> materializationRoots = new TreeSet<>(roots);
                    for (ClassifierObservation candidate : base.classifiers()) {
                        if (!evidencePaths.contains(candidate.sourcePath())) {
                            continue;
                        }
                        if (candidate.kind() == ClassifierKind.CLASS) {
                            materializationRoots.add("java.lang.Object");
                        } else if (candidate.kind() == ClassifierKind.ANNOTATION) {
                            materializationRoots.add("java.lang.annotation.Annotation");
                        }
                    }
                    JavaDependencySymbols.Result symbols;
                    JavaPlatformProvenance platform;
                    try (JavacCompilationContext evidenceJavac = JavacCompilationContext.prepare(
                            root, grouping.context(evidenceContextId), grouping.filesByContext(), dependencyInputs)) {
                        if (!evidenceJavac.complete()) {
                            dependencyEvidenceComplete = false;
                            diagnostics.addAll(evidenceJavac.diagnostics());
                            continue;
                        }
                        platform = JavaPlatformProvenance.capture(grouping.context(evidenceContextId));
                        symbols = JavaDependencySymbols.resolve(
                                evidenceClasspath, materializationRoots, platform, evidenceJavac.options());
                    }
                    if (!symbols.unresolvedRootTypes().isEmpty()) {
                        dependencyEvidenceComplete = false;
                        for (String missing : symbols.unresolvedRootTypes()) {
                            diagnostics.add(incompleteDiagnostic(
                                    base, context,
                                    "dependency bytecode root could not be materialized: " + missing));
                        }
                    }
                    JavaDependencyObservation.Result support = JavaDependencyObservation.materialize(symbols);
                    mergeSupport(support, contextSupportClassifiers, contextSupportMembers);
                    mergeSupport(support, supportClassifiersById, supportMembersByKey);
                    mergeSupportUnits(support.units(), supportUnitsByPath);
                    mergePlatformEvidence(platformEvidenceByContext, platform, symbols);
                    if (evidenceContextId.equals(context.id())) {
                        // This is the active context's own resolution: its
                        // platform and classpath are exactly what this
                        // context's compilation used, so its classifier ids
                        // disambiguate support types that several evidence
                        // contexts materialize under different releases.
                        activeSupportIdsByQualifiedName = support.classifierIdByQualifiedName();
                    }
                    Set<String> resolvedRoots = new TreeSet<>(roots);
                    resolvedRoots.removeAll(symbols.unresolvedRootTypes());
                    for (UnresolvedParent item : unresolved) {
                        if (!resolvedRoots.contains(item.targetName())) continue;
                        String supportId = support.classifierId(item.targetName());
                        if (supportId != null) {
                            dependencyEvidenceComplete &= mergeConsistent(
                                    supportParentByUnresolvedKey, Map.of(unresolvedKey(item), supportId),
                                    parentConflicts, diagnostics, base, context, "parent evidence");
                            if (evidenceContextId.equals(context.id())) {
                                resolvedDirectRoots.add(item.targetName());
                            }
                        }
                    }
                    evidenceClasspath.verifyUnchanged();
                }

                Set<String> scopedPaths = entry.getValue().stream()
                        .map(path -> relativePath(root, path))
                        .collect(java.util.stream.Collectors.toSet());
                List<ClassifierObservation> scopedClassifiers = base.classifiers().stream()
                        .filter(item -> scopedPaths.contains(item.sourcePath())).toList();
                List<MemberObservation> scopedMembers = base.members().stream()
                        .filter(item -> scopedPaths.contains(item.sourcePath())).toList();
                Set<String> upstreamPaths = dependencyInputs.upstreamClosure(context.id()).stream()
                        .flatMap(id -> grouping.filesByContext().getOrDefault(id, List.of()).stream())
                        .map(path -> relativePath(root, path))
                        .collect(java.util.stream.Collectors.toSet());
                List<ClassifierObservation> upstreamClassifiers = base.classifiers().stream()
                        .filter(item -> upstreamPaths.contains(item.sourcePath())).toList();
                List<MemberObservation> upstreamMembers = base.members().stream()
                        .filter(item -> upstreamPaths.contains(item.sourcePath())).toList();

                JavacDependencyEvidenceObserver.Result observed;
                List<ClassifierObservation> contextSupport = disambiguatedContextSupport(
                        contextSupportClassifiers, activeSupportIdsByQualifiedName);
                try (JavacCompilationContext javac = JavacCompilationContext.prepare(
                        root, context, grouping.filesByContext(), dependencyInputs)) {
                    observed = javac.complete()
                            ? JavacDependencyEvidenceObserver.observeCompilationContext(
                                    root, entry.getValue(), scopedClassifiers, scopedMembers,
                                    upstreamClassifiers, upstreamMembers,
                                    contextSupport,
                                    List.copyOf(contextSupportMembers.values()), javac.options())
                            : JavacDependencyEvidenceObserver.Result.incomplete(javac.diagnostics());
                }
                dependencyEvidenceComplete &= observed.complete();
                diagnostics.addAll(observed.diagnostics());
                dependencyEvidenceComplete &= mergeConsistent(
                        inheritedByClassifier, observed.inheritedByClassifier(), inheritedConflicts,
                        diagnostics, base, context,
                        "inherited-member evidence");
                dependencyEvidenceComplete &= mergeConsistent(
                        overridesByMember, observed.overriddenMemberKeysByMember(), overrideConflicts,
                        diagnostics, base, context,
                        "override evidence");
                dependencyEvidenceComplete &= mergeConsistent(
                        returnTypesByMember, observed.returnTypesByMember(), returnConflicts,
                        diagnostics, base, context,
                        "return-type evidence");
                activeClasspath.verifyUnchanged();
            }

            if (!grouping.unownedFiles().isEmpty()) {
                diagnostics.add(new ObservationDiagnostic(
                        DiagnosticKind.EVIDENCE_INCOMPLETE,
                        relativePath(root, grouping.unownedFiles().get(0)), 0,
                        "Java source is not owned by an observed compilation context"));
            }

            Map<String, List<String>> supportParentsBySourceClassifier = new HashMap<>();
            List<UnresolvedParent> remainingUnresolved = new ArrayList<>();
            for (UnresolvedParent unresolved : base.unresolvedParents()) {
                String supportId = supportParentByUnresolvedKey.get(unresolvedKey(unresolved));
                if (supportId == null) {
                    remainingUnresolved.add(unresolved);
                } else {
                    supportParentsBySourceClassifier
                            .computeIfAbsent(unresolved.ownerId(), ignored -> new ArrayList<>())
                            .add(supportId);
                }
            }

            List<ClassifierObservation> sourceClassifiers = base.classifiers().stream()
                    .map(classifier -> {
                        LinkedHashSet<String> parents = new LinkedHashSet<>(classifier.parentIds());
                        parents.addAll(supportParentsBySourceClassifier
                                .getOrDefault(classifier.id(), List.of()));
                        List<String> inherited = inheritedConflicts.contains(classifier.id()) ? List.of()
                                : inheritedByClassifier.containsKey(classifier.id())
                                ? inheritedByClassifier.get(classifier.id())
                                : classifier.inheritedMemberKeys();
                        return new ClassifierObservation(
                                classifier.id(), classifier.qualifiedName(), classifier.packageName(),
                                classifier.kind(), classifier.sourcePath(), classifier.startLine(),
                                classifier.endLine(), parents.stream().sorted().toList(),
                                classifier.declaredMemberKeys(), inherited, classifier.abstraction());
                    }).toList();

            List<MemberObservation> sourceMembers = base.members().stream().map(member -> {
                if (member.kind() != MemberKind.METHOD) return member;
                return new MemberObservation(
                        member.technicalKey(), member.observedIdentifier(), member.kind(),
                        member.inheritability(), member.visibility(), member.memberName(),
                        member.sourcePath(), member.startLine(), member.endLine(),
                        member.parameterTypes(), member.abstraction(), member.scope(),
                        returnConflicts.contains(member.technicalKey()) ? null
                                : returnTypesByMember.getOrDefault(member.technicalKey(), member.returnType()),
                        overrideConflicts.contains(member.technicalKey()) ? List.of()
                                : overridesByMember.getOrDefault(member.technicalKey(), member.overriddenMemberKeys()));
            }).toList();

            List<ClassifierObservation> classifiers = new ArrayList<>(sourceClassifiers);
            classifiers.addAll(supportClassifiersById.values());
            List<MemberObservation> members = new ArrayList<>(sourceMembers);
            members.addAll(supportMembersByKey.values());

            EnumSet<EvidenceKind> evidence = EnumSet.noneOf(EvidenceKind.class);
            evidence.addAll(base.completeEvidence());
            boolean boundaryComplete = dependencyEvidenceComplete && remainingUnresolved.isEmpty();
            if (boundaryComplete) {
                evidence.add(EvidenceKind.HIERARCHY);
                evidence.add(EvidenceKind.INHERITED_MEMBERS);
                if (!overridesByMember.isEmpty()
                        || base.completeEvidence().contains(EvidenceKind.OVERRIDE_RELATIONS)) {
                    evidence.add(EvidenceKind.OVERRIDE_RELATIONS);
                }
                if (!returnTypesByMember.isEmpty()
                        || base.completeEvidence().contains(EvidenceKind.METHOD_RETURN_TYPES)) {
                    evidence.add(EvidenceKind.METHOD_RETURN_TYPES);
                }
            } else {
                evidence.remove(EvidenceKind.HIERARCHY);
                evidence.remove(EvidenceKind.INHERITED_MEMBERS);
                evidence.remove(EvidenceKind.METHOD_RETURN_TYPES);
                evidence.remove(EvidenceKind.OVERRIDE_RELATIONS);
            }

            JavaDependencyClasspath.Result allDependencies = JavaDependencyClasspath.resolve(dependencyInputs);
            allDependencies.verifyUnchanged();
            return new DecodedFrame(new Observation(
                    "14", ADAPTER_ID, ADAPTER_VERSION, base.externalParents(), evidence,
                    mergeUnits(base.units(), mergedSupportUnits(allDependencies.units(), supportUnitsByPath.values())), classifiers, members,
                    base.methodBodies(), base.implementationBindings(), remainingUnresolved,
                    mergeDiagnostics(base.diagnostics(), diagnostics),
                    List.copyOf(platformEvidenceByContext.values())), declarations);
        } catch (ObservationException exception) {
            throw exception;
        } catch (IOException | RuntimeException failure) {
            throw new ObservationException(
                    "Java compilation-context dependency observation failed: " + failure.getMessage(), failure);
        }
    }

    private static <K, V> boolean mergeConsistent(
            Map<K, V> target, Map<K, V> observed, Set<K> conflicts,
            List<ObservationDiagnostic> diagnostics, Observation base,
            JavaCompilationContext context, String label) {
        boolean consistent = true;
        for (Map.Entry<K, V> entry : observed.entrySet()) {
            if (conflicts.contains(entry.getKey())) {
                consistent = false;
                continue;
            }
            V previous = target.putIfAbsent(entry.getKey(), entry.getValue());
            if (previous != null && !previous.equals(entry.getValue())) {
                consistent = false;
                target.remove(entry.getKey());
                conflicts.add(entry.getKey());
                diagnostics.add(incompleteDiagnostic(base, context,
                        "conflicting " + label + " across compilation contexts"));
            }
        }
        return consistent;
    }

    /**
     * Several evidence contexts (a context and its upstream closure) can
     * materialize the same support type under different platform releases, so
     * the merged support map holds several candidates for one qualified name.
     * The active context's own resolution is exactly what its compilation
     * used, so it disambiguates the candidates; a name the active context did
     * not resolve keeps every candidate, which the evidence observer fails
     * closed on rather than guessing.
     */
    private static List<ClassifierObservation> disambiguatedContextSupport(
            Map<String, ClassifierObservation> contextSupportClassifiers,
            Map<String, String> activeSupportIdsByQualifiedName) {
        if (contextSupportClassifiers.isEmpty()) {
            return List.of();
        }
        Map<String, List<ClassifierObservation>> byQualifiedName = new HashMap<>();
        for (ClassifierObservation classifier : contextSupportClassifiers.values()) {
            byQualifiedName.computeIfAbsent(classifier.qualifiedName(), ignored -> new ArrayList<>())
                    .add(classifier);
        }
        List<ClassifierObservation> support = new ArrayList<>();
        for (Map.Entry<String, List<ClassifierObservation>> candidates : byQualifiedName.entrySet()) {
            if (candidates.getValue().size() == 1) {
                support.add(candidates.getValue().get(0));
                continue;
            }
            String activeId = activeSupportIdsByQualifiedName.get(candidates.getKey());
            ClassifierObservation preferred = null;
            if (activeId != null) {
                for (ClassifierObservation candidate : candidates.getValue()) {
                    if (candidate.id().equals(activeId)) {
                        preferred = candidate;
                        break;
                    }
                }
            }
            if (preferred != null) {
                support.add(preferred);
            } else {
                support.addAll(candidates.getValue());
            }
        }
        return support;
    }

    private static void mergeSupport(
            JavaDependencyObservation.Result support,
            Map<String, ClassifierObservation> classifiers,
            Map<String, MemberObservation> members) throws ObservationException {
        for (ClassifierObservation classifier : support.classifiers()) {
            ClassifierObservation previous = classifiers.putIfAbsent(classifier.id(), classifier);
            if (previous != null && !previous.equals(classifier)) {
                throw new ObservationException(
                        "dependency support classifier identity collision: " + classifier.id()
                                + " (" + classifier.qualifiedName() + ") "
                                + previous.sourcePath() + " vs " + classifier.sourcePath());
            }
        }
        for (MemberObservation member : support.members()) {
            MemberObservation previous = members.putIfAbsent(member.technicalKey(), member);
            if (previous != null && !previous.equals(member)) {
                throw new ObservationException(
                        "dependency support member identity collision: " + member.technicalKey()
                                + " (" + member.memberName() + ") "
                                + previous.sourcePath() + " vs " + member.sourcePath());
            }
        }
    }

    private static void mergeSupportUnits(List<SourceUnit> units, Map<String, SourceUnit> target)
            throws ObservationException {
        for (SourceUnit unit : units) {
            SourceUnit previous = target.putIfAbsent(unit.path(), unit);
            if (previous != null && !previous.equals(unit)) {
                throw new ObservationException("support source-unit identity collision: " + unit.path());
            }
        }
    }

    private static List<SourceUnit> mergedSupportUnits(
            List<SourceUnit> dependencies, java.util.Collection<SourceUnit> supportUnits) {
        Map<String, SourceUnit> merged = new TreeMap<>();
        for (SourceUnit unit : dependencies) merged.put(unit.path(), unit);
        for (SourceUnit unit : supportUnits) {
            SourceUnit previous = merged.putIfAbsent(unit.path(), unit);
            if (previous != null && !previous.equals(unit)) {
                throw new IllegalArgumentException("support source-unit identity collision: " + unit.path());
            }
        }
        return List.copyOf(merged.values());
    }

    private static void mergePlatformEvidence(
            Map<String, PlatformEvidence> target,
            JavaPlatformProvenance provenance,
            JavaDependencySymbols.Result symbols) throws ObservationException {
        List<String> terminals = symbols.types().stream()
                .filter(type -> type.sourceLanguage() == Language.JAVA_PLATFORM)
                .filter(type -> type.parentQualifiedNames().isEmpty())
                .map(JavaDependencySymbols.TypeSymbol::qualifiedName).sorted().toList();
        if (terminals.isEmpty()) return;
        PlatformEvidence candidate = provenance.withTerminalTypes(terminals);
        PlatformEvidence previous = target.get(candidate.contextId());
        if (previous == null) {
            target.put(candidate.contextId(), candidate);
            return;
        }
        if (!previous.compilerIdentity().equals(candidate.compilerIdentity())
                || !previous.compilerSemantics().equals(candidate.compilerSemantics())
                || !previous.platformIdentity().equals(candidate.platformIdentity())
                || !previous.platformContentSha256().equals(candidate.platformContentSha256())) {
            throw new ObservationException("conflicting platform provenance for compilation context: "
                    + candidate.contextId());
        }
        List<String> combined = new ArrayList<>(previous.terminalTypeNames());
        combined.addAll(candidate.terminalTypeNames());
        target.put(candidate.contextId(), previous.withTerminalTypeNames(combined));
    }

    private static ObservationDiagnostic incompleteDiagnostic(
            Observation base, JavaCompilationContext context, String message) {
        String source = base.classifiers().stream()
                .filter(item -> context.ownsSourcePath(item.sourcePath()))
                .map(ClassifierObservation::sourcePath).sorted().findFirst()
                .orElseGet(() -> base.units().stream()
                        .filter(unit -> unit.language() == metamodel.conformance.pipeline.model.Language.JAVA)
                        .map(SourceUnit::path).sorted().findFirst().orElse("<unknown>.java"));
        return new ObservationDiagnostic(DiagnosticKind.EVIDENCE_INCOMPLETE, source, 0, message);
    }

    private static String unresolvedKey(UnresolvedParent unresolved) {
        return unresolved.ownerId() + "\0" + unresolved.targetName() + "\0"
                + unresolved.sourcePath() + "\0" + unresolved.line();
    }

    private static List<SourceUnit> mergeUnits(
            List<SourceUnit> base,
            List<SourceUnit> dependencies) {
        Map<String, SourceUnit> byPath = new TreeMap<>();
        for (SourceUnit unit : base) {
            byPath.put(unit.path(), unit);
        }
        for (SourceUnit unit : dependencies) {
            SourceUnit previous = byPath.putIfAbsent(unit.path(), unit);
            if (previous != null && !previous.equals(unit)) {
                throw new IllegalArgumentException("source-unit identity collision: " + unit.path());
            }
        }
        return List.copyOf(byPath.values());
    }

    private static List<ObservationDiagnostic> mergeDiagnostics(
            List<ObservationDiagnostic> base,
            List<ObservationDiagnostic> extra) {
        List<ObservationDiagnostic> all = new ArrayList<>(base);
        all.addAll(extra);
        return all.stream().distinct()
                .sorted(Comparator.comparing(ObservationDiagnostic::sourcePath)
                        .thenComparingInt(ObservationDiagnostic::line)
                        .thenComparing(item -> item.kind().name())
                        .thenComparing(ObservationDiagnostic::message))
                .toList();
    }

    private static List<Path> observedJavaFiles(
            Path root, List<SourceUnit> units) throws IOException, ObservationException {
        Map<String, Path> filesByPath = new TreeMap<>();
        for (SourceUnit unit : units) {
            if (unit.language() != Language.JAVA) {
                continue;
            }
            Path candidate = root.resolve(unit.path()).normalize();
            if (!candidate.startsWith(root)) {
                throw new ObservationException("observed Java source escapes declared root: " + unit.path());
            }
            if (Files.isSymbolicLink(candidate)
                    || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new ObservationException(
                        "observed Java source is not a regular file: " + unit.path());
            }
            Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.startsWith(root)) {
                throw new ObservationException("observed Java source escapes declared root: " + unit.path());
            }
            String relative = relativePath(root, real);
            if (!relative.equals(unit.path())) {
                throw new ObservationException(
                        "observed Java source path changed after canonical observation: " + unit.path());
            }
            filesByPath.putIfAbsent(relative, real);
        }
        return List.copyOf(filesByPath.values());
    }

    private static JavaContextualObservation.DomainIdentity domainIdentity(
            JavaCompilationContext context, JavaDependencyInputs inputs, Observation frame, boolean sourceOnly,
            PlatformEvidence runtimePlatform) throws ObservationException {
        Map<String, Object> identity = new TreeMap<>();
        identity.put("format", "java-decoded-context-v4");
        identity.put("mode", sourceOnly ? "SOURCE_ONLY" : "SELECTED_BUILD");
        identity.put("context", context.id());
        identity.put("module", context.moduleKey());
        identity.put("sources", context.sourceRoots());
        identity.put("generatedSources", context.generatedSourceRoots());
        identity.put("excludedSources", inputs.excludedSources(context.id()).stream().sorted().toList());
        identity.put("declaredNoncompiledSources", inputs.noncompiledSources().stream().sorted().toList());
        identity.put("outputs", context.outputs().stream().map(Path::toString).toList());
        identity.put("upstream", context.upstreamContextIds().stream().sorted().toList());
        identity.put("resolutionPaths", context.resolutionPaths().stream().map(path -> Map.of(
                "role", path.role().name(), "qualifier", path.qualifier(),
                "entries", path.entries().stream().map(Path::toString).toList())).toList());
        identity.put("compiler", context.compilerSemantics());
        identity.put("observedUnitSnapshot", metamodel.conformance.pipeline.util.Hashing.sourceSetDigest(frame.units()));
        identity.put("observedPlatform", frame.platformEvidence());
        if (runtimePlatform != null) identity.put("sourceOnlyRuntimePlatform", runtimePlatform);
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(identity);
            Set<String> assignedPaths = frame.units().stream()
                    .filter(unit -> unit.language() == metamodel.conformance.pipeline.model.Language.JAVA_ARCHIVE
                            || unit.language() == metamodel.conformance.pipeline.model.Language.JAVA_PLATFORM
                            || (context.ownsSourcePath(unit.path()) && !inputs.noncompiledSources().contains(unit.path())
                                    && !inputs.excludedSources(context.id()).contains(unit.path())))
                    .map(SourceUnit::path).collect(java.util.stream.Collectors.toSet());
            return new JavaContextualObservation.DomainIdentity(
                    metamodel.conformance.pipeline.util.Hashing.sha256(json),
                    (sourceOnly ? "Decoded source-only runtime context" : "Decoded selected declared compilation context")
                            + ", declared compiler metadata and captured source/observed unit/platform snapshot; "
                            + (runtimePlatform == null ? "" : "source-only compiler=" + runtimePlatform.compilerIdentity()
                                    + ", platform/compiler content SHA-256=" + runtimePlatform.platformContentSha256() + "; ")
                            + "complete consumed-input closure remains unproven; unowned/excluded/noncompiled source declarations excluded",
                    frame.classifiers().stream().filter(c -> assignedPaths.contains(c.sourcePath()))
                            .map(ClassifierObservation::id).toList(),
                    frame.members().stream().filter(m -> assignedPaths.contains(m.sourcePath()))
                            .map(MemberObservation::technicalKey).toList());
        } catch (IOException failure) {
            throw new ObservationException("cannot identify decoded Java context", failure);
        }
    }

    private static String relativePath(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }
}
