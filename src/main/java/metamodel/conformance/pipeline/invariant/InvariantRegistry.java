package metamodel.conformance.pipeline.invariant;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;

public final class InvariantRegistry {
    private final List<InvariantDefinition> invariants;

    private InvariantRegistry(List<InvariantDefinition> invariants) {
        this.invariants = invariants.stream().sorted(Comparator.comparing(InvariantDefinition::id)).toList();
        if (this.invariants.stream().map(InvariantDefinition::id).distinct().count() != this.invariants.size()) {
            throw new IllegalArgumentException("invariant identifiers must be unique");
        }
    }

    public static InvariantRegistry load() {
        try (InputStream input = InvariantRegistry.class.getResourceAsStream("/invariants/registry.json")) {
            if (input == null) {
                throw new IllegalStateException("bundled invariant registry is missing");
            }
            CatalogFile file = new ObjectMapper().readValue(input, CatalogFile.class);
            if (!"1".equals(file.schemaVersion()) || file.invariants() == null || file.invariants().isEmpty()) {
                throw new IllegalStateException("unsupported or empty invariant registry");
            }
            return new InvariantRegistry(file.invariants());
        } catch (IOException failure) {
            throw new IllegalStateException("cannot load invariant registry", failure);
        }
    }

    public List<InvariantDefinition> all() {
        return invariants;
    }

    public InvariantDefinition require(String id) {
        return invariants.stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown invariant: " + id));
    }

    private record CatalogFile(String schemaVersion, List<InvariantDefinition> invariants) {
    }
}
