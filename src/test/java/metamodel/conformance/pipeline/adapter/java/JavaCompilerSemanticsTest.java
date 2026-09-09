package metamodel.conformance.pipeline.adapter.java;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaCompilerSemanticsTest {
    @Test
    void preservesBuildObservedReleaseAndPlatformIdentity() {
        JavaCompilerSemantics semantics = new JavaCompilerSemantics(null, null, 17, true, "temurin-17");
        assertEquals(17, semantics.releaseLevel());
        assertEquals("temurin-17", semantics.platformIdentity());
        assertTrue(semantics.previewEnabled());
    }

    @Test
    void keepsSourceTargetSemanticsDistinctFromReleaseSemantics() {
        JavaCompilerSemantics semantics = new JavaCompilerSemantics(11, 11, null, false, "jdk-21-host");
        assertEquals(11, semantics.sourceLevel());
        assertEquals(11, semantics.targetLevel());
        assertEquals(null, semantics.releaseLevel());
    }

    @Test
    void rejectsContradictoryOrInvalidCompilerSemantics() {
        assertThrows(IllegalArgumentException.class,
                () -> new JavaCompilerSemantics(17, 17, 17, false, "jdk-17"));
        assertThrows(IllegalArgumentException.class,
                () -> new JavaCompilerSemantics(0, null, null, false, ""));
    }

    @Test
    void runtimeFallbackIsExplicitRatherThanBuildFileGuessing() {
        JavaCompilerSemantics runtime = JavaCompilerSemantics.runtime();
        assertEquals(Runtime.version().feature(), runtime.releaseLevel());
        assertTrue(runtime.platformIdentity().startsWith("runtime-jdk-"));
    }
}
