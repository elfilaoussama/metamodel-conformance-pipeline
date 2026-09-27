package metamodel.conformance.pipeline.adapter.java;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The platform home and ct.sym file are shared across {@code --release} levels,
 * so the platform identity must also fingerprint the effective release and
 * preview semantics. Otherwise two contexts that observe different platform
 * signatures would share one identity and collide on materialized support
 * classifiers such as {@code java.util.List}.
 */
class JavaPlatformProvenanceTest {

    @Test
    void platformIdentityDistinguishesReleaseLevels() throws Exception {
        String release8 = JavaPlatformProvenance.capture(context(8)).sha256();
        String release17 = JavaPlatformProvenance.capture(context(17)).sha256();
        assertNotEquals(release8, release17);
    }

    @Test
    void platformIdentityIsStableForTheSameRelease() throws Exception {
        assertEquals(
                JavaPlatformProvenance.capture(context(17)).sha256(),
                JavaPlatformProvenance.capture(context(17)).sha256());
    }

    private static JavaCompilationContext context(int release) {
        return new JavaCompilationContext(
                "maven|.|compile",
                ".",
                List.of("src/main/java"),
                List.of(),
                List.of(),
                List.of(),
                Set.of(),
                new JavaCompilerSemantics(null, null, release, false, ""));
    }
}
