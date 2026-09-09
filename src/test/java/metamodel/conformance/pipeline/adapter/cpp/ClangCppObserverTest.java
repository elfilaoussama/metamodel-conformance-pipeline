package metamodel.conformance.pipeline.adapter.cpp;

import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Language;
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

class ClangCppObserverTest {
    @TempDir
    Path temp;

    @Test
    void observesNamespaceQualifiedDirectHierarchy() throws Exception {
        Files.writeString(temp.resolve("models.cpp"), """
                namespace demo {
                class Base {};
                struct Child : public Base {};
                }
                """);

        Observation observation = new ClangCppObserver().observe(temp, Set.of());

        assertEquals("clang-cpp", observation.adapterId());
        assertTrue(observation.adapterVersion().startsWith("0.2.0/clang-"));
        assertTrue(observation.units().stream().anyMatch(unit ->
                unit.language() == Language.CPP && unit.path().equals("models.cpp")));
        assertTrue(observation.units().stream().anyMatch(unit ->
                unit.language() == Language.CPP && unit.path().equals("cpp-toolchain/clang-executable")));
        assertEquals(2, observation.classifiers().size());
        assertTrue(observation.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertFalse(observation.completeEvidence().contains(EvidenceKind.DECLARATION_OWNERSHIP));
        assertTrue(observation.members().isEmpty());
        assertTrue(observation.unresolvedParents().isEmpty());

        var base = classifier(observation, "demo::Base");
        var child = classifier(observation, "demo::Child");
        assertEquals("demo", child.packageName());
        assertEquals(List.of(base.id()), child.parentIds());
    }

    @Test
    void standardLibraryHeadersAreAvailableAndFingerprintedWithoutBecomingSourceClassifiers() throws Exception {
        Files.writeString(temp.resolve("models.cpp"), """
                #include <string>
                class Base {
                    std::string value;
                };
                class Child : public Base {};
                """);

        Observation observation = new ClangCppObserver().observe(temp, Set.of());

        assertTrue(observation.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertEquals(2, observation.classifiers().size());
        assertTrue(observation.classifiers().stream().noneMatch(item -> item.qualifiedName().startsWith("std::")));
        assertTrue(observation.units().stream().anyMatch(unit -> unit.path().startsWith("cpp-dependency/")));
        assertTrue(observation.units().stream().anyMatch(unit -> unit.path().equals("cpp-toolchain/clang-executable")));
        var base = classifier(observation, "Base");
        var child = classifier(observation, "Child");
        assertEquals(List.of(base.id()), child.parentIds());
    }

    @Test
    void conventionalIncludeGuardsDoNotCreateFalseConfigurationUncertainty() throws Exception {
        Files.writeString(temp.resolve("base.hpp"), """
                #ifndef DEMO_BASE_HPP
                #define DEMO_BASE_HPP
                class Base {};
                #endif
                """);
        Files.writeString(temp.resolve("child.hpp"), """
                #ifndef DEMO_CHILD_HPP
                #define DEMO_CHILD_HPP
                #include "base.hpp"
                class Child : public Base {};
                #endif
                """);

        Observation observation = new ClangCppObserver().observe(temp, Set.of());

        assertTrue(observation.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertTrue(observation.diagnostics().stream().noneMatch(item ->
                item.message().contains("conditional preprocessing")));
        var base = classifier(observation, "Base");
        var child = classifier(observation, "Child");
        assertEquals(List.of(base.id()), child.parentIds());
    }

    @Test
    void dependentTemplateBaseRemainsFailClosed() throws Exception {
        Files.writeString(temp.resolve("template.cpp"), """
                template <typename T>
                struct Child : public T {};
                """);

        Observation observation = new ClangCppObserver().observe(temp, Set.of());

        assertFalse(observation.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertEquals(1, observation.unresolvedParents().size());
        assertEquals("T", observation.unresolvedParents().get(0).targetName());
    }

    @Test
    void conditionalPreprocessingPreventsConfigurationIndependentHierarchyClaim() throws Exception {
        Files.writeString(temp.resolve("conditional.cpp"), """
                #ifdef ENABLE_ALT
                class Base {};
                #else
                class Base {};
                #endif
                class Child : public Base {};
                """);

        Observation observation = new ClangCppObserver().observe(temp, Set.of());

        assertFalse(observation.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertTrue(observation.diagnostics().stream().anyMatch(item ->
                item.kind() == DiagnosticKind.EVIDENCE_INCOMPLETE
                        && item.message().contains("conditional preprocessing")));
    }

    @Test
    void nestedConditionalInsideIncludeGuardStillPreventsCompleteness() throws Exception {
        Files.writeString(temp.resolve("guarded.hpp"), """
                #ifndef DEMO_GUARDED_HPP
                #define DEMO_GUARDED_HPP
                class Base {};
                #ifdef ENABLE_CHILD
                class Child : public Base {};
                #endif
                #endif
                """);

        Observation observation = new ClangCppObserver().observe(temp, Set.of());

        assertFalse(observation.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertTrue(observation.diagnostics().stream().anyMatch(item ->
                item.kind() == DiagnosticKind.EVIDENCE_INCOMPLETE
                        && item.message().contains("conditional preprocessing")));
    }

    @Test
    void compilerFailureIsPreservedAsIncompleteEvidenceInsteadOfGuessedHierarchy() throws Exception {
        Files.writeString(temp.resolve("broken.cpp"), "class Broken : { };\n");

        Observation observation = new ClangCppObserver().observe(temp, Set.of());

        assertFalse(observation.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertTrue(observation.classifiers().isEmpty());
        assertTrue(observation.diagnostics().stream().anyMatch(item ->
                item.kind() == DiagnosticKind.EVIDENCE_INCOMPLETE
                        && item.message().contains("Clang could not analyze")));
    }

    private static metamodel.conformance.pipeline.model.ClassifierObservation classifier(
            Observation observation, String qualifiedName) {
        return observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals(qualifiedName))
                .findFirst().orElseThrow();
    }
}
