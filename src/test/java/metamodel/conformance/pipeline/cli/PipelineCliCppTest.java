package metamodel.conformance.pipeline.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineCliCppTest {
    @TempDir
    Path temp;

    @Test
    void analyzesCppThroughThePublicCliBoundary() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        Path output = temp.resolve("output");
        Files.writeString(source.resolve("models.cpp"), """
                class Base {};
                class Child : public Base {};
                """);

        int analyzeExit = PipelineCli.run(new String[] {
                "analyze",
                "--source", source.toString(),
                "--output", output.toString(),
                "--language", "cpp"
        });

        // The first C++ slice claims only direct hierarchy evidence. Member-dependent
        // invariants therefore remain NOT_EVALUATED while artifacts remain replayable.
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
