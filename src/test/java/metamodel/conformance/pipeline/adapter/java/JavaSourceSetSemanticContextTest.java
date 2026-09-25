package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.adapter.SourceObserverFactory;
import metamodel.conformance.pipeline.alloy.AlloyInvariantEvaluator;
import metamodel.conformance.pipeline.alloy.ExactAlloyEncoder;
import metamodel.conformance.pipeline.decision.DecisionStatus;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Language;
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

class JavaSourceSetSemanticContextTest {
    @TempDir
    Path temporary;

    @Test
    void auxiliarySetUsesProductionBinariesWithoutCollapsingDuplicateQualifiedNames()
            throws Exception {
        Path root = Files.createDirectory(temporary.resolve("repository"));
        Path main = Files.createDirectories(root.resolve("code/production/example"));
        Path test = Files.createDirectories(root.resolve("verification/context/example"));

        Files.writeString(main.resolve("Base.java"), """
                package example;
                public class Base {
                    public Number value() { return 1; }
                    protected void inheritedOnly() {}
                }
                """);
        Files.writeString(main.resolve("Duplicate.java"), """
                package example;
                public class Duplicate {
                    public String origin() { return "main"; }
                }
                """);
        Files.writeString(test.resolve("Duplicate.java"), """
                package example;
                public class Duplicate {
                    public int testOnly() { return 1; }
                }
                """);
        Files.writeString(test.resolve("Child.java"), """
                package example;
                public class Child extends Base {
                    @Override
                    public Number value() { return 2; }
                }
                """);

        Observation observation = SourceObserverFactory.create(Language.JAVA, contextInputs(root))
                .observe(root, Set.of());

        assertTrue(observation.completeEvidence().containsAll(Set.of(
                EvidenceKind.HIERARCHY,
                EvidenceKind.DECLARATION_OWNERSHIP,
                EvidenceKind.LOCAL_SIGNATURES,
                EvidenceKind.INHERITABILITY,
                EvidenceKind.INHERITED_MEMBERS,
                EvidenceKind.METHOD_BODIES,
                EvidenceKind.METHOD_ABSTRACTION,
                EvidenceKind.IMPLEMENTATION_BINDINGS,
                EvidenceKind.METHOD_SCOPE,
                EvidenceKind.METHOD_RETURN_TYPES,
                EvidenceKind.OVERRIDE_RELATIONS)));

        var duplicates = observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("example.Duplicate"))
                .toList();
        assertEquals(2, duplicates.size());
        assertEquals(2, duplicates.stream().map(item -> item.id()).distinct().count());
        assertEquals(2, duplicates.stream().map(item -> item.sourcePath()).distinct().count());

        var base = observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("example.Base"))
                .findFirst().orElseThrow();
        var child = observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("example.Child"))
                .findFirst().orElseThrow();
        assertEquals(List.of(base.id()), child.parentIds());

        var baseValue = observation.members().stream()
                .filter(item -> item.kind() == MemberKind.METHOD)
                .filter(item -> item.memberName().equals("value"))
                .filter(item -> item.sourcePath().contains("code/production"))
                .findFirst().orElseThrow();
        var childValue = observation.members().stream()
                .filter(item -> item.kind() == MemberKind.METHOD)
                .filter(item -> item.memberName().equals("value"))
                .filter(item -> item.sourcePath().contains("verification/context"))
                .findFirst().orElseThrow();
        var inheritedOnly = observation.members().stream()
                .filter(item -> item.kind() == MemberKind.METHOD)
                .filter(item -> item.memberName().equals("inheritedOnly"))
                .findFirst().orElseThrow();

        assertEquals(List.of(baseValue.technicalKey()), childValue.overriddenMemberKeys());
        assertTrue(child.inheritedMemberKeys().contains(inheritedOnly.technicalKey()));
        assertFalse(child.inheritedMemberKeys().contains(baseValue.technicalKey()));

        var decisions = new AlloyInvariantEvaluator().evaluateAll(
                observation, new ExactAlloyEncoder().encode(observation));
        assertEquals(DecisionStatus.CONFORMANT, decisions.stream()
                .filter(item -> item.invariantId().equals("override-relation-consistency"))
                .findFirst().orElseThrow().status());
        assertEquals(DecisionStatus.CONFORMANT, decisions.stream()
                .filter(item -> item.invariantId().equals("override-discipline"))
                .findFirst().orElseThrow().status());
    }

    @Test
    void unbuildableProductionSiblingKeepsAuxiliarySemanticEvidenceFailClosed()
            throws Exception {
        Path root = Files.createDirectory(temporary.resolve("broken-repository"));
        Path main = Files.createDirectories(root.resolve("code/production/example"));
        Path test = Files.createDirectories(root.resolve("verification/context/example"));

        Files.writeString(main.resolve("Base.java"), """
                package example;
                import missing.Dependency;
                public class Base {
                    Dependency value() { return null; }
                }
                """);
        Files.writeString(test.resolve("Child.java"), """
                package example;
                public class Child extends Base {}
                """);

        Observation observation = new JavaImplementationSourceObserver(contextInputs(root))
                .observe(root, Set.of());

        assertFalse(observation.completeEvidence().contains(EvidenceKind.INHERITED_MEMBERS));
        assertFalse(observation.completeEvidence().contains(EvidenceKind.IMPLEMENTATION_BINDINGS));
        assertFalse(observation.completeEvidence().contains(EvidenceKind.METHOD_RETURN_TYPES));
        assertFalse(observation.completeEvidence().contains(EvidenceKind.OVERRIDE_RELATIONS));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_ABSTRACTION));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_SCOPE));
        assertTrue(observation.diagnostics().stream()
                .anyMatch(item -> item.message().contains("missing.Dependency")
                        || item.message().contains("package missing does not exist")));

        var decisions = new AlloyInvariantEvaluator().evaluateAll(
                observation, new ExactAlloyEncoder().encode(observation));
        assertEquals(DecisionStatus.NOT_EVALUATED, decisions.stream()
                .filter(item -> item.invariantId().equals("override-relation-consistency"))
                .findFirst().orElseThrow().status());
        assertEquals(DecisionStatus.NOT_EVALUATED, decisions.stream()
                .filter(item -> item.invariantId().equals("override-discipline"))
                .findFirst().orElseThrow().status());
    }
    private JavaDependencyInputs contextInputs(Path root) throws Exception {
        Path manifest = root.resolve("contexts.tsv");
        Files.writeString(manifest, String.join("\n",
                "context\tproduction\t.\t\t\t17\tfalse\tjdk-17",
                "source\tproduction\tcode/production",
                "context\tverification\t.\t\t\t17\tfalse\tjdk-17",
                "source\tverification\tverification/context",
                "upstream\tverification\tproduction") + "\n");
        return JavaDependencyInputs.fromManifest(manifest);
    }

}
