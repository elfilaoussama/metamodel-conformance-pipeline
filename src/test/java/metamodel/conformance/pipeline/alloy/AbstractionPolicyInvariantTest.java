package metamodel.conformance.pipeline.alloy;

import metamodel.conformance.pipeline.decision.Decision;
import metamodel.conformance.pipeline.decision.DecisionStatus;
import metamodel.conformance.pipeline.model.ClassifierAbstraction;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
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
import metamodel.conformance.pipeline.model.SourceUnit;
import metamodel.conformance.pipeline.util.Hashing;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AbstractionPolicyInvariantTest {
    private static final String CLASSIFIER = "cls_" + Hashing.sha256("classifier");
    private static final String METHOD = "mem_" + Hashing.sha256("method");
    private static final String BODY = "body_" + Hashing.sha256("body");
    private static final String BINDING = "bind_" + Hashing.sha256("binding");
    private static final String PATH = "example/Sample.java";

    @Test
    void abstractClassifierMayCarryAnUnresolvedMethod() {
        Observation observation = observation(
                ClassifierAbstraction.ABSTRACT,
                MethodAbstraction.ABSTRACT,
                MemberScope.INSTANCE,
                false);

        assertEquals(DecisionStatus.CONFORMANT,
                decision(observation, "abstraction-implementation-consistency").status());
        assertEquals(DecisionStatus.CONFORMANT,
                decision(observation, "static-abstract-method-separation").status());
    }

    @Test
    void concreteClassifierWithUnresolvedMethodIsRejectedByAlloy() {
        Observation observation = observation(
                ClassifierAbstraction.CONCRETE,
                MethodAbstraction.ABSTRACT,
                MemberScope.INSTANCE,
                false);

        Decision decision = decision(observation, "abstraction-implementation-consistency");
        assertEquals(DecisionStatus.NON_CONFORMANT, decision.status());
        assertEquals(1, decision.witnesses().size());
        assertEquals(List.of(CLASSIFIER), decision.witnesses().get(0).technicalKeys());
    }

    @Test
    void staticAbstractMethodIsRejectedIndependently() {
        Observation observation = observation(
                ClassifierAbstraction.ABSTRACT,
                MethodAbstraction.ABSTRACT,
                MemberScope.STATIC,
                false);

        Decision decision = decision(observation, "static-abstract-method-separation");
        assertEquals(DecisionStatus.NON_CONFORMANT, decision.status());
        assertEquals(1, decision.witnesses().size());
        assertEquals(List.of(METHOD), decision.witnesses().get(0).technicalKeys());
    }

    @Test
    void concreteClassifierWithImplementedConcreteMethodConforms() {
        Observation observation = observation(
                ClassifierAbstraction.CONCRETE,
                MethodAbstraction.CONCRETE,
                MemberScope.INSTANCE,
                true);

        assertEquals(DecisionStatus.CONFORMANT,
                decision(observation, "abstraction-implementation-consistency").status());
        assertEquals(DecisionStatus.CONFORMANT,
                decision(observation, "static-abstract-method-separation").status());
    }

    private static Observation observation(
            ClassifierAbstraction classifierAbstraction,
            MethodAbstraction methodAbstraction,
            MemberScope scope,
            boolean implemented) {
        MemberObservation method = new MemberObservation(
                METHOD,
                null,
                MemberKind.METHOD,
                Inheritability.INHERITABLE,
                MemberVisibility.PUBLIC,
                "work",
                PATH,
                3,
                5,
                List.of(),
                methodAbstraction,
                scope);
        ClassifierObservation classifier = new ClassifierObservation(
                CLASSIFIER,
                "example.Sample",
                "example",
                ClassifierKind.CLASS,
                PATH,
                1,
                6,
                List.of(),
                List.of(METHOD),
                List.of(),
                classifierAbstraction);
        List<MethodBodyObservation> bodies = implemented
                ? List.of(new MethodBodyObservation(BODY, PATH, 3, 5))
                : List.of();
        List<ImplementationBindingObservation> bindings = implemented
                ? List.of(new ImplementationBindingObservation(BINDING, CLASSIFIER, METHOD, BODY))
                : List.of();
        return new Observation(
                "11",
                "test-adapter",
                "1.0.0",
                List.of(),
                Set.of(
                        EvidenceKind.HIERARCHY,
                        EvidenceKind.DECLARATION_OWNERSHIP,
                        EvidenceKind.LOCAL_SIGNATURES,
                        EvidenceKind.INHERITABILITY,
                        EvidenceKind.METHOD_BODIES,
                        EvidenceKind.METHOD_ABSTRACTION,
                        EvidenceKind.IMPLEMENTATION_BINDINGS,
                        EvidenceKind.CLASSIFIER_ABSTRACTION,
                        EvidenceKind.METHOD_SCOPE),
                List.of(new SourceUnit(Language.JAVA, PATH, Hashing.sha256("source"))),
                List.of(classifier),
                List.of(method),
                bodies,
                bindings,
                List.of(),
                List.of());
    }

    private static Decision decision(Observation observation, String invariantId) {
        return new AlloyInvariantEvaluator().evaluateAll(
                        observation, new ExactAlloyEncoder().encode(observation)).stream()
                .filter(item -> item.invariantId().equals(invariantId))
                .findFirst().orElseThrow();
    }
}
