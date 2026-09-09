package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.model.ClassifierAbstraction;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.Inheritability;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberScope;
import metamodel.conformance.pipeline.model.MemberVisibility;
import metamodel.conformance.pipeline.model.MethodAbstraction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaDependencyObservationTest {
    @Test
    void materializesDeterministicHashDerivedSupportGraph() throws Exception {
        String digest = "a".repeat(64);
        String unit = "dependencies/" + digest + "/dependency.jar";
        JavaDependencySymbols.TypeSymbol parent = new JavaDependencySymbols.TypeSymbol(
                unit,
                digest,
                "dep.Parent",
                "dep",
                ClassifierKind.CLASS,
                ClassifierAbstraction.CONCRETE,
                List.of(),
                List.of(new JavaDependencySymbols.MemberSymbol(
                        MemberKind.METHOD,
                        "work",
                        List.of("java.lang.String"),
                        "void",
                        Inheritability.INHERITABLE,
                        MemberVisibility.PUBLIC,
                        MethodAbstraction.CONCRETE,
                        MemberScope.INSTANCE)));
        JavaDependencySymbols.TypeSymbol child = new JavaDependencySymbols.TypeSymbol(
                unit,
                digest,
                "dep.Child",
                "dep",
                ClassifierKind.CLASS,
                ClassifierAbstraction.CONCRETE,
                List.of("dep.Parent"),
                List.of());

        JavaDependencySymbols.Result symbols = new JavaDependencySymbols.Result(
                List.of(parent, child), Set.of());
        JavaDependencyObservation.Result first = JavaDependencyObservation.materialize(symbols);
        JavaDependencyObservation.Result second = JavaDependencyObservation.materialize(symbols);

        assertEquals(first.classifiers(), second.classifiers());
        assertEquals(first.members(), second.members());
        assertEquals(2, first.classifiers().size());
        assertEquals(1, first.members().size());
        var childObservation = first.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("dep.Child"))
                .findFirst().orElseThrow();
        assertEquals(List.of(first.classifierId("dep.Parent")), childObservation.parentIds());
        assertNotNull(first.memberKey(
                "dep.Parent", MemberKind.METHOD, "work", List.of("java.lang.String")));
    }
}
