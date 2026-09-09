package metamodel.conformance.pipeline.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PipelineCliDependencyInputTest {
    @Test
    void rejectsManifestAndGlobalJarInputsTogether() {
        int exit = PipelineCli.run(new String[]{
                "analyze",
                "--source", "source",
                "--output", "output",
                "--dependency-manifest", "dependencies.tsv",
                "--dependency-jar", "dependency.jar"
        });

        assertEquals(64, exit);
    }
}
