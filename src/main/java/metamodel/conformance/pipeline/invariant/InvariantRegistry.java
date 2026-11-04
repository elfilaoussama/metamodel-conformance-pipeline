package metamodel.conformance.pipeline.invariant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;

import java.io.IOException;
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
        return parse(InvariantProfile.readResource("invariants/registry.json", InvariantProfile.MAX_REGISTRY_BYTES));
    }

    static InvariantRegistry parse(byte[] bytes) {
        try {
            CatalogFile file = new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(bytes, CatalogFile.class);
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
