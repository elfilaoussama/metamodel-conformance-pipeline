package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.adapter.SourceObserver;
import metamodel.conformance.pipeline.model.ClassifierObservation;
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
    public static final String ADAPTER_VERSION = "1.14.0";

    private final JavaDependencyInputs dependencyInputs;
    private final SourceObserver delegate;

    public JavaDependencyAwareSourceObserver(List<Path> dependencyArchives) {
        this(JavaDependencyInputs.global(dependencyArchives));
    }

    public JavaDependencyAwareSourceObserver(JavaDependencyInputs dependencyInputs) {
        this(dependencyInputs, new JavaImplementationSourceObserver(dependencyInputs));
    }

    JavaDependencyAwareSourceObserver(
            JavaDependencyInputs dependencyInputs,
            SourceObserver delegate) {
        this.dependencyInputs = dependencyInputs == null
                ? JavaDependencyInputs.none() : dependencyInputs;
        this.delegate = delegate;
    }

    @Override
    public Observation observe(Path sourceRoot, Set<String> externalParents) throws ObservationException {
        Observation base = delegate.observe(sourceRoot, externalParents);
        if (dependencyInputs.contexts().isEmpty()
                || base.diagnostics().stream().anyMatch(item -> item.kind() == DiagnosticKind.PARSE_ERROR)
                || (base.classifiers().isEmpty()
                    && !base.completeEvidence().contains(EvidenceKind.DECLARATION_OWNERSHIP))) {
            return base;
        }

        try {
            Path root = sourceRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
            List<Path> files = observedJavaFiles(root, base.units());
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

                JavaDependencyClasspath.Result activeClasspath = JavaDependencyClasspath.resolve(
                        dependencyInputs.archivePathsForContext(context.id()));
                for (String evidenceContextId : evidenceContextIds) {
                    List<UnresolvedParent> unresolved = unresolvedByContext.getOrDefault(
                            evidenceContextId, List.of());
                    if (unresolved.isEmpty()) {
                        continue;
                    }
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
                    if (roots.isEmpty()) {
                        evidenceClasspath.verifyUnchanged();
                        continue;
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
                                evidenceClasspath, roots, platform, evidenceJavac.options());
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
                    Set<String> resolvedRoots = new TreeSet<>(roots);
                    resolvedRoots.removeAll(symbols.unresolvedRootTypes());
                    for (UnresolvedParent item : unresolved) {
                        if (!resolvedRoots.contains(item.targetName())) continue;
                        String supportId = support.classifierId(item.targetName());
                        if (supportId != null) {
                            supportParentByUnresolvedKey.put(unresolvedKey(item), supportId);
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
                try (JavacCompilationContext javac = JavacCompilationContext.prepare(
                        root, context, grouping.filesByContext(), dependencyInputs)) {
                    observed = javac.complete()
                            ? JavacDependencyEvidenceObserver.observeCompilationContext(
                                    root, entry.getValue(), scopedClassifiers, scopedMembers,
                                    upstreamClassifiers, upstreamMembers,
                                    List.copyOf(contextSupportClassifiers.values()),
                                    List.copyOf(contextSupportMembers.values()), javac.options())
                            : JavacDependencyEvidenceObserver.Result.incomplete(javac.diagnostics());
                }
                dependencyEvidenceComplete &= observed.complete();
                diagnostics.addAll(observed.diagnostics());
                dependencyEvidenceComplete &= mergeConsistent(
                        inheritedByClassifier, observed.inheritedByClassifier(), diagnostics,
                        "inherited-member evidence");
                dependencyEvidenceComplete &= mergeConsistent(
                        overridesByMember, observed.overriddenMemberKeysByMember(), diagnostics,
                        "override evidence");
                dependencyEvidenceComplete &= mergeConsistent(
                        returnTypesByMember, observed.returnTypesByMember(), diagnostics,
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
                        List<String> inherited = inheritedByClassifier.containsKey(classifier.id())
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
                        returnTypesByMember.getOrDefault(member.technicalKey(), member.returnType()),
                        overridesByMember.getOrDefault(member.technicalKey(), member.overriddenMemberKeys()));
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
            return new Observation(
                    "13", ADAPTER_ID, ADAPTER_VERSION, base.externalParents(), evidence,
                    mergeUnits(base.units(), mergedSupportUnits(allDependencies.units(), supportUnitsByPath.values())), classifiers, members,
                    base.methodBodies(), base.implementationBindings(), remainingUnresolved,
                    mergeDiagnostics(base.diagnostics(), diagnostics),
                    List.copyOf(platformEvidenceByContext.values()));
        } catch (ObservationException exception) {
            throw exception;
        } catch (IOException | RuntimeException failure) {
            throw new ObservationException(
                    "Java compilation-context dependency observation failed: " + failure.getMessage(), failure);
        }
    }

    private static <K, V> boolean mergeConsistent(
            Map<K, V> target, Map<K, V> observed, List<ObservationDiagnostic> diagnostics, String label) {
        boolean consistent = true;
        for (Map.Entry<K, V> entry : observed.entrySet()) {
            V previous = target.putIfAbsent(entry.getKey(), entry.getValue());
            if (previous != null && !previous.equals(entry.getValue())) {
                consistent = false;
                diagnostics.add(new ObservationDiagnostic(
                        DiagnosticKind.EVIDENCE_INCOMPLETE, "<context>.java", 0,
                        "conflicting " + label + " across compilation contexts"));
            }
        }
        return consistent;
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

    private static String relativePath(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }
}
