package metamodel.conformance.pipeline.model;

import metamodel.conformance.pipeline.TestObservations;
import metamodel.conformance.pipeline.util.Hashing;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObservationValidationTest {
    @Test
    void rejectsNonCanonicalSourcePaths() {
        for (String path : List.of(
                "/absolute/A.java", "C:/absolute/A.java", "a\\A.java",
                "a/../A.java", "a/./A.java", "a//A.java", "a/")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new SourceUnit(Language.JAVA, path, Hashing.sha256("source")), path);
        }
    }

    @Test
    void rejectsNonCanonicalDigestsAndTechnicalIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> new SourceUnit(Language.JAVA, "A.java", "ABC"));
        assertThrows(IllegalArgumentException.class,
                () -> new ClassifierObservation(
                        "A", "example.A", ClassifierKind.CLASS,
                        "A.java", 1, 1, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new MemberObservation(
                        "work", null, MemberKind.METHOD, "work",
                        "A.java", 1, 1, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ImplementationBindingObservation(
                        "binding", TestObservations.A,
                        "mem_" + "1".repeat(64), "body_" + "2".repeat(64)));
    }

    @Test
    void rejectsDuplicateSourceUnitPaths() {
        Observation base = TestObservations.acyclic();
        SourceUnit unit = base.units().get(0);

        assertThrows(IllegalArgumentException.class, () -> new Observation(
                base.schemaVersion(), base.adapterId(), base.adapterVersion(),
                base.externalParents(), base.completeEvidence(),
                List.of(unit, unit), List.of(), List.of(), List.of()));
    }

    @Test
    void rejectsImplementationBindingReferencesOutsideCanonicalEvidence() {
        String classifierKey = TestObservations.A;
        String memberKey = "mem_" + "1".repeat(64);
        String bodyKey = "body_" + "2".repeat(64);
        String bindingKey = "bind_" + "3".repeat(64);
        SourceUnit unit = new SourceUnit(Language.JAVA, "A.java", Hashing.sha256("source"));
        ClassifierObservation classifier = new ClassifierObservation(
                classifierKey, "A", ClassifierKind.CLASS,
                "A.java", 1, 4, List.of(), List.of(memberKey));
        MemberObservation member = new MemberObservation(
                memberKey, null, MemberKind.METHOD, Inheritability.INHERITABLE,
                MemberVisibility.PUBLIC, "run", "A.java", 2, 3, List.of(),
                MethodAbstraction.CONCRETE);
        MethodBodyObservation body = new MethodBodyObservation(bodyKey, "A.java", 2, 3);

        assertThrows(IllegalArgumentException.class, () -> new Observation(
                "10", "test", "1", List.of(), Set.of(), List.of(unit),
                List.of(classifier), List.of(member), List.of(body),
                List.of(new ImplementationBindingObservation(
                        bindingKey, "cls_" + "f".repeat(64), memberKey, bodyKey)),
                List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Observation(
                "10", "test", "1", List.of(), Set.of(), List.of(unit),
                List.of(classifier), List.of(member), List.of(body),
                List.of(new ImplementationBindingObservation(
                        bindingKey, classifierKey, "mem_" + "e".repeat(64), bodyKey)),
                List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Observation(
                "10", "test", "1", List.of(), Set.of(), List.of(unit),
                List.of(classifier), List.of(member), List.of(body),
                List.of(new ImplementationBindingObservation(
                        bindingKey, classifierKey, memberKey, "body_" + "d".repeat(64))),
                List.of(), List.of()));
    }

    @Test
    void rejectsProvenanceOutsideTheHashedSourceSet() {
        ClassifierObservation classifier = new ClassifierObservation(
                TestObservations.A, "example.A", ClassifierKind.CLASS,
                "missing/A.java", 1, 1, List.of());

        assertThrows(IllegalArgumentException.class, () -> new Observation(
                "6", "test-adapter", "1.0.0", List.of(), Set.of(EvidenceKind.HIERARCHY),
                List.of(new SourceUnit(
                        Language.JAVA, "present/A.java", Hashing.sha256("source"))),
                List.of(classifier), List.of(), List.of()));
    }

    @Test
    void allowsEvidenceDiagnosticsAlongsideIndependentCompleteEvidence() {
        Observation base = TestObservations.acyclic();
        assertDoesNotThrow(() -> new Observation(
                base.schemaVersion(), base.adapterId(), base.adapterVersion(), base.externalParents(),
                base.completeEvidence(), base.units(), base.classifiers(), base.members(),
                base.unresolvedParents(), List.of(new ObservationDiagnostic(
                        DiagnosticKind.EVIDENCE_INCOMPLETE,
                        base.units().get(0).path(), 0, "inherited view unavailable"))));
    }

    @Test
    void parseDiagnosticsStillForbidAllCompletenessClaims() {
        Observation base = TestObservations.acyclic();
        assertThrows(IllegalArgumentException.class, () -> new Observation(
                base.schemaVersion(), base.adapterId(), base.adapterVersion(), base.externalParents(),
                base.completeEvidence(), base.units(), base.classifiers(), base.members(),
                base.unresolvedParents(), List.of(new ObservationDiagnostic(
                        DiagnosticKind.PARSE_ERROR,
                        base.units().get(0).path(), 1, "invalid Java source"))));
    }
}
