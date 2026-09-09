package metamodel.conformance.pipeline.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineCliPythonTest {
    @TempDir
    Path temp;

    @Test
    void analyzesPythonThroughThePublicCliBoundary() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Path output = temp.resolve("output");
        Files.writeString(source.resolve("models.py"), """
                class Base:
                    pass

                class Child(Base):
                    pass
                """);

        int analyzeExit = PipelineCli.run(new String[] {
                "analyze",
                "--source", source.toString(),
                "--output", output.toString(),
                "--language", "python"
        });

        // Only hierarchy evidence is complete in the first Python slice, so member-dependent
        // invariants correctly remain NOT_EVALUATED even though the pipeline itself succeeds.
        assertEquals(3, analyzeExit);
        assertTrue(Files.isRegularFile(output.resolve("observation.xmi")));
        assertTrue(Files.isRegularFile(output.resolve("repository-instance.als")));
        assertTrue(Files.isRegularFile(output.resolve("verification-capsule.json")));

        int verifyExit = PipelineCli.run(new String[] {
                "verify-capsule",
                "--capsule", output.resolve("verification-capsule.json").toString()
        });
        assertEquals(0, verifyExit);
    }
}
