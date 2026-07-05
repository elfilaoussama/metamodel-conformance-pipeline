package com.verification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InvariantCheckerTest {

    private final InvariantChecker checker = new InvariantChecker(false, false);

    @Test
    void emptyAtomsReturnsSat() {
        String aie = "Root = { classes = {} }";
        VerificationReport report = checker.check(aie, "");
        assertEquals("SAT", report.getResult());
        assertTrue(report.getViolations().isEmpty());
    }

    @Test
    void singleClassNoViolationsReturnsSat() throws Exception {
        String aie = loadResource("valid.aie");
        VerificationReport report = checker.check(aie, "");
        assertEquals("SAT", report.getResult());
        assertTrue(report.getViolations().isEmpty());
    }

    @Test
    void duplicateTypeNamesReportsUnsat() throws Exception {
        String aie = loadResource("duplicate-names.aie");
        VerificationReport report = checker.check(aie, "");
        assertEquals("UNSAT", report.getResult());
        assertFalse(report.getViolations().isEmpty());
        assertTrue(report.getViolations().stream()
                .anyMatch(v -> "IdentifierIntegrity".equals(v.getInvariantName())));
    }

    @Test
    void cyclicInheritanceReportsUnsat() throws Exception {
        String aie = loadResource("cyclic-inheritance.aie");
        VerificationReport report = checker.check(aie, "");
        assertEquals("UNSAT", report.getResult());
        assertFalse(report.getViolations().isEmpty());
        assertTrue(report.getViolations().stream()
                .anyMatch(v -> "AcyclicGeneralization".equals(v.getInvariantName())));
    }

    @Test
    void abstractMethodInNonAbstractClassReportsUnsat() throws Exception {
        String aie = loadResource("abstract-method-violation.aie");
        VerificationReport report = checker.check(aie, "");
        assertEquals("UNSAT", report.getResult());
        assertFalse(report.getViolations().isEmpty());
        assertTrue(report.getViolations().stream()
                .anyMatch(v -> "AbstractionPolicy".equals(v.getInvariantName())));
    }

    @Test
    void malformedAieReturnsSatForEmptyInstance() {
        String aie = "this is not valid AIE content";
        VerificationReport report = checker.check(aie, "");
        assertEquals("SAT", report.getResult());
        assertTrue(report.getViolations().isEmpty());
    }

    @Test
    void interfaceWithNonAbstractMethodReportsUnsat() throws Exception {
        String aie = "Root = {\n" +
                "  classes = {Class0}\n" +
                "  Class0 = { name = \"MyInterface\", abstract = \"true\", kind = \"interface\" }\n" +
                "  Method0 = { name = \"foo\", abstract = \"false\" }\n" +
                "  classParent[Class0] = null\n" +
                "  classMethods[Class0] = {Method0}\n" +
                "}\n";
        VerificationReport report = checker.check(aie, "");
        assertEquals("UNSAT", report.getResult());
        assertTrue(report.getViolations().stream()
                .anyMatch(v -> "InterfacePolicy".equals(v.getInvariantName())));
    }

    @Test
    void staticAbstractMethodReportsUnsat() throws Exception {
        String aie = "Root = {\n" +
                "  classes = {Class0}\n" +
                "  Class0 = { name = \"MyClass\", abstract = \"false\", kind = \"class\" }\n" +
                "  Method0 = { name = \"foo\", abstract = \"true\", static = \"true\" }\n" +
                "  classParent[Class0] = null\n" +
                "  classMethods[Class0] = {Method0}\n" +
                "}\n";
        VerificationReport report = checker.check(aie, "");
        assertEquals("UNSAT", report.getResult());
        assertTrue(report.getViolations().stream()
                .anyMatch(v -> "StaticMethodPolicy".equals(v.getInvariantName())));
    }

    @Test
    void interfaceWithFieldReportsUnsat() throws Exception {
        String aie = "Root = {\n" +
                "  classes = {Class0}\n" +
                "  Class0 = { name = \"MyInterface\", abstract = \"true\", kind = \"interface\" }\n" +
                "  Field0 = { name = \"x\", visibility = \"public\", static = \"false\" }\n" +
                "  classParent[Class0] = null\n" +
                "  classAttributes[Class0] = {Field0}\n" +
                "}\n";
        VerificationReport report = checker.check(aie, "");
        assertEquals("UNSAT", report.getResult());
        assertTrue(report.getViolations().stream()
                .anyMatch(v -> "InterfacePolicy".equals(v.getInvariantName())));
    }

    @ParameterizedTest
    @CsvSource({
        "valid.aie, SAT",
        "duplicate-names.aie, UNSAT",
        "cyclic-inheritance.aie, UNSAT",
        "abstract-method-violation.aie, UNSAT"
    })
    void endToEndWithResourceFiles(String resource, String expectedResult) throws Exception {
        String aie = loadResource(resource);
        VerificationReport report = checker.check(aie, "");
        assertEquals(expectedResult, report.getResult());
    }

    private static String loadResource(String name) throws IOException, URISyntaxException {
        URL url = InvariantCheckerTest.class.getResource("/" + name);
        if (url == null) throw new IOException("Resource not found: " + name);
        return Files.readString(Path.of(url.toURI()));
    }
}
