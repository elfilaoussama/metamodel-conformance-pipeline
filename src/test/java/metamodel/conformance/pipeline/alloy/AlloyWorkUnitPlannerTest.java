package metamodel.conformance.pipeline.alloy;

import metamodel.conformance.pipeline.invariant.InvariantRegistry;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.SourceUnit;
import metamodel.conformance.pipeline.util.Hashing;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlloyWorkUnitPlannerTest {
    private final AlloyWorkUnitPlanner planner = new AlloyWorkUnitPlanner();

    @Test
    void partitionsHierarchyByExactWeakComponents() {
        String a = classifierId("A");
        String b = classifierId("B");
        String c = classifierId("C");
        Observation observation = observation(
                List.of(classifier(a, List.of()), classifier(b, List.of(a)), classifier(c, List.of())),
                List.of(), Set.of(EvidenceKind.HIERARCHY));

        List<Observation> units = planner.plan(observation,
                InvariantRegistry.load().require("acyclic-generalization"));

        assertEquals(1, units.size());
        assertEquals(3, units.get(0).classifiers().size());
        assertEquals(0, units.stream().mapToInt(unit -> unit.members().size()).sum());
        assertTrue(units.stream().anyMatch(unit -> unit.classifiers().stream()
                .anyMatch(item -> item.id().equals(b) && item.parentIds().equals(List.of(a)))));
        assertTrue(units.get(0).classifiers().stream()
                .anyMatch(item -> item.id().equals(c) && item.parentIds().isEmpty()));
    }

    @Test
    void partitionsLocalNamespaceWithoutDroppingAnyDeclaredMember() {
        List<ClassifierObservation> classifiers = new ArrayList<>();
        List<MemberObservation> members = new ArrayList<>();
        for (int index = 0; index < 400; index++) {
            String memberKey = memberId("member-" + index);
            classifiers.add(classifier(classifierId("classifier-" + index), List.of(), List.of(memberKey)));
            members.add(new MemberObservation(
                    memberKey, null, MemberKind.METHOD, "work", "example/A.java",
                    index + 1, index + 1, List.of("Type" + index)));
        }
        Observation observation = observation(classifiers, members,
                Set.of(EvidenceKind.DECLARATION_OWNERSHIP, EvidenceKind.LOCAL_SIGNATURES));

        List<Observation> units = planner.plan(observation,
                InvariantRegistry.load().require("local-namespace-uniqueness"));

        assertTrue(units.size() > 1 && units.size() < 400);
        assertTrue(units.stream().allMatch(unit ->
                unit.classifiers().size() + unit.members().size()
                        <= AlloyWorkUnitPlanner.WORK_UNIT_ATOM_TARGET));
        assertEquals(400, units.stream().mapToInt(unit -> unit.classifiers().size()).sum());
        assertEquals(400, units.stream().mapToInt(unit -> unit.members().size()).sum());
    }

    @Test
    void keepsUnownedMembersAsOwnershipWorkUnits() {
        String memberKey = memberId("unowned");
        Observation observation = observation(
                List.of(classifier(classifierId("A"), List.of())),
                List.of(new MemberObservation(
                        memberKey, null, MemberKind.ATTRIBUTE, "value",
                        "example/A.java", 1, 1, List.of())),
                Set.of(EvidenceKind.DECLARATION_OWNERSHIP));

        List<Observation> units = planner.plan(observation,
                InvariantRegistry.load().require("exclusive-declaration-ownership"));

        assertEquals(1, units.size());
        assertEquals(List.of(memberKey), units.get(0).members().stream()
                .map(MemberObservation::technicalKey).toList());
        assertTrue(units.get(0).classifiers().isEmpty());
    }

    @Test
    void partitionsFiveThousandIndependentClassifiersWithoutLoss() {
        List<ClassifierObservation> classifiers = new ArrayList<>();
        for (int index = 0; index < 5_000; index++) {
            classifiers.add(classifier(classifierId("large-" + index), List.of()));
        }
        Observation observation = observation(classifiers, List.of(), Set.of(EvidenceKind.HIERARCHY));

        List<Observation> units = planner.plan(
                observation, InvariantRegistry.load().require("acyclic-generalization"));

        assertTrue(units.size() > 1);
        assertTrue(units.stream().allMatch(unit ->
                unit.classifiers().size() <= AlloyWorkUnitPlanner.WORK_UNIT_ATOM_TARGET));
        assertEquals(5_000, units.stream().mapToInt(unit -> unit.classifiers().size()).sum());
        assertEquals(5_000, units.stream().flatMap(unit -> unit.classifiers().stream())
                .map(ClassifierObservation::id).distinct().count());
    }

    @Test
    void preservesAnOversizedConnectedHierarchyAsOneExactUnit() {
        List<ClassifierObservation> classifiers = new ArrayList<>();
        String parent = null;
        for (int index = 0; index < 600; index++) {
            String current = classifierId("chain-" + index);
            classifiers.add(classifier(current, parent == null ? List.of() : List.of(parent)));
            parent = current;
        }
        Observation observation = observation(classifiers, List.of(), Set.of(EvidenceKind.HIERARCHY));

        List<Observation> units = planner.plan(
                observation, InvariantRegistry.load().require("acyclic-generalization"));

        assertEquals(1, units.size());
        assertEquals(600, units.get(0).classifiers().size());
        assertEquals(599, units.get(0).classifiers().stream()
                .mapToInt(item -> item.parentIds().size()).sum());
    }

    private static Observation observation(
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            Set<EvidenceKind> evidence) {
        return new Observation(
                "7", "test-adapter", "1.0.0", List.of(), evidence,
                List.of(new SourceUnit(
                        Language.JAVA, "example/A.java", Hashing.sha256("source"))),
                classifiers, members, List.of());
    }

    private static ClassifierObservation classifier(String id, List<String> parents) {
        return classifier(id, parents, List.of());
    }

    private static ClassifierObservation classifier(
            String id, List<String> parents, List<String> declaredMembers) {
        return new ClassifierObservation(
                id, "example." + id.substring(4, 12), ClassifierKind.CLASS,
                "example/A.java", 1, 1, parents, declaredMembers);
    }

    private static String classifierId(String seed) {
        return "cls_" + Hashing.sha256(seed);
    }

    private static String memberId(String seed) {
        return "mem_" + Hashing.sha256(seed);
    }
}
