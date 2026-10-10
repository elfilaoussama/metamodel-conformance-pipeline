package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.model.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Joins independently attributed source declarations to one qualified compiler world. */
final class JavaNativeDeclarations {
    private JavaNativeDeclarations() { }

    static Observation populate(JavaCompilationContext context, JavaDependencyInputs inputs,
            Observation physical, JavacDirectParentNames.Result measured,
            JavaContextualObservation.DomainIdentity identity, boolean sourceOnly) {
        String contextId = context.id();
        Observation qualified = JavaContextualObservation.qualify(contextId, physical);
        Set<String> classifiers = Set.copyOf(identity.classifierIds());
        Set<String> members = Set.copyOf(identity.memberKeys());
        Map<String, List<ClassifierObservation>> byName = physical.classifiers().stream()
                .filter(c -> classifiers.contains(c.id())).collect(Collectors.groupingBy(ClassifierObservation::qualifiedName));
        Set<String> supportPaths = physical.units().stream()
                .filter(unit -> unit.language() == Language.JAVA_ARCHIVE || unit.language() == Language.JAVA_PLATFORM)
                .map(SourceUnit::path).collect(Collectors.toSet());
        var bases = new ArrayList<NativeEvidence.Bases>();
        for (ClassifierObservation classifier : physical.classifiers()) {
            if (!classifiers.contains(classifier.id())) continue;
            var observed = measured.bases().get(classifier.id());
            NativeEvidence.Evidence<NativeEvidence.DirectBase> receipt;
            if (observed == null || observed.knowledge() != NativeEvidence.Knowledge.KNOWN) {
                receipt = unavailable("Source-declared bases lack unique successful source attribution; binary order is not reconstructed");
            } else {
                receipt = NativeEvidence.Evidence.known(observed.values().stream().map(base -> {
                    var targets = base.binaryName() == null ? List.<ClassifierObservation>of()
                            : byName.getOrDefault(base.binaryName(), List.of()).stream()
                                    .filter(target -> base.sourceDeclaration()
                                            ? target.id().equals(base.sourceClassifierId())
                                            : supportPaths.contains(target.sourcePath())).toList();
                    NativeEvidence.Evidence<String> target = targets.size() == 1
                            ? NativeEvidence.Evidence.known(List.of(JavaContextualObservation.key(contextId, targets.get(0).id())))
                            : NativeEvidence.Evidence.unavailable(targets.size() > 1
                                    ? NativeEvidence.Knowledge.AMBIGUOUS : NativeEvidence.Knowledge.UNKNOWN,
                                    "Attributed base target lacks unique in-domain correspondence: " + base.binaryName());
                    return new NativeEvidence.DirectBase(base.spelling(), target,
                            NativeEvidence.Access.NOT_APPLICABLE, NativeEvidence.Virtuality.NOT_APPLICABLE);
                }).toList());
            }
            bases.add(new NativeEvidence.Bases(contextId, JavaContextualObservation.key(contextId, classifier.id()), receipt));
        }
        var signatures = new ArrayList<NativeEvidence.Signature>();
        for (MemberObservation member : physical.members()) {
            if (!members.contains(member.technicalKey())
                    || member.kind() != MemberKind.METHOD) continue;
            var observed = measured.signatures().get(member.technicalKey());
            signatures.add(new NativeEvidence.Signature(contextId,
                    JavaContextualObservation.key(contextId, member.technicalKey()),
                    observed == null ? unavailable("Parameter types lack unique successful source attribution") : observed.parameters(),
                    observed == null ? unavailable("Receiver semantics lack unique successful source attribution") : observed.receiverQualifiers(),
                    observed == null ? unavailable("Return type lacks unique successful source attribution") : observed.returnType()));
        }
        var roles = new ArrayList<NativeEvidence.RoleReceipt>();
        for (SourceUnit unit : physical.units()) {
            NativeEvidence.SourceRole role;
            NativeEvidence.Generation generation = NativeEvidence.Generation.UNKNOWN;
            String provenance;
            if (unit.language() == Language.JAVA_ARCHIVE || unit.language() == Language.JAVA_PLATFORM) {
                role = NativeEvidence.SourceRole.SUPPORT;
                provenance = "Declared/observed dependency or platform unit; complete consumed-input closure remains unproven";
            } else if (unit.language() == Language.JAVA) {
                if (inputs.noncompiledSources().contains(unit.path())
                        || (context.ownsSourcePath(unit.path()) && inputs.excludedSources(contextId).contains(unit.path()))) {
                    role = NativeEvidence.SourceRole.DECLARED_NONCOMPILED;
                    provenance = "Provider-declared noncompiled source or selected-context exclusion";
                } else if (context.ownsSourcePath(unit.path())) {
                    role = NativeEvidence.SourceRole.TARGET;
                    provenance = sourceOnly ? "Source-only target population; project-build ownership is not claimed"
                            : "Selected declared compilation root; generation remains unknown outside declared generated roots";
                } else {
                    role = NativeEvidence.SourceRole.UNKNOWN;
                    provenance = "Physical source is not assigned to this selected compilation context";
                }
                if (context.generatedSourceRoots().stream().anyMatch(root -> root.equals(".")
                        || unit.path().equals(root) || unit.path().startsWith(root + "/"))) {
                    generation = NativeEvidence.Generation.GENERATED;
                }
            } else continue;
            roles.add(new NativeEvidence.RoleReceipt(contextId, unit.path(), unit.sha256(), role, generation, provenance));
        }
        var domain = new NativeEvidence.Domain(contextId, Language.JAVA,
                sourceOnly ? NativeEvidence.MeasurementMode.SOURCE_ONLY : NativeEvidence.MeasurementMode.SELECTED_BUILD,
                sourceOnly ? NativeEvidence.View.SOURCE_DECLARATIONS : NativeEvidence.View.EFFECTIVE_LOOKUP,
                identity.sha256(), identity.provenance(),
                identity.classifierIds().stream().map(id -> JavaContextualObservation.key(contextId, id)).toList(),
                identity.memberKeys().stream().map(id -> JavaContextualObservation.key(contextId, id)).toList());
        return new Observation(qualified.schemaVersion(), JavaDependencyAwareSourceObserver.ADAPTER_ID,
                JavaDependencyAwareSourceObserver.ADAPTER_VERSION, qualified.externalParents(), qualified.completeEvidence(),
                qualified.units(), qualified.classifiers(), qualified.members(), qualified.methodBodies(), qualified.implementationBindings(),
                qualified.unresolvedParents(), qualified.diagnostics(), qualified.platformEvidence(),
                new NativeEvidence.Bundle(List.of(domain), roles, List.of(), bases, signatures, List.of(), List.of()));
    }

    private static <T> NativeEvidence.Evidence<T> unavailable(String reason) {
        return NativeEvidence.Evidence.unavailable(NativeEvidence.Knowledge.UNKNOWN, reason);
    }
}
