package metamodel.conformance.pipeline;

import metamodel.conformance.pipeline.adapter.java.JavaImplementationSourceObserver;
import metamodel.conformance.pipeline.capsule.CapsuleVerifier;
import metamodel.conformance.pipeline.model.SourceUnit;
import metamodel.conformance.pipeline.util.Hashing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceProvenanceAuditTest {
    @TempDir
    Path temporary;

    @Test
    void everyCycleWitnessResolvesToHashedSourceEvidence() throws Exception {
        Path source = Path.of(getClass().getResource("/fixtures/cyclic").toURI());
        PipelineResult result = new ConformancePipeline(new JavaImplementationSourceObserver(List.of()))
                .analyze(source, temporary.resolve("audit"), Set.of());
        Map<String, SourceUnit> units = result.observation().units().stream()
                .collect(Collectors.toMap(SourceUnit::path, Function.identity()));

        Map<String, String> actualHashes = units.values().stream()
                .collect(Collectors.toMap(SourceUnit::path, unit -> {
                    try {
                        return Hashing.sha256(source.resolve(unit.path()));
                    } catch (java.io.IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                }));

        result.invariant("acyclic-generalization").witnessTechnicalKeys().forEach(key -> {
            var classifier = result.observation().classifiers().stream()
                    .filter(item -> item.id().equals(key)).findFirst().orElseThrow();
            SourceUnit unit = units.get(classifier.sourcePath());
            assertTrue(unit != null);
            assertEquals(unit.sha256(), actualHashes.get(unit.path()));
        });
        assertTrue(new CapsuleVerifier().verify(result.capsulePath()).valid());

        Files.writeString(result.observationPath(),
                Files.readString(result.observationPath()).replaceFirst("sha256=\".", "sha256=\"0"));
        assertFalse(new CapsuleVerifier().verify(result.capsulePath()).valid());
    }
}
