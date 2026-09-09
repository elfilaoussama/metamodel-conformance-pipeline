package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.adapter.SourceObserver;
import metamodel.conformance.pipeline.model.ClassifierAbstraction;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.ImplementationBindingObservation;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.MemberScope;
import metamodel.conformance.pipeline.model.MethodAbstraction;
import metamodel.conformance.pipeline.model.MethodBodyObservation;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;
import metamodel.conformance.pipeline.util.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Enriches the structural Java observation with independent implementation, abstraction,
 * return-type, and override evidence. Frontends observe facts only; invariant semantics
 * remain in Alloy.
 */
public final class JavaImplementationSourceObserver implements SourceObserver {
    public static final String ADAPTER_ID = SpoonJavaObserver.ADAPTER_ID;
    public static final String ADAPTER_VERSION = "1.4.0";

    private final JavaDependencyInputs dependencyInputs;

    public JavaImplementationSourceObserver(List<Path> dependencyArchives) {
        this(JavaDependencyInputs.global(dependencyArchives));
    }

    public JavaImplementationSourceObserver(JavaDependencyInputs dependencyInputs) {
        this.dependencyInputs = dependencyInputs == null ? JavaDependencyInputs.none() : dependencyInputs;
    }

    @Override
    public Observation observe(Path sourceRoot, Set<String> externalParents) throws ObservationException {
        Observation base = new SpoonJavaObserver(dependencyInputs).observe(sourceRoot, externalParents);
        if (base.diagnostics().stream().anyMatch(item -> item.kind()
                == metamodel.conformance.pipeline.model.DiagnosticKind.PARSE_ERROR)) {
            return upgrade(
                    base, base.classifiers(), base.members(), List.of(), List.of(), Set.of(), List.of());
        }
        try {
            Path root = sourceRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
            List<Path> files = discoverJavaFiles(root);
            SpoonMethodBodyObserver.Result bodyResult = observeBodiesByContext(root, files, dependencyInputs);
            SpoonAbstractionObserver.Result abstractionResult =
                    observeAbstractionByContext(root, files, base.classifiers(), base.members(), dependencyInputs);
            JavaDependencyClasspath.Result dependencies = JavaDependencyClasspath.resolve(dependencyInputs);
            JavacImplementationObserver.Result implementation = bodyResult.complete()
                    ? new JavacImplementationObserver().observe(
                            root,
                            files,
                            base.classifiers(),
                            base.members(),
                            bodyResult.bodies(),
                            dependencies.paths())
                    : JavacImplementationObserver.Result.incomplete();
            JavacOverrideObserver.Result overrideEvidence = new JavacOverrideObserver().observe(
                    root,
                    files,
                    base.classifiers(),
                    base.members(),
                    dependencies.paths());

            List<ClassifierObservation> classifiers = base.classifiers().stream().map(classifier ->
                    new ClassifierObservation(
                            classifier.id(),
                            classifier.qualifiedName(),
                            classifier.packageName(),
                            classifier.kind(),
                            classifier.sourcePath(),
                            classifier.startLine(),
                            classifier.endLine(),
                            classifier.parentIds(),
                            classifier.declaredMemberKeys(),
                            classifier.inheritedMemberKeys(),
                            abstractionResult.abstractionByClassifier().getOrDefault(
                                    classifier.id(), ClassifierAbstraction.UNKNOWN)))
                    .toList();

            List<MemberObservation> members = base.members().stream().map(member -> {
                MethodAbstraction abstraction = member.kind() == MemberKind.METHOD
                        ? abstractionResult.abstractionByMember().getOrDefault(
                                member.technicalKey(), MethodAbstraction.UNKNOWN)
                        : MethodAbstraction.UNKNOWN;
                MemberScope scope = member.kind() == MemberKind.METHOD
                        ? abstractionResult.scopeByMember().getOrDefault(
                                member.technicalKey(), MemberScope.UNKNOWN)
                        : MemberScope.UNKNOWN;
                String returnType = member.kind() == MemberKind.METHOD
                        ? overrideEvidence.returnTypesByMember().get(member.technicalKey())
                        : null;
                List<String> overriddenKeys = member.kind() == MemberKind.METHOD
                        ? overrideEvidence.overriddenMemberKeysByMember()
                                .getOrDefault(member.technicalKey(), List.of())
                        : List.of();
                return new MemberObservation(
                        member.technicalKey(),
                        member.observedIdentifier(),
                        member.kind(),
                        member.inheritability(),
                        member.visibility(),
                        member.memberName(),
                        member.sourcePath(),
                        member.startLine(),
                        member.endLine(),
                        member.parameterTypes(),
                        abstraction,
                        scope,
                        returnType,
                        overriddenKeys);
            }).toList();

            BindingResult bindingResult = implementation.complete()
                    ? toBindings(classifiers, implementation.bodyKeysByMember())
                    : BindingResult.incomplete();

            EnumSet<EvidenceKind> added = EnumSet.noneOf(EvidenceKind.class);
            if (bodyResult.complete()) {
                added.add(EvidenceKind.METHOD_BODIES);
            }
            if (abstractionResult.methodAbstractionComplete()) {
                added.add(EvidenceKind.METHOD_ABSTRACTION);
            }
            if (abstractionResult.classifierAbstractionComplete()) {
                added.add(EvidenceKind.CLASSIFIER_ABSTRACTION);
            }
            if (abstractionResult.methodScopeComplete()) {
                added.add(EvidenceKind.METHOD_SCOPE);
            }
            if (bodyResult.complete() && implementation.complete() && bindingResult.complete()) {
                added.add(EvidenceKind.IMPLEMENTATION_BINDINGS);
            }
            if (overrideEvidence.complete()) {
                added.add(EvidenceKind.METHOD_RETURN_TYPES);
                added.add(EvidenceKind.OVERRIDE_RELATIONS);
            }
            List<ObservationDiagnostic> extraDiagnostics = new ArrayList<>();
            extraDiagnostics.addAll(bodyResult.diagnostics());
            extraDiagnostics.addAll(abstractionResult.diagnostics());
            extraDiagnostics.addAll(implementation.diagnostics());
            extraDiagnostics.addAll(overrideEvidence.diagnostics());
            extraDiagnostics.addAll(bindingResult.diagnostics());
            dependencies.verifyUnchanged();
            return upgrade(
                    base,
                    classifiers,
                    members,
                    bodyResult.bodies(),
                    bindingResult.bindings(),
                    added,
                    extraDiagnostics);
        } catch (IOException | RuntimeException failure) {
            throw new ObservationException(
                    "Java implementation-evidence observation failed: " + failure.getMessage(), failure);
        }
    }

