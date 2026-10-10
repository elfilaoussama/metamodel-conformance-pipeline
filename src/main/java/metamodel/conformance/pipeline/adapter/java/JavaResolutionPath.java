package metamodel.conformance.pipeline.adapter.java;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

/** One ordered compiler resolution path. Entry order is semantically significant. */
record JavaResolutionPath(JavaResolutionPathRole role, String qualifier, List<Path> entries) {
    JavaResolutionPath {
        if (role == null) {
            throw new IllegalArgumentException("resolution path role must not be null");
        }
        qualifier = qualifier == null ? "" : qualifier.trim();
        if (role != JavaResolutionPathRole.PATCH_MODULE && !qualifier.isEmpty()) {
            throw new IllegalArgumentException("only PATCH_MODULE resolution paths may have a qualifier");
        }
        if (role == JavaResolutionPathRole.PATCH_MODULE && qualifier.isEmpty()) {
            throw new IllegalArgumentException("PATCH_MODULE resolution paths require a module qualifier");
        }
        LinkedHashSet<Path> ordered = new LinkedHashSet<>();
        for (Path entry : entries == null ? List.<Path>of() : entries) {
            if (entry == null) {
                throw new IllegalArgumentException("resolution path entry must not be null");
            }
            ordered.add(entry.toAbsolutePath().normalize());
        }
        entries = List.copyOf(ordered);
    }
}
