package com.javapipeline.cpp;

import com.javapipeline.core.*;
import com.javapipeline.core.model.ExtractionResult;
import com.javapipeline.spoon.ExtractionJsonWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CppExtractionServiceTest {

    private final CppExtractionService service = new CppExtractionService();
    private final ExtractionJsonWriter writer = new ExtractionJsonWriter();

    @TempDir
    Path tempDir;

    @Test
    void languageDetection() {
        assertEquals(Language.CPP, service.getLanguage());
    }

    @Test
    void extractsSimpleClass() throws Exception {
        writeCppFile("foo.hpp", """
                class Animal {
                public:
                    void speak();
                private:
                    int age;
                };
                """);

        ExtractionResult result = service.extract("test", tempDir,
                ExtractionOptions.forLanguage(Language.CPP), null, null);

        assertTrue(result.types().size() > 0, "Should extract at least one type");
        var animal = result.types().get(0);
        assertTrue(animal.simpleName().contains("Animal"));
        assertEquals("class", animal.kind());
        assertFalse(animal.abstractType());
        assertTrue(animal.executables().size() > 0);
    }

    @Test
    void detectsPureVirtualAsAbstract() throws Exception {
        writeCppFile("abstract.hpp", """
                class IShape {
                public:
                    virtual void draw() = 0;
                };
                """);

        ExtractionResult result = service.extract("test", tempDir,
                ExtractionOptions.forLanguage(Language.CPP), null, null);

        assertTrue(result.types().size() > 0);
        var shape = result.types().get(0);
        assertTrue(shape.abstractType(), "Class with pure virtual should be abstract");
        assertTrue(shape.executables().stream()
                .anyMatch(e -> e.abstractExecutable()), "draw() should be abstract");
    }

    @Test
    void detectsInheritance() throws Exception {
        writeCppFile("hierarchy.hpp", """
                class Animal {
                public:
                    void breathe();
                };

                class Dog : public Animal {
                public:
                    void bark();
                };
                """);

        ExtractionResult result = service.extract("test", tempDir,
                ExtractionOptions.forLanguage(Language.CPP), null, null);

        var dog = result.types().stream()
                .filter(t -> t.simpleName().contains("Dog")).findFirst().orElse(null);
        assertNotNull(dog, "Should find Dog class");
        assertEquals("Animal", dog.superClass());
    }

    @Test
    void emptyRepoReturnsDiagnostics() throws Exception {
        ExtractionResult result = service.extract("empty", tempDir,
                ExtractionOptions.forLanguage(Language.CPP), null, null);

        assertTrue(result.types().isEmpty());
        assertFalse(result.diagnostics().isEmpty());
    }

    @Test
    void writesValidExtractionJson() throws Exception {
        writeCppFile("main.hpp", """
                class Foo {
                public:
                    int value;
                    void bar();
                };
                """);

        ExtractionResult result = service.extract("test", tempDir,
                ExtractionOptions.forLanguage(Language.CPP), null, null);
        assertTrue(result.types().size() > 0);

        Path json = tempDir.resolve("extraction.json");
        writer.write(result, json);
        assertTrue(Files.isRegularFile(json));

        String content = Files.readString(json, StandardCharsets.UTF_8);
        assertTrue(content.contains("\"Foo\""));
        assertTrue(content.contains("\"bar\""));
    }

    private void writeCppFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
