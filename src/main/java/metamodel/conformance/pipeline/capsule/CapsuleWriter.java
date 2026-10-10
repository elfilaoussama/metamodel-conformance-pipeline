package metamodel.conformance.pipeline.capsule;

import metamodel.conformance.pipeline.util.ArtifactLimits;
import metamodel.conformance.pipeline.util.AtomicFiles;

import java.io.IOException;
import java.nio.file.Path;

public final class CapsuleWriter {
    public void write(VerificationCapsule capsule, Path target) throws IOException {
        String json = CapsuleJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(capsule) + "\n";
        AtomicFiles.writeUtf8(
                target, json, ArtifactLimits.MAX_CAPSULE_BYTES, "verification capsule");
    }
}
