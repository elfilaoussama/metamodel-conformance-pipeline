package metamodel.conformance.pipeline.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class AtomicFiles {
    private AtomicFiles() {
    }

    public static void writeUtf8(Path target, String content) throws IOException {
        writeUtf8(target, content, Long.MAX_VALUE, "artifact");
    }

    public static void writeUtf8(
            Path target, String content, long maximumBytes, String label) throws IOException {
        ArtifactLimits.requireUtf8Within(label, content, maximumBytes);
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Files.createDirectories(normalizedTarget.getParent());
        Path temporary = Files.createTempFile(
                normalizedTarget.getParent(), normalizedTarget.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            move(temporary, normalizedTarget);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
