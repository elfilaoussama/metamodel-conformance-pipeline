package metamodel.conformance.pipeline;

import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Inheritability;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.SourceUnit;
import metamodel.conformance.pipeline.model.UnresolvedParent;
import metamodel.conformance.pipeline.util.Hashing;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public final class TestObservations {
    public static final String A = id("A");
    public static final String B = id("B");

    private TestObservations() {
    }

    public static Observation acyclic() {
        return observation(
                List.of(classifier(A, "example.A", List.of()), classifier(B, "example.B", List.of(A))),
                List.of());
    }

    public static Observation cyclic() {
        return observation(
                List.of(classifier(A, "example.A", List.of(B)), classifier(B, "example.B", List.of(A))),
                List.of());
    }

    public static Observation unresolved() {
        return observation(
                List.of(classifier(A, "example.A", List.of())),
                List.of(new UnresolvedParent(A, "missing.Parent", "example/A.java", 3)));
    }

    public static Observation membersConformant() {
        MemberObservation method = method("method-one", "work", List.of("java.lang.String"));
        MemberObservation attribute = attribute("attribute-one", "value");
        return memberObservation(
                List.of(classifier(A, "example.A", List.of(), List.of(method.technicalKey(), attribute.technicalKey()))),
                List.of(method, attribute),
                Set.of(EvidenceKind.HIERARCHY, EvidenceKind.DECLARATION_OWNERSHIP, EvidenceKind.LOCAL_SIGNATURES));
    }

    public static Observation unownedMember() {
        MemberObservation method = method("unowned", "work", List.of());
        return memberObservation(
                List.of(classifier(A, "example.A", List.of(), List.of())),
                List.of(method),
                Set.of(EvidenceKind.HIERARCHY, EvidenceKind.DECLARATION_OWNERSHIP, EvidenceKind.LOCAL_SIGNATURES));
    }

    public static Observation duplicateLocalMethods() {
        MemberObservation first = method("duplicate-one", "work", List.of("java.lang.String", "int"));
        MemberObservation second = method("duplicate-two", "work", List.of("java.lang.String", "int"));
        return memberObservation(
                List.of(classifier(A, "example.A", List.of(),
                        List.of(first.technicalKey(), second.technicalKey()))),
                List.of(first, second),
                Set.of(EvidenceKind.HIERARCHY, EvidenceKind.DECLARATION_OWNERSHIP, EvidenceKind.LOCAL_SIGNATURES));
    }

    public static Observation overloadedMethods() {
        MemberObservation first = method("overload-one", "work", List.of("java.lang.String", "int"));
        MemberObservation second = method("overload-two", "work", List.of("int", "java.lang.String"));
        return memberObservation(
                List.of(classifier(A, "example.A", List.of(),
                        List.of(first.technicalKey(), second.technicalKey()))),
                List.of(first, second),
                Set.of(EvidenceKind.HIERARCHY, EvidenceKind.DECLARATION_OWNERSHIP, EvidenceKind.LOCAL_SIGNATURES));
    }

    public static Observation repeatedParameterTypes() {
        MemberObservation method = method(
                "repeated-parameters", "work", List.of("Type", "Type", "Type", "Type"));
        return memberObservation(
                List.of(classifier(A, "example.A", List.of(), List.of(method.technicalKey()))),
                List.of(method),
                Set.of(EvidenceKind.DECLARATION_OWNERSHIP, EvidenceKind.LOCAL_SIGNATURES));
    }

    public static Observation duplicateLocalAttributes() {
        MemberObservation first = attribute("duplicate-attribute-one", "value");
        MemberObservation second = attribute("duplicate-attribute-two", "value");
        return memberObservation(
                List.of(classifier(A, "example.A", List.of(),
                        List.of(first.technicalKey(), second.technicalKey()))),
                List.of(first, second),
                Set.of(EvidenceKind.HIERARCHY, EvidenceKind.DECLARATION_OWNERSHIP, EvidenceKind.LOCAL_SIGNATURES));
    }

    public static Observation incompleteLocalSignatures() {
        Observation base = membersConformant();
        return new Observation(
                base.schemaVersion(), base.adapterId(), base.adapterVersion(), base.externalParents(),
                Set.of(EvidenceKind.HIERARCHY, EvidenceKind.DECLARATION_OWNERSHIP),
                base.units(), base.classifiers(), base.members(), base.unresolvedParents());
    }

    public static Observation inheritedViewConformant() {
        MemberObservation inherited = inheritableMethod("inherited", "work", List.of("java.lang.String"));
        return memberObservation(
                List.of(
                        new ClassifierObservation(A, "example.A", ClassifierKind.CLASS,
                                "example/A.java", 3, 8, List.of(),
                                List.of(inherited.technicalKey()), List.of()),
                        new ClassifierObservation(B, "example.B", ClassifierKind.CLASS,
                                "example/B.java", 3, 6, List.of(A),
                                List.of(), List.of(inherited.technicalKey()))),
                List.of(inherited),
                inheritedEvidence());
    }

    public static Observation missingInheritedMember() {
        Observation base = inheritedViewConformant();
        ClassifierObservation child = base.classifiers().stream()
                .filter(classifier -> classifier.id().equals(B)).findFirst().orElseThrow();
        ClassifierObservation withoutInherited = new ClassifierObservation(
                child.id(), child.qualifiedName(), child.kind(), child.sourcePath(),
                child.startLine(), child.endLine(), child.parentIds(),
                child.declaredMemberKeys(), List.of());
        return new Observation(
                base.schemaVersion(), base.adapterId(), base.adapterVersion(), base.externalParents(),
                base.completeEvidence(), base.units(),
                List.of(base.classifiers().stream().filter(item -> item.id().equals(A)).findFirst().orElseThrow(),
                        withoutInherited),
                base.members(), base.unresolvedParents());
    }

    public static Observation localInheritedOverlap() {
        MemberObservation member = inheritableMethod("overlap", "work", List.of());
        return memberObservation(
                List.of(new ClassifierObservation(A, "example.A", ClassifierKind.CLASS,
                        "example/A.java", 3, 8, List.of(),
                        List.of(member.technicalKey()), List.of(member.technicalKey()))),
                List.of(member), inheritedEvidence());
    }

    public static Observation duplicateInheritedMethods() {
        String left = id("left");
        String right = id("right");
        String child = id("child");
        MemberObservation first = inheritableMethod("inherited-left", "work", List.of("int"));
        MemberObservation second = inheritableMethod("inherited-right", "work", List.of("int"));
        return memberObservation(
                List.of(
                        new ClassifierObservation(left, "example.Left", ClassifierKind.CLASS,
                                "example/A.java", 1, 2, List.of(), List.of(first.technicalKey()), List.of()),
                        new ClassifierObservation(right, "example.Right", ClassifierKind.CLASS,
                                "example/A.java", 3, 4, List.of(), List.of(second.technicalKey()), List.of()),
                        new ClassifierObservation(child, "example.Child", ClassifierKind.CLASS,
                                "example/A.java", 5, 6, List.of(left, right), List.of(),
                                List.of(first.technicalKey(), second.technicalKey()))),
                List.of(first, second), inheritedEvidence());
    }

    public static String id(String seed) {
        return "cls_" + Hashing.sha256(seed);
    }

    private static ClassifierObservation classifier(String id, String name, List<String> parents) {
        return classifier(id, name, parents, List.of());
    }

    private static ClassifierObservation classifier(
            String id, String name, List<String> parents, List<String> declaredMembers) {
        return new ClassifierObservation(id, name, ClassifierKind.CLASS, "example/" + name.substring(8)
                + ".java", 3, 4, parents, declaredMembers);
    }

    private static Observation observation(
            List<ClassifierObservation> classifiers, List<UnresolvedParent> unresolved) {
        return new Observation(
                "7", "test-adapter", "1.0.0", List.of(),
                sourceUnits(classifiers, List.of()),
                classifiers,
                unresolved);
    }

    private static Observation memberObservation(
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            Set<EvidenceKind> evidence) {
        return new Observation(
                "7", "test-adapter", "1.0.0", List.of(), evidence,
                sourceUnits(classifiers, members),
                classifiers, members, List.of());
    }

    private static List<SourceUnit> sourceUnits(
            List<ClassifierObservation> classifiers, List<MemberObservation> members) {
        return Stream.concat(
                        classifiers.stream().map(ClassifierObservation::sourcePath),
                        members.stream().map(MemberObservation::sourcePath))
                .distinct()
                .sorted()
                .map(path -> new SourceUnit(
                        Language.JAVA, path, Hashing.sha256("source\0" + path)))
                .toList();
    }

    private static MemberObservation method(String seed, String name, List<String> parameterTypes) {
        return new MemberObservation(
                "mem_" + Hashing.sha256(seed), null, MemberKind.METHOD, name,
                "example/A.java", 5, 6, parameterTypes);
    }

    private static MemberObservation attribute(String seed, String name) {
        return new MemberObservation(
                "mem_" + Hashing.sha256(seed), null, MemberKind.ATTRIBUTE, name,
                "example/A.java", 7, 7, List.of());
    }

    private static MemberObservation inheritableMethod(
            String seed, String name, List<String> parameterTypes) {
        return new MemberObservation(
                "mem_" + Hashing.sha256(seed), null, MemberKind.METHOD,
                Inheritability.INHERITABLE, name, "example/A.java", 5, 6, parameterTypes);
    }

    private static Set<EvidenceKind> inheritedEvidence() {
        return Set.of(
                EvidenceKind.HIERARCHY,
                EvidenceKind.DECLARATION_OWNERSHIP,
                EvidenceKind.LOCAL_SIGNATURES,
                EvidenceKind.INHERITABILITY,
                EvidenceKind.INHERITED_MEMBERS);
    }
}
