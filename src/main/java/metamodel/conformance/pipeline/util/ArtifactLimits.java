package metamodel.conformance.pipeline.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ArtifactLimits {
    // Runaway guards, not semantic size limits: supported repositories must
    // never be rejected because their exact observation is large. Measured
    // examples: Commons Codec Alloy 5.3 MiB, jackson-core 8.5 MiB,
    // ArchUnit 20.6 MiB, caffeine 17.4 MiB. Workflow execution budgets are a
    // separate operational control; these bounds only stop pathological
    // output growth.
    public static final long MAX_CAPSULE_BYTES = 64L * 1024L * 1024L;
    public static final long MAX_ALLOY_BYTES = 1024L * 1024L * 1024L;
    public static final long MAX_XMI_BYTES = 512L * 1024L * 1024L;

    private ArtifactLimits() {
    }

    public static void requireUtf8Within(String label, String content, long maximum) throws IOException {
        long size = content.getBytes(StandardCharsets.UTF_8).length;
        requireWithin(label, size, maximum);
    }

    public static void requireFileWithin(String label, Path path, long maximum) throws IOException {
        requireWithin(label, Files.size(path), maximum);
    }

    private static void requireWithin(String label, long size, long maximum) throws IOException {
        if (size > maximum) {
            throw new IOException(label + " exceeds " + maximum + " bytes: " + size);
        }
    }
}
