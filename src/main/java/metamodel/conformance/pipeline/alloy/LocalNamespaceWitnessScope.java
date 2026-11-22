package metamodel.conformance.pipeline.alloy;

import metamodel.conformance.pipeline.invariant.InvariantDefinition;
import metamodel.conformance.pipeline.invariant.InvariantProfile;
import metamodel.conformance.pipeline.invariant.EvidenceRequirement;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.NativeEvidence;
import metamodel.conformance.pipeline.model.Observation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Projects positive duplicate proofs for the qualified, existential P0 witness. */
final class LocalNamespaceWitnessScope {
    // The proof depends on the witness being existential in two co-owned members.
    // External rule sets need their own qualification before using this projection.
    private static final Set<String> QUALIFIED_RULES = Set.of(
            "fa92f7995d4d30a6a25a3ad1bd6091adc6717d5ab5903c135b2db7c2d4f16fb7",
            "d47e320eefa9c8162904d1e1062c6770ff78beb716cede40a53630133ae38c29");
    private static final Set<EvidenceKind> REQUIRED =
            Set.of(EvidenceKind.DECLARATION_OWNERSHIP, EvidenceKind.LOCAL_SIGNATURES);

    private LocalNamespaceWitnessScope() { }

    static boolean applies(Observation observation, InvariantDefinition definition, InvariantProfile profile) {
        return QUALIFIED_RULES.contains(profile.identity().rulesSha256())
                && definition.witnessFunction().equals("LocalNamespaceUniquenessViolations")
                && definition.witnessArity() == 1
                && definition.requiredEvidence().equals(REQUIRED)
                // KNOWN_REQUIRED_VALUES adds no constraints for these two evidence kinds.
                && Set.of(EvidenceRequirement.KNOWN_REQUIRED_VALUES).containsAll(definition.evidenceRequirements())
                && observation.completeEvidence().contains(EvidenceKind.DECLARATION_OWNERSHIP)
                && !observation.completeEvidence().contains(EvidenceKind.LOCAL_SIGNATURES);
    }

    static List<Observation> plan(Observation observation) {
        var members = new HashMap<String, MemberObservation>();
        observation.members().forEach(member -> members.put(member.technicalKey(), member));
        var signatures = new HashMap<String, NativeEvidence.Signature>();
        observation.nativeEvidence().signatures().forEach(signature -> signatures.put(signature.memberKey(), signature));
        var domainsByOwner = new HashMap<String, NativeEvidence.Domain>();
        observation.nativeEvidence().domains().stream()
                .filter(domain -> domain.view() == NativeEvidence.View.SOURCE_DECLARATIONS)
                .forEach(domain -> domain.classifierIds().forEach(id -> domainsByOwner.put(id, domain)));

        var sourcePaths = AlloyWorkUnitPlanner.sourcePaths(observation);
        List<Observation> workUnits = new ArrayList<>();
        for (var owner : observation.classifiers()) {
            var domain = domainsByOwner.get(owner.id());
            if (domain == null || !sourcePaths.contains(owner.sourcePath())) continue;
            var domainMembers = Set.copyOf(domain.memberKeys());
            var byKey = new LinkedHashMap<Key, List<String>>();
            for (String memberKey : owner.declaredMemberKeys()) {
                if (!domainMembers.contains(memberKey)) continue;
                var member = members.get(memberKey);
                // A native signature receipt corroborates each method key. Attribute
                // occurrences have no equivalent proof of separate namespace bindings.
                if (member.kind() != MemberKind.METHOD
                        || !hasMatchingSignature(member, signatures.get(memberKey), domain.contextId())) continue;
                byKey.computeIfAbsent(new Key(member.memberName(), member.parameterTypes()),
                        ignored -> new ArrayList<>()).add(memberKey);
            }
            for (var group : byKey.values()) {
                if (group.size() < 2) continue;
                workUnits.add(AlloyWorkUnitPlanner.declarationScope(observation, owner.id(), group));
            }
        }
        return List.copyOf(workUnits);
    }

    private static boolean hasMatchingSignature(MemberObservation member,
            NativeEvidence.Signature signature, String context) {
        if (signature == null || !signature.contextId().equals(context)
                || signature.parameters().knowledge() != NativeEvidence.Knowledge.KNOWN) return false;
        var parameters = signature.parameters().values();
        return parameters.stream().allMatch(parameter ->
                parameter.meaning() == NativeEvidence.TypeMeaning.COMPILER_TYPE
                        || parameter.meaning() == NativeEvidence.TypeMeaning.SOURCE_ANNOTATION)
                && member.parameterTypes().equals(parameters.stream().map(NativeEvidence.Parameter::spelling).toList());
    }

    private record Key(String name, List<String> parameterTypes) { }
}
