package metamodel.conformance.pipeline.alloy;

import metamodel.conformance.pipeline.decision.DecisionStatus;
import metamodel.conformance.pipeline.model.ClassifierAbstraction;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Inheritability;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.MemberScope;
import metamodel.conformance.pipeline.model.MemberVisibility;
import metamodel.conformance.pipeline.model.MethodAbstraction;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.SourceUnit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencySupportInvariantTest {
    @Test
    void dependencySupportParticipatesInEvidenceWithoutBecomingAnEvaluationSubject() {
        String sourceId = "cls_" + "a".repeat(64);
        String supportId = "cls_" + "b".repeat(64);
        String supportMethodKey = "mem_" + "c".repeat(64);
        String sourcePath = "src/main/java/app/Child.java";
        String archiveDigest = "d".repeat(64);
        String archivePath = "dependencies/" + archiveDigest + "/dependency.jar";

        MemberObservation supportMethod = new MemberObservation(
                supportMethodKey,
                null,
                MemberKind.METHOD,
                Inheritability.INHERITABLE,
                MemberVisibility.PUBLIC,
                "work",
                archivePath,
                1,
                1,
                List.of(),
                MethodAbstraction.CONCRETE,
                MemberScope.INSTANCE,
                "void",
                List.of());
        ClassifierObservation support = new ClassifierObservation(
                supportId,
                "dep.Parent",
                "dep",
                ClassifierKind.CLASS,
                archivePath,
                1,
                1,
                List.of(),
                List.of(supportMethodKey),
                List.of(),
                ClassifierAbstraction.CONCRETE);
        ClassifierObservation source = new ClassifierObservation(
                sourceId,
                "app.Child",
                "app",
                ClassifierKind.CLASS,
                sourcePath,
                1,
                1,
                List.of(supportId),
                List.of(),
                List.of(supportMethodKey),
                ClassifierAbstraction.CONCRETE);

        Observation observation = new Observation(
                "13",
                "spoon-java",
                "1.4.0",
                List.of(),
                Set.of(
                        EvidenceKind.HIERARCHY,
                        EvidenceKind.DECLARATION_OWNERSHIP,
                        EvidenceKind.LOCAL_SIGNATURES,
                        EvidenceKind.INHERITABILITY,
                        EvidenceKind.INHERITED_MEMBERS,
                        EvidenceKind.METHOD_BODIES,
                        EvidenceKind.METHOD_ABSTRACTION,
                        EvidenceKind.IMPLEMENTATION_BINDINGS,
                        EvidenceKind.CLASSIFIER_ABSTRACTION,
                        EvidenceKind.METHOD_SCOPE,
                        EvidenceKind.METHOD_RETURN_TYPES,
                        EvidenceKind.OVERRIDE_RELATIONS),
                List.of(
                        new SourceUnit(Language.JAVA, sourcePath, "e".repeat(64)),
                        new SourceUnit(Language.JAVA_ARCHIVE, archivePath, archiveDigest)),
                List.of(source, support),
                List.of(supportMethod),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        ExactAlloyEncoder encoder = new ExactAlloyEncoder();
        String alloy = encoder.encode(observation);
        String sourceAtom = ExactAlloyEncoder.classifierAtom(sourceId);
        String supportAtom = ExactAlloyEncoder.classifierAtom(supportId);
        String sourceScope = alloy.substring(
                alloy.indexOf("fun SourceClassifiers"),
                alloy.indexOf("fact ExactObservation"));
        assertTrue(sourceScope.contains(sourceAtom));
        assertFalse(sourceScope.contains(supportAtom));

        var decisions = new AlloyInvariantEvaluator().evaluateAll(observation, alloy);
        assertEquals(
                DecisionStatus.CONFORMANT,
                decisions.stream()
                        .filter(item -> item.invariantId().equals("inherited-view-consistency"))
                        .findFirst().orElseThrow().status());
        assertEquals(
                DecisionStatus.CONFORMANT,
                decisions.stream()
                        .filter(item -> item.invariantId().equals("abstraction-implementation-consistency"))
                        .findFirst().orElseThrow().status());
    }
}
