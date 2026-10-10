package metamodel.conformance.pipeline.adapter.java;

import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeParameter;

import java.util.List;

/** The same source declaration domain for structural, body, and abstraction observation. */
final class SpoonSourceTypes {
    private SpoonSourceTypes() {}

    static List<CtType<?>> declarations(CtModel model) {
        // getAllTypes() returns top-level types only. Walk all declarations, but
        // do not turn generic type parameters (also CtType in Spoon) into classifiers.
        return model.getElements((CtType<?> type) -> !(type instanceof CtTypeParameter)
                && type.getPosition().isValidPosition());
    }
}
