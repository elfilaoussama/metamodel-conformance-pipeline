package metamodel.conformance.pipeline.invariant;

import metamodel.conformance.pipeline.model.ClassifierAbstraction;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Inheritability;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.MemberScope;
import metamodel.conformance.pipeline.model.MethodAbstraction;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.SourceUnit;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Checks evidence authority before projection. It does not compute rule verdicts. */
public final class InvariantEvidenceAdmission {
    private InvariantEvidenceAdmission() { }

    public static Optional<String> unavailableReason(Observation observation, InvariantDefinition definition) {
        String missing = definition.requiredEvidence().stream()
                .filter(required -> !observation.completeEvidence().contains(required))
                .map(Enum::name).sorted().collect(Collectors.joining(", "));
        if (!missing.isEmpty()) return Optional.of("Required evidence is incomplete: " + missing);
        if (definition.evidenceRequirements().isEmpty()) return Optional.empty();

        Set<String> sourcePaths = observation.units().stream()
                .filter(unit -> unit.language() != Language.JAVA_ARCHIVE && unit.language() != Language.JAVA_PLATFORM)
                .map(SourceUnit::path).collect(Collectors.toSet());
        Map<String, ClassifierObservation> classifiers = observation.classifiers().stream()
                .collect(Collectors.toMap(ClassifierObservation::id, value -> value));
        List<ClassifierObservation> subjects = observation.classifiers().stream()
                .filter(value -> sourcePaths.contains(value.sourcePath())).toList();
        Set<String> reached = new TreeSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        subjects.forEach(value -> pending.add(value.id()));
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (!reached.add(id)) continue;
            classifiers.get(id).parentIds().forEach(pending::addLast);
        }

        if (definition.evidenceRequirements().contains(EvidenceRequirement.KNOWN_REQUIRED_VALUES)) {
            Set<String> memberKeys = new HashSet<>();
            subjects.forEach(value -> {
                memberKeys.addAll(value.declaredMemberKeys());
                memberKeys.addAll(value.inheritedMemberKeys());
            });
            if (definition.requiredEvidence().contains(EvidenceKind.HIERARCHY)) {
                reached.forEach(id -> memberKeys.addAll(classifiers.get(id).declaredMemberKeys()));
            }
            for (MemberObservation member : observation.members()) {
                if (!memberKeys.contains(member.technicalKey())) continue;
                if (definition.requiredEvidence().contains(EvidenceKind.INHERITABILITY)
                        && member.inheritability() == Inheritability.UNKNOWN)
                    return unknown("inheritability", member.technicalKey());
                if (member.kind() != MemberKind.METHOD) continue;
                if (definition.requiredEvidence().contains(EvidenceKind.METHOD_ABSTRACTION)
                        && member.abstraction() == MethodAbstraction.UNKNOWN)
                    return unknown("method abstraction", member.technicalKey());
                if (definition.requiredEvidence().contains(EvidenceKind.METHOD_SCOPE)
                        && member.scope() == MemberScope.UNKNOWN)
                    return unknown("method scope", member.technicalKey());
            }
            if (definition.requiredEvidence().contains(EvidenceKind.CLASSIFIER_ABSTRACTION)) {
                for (ClassifierObservation subject : subjects)
                    if (subject.abstraction() == ClassifierAbstraction.UNKNOWN)
                        return unknown("classifier abstraction", subject.id());
            }
        }

        if (definition.evidenceRequirements().contains(EvidenceRequirement.SOURCE_ANCESTOR_IMPLEMENTATION_COVERAGE)) {
            // Existing body/binding completeness claims describe source declarations. Native
            // records carry positive/unknown measurements, not a dependency-wide absence
            // certificate. Do not promote them to such a certificate here. Supporting that
            // broader claim requires a versioned provider/observation coverage contract.
            List<String> support = reached.stream()
                    .filter(id -> !sourcePaths.contains(classifiers.get(id).sourcePath())).toList();
            if (!support.isEmpty()) return Optional.of(
                    "Implementation binding completeness is not established for " + support.size()
                    + " support ancestor(s); source-local evidence does not cover " + support.get(0));
        }
        return Optional.empty();
    }

    private static Optional<String> unknown(String field, String key) {
        return Optional.of("Required evidence contradicts an unknown " + field + " value: " + key);
    }
}
