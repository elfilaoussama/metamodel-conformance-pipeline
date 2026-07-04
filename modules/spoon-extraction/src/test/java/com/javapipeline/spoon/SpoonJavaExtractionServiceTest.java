package com.javapipeline.spoon;

import com.javapipeline.core.CancellationToken;
import com.javapipeline.core.ExtractionOptions;
import com.javapipeline.core.ProgressListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpoonJavaExtractionServiceTest {
    @TempDir Path temporary;

    @Test
    void discoversMainSourcesAndExtractsConstructorsAndInheritance() throws Exception {
        Path source = temporary.resolve("src/main/java/demo");
        Files.createDirectories(source);
        Files.writeString(source.resolve("Base.java"), "package demo; public class Base {}\n");
        Files.writeString(source.resolve("Child.java"), """
                package demo;
                public final class Child extends Base {
                    private final String name;
                    public Child(String name) { this.name = name; }
                    public String name() { return name; }
                }
                """);

        var result = new SpoonJavaExtractionService().extract(
                "fixture", temporary, ExtractionOptions.DEFAULT,
                ProgressListener.NONE, CancellationToken.NONE);

        assertEquals(2, result.types().size());
        var child = result.types().stream()
                .filter(type -> type.qualifiedName().equals("demo.Child"))
                .findFirst().orElseThrow();
        assertEquals("demo.Base", child.superClass());
        assertTrue(child.executables().stream().anyMatch(executable -> executable.constructor()));
        assertTrue(child.sourceFile().endsWith("src/main/java/demo/Child.java"));
    }

    @Test
    void serializesConcretePlatformPathImplementations() throws Exception {
        var result = new com.javapipeline.core.model.ExtractionResult(
                "1.0", "fixture", temporary, Instant.now(),
                List.of(temporary.resolve("src/main/java")), List.of(), List.of());
        Path output = temporary.resolve("result.json");

        new ExtractionJsonWriter().write(result, output);

        String json = Files.readString(output);
        assertTrue(json.contains("fixture"));
        assertFalse(json.contains("sun.nio.fs"));
    }
}
