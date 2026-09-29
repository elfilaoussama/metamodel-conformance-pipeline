package metamodel.conformance.pipeline.adapter.cpp;

import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Inheritability;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberScope;
import metamodel.conformance.pipeline.model.MemberVisibility;
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
        assertTrue(observation.adapterVersion().startsWith("0.3.0/clang-"));
        assertTrue(observation.units().stream().anyMatch(unit ->
                unit.language() == Language.CPP && unit.path().equals("models.cpp")));
        assertTrue(observation.units().stream().anyMatch(unit ->
                unit.language() == Language.CPP && unit.path().equals("cpp-toolchain/clang-executable")));
        assertEquals(2, observation.classifiers().size());
        assertTrue(observation.completeEvidence().contains(EvidenceKind.HIERARCHY));
        // Empty class/struct definitions claim member evidence vacuously.
        assertTrue(observation.completeEvidence().contains(EvidenceKind.DECLARATION_OWNERSHIP));
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

    @Test
    void observesMembersWithAccessInheritabilityScopeAndSignatureQualifiers() throws Exception {
        Files.writeString(temp.resolve("account.cpp"), """
                class Account {
                public:
                    static int created;
                    int balance() const;
                    void add(int amount);
                protected:
                    long limit;
                private:
                    int secret;
                };
                """);

        Observation observation = new ClangCppObserver().observe(temp, Set.of());

        assertTrue(observation.adapterVersion().startsWith("0.3.0/clang-"));
        assertEquals(5, observation.members().size());
        var account = classifier(observation, "Account");
        assertEquals(5, account.declaredMemberKeys().size());

        var created = member(observation, "created");
        assertEquals(MemberKind.ATTRIBUTE, created.kind());
        assertEquals(MemberVisibility.PUBLIC, created.visibility());
        assertEquals(Inheritability.INHERITABLE, created.inheritability());
        assertEquals(MemberScope.UNKNOWN, created.scope());
        assertTrue(created.parameterTypes().isEmpty());

        // cv-qualifiers are canonical signature tokens: balance() const and
        // balance() would be distinct declarations.
        var balance = member(observation, "balance");
        assertEquals(MemberKind.METHOD, balance.kind());
        assertEquals(MethodAbstraction.CONCRETE, balance.abstraction());
        assertEquals(MemberScope.INSTANCE, balance.scope());
        assertEquals(List.of("const"), balance.parameterTypes());

        var add = member(observation, "add");
        assertEquals(List.of("int"), add.parameterTypes());
        assertEquals(MemberScope.INSTANCE, add.scope());

        var limit = member(observation, "limit");
        assertEquals(MemberVisibility.PROTECTED, limit.visibility());
        assertEquals(Inheritability.INHERITABLE, limit.inheritability());

        var secret = member(observation, "secret");
        assertEquals(MemberVisibility.PRIVATE, secret.visibility());
        assertEquals(Inheritability.NOT_INHERITABLE, secret.inheritability());

        assertTrue(observation.completeEvidence().contains(EvidenceKind.DECLARATION_OWNERSHIP));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.LOCAL_SIGNATURES));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.INHERITABILITY));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_ABSTRACTION));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_SCOPE));
    }

    @Test
    void observesPureVirtualAbstractionAndKeepsUnsupportedSignaturesFailClosed() throws Exception {
        Files.writeString(temp.resolve("example.cpp"), """
                class Example {
                public:
                    virtual void pure(int value) = 0;
                    static void utility();
                    void variadic(int first, ...);
                    template <typename T> void templated(T value);
                };
                """);

        Observation observation = new ClangCppObserver().observe(temp, Set.of());

        var pure = member(observation, "pure");
        assertEquals(MethodAbstraction.ABSTRACT, pure.abstraction());
        assertEquals(List.of("int"), pure.parameterTypes());

        var utility = member(observation, "utility");
        assertEquals(MemberScope.STATIC, utility.scope());

        var variadic = member(observation, "variadic");
        assertEquals(MemberKind.METHOD, variadic.kind());
        assertTrue(variadic.parameterTypes().isEmpty());

        var templated = member(observation, "templated");
        assertTrue(templated.parameterTypes().isEmpty());

        assertFalse(observation.completeEvidence().contains(EvidenceKind.LOCAL_SIGNATURES));
        assertTrue(observation.diagnostics().stream().anyMatch(item ->
                item.kind() == DiagnosticKind.EVIDENCE_INCOMPLETE
                        && item.message().contains("2 of 4")));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.DECLARATION_OWNERSHIP));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_ABSTRACTION));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.METHOD_SCOPE));
    }

    @Test
    void observesNestedAndLocalClassesWithoutLeakingTheirMembersOutwards() throws Exception {
        Files.writeString(temp.resolve("outer.cpp"), """
                class Outer {
                public:
                    class Nested {
                    public:
                        int inner;
                    };
                    int outerValue;
                    void local() {
                        class Local {
                        public:
                            int localValue;
                        };
                    }
                };
                """);

        Observation observation = new ClangCppObserver().observe(temp, Set.of());

        assertEquals(3, observation.classifiers().size());
        assertEquals(2, memberCount(observation, "Outer"));
        assertEquals(1, memberCount(observation, "Outer::Nested"));
        assertEquals(1, memberCount(observation, "Outer::<local@8>::Local"));
        assertEquals("outerValue", member(observation, "outerValue").memberName());
        assertEquals("inner", member(observation, "inner").memberName());
        assertEquals("localValue", member(observation, "localValue").memberName());
    }

    private static int memberCount(Observation observation, String qualifiedName) {
        return classifier(observation, qualifiedName).declaredMemberKeys().size();
    }

    private static metamodel.conformance.pipeline.model.MemberObservation member(
            Observation observation, String name) {
        List<metamodel.conformance.pipeline.model.MemberObservation> matches = observation.members().stream()
                .filter(item -> item.memberName().equals(name)).toList();
        assertEquals(1, matches.size(), () -> "expected unique member " + name + ": " + matches);
        return matches.get(0);
    }

    private static metamodel.conformance.pipeline.model.ClassifierObservation classifier(
            Observation observation, String qualifiedName) {
        return observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals(qualifiedName))
                .findFirst().orElseThrow();
    }
}
