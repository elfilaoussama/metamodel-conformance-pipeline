package com.verification.mapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JsonToAieMapperTest {

    private final JsonToAieMapper mapper = new JsonToAieMapper();

    @TempDir
    Path tempDir;

    // ================================================================
    // Existing tests (old JSON format with "name"/"methods"/"superclass")
    // ================================================================

    @Test
    void mapsValidExtraction() throws Exception {
        Path json = resourcePath("current-spoon-valid.json");
        Path aieOutput = tempDir.resolve("output.aie");
        mapper.map(json, aieOutput);
        assertTrue(Files.isRegularFile(aieOutput));
        String content = Files.readString(aieOutput);
        assertTrue(content.contains("Root = {"));
        assertTrue(content.contains("classifiers = {Class0}"));
        assertTrue(content.contains("\"Animal\"") && content.contains("isAbstract = No"));
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

    // ================================================================
    // New regression tests (new JSON format: qualifiedName/simpleName/executables)
    // ================================================================

    @Test
    void superClassMatchingUsesSimpleNameFallback() throws Exception {
        String json = """
                {
                  "types": [
                    {"qualifiedName":"animals.Animal","simpleName":"Animal","kind":"class","superClass":null,
                     "executables":[],"fields":[],"abstractType":"false"},
                    {"qualifiedName":"pets.Dog","simpleName":"Dog","kind":"class","superClass":"Animal",
                     "executables":[],"fields":[],"abstractType":"false"}
                  ]
                }""";
        Path jsonPath = tempDir.resolve("simpleName.json");
        Files.writeString(jsonPath, json);
        Path aieOutput = tempDir.resolve("output.aie");
        mapper.map(jsonPath, aieOutput);
        String content = Files.readString(aieOutput);
        assertTrue(content.contains("classParent[Class1] = Class0"),
                "Dog should have Animal as classParent via simpleName fallback:\n" + content);
    }

    @Test
    void superClassMatchingUsesQualifiedNameFirst() throws Exception {
        String json = """
                {
                  "types": [
                    {"qualifiedName":"com.example.Animal","simpleName":"Animal","kind":"class","superClass":null,
                     "executables":[],"fields":[],"abstractType":"false"},
                    {"qualifiedName":"com.example.Dog","simpleName":"Dog","kind":"class",
                     "superClass":"com.example.Animal","executables":[],"fields":[],"abstractType":"false"}
                  ]
                }""";
        Path jsonPath = tempDir.resolve("qualName.json");
        Files.writeString(jsonPath, json);
        Path aieOutput = tempDir.resolve("output.aie");
        mapper.map(jsonPath, aieOutput);
        String content = Files.readString(aieOutput);
        assertTrue(content.contains("classParent[Class1] = Class0"),
                "Dog should have Animal as classParent via qualifiedName match:\n" + content);
    }

    @Test
    void interfaceParentsMatchingUsesSimpleNameFallback() throws Exception {
        String json = """
                {
                  "types": [
                    {"qualifiedName":"animals.Bird","simpleName":"Bird","kind":"class","superClass":null,
                     "executables":[],"fields":[],"interfaces":["Flyable"],"abstractType":"false"},
                    {"qualifiedName":"traits.Flyable","simpleName":"Flyable","kind":"interface",
                     "superClass":null,"executables":[],"fields":[],"abstractType":"true"}
                  ]
                }""";
        Path jsonPath = tempDir.resolve("ifaceSimple.json");
        Files.writeString(jsonPath, json);
        Path aieOutput = tempDir.resolve("output.aie");
        mapper.map(jsonPath, aieOutput);
        String content = Files.readString(aieOutput);
        assertTrue(content.contains("interfaceParents[Class0] = {Interface1}"),
                "Bird should have Flyable in interfaceParents via simpleName fallback:\n" + content);
    }

    @Test
    void cppMultiInheritanceMapsExtraBasesAsInterfaceParents() throws Exception {
        String json = """
                {
                  "types": [
                    {"qualifiedName":"ns.A","simpleName":"A","kind":"class","superClass":null,
                     "executables":[],"fields":[],"abstractType":"false"},
                    {"qualifiedName":"ns.B","simpleName":"B","kind":"class","superClass":null,
                     "executables":[],"fields":[],"abstractType":"false"},
                    {"qualifiedName":"ns.C","simpleName":"C","kind":"class","superClass":"A",
                     "interfaces":["B"],"executables":[],"fields":[],"abstractType":"false"}
                  ]
                }""";
        Path jsonPath = tempDir.resolve("cppMulti.json");
        Files.writeString(jsonPath, json);
        Path aieOutput = tempDir.resolve("output.aie");
        mapper.map(jsonPath, aieOutput);
        String content = Files.readString(aieOutput);
        assertTrue(content.contains("classParent[Class2] = Class0"),
                "C should have A as classParent:\n" + content);
        assertTrue(content.contains("interfaceParents[Class2] = {Class1}"),
                "C should have B in interfaceParents (C++ multi-inheritance):\n" + content);
    }

    @Test
    void inheritedMethodsComputedFromBothClassAndInterfaceParents() throws Exception {
        String json = """
                {
                  "types": [
                    {"qualifiedName":"A","simpleName":"A","kind":"class","superClass":null,
                     "executables":[{"name":"foo","returnType":"void","visibility":"public",
                     "constructor":false,"staticExecutable":false,"abstractExecutable":false,
                     "parameters":[]}],"fields":[],"abstractType":"false"},
                    {"qualifiedName":"IB","simpleName":"IB","kind":"interface","superClass":null,
                     "executables":[{"name":"bar","returnType":"void","visibility":"public",
                     "constructor":false,"staticExecutable":false,"abstractExecutable":true,
                     "parameters":[]}],"fields":[],"abstractType":"true"},
                    {"qualifiedName":"C","simpleName":"C","kind":"class","superClass":"A",
                     "interfaces":["IB"],"executables":[],"fields":[],"abstractType":"false"}
                  ]
                }""";
        Path jsonPath = tempDir.resolve("inherited.json");
        Files.writeString(jsonPath, json);
        Path aieOutput = tempDir.resolve("output.aie");
        mapper.map(jsonPath, aieOutput);
        String content = Files.readString(aieOutput);
        assertTrue(content.contains("localMethods[Class0] = {Method0}"),
                "A should have local method foo");
        assertTrue(content.contains("localMethods[Interface1] = {Method1}"),
                "IB should have local abstract method bar");
        assertTrue(content.contains("inheritedMethods[Class2]"),
                "C should inherit methods from both A and IB:\n" + content);
        assertTrue(content.contains("ImplementationBinding"),
                "Non-abstract method foo should get ImplementationBinding");
    }

    @Test
    void threeLevelInheritanceChainWithBindings() throws Exception {
        String json = """
                {
                  "types": [
                    {"qualifiedName":"A","simpleName":"A","kind":"class","superClass":null,
                     "executables":[{"name":"m","returnType":"int","visibility":"public",
                     "constructor":false,"staticExecutable":false,"abstractExecutable":false,
                     "parameters":[]}],"fields":[],"abstractType":"false"},
                    {"qualifiedName":"B","simpleName":"B","kind":"class","superClass":"A",
                     "executables":[],"fields":[],"abstractType":"false"},
                    {"qualifiedName":"C","simpleName":"C","kind":"class","superClass":"B",
                     "executables":[],"fields":[],"abstractType":"false"}
                  ]
                }""";
        Path jsonPath = tempDir.resolve("threeLevel.json");
        Files.writeString(jsonPath, json);
        Path aieOutput = tempDir.resolve("output.aie");
        mapper.map(jsonPath, aieOutput);
        String content = Files.readString(aieOutput);
        assertTrue(content.contains("classParent[Class1] = Class0"));
        assertTrue(content.contains("classParent[Class2] = Class1"));
        assertTrue(content.contains("inheritedMethods[Class1]"),
                "B should inherit m from A");
        assertTrue(content.contains("inheritedMethods[Class2]"),
                "C should inherit m from A via B");
        assertTrue(content.contains("ImplementationBinding"),
                "m should have ImplementationBinding in A");
    }

    @Test
    void abstractMethodsDoNotGetImplementationBinding() throws Exception {
        String json = """
                {
                  "types": [
                    {"qualifiedName":"Base","simpleName":"Base","kind":"class","superClass":null,
                     "executables":[{"name":"abstractMethod","returnType":"void","visibility":"public",
                     "constructor":false,"staticExecutable":false,"abstractExecutable":true,
                     "parameters":[]}],"fields":[],"abstractType":"true"}
                  ]
                }""";
        Path jsonPath = tempDir.resolve("abstractMethod.json");
        Files.writeString(jsonPath, json);
        Path aieOutput = tempDir.resolve("output.aie");
        mapper.map(jsonPath, aieOutput);
        String content = Files.readString(aieOutput);
        assertTrue(content.contains("isAbstract = Yes"));
        assertFalse(content.contains("MethodBody0") && content.contains("ImplementationBinding0"),
                "Abstract methods should NOT get ImplementationBinding:\n" + content);
    }

    @Test
    void nonAbstractClassWithInheritedBindingIsValid() throws Exception {
        String json = """
                {
                  "types": [
                    {"qualifiedName":"A","simpleName":"A","kind":"class","superClass":null,
                     "executables":[{"name":"m","returnType":"void","visibility":"public",
                     "constructor":false,"staticExecutable":false,"abstractExecutable":false,
                     "parameters":[]}],"fields":[],"abstractType":"false"},
                    {"qualifiedName":"B","simpleName":"B","kind":"class","superClass":"A",
                     "executables":[],"fields":[],"abstractType":"false"}
                  ]
                }""";
        Path jsonPath = tempDir.resolve("inheritedBinding.json");
        Files.writeString(jsonPath, json);
        Path aieOutput = tempDir.resolve("output.aie");
        mapper.map(jsonPath, aieOutput);
        String content = Files.readString(aieOutput);
        assertTrue(content.contains("classParent[Class1] = Class0"));
        assertTrue(content.contains("ImplementationBinding"),
                "A should create ImplementationBinding for non-abstract method m");
        assertTrue(content.contains("inheritedMethods[Class1] = {Method0}"),
                "B should inherit m from A");
    }

    // ================================================================
    // Helpers
    // ================================================================

    private static Path resourcePath(String name) throws IOException {
        java.net.URL url = JsonToAieMapperTest.class.getResource("/" + name);
        if (url == null) throw new IOException("Resource not found: " + name);
        try {
            return Path.of(url.toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
    }
}
