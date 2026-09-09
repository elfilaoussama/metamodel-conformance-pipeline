package metamodel.conformance.pipeline.adapter;

import metamodel.conformance.pipeline.model.Observation;

import java.nio.file.Path;
import java.util.Set;

public interface SourceObserver {
    Observation observe(Path sourceRoot, Set<String> externalParents) throws ObservationException;
}
