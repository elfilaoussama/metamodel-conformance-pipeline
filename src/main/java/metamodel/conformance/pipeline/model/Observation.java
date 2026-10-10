package metamodel.conformance.pipeline.model;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record Observation(
        String schemaVersion,
        String adapterId,
        String adapterVersion,
        List<String> externalParents,
        Set<EvidenceKind> completeEvidence,
        List<SourceUnit> units,
        List<ClassifierObservation> classifiers,
        List<MemberObservation> members,
        List<MethodBodyObservation> methodBodies,
        List<ImplementationBindingObservation> implementationBindings,
        List<UnresolvedParent> unresolvedParents,
        List<ObservationDiagnostic> diagnostics,
        List<PlatformEvidence> platformEvidence,
        NativeEvidence.Bundle nativeEvidence) {

    public Observation {
        requireText(schemaVersion, "schemaVersion");
        requireText(adapterId, "adapterId");
        requireText(adapterVersion, "adapterVersion");
        externalParents = sortedStrings(externalParents);
        completeEvidence = completeEvidence == null
                ? Set.of() : Set.copyOf(completeEvidence);
        units = List.copyOf(units).stream().sorted(Comparator.comparing(SourceUnit::path)).toList();
        classifiers = List.copyOf(classifiers).stream()
                .sorted(Comparator.comparing(ClassifierObservation::id)).toList();
        members = List.copyOf(members).stream()
                .sorted(Comparator.comparing(MemberObservation::technicalKey)).toList();
        methodBodies = methodBodies == null ? List.of() : List.copyOf(methodBodies).stream()
                .sorted(Comparator.comparing(MethodBodyObservation::technicalKey)).toList();
        implementationBindings = implementationBindings == null ? List.of()
                : List.copyOf(implementationBindings).stream()
                        .sorted(Comparator.comparing(ImplementationBindingObservation::technicalKey)).toList();
        unresolvedParents = List.copyOf(unresolvedParents).stream()
                .sorted(Comparator.comparing(UnresolvedParent::ownerId)
                        .thenComparing(UnresolvedParent::targetName)
                        .thenComparingInt(UnresolvedParent::line))
                .toList();
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics).stream()
                .sorted(Comparator.comparing(ObservationDiagnostic::sourcePath)
                        .thenComparingInt(ObservationDiagnostic::line)
                        .thenComparing(item -> item.kind().name())
                .thenComparing(ObservationDiagnostic::message))
                .toList();
        platformEvidence = platformEvidence == null ? List.of() : List.copyOf(platformEvidence).stream()
                .sorted(Comparator.comparing(PlatformEvidence::contextId)
                        .thenComparing(PlatformEvidence::platformContentSha256))
                .toList();
        validateReferences(
                units, classifiers, members, methodBodies, implementationBindings,
                unresolvedParents, diagnostics);
        nativeEvidence = nativeEvidence == null ? NativeEvidence.Bundle.empty() : nativeEvidence;
        if (!nativeEvidence.domains().isEmpty()) validateNativePopulation(nativeEvidence, units, classifiers, members);
        if (!unresolvedParents.isEmpty() && completeEvidence.contains(EvidenceKind.HIERARCHY)) {
            throw new IllegalArgumentException("hierarchy evidence cannot be complete with unresolved parents");
        }
        if (diagnostics.stream().anyMatch(item -> item.kind() == DiagnosticKind.PARSE_ERROR)
                && !completeEvidence.isEmpty()) {
            throw new IllegalArgumentException("parse diagnostics forbid complete evidence claims");
        }
    }

    public Observation(String schemaVersion, String adapterId, String adapterVersion,
                       List<String> externalParents, Set<EvidenceKind> completeEvidence,
                       List<SourceUnit> units, List<ClassifierObservation> classifiers,
                       List<MemberObservation> members, List<MethodBodyObservation> methodBodies,
                       List<ImplementationBindingObservation> implementationBindings,
                       List<UnresolvedParent> unresolvedParents, List<ObservationDiagnostic> diagnostics,
                       List<PlatformEvidence> platformEvidence) {
        this(schemaVersion, adapterId, adapterVersion, externalParents, completeEvidence, units,
                classifiers, members, methodBodies, implementationBindings, unresolvedParents,
                diagnostics, platformEvidence, NativeEvidence.Bundle.empty());
    }

    public Observation(
            String schemaVersion,
            String adapterId,
            String adapterVersion,
            List<String> externalParents,
            Set<EvidenceKind> completeEvidence,
            List<SourceUnit> units,
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            List<MethodBodyObservation> methodBodies,
            List<ImplementationBindingObservation> implementationBindings,
            List<UnresolvedParent> unresolvedParents,
            List<ObservationDiagnostic> diagnostics) {
        this(schemaVersion, adapterId, adapterVersion, externalParents, completeEvidence,
                units, classifiers, members, methodBodies, implementationBindings,
                unresolvedParents, diagnostics, List.of());
    }

    public Observation(
            String schemaVersion,
            String adapterId,
            String adapterVersion,
            List<String> externalParents,
            Set<EvidenceKind> completeEvidence,
            List<SourceUnit> units,
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            List<MethodBodyObservation> methodBodies,
            List<UnresolvedParent> unresolvedParents,
            List<ObservationDiagnostic> diagnostics) {
        this(schemaVersion, adapterId, adapterVersion, externalParents, completeEvidence,
                units, classifiers, members, methodBodies, List.of(), unresolvedParents, diagnostics);
    }

    public Observation(
            String schemaVersion,
            String adapterId,
            String adapterVersion,
            List<String> externalParents,
            Set<EvidenceKind> completeEvidence,
            List<SourceUnit> units,
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            List<UnresolvedParent> unresolvedParents,
            List<ObservationDiagnostic> diagnostics) {
        this(schemaVersion, adapterId, adapterVersion, externalParents, completeEvidence,
                units, classifiers, members, List.of(), List.of(), unresolvedParents, diagnostics);
    }

    public Observation(
            String schemaVersion,
            String adapterId,
            String adapterVersion,
            List<String> externalParents,
            Set<EvidenceKind> completeEvidence,
            List<SourceUnit> units,
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            List<UnresolvedParent> unresolvedParents) {
        this(schemaVersion, adapterId, adapterVersion, externalParents, completeEvidence,
                units, classifiers, members, List.of(), List.of(), unresolvedParents, List.of());
    }

    public Observation(
            String schemaVersion,
            String adapterId,
            String adapterVersion,
            List<String> externalParents,
            List<SourceUnit> units,
            List<ClassifierObservation> classifiers,
            List<UnresolvedParent> unresolvedParents) {
        this(
                schemaVersion,
                adapterId,
                adapterVersion,
                externalParents,
                unresolvedParents.isEmpty() ? Set.of(EvidenceKind.HIERARCHY) : Set.of(),
                units,
                classifiers,
                List.of(),
                List.of(),
                List.of(),
                unresolvedParents,
                List.of());
    }

    private static List<String> sortedStrings(List<String> values) {
        return values == null ? List.of() : values.stream()
                .map(value -> CanonicalObservationValue.text(value, "external parent"))
                .sorted().distinct().toList();
    }

    private static void validateNativePopulation(NativeEvidence.Bundle nativeEvidence, List<SourceUnit> units,
                                                List<ClassifierObservation> classifiers, List<MemberObservation> members) {
        Map<String, Language> languages = new HashMap<>();
        for (SourceUnit unit : units) {
            Language language = unit.language() == Language.JAVA_ARCHIVE || unit.language() == Language.JAVA_PLATFORM
                    ? Language.JAVA : unit.language();
            languages.put(unit.path(), language);
        }
        Map<String, String> classifierPaths = new HashMap<>();
        classifiers.forEach(value -> classifierPaths.put(value.id(), value.sourcePath()));
        Map<String, MemberObservation> byMemberKey = new HashMap<>();
        members.forEach(value -> byMemberKey.put(value.technicalKey(), value));
        for (NativeEvidence.Domain domain : nativeEvidence.domains()) {
            for (String id : domain.classifierIds()) {
                if (languages.get(classifierPaths.get(id)) != domain.language()) {
                    throw new IllegalArgumentException("native classifier is outside its canonical language population");
                }
            }
            for (String key : domain.memberKeys()) {
                MemberObservation member = byMemberKey.get(key);
                if (member == null || languages.get(member.sourcePath()) != domain.language()) {
                    throw new IllegalArgumentException("native member is outside its canonical language population");
                }
            }
        }
        nativeEvidence.signatures().forEach(value -> requireNativeMethod(byMemberKey, value.memberKey()));
        nativeEvidence.bodyBindings().forEach(value -> {
            requireNativeMethod(byMemberKey, value.memberKey());
            value.implementationTargetKeys().values().forEach(key -> requireNativeMethod(byMemberKey, key));
        });
        nativeEvidence.lookups().forEach(value -> value.targets().values().forEach(target ->
                target.overriddenMemberKeys().values().forEach(key -> requireNativeMethod(byMemberKey, key))));
    }

    private static void requireNativeMethod(Map<String, MemberObservation> members, String key) {
        MemberObservation member = members.get(key);
        if (member == null || member.kind() != MemberKind.METHOD) {
            throw new IllegalArgumentException("native method evidence must reference a canonical method");
        }
    }

    private static void validateReferences(
            List<SourceUnit> units,
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            List<MethodBodyObservation> methodBodies,
            List<ImplementationBindingObservation> implementationBindings,
            List<UnresolvedParent> unresolvedParents,
            List<ObservationDiagnostic> diagnostics) {
        Set<String> sourcePaths = new HashSet<>();
        for (SourceUnit unit : units) {
            if (!sourcePaths.add(unit.path())) {
                throw new IllegalArgumentException("duplicate source-unit path: " + unit.path());
            }
        }
        Set<String> ids = new HashSet<>();
        for (ClassifierObservation classifier : classifiers) {
            if (!ids.add(classifier.id())) {
                throw new IllegalArgumentException("duplicate classifier id: " + classifier.id());
            }
            requireSourcePath(sourcePaths, classifier.sourcePath(), "classifier");
        }
        for (ClassifierObservation classifier : classifiers) {
            for (String parentId : classifier.parentIds()) {
                if (!ids.contains(parentId)) {
                    throw new IllegalArgumentException("unknown parent id: " + parentId);
                }
            }
        }
        Set<String> memberKeys = new HashSet<>();
        Map<String, MemberObservation> membersByKey = new HashMap<>();
        for (MemberObservation member : members) {
            if (!memberKeys.add(member.technicalKey())) {
                throw new IllegalArgumentException("duplicate technical member key: " + member.technicalKey());
            }
            membersByKey.put(member.technicalKey(), member);
            requireSourcePath(sourcePaths, member.sourcePath(), "member");
        }
        for (MemberObservation member : members) {
            for (String overriddenKey : member.overriddenMemberKeys()) {
                MemberObservation overridden = membersByKey.get(overriddenKey);
                if (overridden == null) {
                    throw new IllegalArgumentException("unknown overridden member key: " + overriddenKey);
                }
                if (member.technicalKey().equals(overriddenKey)) {
                    throw new IllegalArgumentException("method cannot override itself: " + overriddenKey);
                }
                if (member.kind() != MemberKind.METHOD || overridden.kind() != MemberKind.METHOD) {
                    throw new IllegalArgumentException("override relation must connect methods");
                }
            }
        }
        Set<String> bodyKeys = new HashSet<>();
        for (MethodBodyObservation body : methodBodies) {
            if (!bodyKeys.add(body.technicalKey())) {
                throw new IllegalArgumentException("duplicate technical method-body key: " + body.technicalKey());
            }
            requireSourcePath(sourcePaths, body.sourcePath(), "method body");
        }
        Set<String> bindingKeys = new HashSet<>();
        for (ImplementationBindingObservation binding : implementationBindings) {
            if (!bindingKeys.add(binding.technicalKey())) {
                throw new IllegalArgumentException(
                        "duplicate technical implementation-binding key: " + binding.technicalKey());
            }
            if (!ids.contains(binding.implementerClassifierId())) {
                throw new IllegalArgumentException(
                        "unknown implementation-binding implementer: " + binding.implementerClassifierId());
            }
            if (!memberKeys.contains(binding.targetMemberKey())) {
                throw new IllegalArgumentException(
                        "unknown implementation-binding target: " + binding.targetMemberKey());
            }
            if (!bodyKeys.contains(binding.bodyKey())) {
                throw new IllegalArgumentException(
                        "unknown implementation-binding body: " + binding.bodyKey());
            }
        }
        for (ClassifierObservation classifier : classifiers) {
            for (String memberKey : classifier.declaredMemberKeys()) {
                if (!memberKeys.contains(memberKey)) {
                    throw new IllegalArgumentException("unknown declared member key: " + memberKey);
                }
            }
            for (String memberKey : classifier.inheritedMemberKeys()) {
                if (!memberKeys.contains(memberKey)) {
                    throw new IllegalArgumentException("unknown inherited member key: " + memberKey);
                }
            }
        }
        for (UnresolvedParent unresolved : unresolvedParents) {
            if (!ids.contains(unresolved.ownerId())) {
                throw new IllegalArgumentException("unknown unresolved-parent owner: " + unresolved.ownerId());
            }
            requireSourcePath(sourcePaths, unresolved.sourcePath(), "unresolved parent");
        }
        for (ObservationDiagnostic diagnostic : diagnostics) {
            requireSourcePath(sourcePaths, diagnostic.sourcePath(), "diagnostic");
        }
    }

    private static void requireSourcePath(Set<String> units, String sourcePath, String kind) {
        if (!units.contains(sourcePath)) {
            throw new IllegalArgumentException(
                    kind + " sourcePath has no source unit: " + sourcePath);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