    private static BindingResult toBindings(
            List<ClassifierObservation> classifiers,
            Map<String, List<String>> bodyKeysByMember) {
        Map<String, List<String>> ownersByMember = new HashMap<>();
        for (ClassifierObservation classifier : classifiers) {
            for (String memberKey : classifier.declaredMemberKeys()) {
                ownersByMember.computeIfAbsent(memberKey, ignored -> new ArrayList<>())
                        .add(classifier.id());
            }
        }
        List<ImplementationBindingObservation> bindings = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : bodyKeysByMember.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            List<String> owners = ownersByMember.getOrDefault(entry.getKey(), List.of());
            if (owners.size() != 1) {
                return BindingResult.incomplete();
            }
            String implementer = owners.get(0);
            for (String bodyKey : entry.getValue()) {
                String technicalKey = "bind_" + Hashing.sha256(
                        "java-binding\0" + implementer + "\0" + entry.getKey() + "\0" + bodyKey);
                bindings.add(new ImplementationBindingObservation(
                        technicalKey, implementer, entry.getKey(), bodyKey));
            }
        }
        List<ImplementationBindingObservation> canonical = bindings.stream()
                .distinct()
                .sorted(Comparator.comparing(ImplementationBindingObservation::technicalKey))
                .toList();
        if (canonical.size() != bindings.size()) {
            return BindingResult.incomplete();
        }
        return new BindingResult(true, canonical, List.of());
    }

    private static SpoonMethodBodyObserver.Result observeBodiesByContext(
            Path root, List<Path> files, JavaDependencyInputs inputs) {
        JavaCompilationContexts.Grouping grouping = JavaCompilationContexts.group(root, files, inputs);
        boolean complete = grouping.unownedFiles().isEmpty();
        Map<String, MethodBodyObservation> bodies = new TreeMap<>();
        List<ObservationDiagnostic> diagnostics = new ArrayList<>();
        SpoonMethodBodyObserver observer = new SpoonMethodBodyObserver();
        for (Map.Entry<String, List<Path>> entry : grouping.filesByContext().entrySet()) {
            JavaCompilationContext context = grouping.context(entry.getKey());
            SpoonMethodBodyObserver.Result result = observer.observe(root, entry.getValue(), context);
            complete &= result.complete();
            diagnostics.addAll(result.diagnostics());
            for (MethodBodyObservation body : result.bodies()) {
                MethodBodyObservation previous = bodies.putIfAbsent(body.technicalKey(), body);
                if (previous != null && !previous.equals(body)) {
                    complete = false;
                }
            }
        }
        if (!grouping.unownedFiles().isEmpty()) {
            diagnostics.add(new ObservationDiagnostic(
                    metamodel.conformance.pipeline.model.DiagnosticKind.EVIDENCE_INCOMPLETE,
                    root.relativize(grouping.unownedFiles().get(0)).toString().replace('\\', '/'), 0,
                    "Java source is not owned by an observed compilation context"));
        }
        return new SpoonMethodBodyObserver.Result(
                complete, List.copyOf(bodies.values()), canonicalDiagnostics(diagnostics));
    }

    private static SpoonAbstractionObserver.Result observeAbstractionByContext(
            Path root,
            List<Path> files,
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            JavaDependencyInputs inputs) {
        JavaCompilationContexts.Grouping grouping = JavaCompilationContexts.group(root, files, inputs);
        boolean classifierComplete = grouping.unownedFiles().isEmpty();
        boolean methodAbstractionComplete = grouping.unownedFiles().isEmpty();
        boolean methodScopeComplete = grouping.unownedFiles().isEmpty();
        Map<String, ClassifierAbstraction> classifierAbstraction = new HashMap<>();
        Map<String, MethodAbstraction> methodAbstraction = new HashMap<>();
        Map<String, MemberScope> methodScope = new HashMap<>();
        List<ObservationDiagnostic> diagnostics = new ArrayList<>();
        SpoonAbstractionObserver observer = new SpoonAbstractionObserver();
        for (Map.Entry<String, List<Path>> entry : grouping.filesByContext().entrySet()) {
            JavaCompilationContext context = grouping.context(entry.getKey());
            Set<String> scopedPaths = entry.getValue().stream()
                    .map(path -> root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/'))
                    .collect(java.util.stream.Collectors.toSet());
            List<ClassifierObservation> scopedClassifiers = classifiers.stream()
                    .filter(item -> scopedPaths.contains(item.sourcePath())).toList();
            List<MemberObservation> scopedMembers = members.stream()
                    .filter(item -> scopedPaths.contains(item.sourcePath())).toList();
            SpoonAbstractionObserver.Result result =
                    observer.observe(root, entry.getValue(), scopedClassifiers, scopedMembers, context);
            classifierComplete &= result.classifierAbstractionComplete();
            methodAbstractionComplete &= result.methodAbstractionComplete();
            methodScopeComplete &= result.methodScopeComplete();
            classifierComplete &= mergeConsistent(
                    classifierAbstraction, result.abstractionByClassifier());
            methodAbstractionComplete &= mergeConsistent(
                    methodAbstraction, result.abstractionByMember());
            methodScopeComplete &= mergeConsistent(methodScope, result.scopeByMember());
            diagnostics.addAll(result.diagnostics());
        }
        if (!grouping.unownedFiles().isEmpty()) {
            diagnostics.add(new ObservationDiagnostic(
                    metamodel.conformance.pipeline.model.DiagnosticKind.EVIDENCE_INCOMPLETE,
                    root.relativize(grouping.unownedFiles().get(0)).toString().replace('\\', '/'), 0,
                    "Java source is not owned by an observed compilation context"));
        }
        return new SpoonAbstractionObserver.Result(
                classifierComplete, methodAbstractionComplete, methodScopeComplete,
                classifierAbstraction, methodAbstraction, methodScope, canonicalDiagnostics(diagnostics));
    }

    private static <K, V> boolean mergeConsistent(Map<K, V> target, Map<K, V> observed) {
        boolean consistent = true;
        for (Map.Entry<K, V> entry : observed.entrySet()) {
            V previous = target.putIfAbsent(entry.getKey(), entry.getValue());
            if (previous != null && !previous.equals(entry.getValue())) consistent = false;
        }
        return consistent;
    }

    private static List<ObservationDiagnostic> canonicalDiagnostics(
            List<ObservationDiagnostic> diagnostics) {
        return diagnostics.stream().distinct()
                .sorted(Comparator.comparing(ObservationDiagnostic::sourcePath)
                        .thenComparingInt(ObservationDiagnostic::line)
                        .thenComparing(ObservationDiagnostic::message))
                .toList();
    }

    private static Observation upgrade(
            Observation base,
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            List<MethodBodyObservation> bodies,
            List<ImplementationBindingObservation> bindings,
            Set<EvidenceKind> addedEvidence,
            List<ObservationDiagnostic> extraDiagnostics) {
        EnumSet<EvidenceKind> evidence = EnumSet.noneOf(EvidenceKind.class);
        evidence.addAll(base.completeEvidence());
        evidence.addAll(addedEvidence);
        List<ObservationDiagnostic> diagnostics = new ArrayList<>(base.diagnostics());
        diagnostics.addAll(extraDiagnostics);
        return new Observation(
                "12",
                ADAPTER_ID,
                ADAPTER_VERSION,
                base.externalParents(),
                evidence,
                base.units(),
                classifiers,
                members,
                bodies,
                bindings,
                base.unresolvedParents(),
                diagnostics.stream().distinct().toList());
    }

    private static List<Path> discoverJavaFiles(Path root) throws IOException, ObservationException {
        List<Path> files;
        try (Stream<Path> paths = Files.walk(root)) {
            files = paths.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString().replace('\\', '/')))
                    .toList();
        }
        for (Path file : files) {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new ObservationException("non-regular or symbolic-link Java source rejected: " + file);
            }
            if (!file.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(root)) {
                throw new ObservationException("source escapes declared root: " + file);
            }
        }
        return files;
    }

    private record BindingResult(
            boolean complete,
            List<ImplementationBindingObservation> bindings,
            List<ObservationDiagnostic> diagnostics) {
        private BindingResult {
            bindings = List.copyOf(bindings);
            diagnostics = List.copyOf(diagnostics);
        }

        static BindingResult incomplete() {
            return new BindingResult(false, List.of(), List.of());
        }
    }
}
