package metamodel.conformance.pipeline.adapter.python;

import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Inheritability;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.Observation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the two reviewed Python deepening decisions: the annotation-based
 * local-signature subset (option b: no parameter name is ever used as a type
 * token; unsupported parameter shapes stay outside the subset) and CPython
 * private name mangling with all-methods-inheritable semantics.
 */
class PythonSignatureAndManglingTest {
    @TempDir
    Path temp;

    @Test
    void annotatedMethodsClaimLocalSignaturesWithReceiverExcluded() throws Exception {
        Files.writeString(temp.resolve("service.py"), """
                class Service:
                    def handle(self, request: str, count: int) -> None:
                        return None
                """);

        Observation observation = new PythonAstObserver().observe(temp, Set.of());

        assertTrue(observation.completeEvidence().contains(EvidenceKind.LOCAL_SIGNATURES));
        var member = member(observation, "handle");
        assertEquals(List.of("str", "int"), member.parameterTypes());
        assertEquals(Inheritability.INHERITABLE, member.inheritability());
    }

    @Test
    void unannotatedParameterKeepsLocalSignaturesIncompleteWithoutInventingTokens() throws Exception {
        Files.writeString(temp.resolve("service.py"), """
                class Service:
                    def handle(self, request):
                        return None
                """);

        Observation observation = new PythonAstObserver().observe(temp, Set.of());

        assertFalse(observation.completeEvidence().contains(EvidenceKind.LOCAL_SIGNATURES));
        assertTrue(observation.diagnostics().stream().anyMatch(item ->
                item.kind() == DiagnosticKind.EVIDENCE_INCOMPLETE
                        && item.message().contains("1 of 1")));
        assertTrue(member(observation, "handle").parameterTypes().isEmpty());
    }

    @Test
    void unsupportedParameterShapesStayOutsideTheSignatureSubset() throws Exception {
        Files.writeString(temp.resolve("service.py"), """
                class Service:
                    def with_default(self, x: int = 1):
                        return None
                    def variadic(self, *args: int):
                        return None
                    def keyword_variadic(self, **kwargs: int):
                        return None
                    def keyword_only(self, *, key: int):
                        return None
                    def positional_only(self, value: int, /):
                        return None
                """);

        Observation observation = new PythonAstObserver().observe(temp, Set.of());

        assertFalse(observation.completeEvidence().contains(EvidenceKind.LOCAL_SIGNATURES));
        assertTrue(observation.diagnostics().stream().anyMatch(item ->
                item.kind() == DiagnosticKind.EVIDENCE_INCOMPLETE
                        && item.message().contains("5 of 5")));
        assertTrue(observation.members().stream()
                .allMatch(item -> item.parameterTypes().isEmpty()));
        assertTrue(observation.completeEvidence().contains(EvidenceKind.DECLARATION_OWNERSHIP));
    }

    @Test
    void staticmethodSelfParameterIsARealAnnotatedRequirement() throws Exception {
        Path cleanDir = Files.createDirectories(temp.resolve("clean"));
        Path selfDir = Files.createDirectories(temp.resolve("selfnamed"));
        Files.writeString(cleanDir.resolve("static.py"), """
                class Service:
                    @staticmethod
                    def annotated(x: int):
                        return None
                """);
        Files.writeString(selfDir.resolve("selfnamed.py"), """
                class Other:
                    @staticmethod
                    def selfNamed(self, x: int):
                        return None
                """);

        Observation clean = new PythonAstObserver().observe(cleanDir, Set.of());
        assertTrue(clean.completeEvidence().contains(EvidenceKind.LOCAL_SIGNATURES));
        assertEquals(List.of("int"), member(clean, "annotated").parameterTypes());

        // Under @staticmethod a leading parameter named self is a real parameter,
        // so it must be annotated like any other: without an annotation the method
        // falls outside the subset and stays without invented tokens.
        Observation selfNamed = new PythonAstObserver().observe(selfDir, Set.of());
        assertFalse(selfNamed.completeEvidence().contains(EvidenceKind.LOCAL_SIGNATURES));
        assertTrue(member(selfNamed, "selfNamed").parameterTypes().isEmpty());
    }

    @Test
    void privateNameManglingProducesEffectiveAttributeNames() throws Exception {
        Files.writeString(temp.resolve("models.py"), """
                class A:
                    def __run(self):
                        return None
                    def __init__(self):
                        return None
                    __secret = 1
                    def __value_(self):
                        return None

                class B(A):
                    def __run(self):
                        return None
                """);

        Observation observation = new PythonAstObserver().observe(temp, Set.of());

        // Effective CPython attribute names: mangled privates stay distinct per class,
        // dunder names are untouched, and both private methods remain inheritable.
        var aRun = member(observation, "_A__run");
        var bRun = member(observation, "_B__run");
        assertEquals(Inheritability.INHERITABLE, aRun.inheritability());
        assertEquals(Inheritability.INHERITABLE, bRun.inheritability());
        assertNotEquals(aRun.technicalKey(), bRun.technicalKey());
        member(observation, "__init__");
        var secret = member(observation, "_A__secret");
        assertEquals(Inheritability.UNKNOWN, secret.inheritability());
        member(observation, "_A__value_");
        assertTrue(observation.members().stream()
                .noneMatch(item -> item.memberName().equals("__run")
                        || item.memberName().equals("__secret")
                        || item.memberName().equals("__value_")));
    }

    private static MemberObservation member(Observation observation, String name) {
        List<MemberObservation> matches = observation.members().stream()
                .filter(item -> item.memberName().equals(name)).toList();
        assertEquals(1, matches.size(),
                () -> "expected unique member " + name + " but found: " + matches);
        return matches.get(0);
    }
}
