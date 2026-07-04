package com.javapipeline.desktop;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

final class AnalysisCache {
    private static final String FILE_NAME = "analysis-cache.properties";
    private final Path file;
    private final Properties values = new Properties();

    private AnalysisCache(Path outputDirectory) {
        file = outputDirectory.resolve(FILE_NAME);
        if (Files.isRegularFile(file)) {
            try (InputStream input = Files.newInputStream(file)) { values.load(input); }
            catch (Exception ignored) { values.clear(); }
        }
    }

    static AnalysisCache load(Path outputDirectory) { return new AnalysisCache(outputDirectory); }

    static String extractionKey(Path repository, String revision, int compliance, boolean includeTests) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(("spoon-v2\n" + revision + "\n" + compliance + "\n" + includeTests + "\n")
                .getBytes(StandardCharsets.UTF_8));
        try (var paths = Files.walk(repository)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !ignored(repository.relativize(path)))
                    .sorted().toList()) {
                digest.update(repository.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                try (InputStream input = Files.newInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean ignored(Path relative) {
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals(".git") || name.equals("target") || name.equals("build") || name.equals("out")) return true;
        }
        return false;
    }

    static String verificationKey(String extractionKey, Path metamodel) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("alloy-strict-v2\n".getBytes(StandardCharsets.UTF_8));
        digest.update(extractionKey.getBytes(StandardCharsets.UTF_8));
        try (InputStream input = Files.newInputStream(metamodel)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    boolean hasExtraction(String key, Path extraction) {
        return key.equals(values.getProperty("extraction.key")) && Files.isRegularFile(extraction);
    }

    int typeCount() {
        try { return Integer.parseInt(values.getProperty("extraction.types", "0")); }
        catch (NumberFormatException ignored) { return 0; }
    }

    boolean hasVerification(String key, Path json, Path csv) {
        return key.equals(values.getProperty("verification.key"))
                && Files.isRegularFile(json) && Files.isRegularFile(csv);
    }

    void recordExtraction(String key, int typeCount) throws Exception {
        values.setProperty("extraction.key", key);
        values.setProperty("extraction.types", Integer.toString(typeCount));
        values.remove("verification.key");
        save();
    }

    void recordVerification(String key) throws Exception {
        values.setProperty("verification.key", key);
        save();
    }

    private void save() throws Exception {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), FILE_NAME, ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            values.store(output, "Java analysis pipeline cache; safe to delete");
        }
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
