package metamodel.conformance.pipeline.adapter.python;

import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Observation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonImportResolutionTest {
    @TempDir
    Path temp;

    @Test
    void absoluteImportsAreNotPrefixedByContainingPackage() throws Exception {
        Files.writeString(temp.resolve("base.py"), """
                class Base:
                    pass
                """);
        Files.createDirectories(temp.resolve("pkg"));
        Files.writeString(temp.resolve("pkg/child.py"), """
                from base import Base

                class Child(Base):
                    pass
                """);

        Observation observation = new PythonAstObserver().observe(temp, Set.of());

        assertTrue(observation.completeEvidence().contains(EvidenceKind.HIERARCHY));
        var base = classifier(observation, "base.Base");
        var child = classifier(observation, "pkg.child.Child");
        assertEquals(List.of(base.id()), child.parentIds());
    }

    @Test
    void relativeImportsStillResolveAgainstContainingPackage() throws Exception {
        Files.createDirectories(temp.resolve("pkg"));
        Files.writeString(temp.resolve("pkg/base.py"), """
                class Base:
                    pass
                """);
        Files.writeString(temp.resolve("pkg/child.py"), """
                from .base import Base

                class Child(Base):
                    pass
                """);

        Observation observation = new PythonAstObserver().observe(temp, Set.of());

        assertTrue(observation.completeEvidence().contains(EvidenceKind.HIERARCHY));
        var base = classifier(observation, "pkg.base.Base");
        var child = classifier(observation, "pkg.child.Child");
        assertEquals(List.of(base.id()), child.parentIds());
    }

    @Test
    void absoluteStdlibDecoratorResolutionWorksInsidePackageModule() throws Exception {
        Files.createDirectories(temp.resolve("pkg"));
        Files.writeString(temp.resolve("pkg/models.py"), """
                from dataclasses import dataclass

                @dataclass(frozen=True)
                class Base:
                    value: int = 0

                class Child(Base):
                    pass
                """);

        Observation observation = new PythonAstObserver().observe(temp, Set.of());

        assertTrue(observation.completeEvidence().contains(EvidenceKind.HIERARCHY));
        var base = classifier(observation, "pkg.models.Base");
        var child = classifier(observation, "pkg.models.Child");
        assertEquals(List.of(base.id()), child.parentIds());
    }

    private static metamodel.conformance.pipeline.model.ClassifierObservation classifier(
            Observation observation, String qualifiedName) {
        return observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals(qualifiedName))
                .findFirst().orElseThrow();
    }
}
