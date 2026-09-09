package metamodel.conformance.pipeline.alloy;

import edu.mit.csail.sdg.translator.A4Options;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlloyExecutionConfigTest {
    @Test
    void mapsEveryFrozenSemanticOption() {
        AlloyExecutionConfig config = AlloyExecutionConfig.frozen();
        A4Options options = AlloyOptionsFactory.create(config);

        assertEquals("6.2.0", config.alloyVersion());
        assertEquals("sat4j", options.solver.id());
        assertEquals(config.inferPartialInstance(), options.inferPartialInstance);
        assertEquals(config.symmetry(), options.symmetry);
        assertEquals(config.skolemDepth(), options.skolemDepth);
        assertEquals(config.coreMinimization(), options.coreMinimization);
        assertEquals(config.coreGranularity(), options.coreGranularity);
        assertEquals(config.recordKodkod(), options.recordKodkod);
        assertEquals(config.noOverflow(), options.noOverflow);
        assertEquals(config.unrolls(), options.unrolls);
        assertEquals(config.decomposeMode(), options.decompose_mode);
        assertEquals(config.decomposeThreads(), options.decompose_threads);
    }

    @Test
    void rejectsConfigurationDrift() {
        AlloyExecutionConfig changed = new AlloyExecutionConfig(
                "6.2.0", "sat4j", true, 0, 0, 2, 0,
                false, false, -1, 0, 4);

        assertFalse(changed.supported());
        assertThrows(IllegalArgumentException.class, changed::requireSupported);
        assertTrue(AlloyExecutionConfig.frozen().supported());
    }
}
