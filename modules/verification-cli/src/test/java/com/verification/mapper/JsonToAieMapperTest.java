package com.verification.mapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JsonToAieMapperTest {

    private final JsonToAieMapper mapper = new JsonToAieMapper();

    @TempDir
    Path tempDir;

    @Test
    void mapsValidExtraction() throws Exception {
        Path json = resourcePath("current-spoon-valid.json");
        Path aieOutput = tempDir.resolve("output.aie");

        mapper.map(json, aieOutput);

        assertTrue(Files.isRegularFile(aieOutput));
        String content = Files.readString(aieOutput);
        assertTrue(content.contains("Root = {"));
        assertTrue(content.contains("classifiers = {Class0}"));
        assertTrue(content.contains("name = \"Animal\"") && content.contains("isAbstract = No"));
        assertTrue(content.contains("classParent[Class0] = null"));
    }

    @Test
    void mapsDuplicateExtraction() throws Exception {
        Path json = resourcePath("current-spoon-duplicate.json");
        Path aieOutput = tempDir.resolve("output.aie");

        mapper.map(json, aieOutput);

        assertTrue(Files.isRegularFile(aieOutput));
        String content = Files.readString(aieOutput);
        assertTrue(content.contains("Class0"));
        assertTrue(content.contains("Class1"));
        assertTrue(content.contains("\"Animal\""));
    }

    @Test
    void mapsCyclicInheritance() throws Exception {
        Path json = resourcePath("cyclic-inheritance.json");
        Path aieOutput = tempDir.resolve("output.aie");

        mapper.map(json, aieOutput);

        assertTrue(Files.isRegularFile(aieOutput));
        String content = Files.readString(aieOutput);
        assertTrue(content.contains("classParent[Class0] = Class1"));
        assertTrue(content.contains("classParent[Class1] = Class0"));
    }

    @Test
    void mapsAbstractMethodViolation() throws Exception {
        Path json = resourcePath("abstract-method-violation.json");
        Path aieOutput = tempDir.resolve("output.aie");

        mapper.map(json, aieOutput);

        assertTrue(Files.isRegularFile(aieOutput));
        String content = Files.readString(aieOutput);
        assertTrue(content.contains("memberName = \"doSomething\""));
        assertTrue(content.contains("isAbstract = Yes"));
        assertTrue(content.contains("localMethods[Class0] = {Method0}"));
    }

    @Test
    void handlesEmptyTypesArray() throws Exception {
        String emptyJson = "{\"projectName\":\"empty\",\"types\":[]}";
        Path json = tempDir.resolve("empty.json");
        Files.writeString(json, emptyJson);
        Path aieOutput = tempDir.resolve("output.aie");

        mapper.map(json, aieOutput);

        assertTrue(Files.isRegularFile(aieOutput));
        String content = Files.readString(aieOutput);
        assertTrue(content.contains("classifiers = {}"));
    }

    @Test
    void mapsInterfaceTypesCorrectly() throws Exception {
        String json = "{\"projectName\":\"test\",\"types\":[" +
                "{\"qualifiedName\":\"Foo\",\"kind\":\"class\",\"abstractType\":\"false\",\"superClass\":null}," +
                "{\"qualifiedName\":\"IBar\",\"kind\":\"interface\",\"abstractType\":\"true\",\"superClass\":null}" +
                "]}";
        Path jsonPath = tempDir.resolve("iface.json");
        Files.writeString(jsonPath, json);
        Path aieOutput = tempDir.resolve("output.aie");

        mapper.map(jsonPath, aieOutput);

        assertTrue(Files.isRegularFile(aieOutput));
        String content = Files.readString(aieOutput);
        assertTrue(content.contains("Class0"));
        assertTrue(content.contains("Interface1"));
        assertTrue(content.contains("classifiers = {Class0, Interface1}"));
        assertTrue(content.contains("isAbstract = Yes"));
    }

    private static Path resourcePath(String name) throws IOException, URISyntaxException {
        URL url = JsonToAieMapperTest.class.getResource("/" + name);
        if (url == null) throw new IOException("Resource not found: " + name);
        return Path.of(url.toURI());
    }
}
