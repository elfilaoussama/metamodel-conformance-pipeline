package metamodel.conformance.pipeline.emf;

import metamodel.conformance.pipeline.TestObservations;
import metamodel.conformance.pipeline.model.ClassifierAbstraction;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.ImplementationBindingObservation;
import metamodel.conformance.pipeline.model.Inheritability;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.MemberScope;
import metamodel.conformance.pipeline.model.MemberVisibility;
import metamodel.conformance.pipeline.model.MethodAbstraction;
import metamodel.conformance.pipeline.model.MethodBodyObservation;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;
import metamodel.conformance.pipeline.model.SourceUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ObservationXmiRoundTripTest {
    @TempDir
    Path temporary;

    @Test
    void roundTripsAndSerializesDeterministically() throws Exception {
        Observation observation = schema12(TestObservations.inheritedViewConformant());
        Path first = temporary.resolve("first.xmi");
        Path second = temporary.resolve("second.xmi");
        ObservationXmiWriter writer = new ObservationXmiWriter();
        writer.write(observation, first);
        writer.write(observation, second);
        assertEquals(observation, new ObservationXmiReader().read(first));
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
    }

    @Test
    void preservesRepeatedOrderedParameterTypes() throws Exception {
        Observation observation = schema12(TestObservations.repeatedParameterTypes());
        Path xmi = temporary.resolve("repeated-parameters.xmi");
        new ObservationXmiWriter().write(observation, xmi);
        Observation replayed = new ObservationXmiReader().read(xmi);
        assertEquals(List.of("Type", "Type", "Type", "Type"), replayed.members().get(0).parameterTypes());
        assertEquals(observation, replayed);
    }

    @Test
    void preservesContextualAccessibilityAndEvidenceDiagnostics() throws Exception {
        Observation base = schema12(TestObservations.inheritedViewConformant());
        Observation observation = new Observation(
                base.schemaVersion(), base.adapterId(), base.adapterVersion(), base.externalParents(),
                base.completeEvidence(), base.units(), base.classifiers(), base.members(), base.methodBodies(),
                base.implementationBindings(), base.unresolvedParents(), List.of(new ObservationDiagnostic(
                        DiagnosticKind.EVIDENCE_INCOMPLETE,
                        base.units().get(0).path(), 0, "dependency classpath is incomplete")));
        Path xmi = temporary.resolve("evidence-diagnostic.xmi");
        new ObservationXmiWriter().write(observation, xmi);
        Observation replayed = new ObservationXmiReader().read(xmi);
        assertEquals(observation, replayed);
        assertEquals(base.classifiers().get(0).packageName(), replayed.classifiers().get(0).packageName());
        assertEquals(base.members().get(0).visibility(), replayed.members().get(0).visibility());
    }

    @Test
    void preservesImplementationAbstractionAndOverrideEvidence() throws Exception {
        String parentClassifierKey = "cls_" + "0".repeat(64);
        String classifierKey = "cls_" + "1".repeat(64);
        String inheritedMemberKey = "mem_" + "6".repeat(64);
        String memberKey = "mem_" + "2".repeat(64);
        String bodyKey = "body_" + "3".repeat(64);
        String bindingKey = "bind_" + "4".repeat(64);
        MemberObservation inherited = new MemberObservation(
                inheritedMemberKey, null, MemberKind.METHOD, Inheritability.INHERITABLE,
                MemberVisibility.PUBLIC, "run", "Base.java", 2, 4, List.of(),
                MethodAbstraction.CONCRETE, MemberScope.INSTANCE, "java.lang.Number", List.of());
        MemberObservation member = new MemberObservation(
                memberKey, null, MemberKind.METHOD, Inheritability.INHERITABLE,
                MemberVisibility.PUBLIC, "run", "Sample.java", 2, 4, List.of(),
                MethodAbstraction.CONCRETE, MemberScope.INSTANCE, "java.lang.Number",
                List.of(inheritedMemberKey));
        Observation observation = new Observation(
                "12", "test", "1", List.of(),
                Set.of(EvidenceKind.HIERARCHY, EvidenceKind.DECLARATION_OWNERSHIP,
                        EvidenceKind.METHOD_BODIES, EvidenceKind.METHOD_ABSTRACTION,
                        EvidenceKind.IMPLEMENTATION_BINDINGS, EvidenceKind.CLASSIFIER_ABSTRACTION,
                        EvidenceKind.METHOD_SCOPE, EvidenceKind.METHOD_RETURN_TYPES,
                        EvidenceKind.OVERRIDE_RELATIONS),
                List.of(
                        new SourceUnit(Language.JAVA, "Base.java", "7".repeat(64)),
                        new SourceUnit(Language.JAVA, "Sample.java", "5".repeat(64))),
                List.of(
                        new ClassifierObservation(
                                parentClassifierKey, "Base", "<default>", ClassifierKind.CLASS,
                                "Base.java", 1, 5, List.of(), List.of(inheritedMemberKey), List.of(),
                                ClassifierAbstraction.CONCRETE),
                        new ClassifierObservation(
                                classifierKey, "Sample", "<default>", ClassifierKind.CLASS,
                                "Sample.java", 1, 5, List.of(parentClassifierKey), List.of(memberKey), List.of(),
                                ClassifierAbstraction.CONCRETE)),
                List.of(inherited, member),
                List.of(new MethodBodyObservation(bodyKey, "Sample.java", 2, 4)),
                List.of(new ImplementationBindingObservation(
                        bindingKey, classifierKey, memberKey, bodyKey)),
                List.of(), List.of());
        Path xmi = temporary.resolve("implementation-evidence.xmi");
        new ObservationXmiWriter().write(observation, xmi);
        Observation replayed = new ObservationXmiReader().read(xmi);
        assertEquals(observation, replayed);
        MemberObservation replayedLocal = replayed.members().stream()
                .filter(item -> item.technicalKey().equals(memberKey)).findFirst().orElseThrow();
        assertEquals("java.lang.Number", replayedLocal.returnType());
        assertEquals(List.of(inheritedMemberKey), replayedLocal.overriddenMemberKeys());
        assertEquals(classifierKey, replayed.implementationBindings().get(0).implementerClassifierId());
        assertEquals(memberKey, replayed.implementationBindings().get(0).targetMemberKey());
        assertEquals(bodyKey, replayed.implementationBindings().get(0).bodyKey());
    }

    private static Observation schema12(Observation base) {
        return new Observation(
                "12", base.adapterId(), base.adapterVersion(), base.externalParents(),
                base.completeEvidence(), base.units(), base.classifiers(), base.members(), List.of(), List.of(),
                base.unresolvedParents(), base.diagnostics());
    }
}
