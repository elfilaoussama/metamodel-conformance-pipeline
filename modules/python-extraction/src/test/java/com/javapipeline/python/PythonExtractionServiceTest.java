package com.javapipeline.python;

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

class PythonExtractionServiceTest {

    private final PythonExtractionService service = new PythonExtractionService();
    private final ExtractionJsonWriter writer = new ExtractionJsonWriter();

    @TempDir
    Path tempDir;

    @Test
    void extractsSimpleClasses() throws Exception {
        writePythonFile("app.py", """
                class Animal:
                    def speak(self):
                        pass

                class Dog(Animal):
                    def bark(self):
                        return "woof"
                """);

        ExtractionResult result = service.extract("test", tempDir,
                ExtractionOptions.forLanguage(Language.PYTHON), null, null);

        assertEquals(2, result.types().size());
        assertEquals("Animal", result.types().get(0).simpleName());
    }

    @Test
    void extractsSuperClassCorrectly() throws Exception {
        writePythonFile("animals.py", """
                class Animal:
                    def speak(self):
                        pass

                class Dog(Animal):
                    def bark(self):
                        return "woof"
                """);

        ExtractionResult result = service.extract("test", tempDir,
                ExtractionOptions.forLanguage(Language.PYTHON), null, null);

        var dog = result.types().stream()
                .filter(t -> "Dog".equals(t.simpleName())).findFirst().orElseThrow();
        assertEquals("class", dog.kind());
        assertEquals("Animal", dog.superClass());
        assertTrue(dog.interfaces().isEmpty());
    }

    @Test
    void detectsInterfaceFromABC() throws Exception {
        writePythonFile("shape.py", """
                from abc import ABC, abstractmethod

                class IShape(ABC):
                    @abstractmethod
                    def area(self):
                        pass

                class Circle(IShape):
                    def area(self):
                        return 3.14
                """);

        ExtractionResult result = service.extract("test", tempDir,
                ExtractionOptions.forLanguage(Language.PYTHON), null, null);

        var ishape = result.types().stream()
                .filter(t -> "IShape".equals(t.simpleName())).findFirst().orElseThrow();
        assertEquals("interface", ishape.kind());
        assertTrue(ishape.abstractType());
        assertEquals(1, ishape.executables().size());
        assertTrue(ishape.executables().get(0).abstractExecutable());
    }

    @Test
    void detectsStaticMethods() throws Exception {
        writePythonFile("util.py", """
                class MathUtil:
                    @staticmethod
                    def add(a, b):
                        return a + b
                """);

        ExtractionResult result = service.extract("test", tempDir,
                ExtractionOptions.forLanguage(Language.PYTHON), null, null);

        assertEquals(1, result.types().size());
        var method = result.types().get(0).executables().get(0);
        assertEquals("add", method.name());
        assertTrue(method.staticExecutable());
    }

    @Test
    void languageDetection() {
        assertEquals(Language.PYTHON, service.getLanguage());
    }

    @Test
    void writesValidExtractionJson() throws Exception {
        writePythonFile("main.py", """
                class Foo:
                    count = 0

                    def bar(self):
                        pass
                """);

        ExtractionResult result = service.extract("test", tempDir,
                ExtractionOptions.forLanguage(Language.PYTHON), null, null);
        assertEquals(1, result.types().size());

        Path json = tempDir.resolve("extraction.json");
        writer.write(result, json);
        assertTrue(Files.isRegularFile(json));

        String content = Files.readString(json, StandardCharsets.UTF_8);
        assertTrue(content.contains("\"Foo\""));
        assertTrue(content.contains("\"bar\""));
        assertTrue(content.contains("\"count\""));
    }

    @Test
    void emptyRepoReturnsEmpty() throws Exception {
        ExtractionResult result = service.extract("empty", tempDir,
                ExtractionOptions.forLanguage(Language.PYTHON), null, null);

        assertTrue(result.types().isEmpty());
    }

    private void writePythonFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
