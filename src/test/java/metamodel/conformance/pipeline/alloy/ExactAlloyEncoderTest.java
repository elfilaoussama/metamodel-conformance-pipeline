package metamodel.conformance.pipeline.alloy;

import metamodel.conformance.pipeline.TestObservations;
import metamodel.conformance.pipeline.util.Hashing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactAlloyEncoderTest {
    @Test
    void encodesAnExactRepositoryGraph() {
        String alloy = new ExactAlloyEncoder().encode(TestObservations.acyclic());

        assertEquals(2, alloy.lines().filter(line -> line.startsWith("one sig C_")).count());
        assertTrue(alloy.contains("parents = (" + ExactAlloyEncoder.classifierAtom(TestObservations.B)
                + "->" + ExactAlloyEncoder.classifierAtom(TestObservations.A)));
        assertTrue(alloy.contains("abstract sig NameToken"));
        assertTrue(alloy.contains("abstract sig TypeToken"));
        assertTrue(alloy.contains("abstract sig PositionToken"));
        assertTrue(alloy.contains("abstract sig PackageToken"));
        assertTrue(alloy.contains("abstract sig MemberVisibility"));
        assertTrue(alloy.contains("packageName: one PackageToken"));
        assertTrue(alloy.contains("parameterTypeAt: PositionToken -> lone TypeToken"));
        assertFalse(alloy.contains("namespaceKeyRepresentative"));
        assertFalse(alloy.contains("abstract sig Parameter {"));
        assertTrue(alloy.contains("parents = ("));
        assertTrue(alloy.contains("run ObservationConsistency for exactly 2 Classifier"));
        assertFalse(alloy.contains("run AcyclicGeneralizationViolation"));
        assertTrue(alloy.contains("fun AcyclicGeneralizationViolations : set Classifier"));
        assertFalse(alloy.contains("example.A"));
    }

    @Test
    void encodesObservedInheritedMembershipSeparatelyFromFormalDerivation() {
        var observation = TestObservations.inheritedViewConformant();
        String alloy = new ExactAlloyEncoder().encode(observation);

        assertTrue(alloy.contains("observedInheritedMembers = ("
                + ExactAlloyEncoder.classifierAtom(TestObservations.B) + "->"
                + ExactAlloyEncoder.memberAtom(observation.members().get(0).technicalKey())));
        assertTrue(alloy.contains("fun formalInheritedMembers[c : Classifier]"));
        assertTrue(alloy.contains("pred memberAccessibleFrom[c, owner : Classifier, member : Member]"));
        assertTrue(alloy.contains("pred samePackageInheritancePath[c, owner : Classifier]"));
        assertTrue(alloy.contains("fun InheritedViewConsistencyViolations : Classifier -> Member"));
        assertTrue(alloy.contains("fun LocalInheritedSeparationViolations : Classifier -> Member"));
    }

    @Test
    void encodesCompleteOrderedParametersAsStructuralEvidence() {
        var duplicateObservation = TestObservations.duplicateLocalMethods();
        var overloadedObservation = TestObservations.overloadedMethods();
        String duplicate = new ExactAlloyEncoder().encode(duplicateObservation);
        String overloaded = new ExactAlloyEncoder().encode(overloadedObservation);

        for (var member : duplicateObservation.members()) {
            String atom = ExactAlloyEncoder.memberAtom(member.technicalKey());
            assertTrue(duplicate.contains(atom + "->P_0->T_" + Hashing.sha256("java.lang.String")));
            assertTrue(duplicate.contains(atom + "->P_1->T_" + Hashing.sha256("int")));
        }

        String first = ExactAlloyEncoder.memberAtom(overloadedObservation.members().stream()
                .filter(member -> member.parameterTypes().get(0).equals("java.lang.String"))
                .findFirst().orElseThrow().technicalKey());
        String second = ExactAlloyEncoder.memberAtom(overloadedObservation.members().stream()
                .filter(member -> member.parameterTypes().get(0).equals("int"))
                .findFirst().orElseThrow().technicalKey());
        assertTrue(overloaded.contains(first + "->P_0->T_" + Hashing.sha256("java.lang.String")));
        assertTrue(overloaded.contains(first + "->P_1->T_" + Hashing.sha256("int")));
        assertTrue(overloaded.contains(second + "->P_0->T_" + Hashing.sha256("int")));
        assertTrue(overloaded.contains(second + "->P_1->T_" + Hashing.sha256("java.lang.String")));
        assertEquals(2, overloaded.lines().filter(line -> line.startsWith("one sig T_")).count());
        assertEquals(2, overloaded.lines().filter(line -> line.startsWith("one sig P_")).count());
        assertFalse(overloaded.contains("SignatureToken"));
    }
}
