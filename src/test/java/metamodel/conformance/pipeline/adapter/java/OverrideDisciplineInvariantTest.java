package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.alloy.AlloyInvariantEvaluator;
import metamodel.conformance.pipeline.alloy.ExactAlloyEncoder;
import metamodel.conformance.pipeline.decision.DecisionStatus;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.MemberKind;
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

class OverrideDisciplineInvariantTest {
    @TempDir
    Path temporary;

    @Test
    void observesAndAcceptsConcreteOverrideWithEqualReturnType() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("equal-return"));
        Files.writeString(source.resolve("Base.java"), """
                class Base {
                    Number value() { return 1; }
                }
                """);
        Files.writeString(source.resolve("Child.java"), """
                class Child extends Base {
                    @Override Number value() { return 2; }
                }
                """);
        Observation observation = new JavaImplementationSourceObserver(List.of()).observe(source, Set.of());
        assertEquals("12", observation.schemaVersion());
        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_RETURN_TYPES));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.OVERRIDE_RELATIONS));
        assertEquals(2, observation.members().stream()
                .filter(member -> member.kind() == MemberKind.METHOD)
                .filter(member -> member.returnType() != null).count());
        assertEquals(1, observation.members().stream()
                .mapToLong(member -> member.overriddenMemberKeys().size()).sum());
        assertEquals(DecisionStatus.CONFORMANT,
                decision(observation, "override-relation-consistency"));
        assertEquals(DecisionStatus.CONFORMANT,
                decision(observation, "override-discipline"));
    }

    @Test
    void reportsStrictProfileViolationForLegalCovariantJavaReturn() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("covariant-return"));
        Files.writeString(source.resolve("Base.java"), """
                class Base {
                    Number value() { return 1; }
                }
                """);
        Files.writeString(source.resolve("Child.java"), """
                class Child extends Base {
                    @Override Integer value() { return 2; }
                }
                """);
        Observation observation = new JavaImplementationSourceObserver(List.of()).observe(source, Set.of());
        assertTrue(observation.completeEvidence().contains(EvidenceKind.OVERRIDE_RELATIONS));
        assertEquals(1, observation.members().stream()
                .mapToLong(member -> member.overriddenMemberKeys().size()).sum());
        assertEquals(DecisionStatus.CONFORMANT,
                decision(observation, "override-relation-consistency"));
        assertEquals(DecisionStatus.NON_CONFORMANT,
                decision(observation, "override-discipline"));
    }

    @Test
    void acceptsAbstractOverrideWithoutConcreteBinding() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("abstract-override"));
        Files.writeString(source.resolve("Base.java"), """
                abstract class Base {
                    abstract Number value();
                }
                """);
        Files.writeString(source.resolve("Child.java"), """
                abstract class Child extends Base {
                    @Override abstract Number value();
                }
                """);
        Observation observation = new JavaImplementationSourceObserver(List.of()).observe(source, Set.of());
        assertTrue(observation.completeEvidence().contains(EvidenceKind.OVERRIDE_RELATIONS));
        assertEquals(1, observation.members().stream()
                .mapToLong(member -> member.overriddenMemberKeys().size()).sum());
        assertEquals(DecisionStatus.CONFORMANT,
                decision(observation, "override-relation-consistency"));
        assertEquals(DecisionStatus.CONFORMANT,
                decision(observation, "override-discipline"));
    }

    @Test
    void treatsOverloadAsDistinctFromOverride() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("overload"));
        Files.writeString(source.resolve("Base.java"), """
                class Base {
                    Number value(Number input) { return input; }
                }
                """);
        Files.writeString(source.resolve("Child.java"), """
                class Child extends Base {
                    Number value(Integer input) { return input; }
                }
                """);
        Observation observation = new JavaImplementationSourceObserver(List.of()).observe(source, Set.of());
        assertTrue(observation.completeEvidence().contains(EvidenceKind.OVERRIDE_RELATIONS));
        assertEquals(0, observation.members().stream()
                .mapToLong(member -> member.overriddenMemberKeys().size()).sum());
        assertEquals(DecisionStatus.CONFORMANT,
                decision(observation, "override-relation-consistency"));
        assertEquals(DecisionStatus.CONFORMANT,
                decision(observation, "override-discipline"));
    }

    @Test
    void acceptsInterfaceMethodOverrideWithEqualReturnType() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("interface-override"));
        Files.writeString(source.resolve("Contract.java"), """
                interface Contract {
                    Number value();
                }
                """);
        Files.writeString(source.resolve("Implementation.java"), """
                class Implementation implements Contract {
                    @Override public Number value() { return 1; }
                }
                """);
        Observation observation = new JavaImplementationSourceObserver(List.of()).observe(source, Set.of());
        assertTrue(observation.completeEvidence().contains(EvidenceKind.OVERRIDE_RELATIONS));
        assertEquals(1, observation.members().stream()
                .mapToLong(member -> member.overriddenMemberKeys().size()).sum());
        assertEquals(DecisionStatus.CONFORMANT,
                decision(observation, "override-relation-consistency"));
        assertEquals(DecisionStatus.CONFORMANT,
                decision(observation, "override-discipline"));
    }

    @Test
    void doesNotInventOverrideAcrossInaccessiblePackagePrivateBoundary() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("package-private-boundary"));
        Path basePackage = Files.createDirectories(source.resolve("base"));
        Path childPackage = Files.createDirectories(source.resolve("child"));
        Files.writeString(basePackage.resolve("Base.java"), """
                package base;
                public class Base {
                    Number value() { return 1; }
                }
                """);
        Files.writeString(childPackage.resolve("Child.java"), """
                package child;
                public class Child extends base.Base {
                    public Number value() { return 2; }
                }
                """);
        Observation observation = new JavaImplementationSourceObserver(List.of()).observe(source, Set.of());
        assertTrue(observation.completeEvidence().contains(EvidenceKind.OVERRIDE_RELATIONS));
        assertEquals(0, observation.members().stream()
                .mapToLong(member -> member.overriddenMemberKeys().size()).sum());
        assertEquals(DecisionStatus.CONFORMANT,
                decision(observation, "override-relation-consistency"));
        assertEquals(DecisionStatus.CONFORMANT,
                decision(observation, "override-discipline"));
    }

    @Test
    void reportsStaticHidingAsBridgeMismatchWithoutInventingReturnPolicyFailure() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("static-hiding"));
        Files.writeString(source.resolve("Base.java"), """
                class Base {
                    static Number value() { return 1; }
                }
                """);
        Files.writeString(source.resolve("Child.java"), """
                class Child extends Base {
                    static Number value() { return 2; }
                }
                """);
        Observation observation = new JavaImplementationSourceObserver(List.of()).observe(source, Set.of());
        assertTrue(observation.completeEvidence().contains(EvidenceKind.OVERRIDE_RELATIONS));
        assertEquals(0, observation.members().stream()
                .mapToLong(member -> member.overriddenMemberKeys().size()).sum());
        assertEquals(DecisionStatus.NON_CONFORMANT,
                decision(observation, "override-relation-consistency"));
        assertEquals(DecisionStatus.CONFORMANT,
                decision(observation, "override-discipline"));
    }

    @Test
    void missingCompilerEvidenceMakesBothOverrideChecksNotEvaluated() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("missing-dependency"));
        Files.writeString(source.resolve("Uses.java"), """
                class Uses {
                    third.party.External value() { return null; }
                }
                """);
        Observation observation = new JavaImplementationSourceObserver(List.of()).observe(source, Set.of());
        assertFalse(observation.completeEvidence().contains(EvidenceKind.METHOD_RETURN_TYPES));
        assertFalse(observation.completeEvidence().contains(EvidenceKind.OVERRIDE_RELATIONS));
        assertEquals(DecisionStatus.NOT_EVALUATED,
                decision(observation, "override-relation-consistency"));
        assertEquals(DecisionStatus.NOT_EVALUATED,
                decision(observation, "override-discipline"));
    }

    private static DecisionStatus decision(Observation observation, String invariantId) {
        return new AlloyInvariantEvaluator().evaluateAll(
                        observation, new ExactAlloyEncoder().encode(observation)).stream()
                .filter(item -> item.invariantId().equals(invariantId))
                .findFirst().orElseThrow().status();
    }
}
