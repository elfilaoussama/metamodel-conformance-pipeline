package com.javapipeline.spoon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.javapipeline.core.model.ExtractionResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

public final class ExtractionJsonWriter {
    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeHierarchyAdapter(Path.class, (com.google.gson.JsonSerializer<Path>)
                    (value, type, context) -> context.serialize(value.toString()))
            .registerTypeAdapter(Instant.class, (com.google.gson.JsonSerializer<Instant>)
                    (value, type, context) -> context.serialize(value.toString()))
            .create();

    public Path write(ExtractionResult result, Path outputFile) throws IOException {
        Path target = outputFile.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, gson.toJson(result), StandardCharsets.UTF_8);
        try {
            return Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            return Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
