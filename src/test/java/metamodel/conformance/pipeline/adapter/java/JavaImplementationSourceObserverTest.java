package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.alloy.AlloyInvariantEvaluator;
import metamodel.conformance.pipeline.alloy.ExactAlloyEncoder;
import metamodel.conformance.pipeline.decision.DecisionStatus;
import metamodel.conformance.pipeline.model.ClassifierAbstraction;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberScope;
import metamodel.conformance.pipeline.model.MethodAbstraction;
import metamodel.conformance.pipeline.model.Observation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaImplementationSourceObserverTest {
    @TempDir
    Path temporary;

    @Test
    void emitsIndependentImplementationAndAbstractionEvidence() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("Sample.java"), """
                abstract class Sample {
                    abstract void abstractMethod();
                    void concreteMethod() {
                        int value = 1;
                    }
                }
                """);

        Observation observation = new JavaImplementationSourceObserver(List.of()).observe(source, Set.of());

        assertEquals("12", observation.schemaVersion());
        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_BODIES));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_ABSTRACTION));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.IMPLEMENTATION_BINDINGS));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.CLASSIFIER_ABSTRACTION));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_SCOPE));
        assertEquals(ClassifierAbstraction.ABSTRACT, observation.classifiers().get(0).abstraction());
        assertEquals(1, observation.methodBodies().size());
        assertEquals(1, observation.implementationBindings().size());

        var methods = observation.members().stream()
                .filter(member -> member.kind() == MemberKind.METHOD)
                .collect(java.util.stream.Collectors.toMap(member -> member.memberName(), member -> member));
        assertEquals(MethodAbstraction.ABSTRACT, methods.get("abstractMethod").abstraction());
        assertEquals(MethodAbstraction.CONCRETE, methods.get("concreteMethod").abstraction());
        assertEquals(MemberScope.INSTANCE, methods.get("abstractMethod").scope());
        assertEquals(MemberScope.INSTANCE, methods.get("concreteMethod").scope());

        var binding = observation.implementationBindings().get(0);
        assertEquals(methods.get("concreteMethod").technicalKey(), binding.targetMemberKey());
        assertEquals(observation.methodBodies().get(0).technicalKey(), binding.bodyKey());
        assertTrue(observation.classifiers().stream().anyMatch(
                classifier -> classifier.id().equals(binding.implementerClassifierId())
                        && classifier.declaredMemberKeys().contains(binding.targetMemberKey())));

        var decisions = new AlloyInvariantEvaluator().evaluateAll(
                observation, new ExactAlloyEncoder().encode(observation));
        assertEquals(DecisionStatus.CONFORMANT, decisions.stream()
                .filter(item -> item.invariantId().equals("implementation-binding-consistency"))
                .findFirst().orElseThrow().status());
        assertEquals(DecisionStatus.CONFORMANT, decisions.stream()
                .filter(item -> item.invariantId().equals("abstraction-implementation-consistency"))
                .findFirst().orElseThrow().status());
        assertEquals(DecisionStatus.CONFORMANT, decisions.stream()
                .filter(item -> item.invariantId().equals("static-abstract-method-separation"))
                .findFirst().orElseThrow().status());
    }

    @Test
    void ignoresJavacSyntheticEnumMethodsOutsideTheSourceObservationDomain() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("enum-source"));
        Files.writeString(source.resolve("Mode.java"), """
                enum Mode {
                    FAST,
                    SAFE;

                    void run() {
                        int value = 1;
                    }
                }
                """);

        Observation observation = new JavaImplementationSourceObserver(List.of()).observe(source, Set.of());

        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_BODIES));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_ABSTRACTION));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.IMPLEMENTATION_BINDINGS));
        assertEquals(1, observation.members().stream()
                .filter(member -> member.kind() == MemberKind.METHOD).count());
        assertEquals(1, observation.methodBodies().size());
        assertEquals(1, observation.implementationBindings().size());

        var decision = new AlloyInvariantEvaluator().evaluateAll(
                        observation, new ExactAlloyEncoder().encode(observation)).stream()
                .filter(item -> item.invariantId().equals("implementation-binding-consistency"))
                .findFirst().orElseThrow();
        assertEquals(DecisionStatus.CONFORMANT, decision.status());
    }

    @Test
    void mapsAnnotatedMethodAtItsDeclarationRatherThanAnnotationLine() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("annotated-source"));
        Files.writeString(source.resolve("Annotated.java"), """
                class Annotated {
                    @Override
                    public String toString() {
                        return "annotated";
                    }
                }
                """);

        Observation observation = new JavaImplementationSourceObserver(List.of()).observe(source, Set.of());

        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_BODIES));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_ABSTRACTION));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.IMPLEMENTATION_BINDINGS));
        assertEquals(1, observation.implementationBindings().size());
        assertEquals(3, observation.members().stream()
                .filter(member -> member.kind() == MemberKind.METHOD)
                .findFirst().orElseThrow().startLine());
    }

    @Test
    void mapsNestedTypeOverloadsByIndependentSourceLocation() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("nested-overload-source"));
        Files.writeString(source.resolve("Outer.java"), """
                class Outer {
                    static class Node {}

                    int visit(Node node) {
                        return 1;
                    }

                    int visit(Node node, int depth) {
                        return depth;
                    }
                }
                """);

        Observation observation = new JavaImplementationSourceObserver(List.of()).observe(source, Set.of());

        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_BODIES));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_ABSTRACTION));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.IMPLEMENTATION_BINDINGS));
        assertEquals(2, observation.members().stream()
                .filter(member -> member.kind() == MemberKind.METHOD).count());
        assertEquals(2, observation.methodBodies().size());
        assertEquals(2, observation.implementationBindings().size());

        var decision = new AlloyInvariantEvaluator().evaluateAll(
                        observation, new ExactAlloyEncoder().encode(observation)).stream()
                .filter(item -> item.invariantId().equals("implementation-binding-consistency"))
                .findFirst().orElseThrow();
        assertEquals(DecisionStatus.CONFORMANT, decision.status());
    }

    @Test
    void preservesLexicalAbstractionAndScopeWhenCompilerDependenciesAreMissing() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("missing-dependency-source"));
        Path packageRoot = Files.createDirectories(source.resolve("sample"));
        Files.writeString(packageRoot.resolve("Helper.java"), """
                package sample;
                final class Helper {
                    static final class Nested {}
                }
                """);
        Files.writeString(packageRoot.resolve("Uses.java"), """
                package sample;

                import third.party.External;
                import java.util.List;

                class Uses {
                    static void consume(External... values) {}
                    void consume(List<Helper.Nested> values) {}
                }
                """);

        Observation observation = new JavaImplementationSourceObserver(List.of()).observe(source, Set.of());

        assertTrue(observation.completeEvidence().contains(EvidenceKind.CLASSIFIER_ABSTRACTION));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_ABSTRACTION));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_SCOPE));
        assertFalse(observation.completeEvidence().contains(EvidenceKind.IMPLEMENTATION_BINDINGS));
        assertTrue(observation.members().stream()
                .filter(member -> member.kind() == MemberKind.METHOD)
                .allMatch(member -> member.abstraction() != MethodAbstraction.UNKNOWN));
        assertTrue(observation.members().stream()
                .filter(member -> member.kind() == MemberKind.METHOD)
                .allMatch(member -> member.scope() != MemberScope.UNKNOWN));
        assertFalse(observation.diagnostics().stream()
                .anyMatch(item -> item.message().contains("method abstraction")));

        var decision = new AlloyInvariantEvaluator().evaluateAll(
                        observation, new ExactAlloyEncoder().encode(observation)).stream()
                .filter(item -> item.invariantId().equals("static-abstract-method-separation"))
                .findFirst().orElseThrow();
        assertEquals(DecisionStatus.CONFORMANT, decision.status());
    }

    @Test
    void nativeConcreteMethodIsAVisibleStrictPolicyViolationRatherThanBeingReclassifiedAbstract() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("native-source"));
        Files.writeString(source.resolve("NativeSample.java"), "class NativeSample { native void run(); }");

        Observation observation = new JavaImplementationSourceObserver(List.of()).observe(source, Set.of());
        var method = observation.members().stream().filter(member -> member.kind() == MemberKind.METHOD).findFirst().orElseThrow();
        assertEquals(MethodAbstraction.CONCRETE, method.abstraction());
        assertTrue(observation.implementationBindings().isEmpty());

        var decision = new AlloyInvariantEvaluator().evaluateAll(
                        observation, new ExactAlloyEncoder().encode(observation)).stream()
                .filter(item -> item.invariantId().equals("implementation-binding-consistency"))
                .findFirst().orElseThrow();
        assertEquals(DecisionStatus.NON_CONFORMANT, decision.status());
    }
}
