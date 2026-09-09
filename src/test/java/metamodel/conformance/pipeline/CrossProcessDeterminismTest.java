package metamodel.conformance.pipeline;

import metamodel.conformance.pipeline.capsule.CapsuleVerifier;
import metamodel.conformance.pipeline.cli.PipelineCli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossProcessDeterminismTest {
    @TempDir
    Path temporary;

    @Test
    void independentJvmRunsProduceByteIdenticalArtifacts() throws Exception {
        Path source = Path.of(getClass().getResource("/fixtures/acyclic").toURI());
        Path first = temporary.resolve("first");
        Path second = temporary.resolve("second");

        assertEquals(0, analyzeInFreshJvm(source, first));
        assertEquals(0, analyzeInFreshJvm(source, second));

        for (String artifact : new String[] {
                "observation.xmi", "repository-instance.als", "verification-capsule.json"}) {
            assertArrayEquals(
                    Files.readAllBytes(first.resolve(artifact)),
                    Files.readAllBytes(second.resolve(artifact)),
                    artifact);
        }
        assertTrue(new CapsuleVerifier().verify(first.resolve("verification-capsule.json")).valid());
        assertTrue(new CapsuleVerifier().verify(second.resolve("verification-capsule.json")).valid());
    }

    private static int analyzeInFreshJvm(Path source, Path output) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty(
                "surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(
                java,
                "-Duser.language=en",
                "-Duser.country=US",
                "-Duser.timezone=UTC",
                "-cp",
                classpath,
                PipelineCli.class.getName(),
                "analyze",
                "--source",
                source.toString(),
                "--output",
                output.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        boolean finished = process.waitFor(2, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
        }
        assertTrue(finished, "fresh JVM analysis timed out");
        return process.exitValue();
    }
}
