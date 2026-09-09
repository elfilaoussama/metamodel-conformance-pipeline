package metamodel.conformance.pipeline.alloy;

import edu.mit.csail.sdg.translator.A4Options;
import kodkod.engine.satlab.SATFactory;

final class AlloyOptionsFactory {
    private AlloyOptionsFactory() {
    }

    static A4Options create(AlloyExecutionConfig config) {
        config.requireSupported();
        A4Options options = new A4Options();
        options.inferPartialInstance = config.inferPartialInstance();
        options.symmetry = config.symmetry();
        options.skolemDepth = config.skolemDepth();
        options.coreMinimization = config.coreMinimization();
        options.coreGranularity = config.coreGranularity();
        options.solver = SATFactory.get(config.solverId());
        options.recordKodkod = config.recordKodkod();
        options.noOverflow = config.noOverflow();
        options.unrolls = config.unrolls();
        options.decompose_mode = config.decomposeMode();
        options.decompose_threads = config.decomposeThreads();
        return options;
    }
}
