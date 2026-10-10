package metamodel.conformance.pipeline.util;

import metamodel.conformance.pipeline.model.SourceUnit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class Hashing {
    private Hashing() {
    }

    public static String sha256(Path path) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String sha256(String value) {
        return HexFormat.of().formatHex(digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    public static String sourceSetDigest(List<SourceUnit> units) {
        StringBuilder canonical = new StringBuilder();
        units.stream().sorted(java.util.Comparator.comparing(SourceUnit::path))
                .forEach(unit -> canonical.append(unit.language()).append('\0')
                        .append(unit.path()).append('\0')
                        .append(unit.sha256()).append('\n'));
        return sha256(canonical.toString());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
