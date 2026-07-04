package com.verification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RuntimeClasspathWriter {
    private RuntimeClasspathWriter() {}

    public static String readCachedClasspath(Path cacheFile) throws IOException {
        if (Files.isRegularFile(cacheFile)) {
            return Files.readString(cacheFile, StandardCharsets.UTF_8).trim();
        }
        return null;
    }

    public static void writeCachedClasspath(Path cacheFile, String classpath) throws IOException {
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, classpath, StandardCharsets.UTF_8);
    }
}
