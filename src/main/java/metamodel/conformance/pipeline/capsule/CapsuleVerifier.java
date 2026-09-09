package metamodel.conformance.pipeline.capsule;

import metamodel.conformance.pipeline.PipelineVersion;
import metamodel.conformance.pipeline.alloy.AlloyExecutionConfig;
import metamodel.conformance.pipeline.alloy.AlloyInvariantEvaluator;
import metamodel.conformance.pipeline.alloy.ExactAlloyEncoder;
import metamodel.conformance.pipeline.emf.ObservationXmiReader;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.util.ArtifactLimits;
import metamodel.conformance.pipeline.util.Hashing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;

public final class CapsuleVerifier {
    public CapsuleVerification verify(Path capsulePath) {
        try {
            Path capsuleFile = capsulePath.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!Files.isRegularFile(capsuleFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(capsulePath)
                    || Files.size(capsuleFile) > ArtifactLimits.MAX_CAPSULE_BYTES) {
                return invalid("capsule is not a regular file or exceeds the size limit");
            }
            VerificationCapsule capsule = CapsuleJson.MAPPER.readValue(capsuleFile.toFile(), VerificationCapsule.class);
            if (!"6".equals(capsule.formatVersion()) || capsule.decisions().isEmpty()) {
                return invalid("unsupported or empty capsule format");
            }
            if (!PipelineVersion.TOOL_ID.equals(capsule.toolId())
                    || !PipelineVersion.VERSION.equals(capsule.toolVersion())) {
                return invalid("unsupported capsule producer identity or version");
            }
            if (capsule.alloyExecution() == null || !capsule.alloyExecution().supported()) {
                return invalid("unsupported or missing Alloy execution configuration");
            }
            Path root = capsuleFile.getParent().toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path observationPath = resolveArtifact(root, capsule.observationPath());
            Path alloyPath = resolveArtifact(root, capsule.alloyPath());
            ArtifactLimits.requireFileWithin(
                    "canonical XMI", observationPath, ArtifactLimits.MAX_XMI_BYTES);
            ArtifactLimits.requireFileWithin(
                    "Alloy artifact", alloyPath, ArtifactLimits.MAX_ALLOY_BYTES);
            if (!digestEquals(capsule.observationSha256(), Hashing.sha256(observationPath))) {
                return invalid("observation digest mismatch");
            }
            if (!digestEquals(capsule.alloySha256(), Hashing.sha256(alloyPath))) {
                return invalid("Alloy digest mismatch");
            }

            Observation observation = new ObservationXmiReader().read(observationPath);
            if (!capsule.schemaVersion().equals(observation.schemaVersion())
                    || !capsule.adapterId().equals(observation.adapterId())
                    || !capsule.adapterVersion().equals(observation.adapterVersion())) {
                return invalid("observation metadata does not match capsule");
            }
            if (!digestEquals(capsule.sourceSetSha256(), Hashing.sourceSetDigest(observation.units()))) {
                return invalid("source-set digest mismatch");
            }
            if (!capsule.observationDiagnostics().equals(observation.diagnostics())) {
                return invalid("observation diagnostics do not match capsule");
            }
            String alloy = Files.readString(alloyPath, StandardCharsets.UTF_8);
            String regeneratedAlloy = new ExactAlloyEncoder().encode(observation);
            if (!regeneratedAlloy.equals(alloy)) {
                return invalid(describeAlloyDrift(alloy, regeneratedAlloy));
            }
            var repeated = new AlloyInvariantEvaluator(capsule.alloyExecution())
                    .evaluateAll(observation, alloy);
            if (!repeated.equals(capsule.decisions())) {
                return invalid("repeated Alloy decisions do not match capsule");
            }
            return new CapsuleVerification(true, "Capsule artifacts and repeated Alloy decisions are valid.");
        } catch (Exception | LinkageError failure) {
            String message = failure.getMessage();
            return invalid(message == null ? failure.getClass().getSimpleName() : message);
        }
    }

    private static Path resolveArtifact(Path root, String relative) throws IOException {
        if (relative == null || relative.isBlank()) {
            throw new IOException("artifact path is blank");
        }
        Path relativePath = Path.of(relative);
        if (relativePath.isAbsolute() || relativePath.normalize().startsWith("..")) {
            throw new IOException("artifact path escapes capsule directory: " + relative);
        }
        Path candidate = root.resolve(relativePath).normalize();
        if (Files.isSymbolicLink(candidate)) {
            throw new IOException("symbolic-link artifacts are forbidden: " + relative);
        }
        Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.startsWith(root) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("artifact is outside capsule directory or is not a file: " + relative);
        }
        return real;
    }

    private static boolean digestEquals(String expected, String actual) {
        if (expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static String describeAlloyDrift(String archived, String regenerated) {
        String[] archivedLines = archived.split("\\R", -1);
        String[] regeneratedLines = regenerated.split("\\R", -1);
        int shared = Math.min(archivedLines.length, regeneratedLines.length);
        int line = 0;
        while (line < shared && archivedLines[line].equals(regeneratedLines[line])) {
            line++;
        }
        if (line == shared) {
            return "canonical XMI-to-Alloy drift after line " + line
                    + " (archived lines=" + archivedLines.length
                    + ", regenerated lines=" + regeneratedLines.length + ")";
        }
        int column = firstDifference(archivedLines[line], regeneratedLines[line]);
        return "canonical XMI-to-Alloy drift at line " + (line + 1)
                + ", column " + (column + 1)
                + ": archived=" + context(archivedLines[line], column)
                + "; regenerated=" + context(regeneratedLines[line], column);
    }

    private static int firstDifference(String left, String right) {
        int shared = Math.min(left.length(), right.length());
        int column = 0;
        while (column < shared && left.charAt(column) == right.charAt(column)) {
            column++;
        }
        return column;
    }

    private static String context(String value, int column) {
        int start = Math.max(0, column - 100);
        int end = Math.min(value.length(), column + 140);
        return (start == 0 ? "" : "...") + value.substring(start, end)
                + (end == value.length() ? "" : "...");
    }

    private static CapsuleVerification invalid(String message) {
        return new CapsuleVerification(false, message);
    }
}
