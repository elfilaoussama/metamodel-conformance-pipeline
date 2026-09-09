package metamodel.conformance.pipeline.alloy;

import metamodel.conformance.pipeline.decision.Decision;
import metamodel.conformance.pipeline.decision.DecisionStatus;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.ImplementationBindingObservation;
import metamodel.conformance.pipeline.model.Inheritability;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.MemberVisibility;
import metamodel.conformance.pipeline.model.MethodAbstraction;
import metamodel.conformance.pipeline.model.MethodBodyObservation;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.SourceUnit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImplementationBindingInvariantTest {
    private static final String BASE = "cls_" + "a".repeat(64);
    private static final String CHILD = "cls_" + "b".repeat(64);
    private static final String METHOD = "mem_" + "c".repeat(64);
    private static final String OTHER_METHOD = "mem_" + "d".repeat(64);
    private static final String BODY = "body_" + "e".repeat(64);
    private static final String BODY_2 = "body_" + "f".repeat(64);
    private static final String BINDING = "bind_" + "1".repeat(64);
    private static final String BINDING_2 = "bind_" + "2".repeat(64);

    @Test
    void acceptsExactLocalConcreteBinding() {
        Observation observation = observation(
                List.of(classifier(BASE, List.of(), List.of(METHOD))),
                List.of(method(METHOD, MethodAbstraction.CONCRETE)),
                List.of(body(BODY)),
                List.of(binding(BINDING, BASE, METHOD, BODY)));
        assertEquals(DecisionStatus.CONFORMANT, decision(observation).status());
    }

    @Test
    void reportsConcreteLocalMethodWithoutBindingAndOrphanBody() {
        Observation observation = observation(
                List.of(classifier(BASE, List.of(), List.of(METHOD))),
                List.of(method(METHOD, MethodAbstraction.CONCRETE)),
                List.of(body(BODY)), List.of());
        Decision decision = decision(observation);
        assertEquals(DecisionStatus.NON_CONFORMANT, decision.status());
        Set<String> witnesses = witnessKeys(decision);
        assertTrue(witnesses.contains(METHOD));
        assertTrue(witnesses.contains(BODY));
    }

    @Test
    void reportsBindingOnAbstractLocalMethod() {
        Observation observation = observation(
                List.of(classifier(BASE, List.of(), List.of(METHOD))),
                List.of(method(METHOD, MethodAbstraction.ABSTRACT)),
                List.of(body(BODY)),
                List.of(binding(BINDING, BASE, METHOD, BODY)));
        assertEquals(DecisionStatus.NON_CONFORMANT, decision(observation).status());
    }

    @Test
    void reportsDuplicateBindingForSameImplementerAndTarget() {
        Observation observation = observation(
                List.of(classifier(BASE, List.of(), List.of(METHOD))),
                List.of(method(METHOD, MethodAbstraction.CONCRETE)),
                List.of(body(BODY), body(BODY_2)),
                List.of(
                        binding(BINDING, BASE, METHOD, BODY),
                        binding(BINDING_2, BASE, METHOD, BODY_2)));
        assertEquals(DecisionStatus.NON_CONFORMANT, decision(observation).status());
    }

    @Test
    void ternaryBindingCanTargetInheritedMethodFromDifferentImplementer() {
        Observation observation = observation(
                List.of(
                        classifier(BASE, List.of(), List.of(METHOD)),
                        classifier(CHILD, List.of(BASE), List.of())),
                List.of(method(METHOD, MethodAbstraction.ABSTRACT)),
                List.of(body(BODY)),
                List.of(binding(BINDING, CHILD, METHOD, BODY)));
        assertEquals(DecisionStatus.CONFORMANT, decision(observation).status());
    }

    @Test
    void reportsTargetThatIsNotAvailableToImplementer() {
        Observation observation = observation(
                List.of(
                        classifier(BASE, List.of(), List.of(METHOD)),
                        classifier(CHILD, List.of(), List.of(OTHER_METHOD))),
                List.of(
                        method(METHOD, MethodAbstraction.ABSTRACT),
                        method(OTHER_METHOD, MethodAbstraction.ABSTRACT)),
                List.of(body(BODY)),
                List.of(binding(BINDING, CHILD, METHOD, BODY)));
        assertEquals(DecisionStatus.NON_CONFORMANT, decision(observation).status());
    }

    private static Set<String> witnessKeys(Decision decision) {
        return decision.witnesses().stream().flatMap(item -> item.technicalKeys().stream())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static Decision decision(Observation observation) {
        return new AlloyInvariantEvaluator().evaluateAll(
                        observation, new ExactAlloyEncoder().encode(observation)).stream()
                .filter(item -> item.invariantId().equals("implementation-binding-consistency"))
                .findFirst().orElseThrow();
    }

    private static Observation observation(
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            List<MethodBodyObservation> bodies,
            List<ImplementationBindingObservation> bindings) {
        return new Observation(
                "10", "test", "1", List.of(),
                Set.of(
                        EvidenceKind.HIERARCHY,
                        EvidenceKind.DECLARATION_OWNERSHIP,
                        EvidenceKind.LOCAL_SIGNATURES,
                        EvidenceKind.INHERITABILITY,
                        EvidenceKind.METHOD_BODIES,
                        EvidenceKind.METHOD_ABSTRACTION,
                        EvidenceKind.IMPLEMENTATION_BINDINGS),
                List.of(new SourceUnit(Language.JAVA, "Sample.java", "0".repeat(64))),
                classifiers, members, bodies, bindings, List.of(), List.of());
    }

    private static ClassifierObservation classifier(String id, List<String> parents, List<String> members) {
        return new ClassifierObservation(
                id, id.equals(BASE) ? "Base" : "Child", "<default>", ClassifierKind.CLASS,
                "Sample.java", 1, 20, parents, members, List.of());
    }

    private static MemberObservation method(String key, MethodAbstraction abstraction) {
        return new MemberObservation(
                key, null, MemberKind.METHOD, Inheritability.INHERITABLE,
                MemberVisibility.PUBLIC, key.equals(METHOD) ? "run" : "other",
                "Sample.java", 2, 4, List.of(), abstraction);
    }

    private static MethodBodyObservation body(String key) {
        return new MethodBodyObservation(key, "Sample.java", 5, 7);
    }

    private static ImplementationBindingObservation binding(
            String key, String implementer, String target, String body) {
        return new ImplementationBindingObservation(key, implementer, target, body);
    }
}
