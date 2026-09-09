package metamodel.conformance.pipeline;

import metamodel.conformance.pipeline.adapter.java.JavaImplementationSourceObserver;
import metamodel.conformance.pipeline.alloy.AlloyInvariantEvaluator;
import metamodel.conformance.pipeline.alloy.ExactAlloyEncoder;
import metamodel.conformance.pipeline.decision.DecisionStatus;
import metamodel.conformance.pipeline.emf.ObservationXmiReader;
import metamodel.conformance.pipeline.emf.ObservationXmiWriter;
import metamodel.conformance.pipeline.invariant.InvariantRegistry;
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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audits the O-01 representation bridge without pretending that generated trace keys
 * are independently observed source identities.
 */
class IdentityBridgeIntegrityTest {
    @TempDir
    Path temporary;

    @Test
    void sameNameAndSignatureInDifferentDeclarationsRemainDistinctThroughXmiAndAlloy() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("same-signature"));
        Path firstPackage = Files.createDirectories(source.resolve("first"));
        Path secondPackage = Files.createDirectories(source.resolve("second"));
        Files.writeString(firstPackage.resolve("First.java"), """
                package first;
                public class First {
                    public Number value(String input) { return 1; }
                }
                """);
        Files.writeString(secondPackage.resolve("Second.java"), """
                package second;
                public class Second {
                    public Number value(String input) { return 2; }
                }
                """);

        Observation observed = new JavaImplementationSourceObserver(List.of()).observe(source, Set.of());
        List<MemberObservation> methods = observed.members().stream()
                .filter(member -> member.kind() == MemberKind.METHOD)
                .filter(member -> member.memberName().equals("value"))
                .toList();
        assertEquals(2, methods.size());
        assertEquals(1, methods.stream().map(MemberObservation::memberName).distinct().count());
        assertEquals(1, methods.stream().map(MemberObservation::parameterTypes).distinct().count());
        assertEquals(2, methods.stream().map(MemberObservation::technicalKey).distinct().count());

        Set<String> owners = observed.classifiers().stream()
                .filter(classifier -> classifier.declaredMemberKeys().stream()
                        .anyMatch(key -> methods.stream().anyMatch(method -> method.technicalKey().equals(key))))
                .map(ClassifierObservation::id)
                .collect(Collectors.toSet());
        assertEquals(2, owners.size());

        Path xmi = temporary.resolve("same-signature.xmi");
        new ObservationXmiWriter().write(observed, xmi);
        Observation replayed = new ObservationXmiReader().read(xmi);
        assertEquals(observed, replayed);

        Set<String> methodKeys = methods.stream().map(MemberObservation::technicalKey)
                .collect(Collectors.toSet());
        var atomMap = new ExactAlloyEncoder().atomTechnicalKeys(replayed);
        assertTrue(atomMap.values().containsAll(methodKeys));
        assertEquals(2, atomMap.values().stream().filter(methodKeys::contains).count());

        var decision = new AlloyInvariantEvaluator().evaluateAll(
                        replayed, new ExactAlloyEncoder().encode(replayed)).stream()
                .filter(item -> item.invariantId().equals("local-namespace-uniqueness"))
                .findFirst().orElseThrow();
        assertEquals(DecisionStatus.CONFORMANT, decision.status());
    }

    @Test
    void duplicateObservedLabelsRemainRepresentableWithoutCollapsingTechnicalIdentity() throws Exception {
        String firstClassifier = "cls_" + "1".repeat(64);
        String secondClassifier = "cls_" + "2".repeat(64);
        String firstMember = "mem_" + "3".repeat(64);
        String secondMember = "mem_" + "4".repeat(64);
        String sharedObservedLabel = "serialized-member-17";

        MemberObservation left = new MemberObservation(
                firstMember, sharedObservedLabel, MemberKind.METHOD, Inheritability.INHERITABLE,
                MemberVisibility.PUBLIC, "run", "First.java", 2, 2, List.of("java.lang.String"),
                MethodAbstraction.CONCRETE, MemberScope.INSTANCE);
        MemberObservation right = new MemberObservation(
                secondMember, sharedObservedLabel, MemberKind.METHOD, Inheritability.INHERITABLE,
                MemberVisibility.PUBLIC, "run", "Second.java", 2, 2, List.of("java.lang.String"),
                MethodAbstraction.CONCRETE, MemberScope.INSTANCE);
        assertEquals(left.observedIdentifier(), right.observedIdentifier());
        assertNotEquals(left.technicalKey(), right.technicalKey());

        Observation observation = new Observation(
                "12", "identity-bridge-audit", "1", List.of(),
                Set.of(EvidenceKind.DECLARATION_OWNERSHIP, EvidenceKind.LOCAL_SIGNATURES,
                        EvidenceKind.METHOD_ABSTRACTION, EvidenceKind.METHOD_SCOPE),
                List.of(
                        new SourceUnit(Language.JAVA, "First.java", "a".repeat(64)),
                        new SourceUnit(Language.JAVA, "Second.java", "b".repeat(64))),
                List.of(
                        new ClassifierObservation(
                                firstClassifier, "First", "<default>", ClassifierKind.CLASS,
                                "First.java", 1, 3, List.of(), List.of(firstMember), List.of(),
                                ClassifierAbstraction.CONCRETE),
                        new ClassifierObservation(
                                secondClassifier, "Second", "<default>", ClassifierKind.CLASS,
                                "Second.java", 1, 3, List.of(), List.of(secondMember), List.of(),
                                ClassifierAbstraction.CONCRETE)),
                List.of(left, right),
                List.of(), List.of(), List.of(), List.of());

        Path xmi = temporary.resolve("duplicate-observed-label.xmi");
        new ObservationXmiWriter().write(observation, xmi);
        Observation replayed = new ObservationXmiReader().read(xmi);
        assertEquals(observation, replayed);
        assertEquals(2, replayed.members().stream()
                .filter(member -> sharedObservedLabel.equals(member.observedIdentifier())).count());
        assertEquals(2, replayed.members().stream()
                .map(MemberObservation::technicalKey).distinct().count());

        var atomMap = new ExactAlloyEncoder().atomTechnicalKeys(replayed);
        assertTrue(atomMap.values().contains(firstMember));
        assertTrue(atomMap.values().contains(secondMember));
        assertNotEquals(
                atomMap.entrySet().stream().filter(entry -> entry.getValue().equals(firstMember))
                        .findFirst().orElseThrow().getKey(),
                atomMap.entrySet().stream().filter(entry -> entry.getValue().equals(secondMember))
                        .findFirst().orElseThrow().getKey());
    }

    @Test
    void generatedTraceKeysAreNotRegisteredAsEmpiricalO01Evidence() {
        assertFalse(InvariantRegistry.load().all().stream()
                .anyMatch(definition -> "O-01".equals(definition.specificationTrace())));
    }
}
