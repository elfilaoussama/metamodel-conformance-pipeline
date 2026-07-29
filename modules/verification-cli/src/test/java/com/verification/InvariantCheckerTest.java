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
        String aie = "Root = { classifiers = {} }";
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
        assertTrue(report.getViolations().stream()
                .anyMatch(v -> "IdentifierIntegrity".equals(v.getInvariantName())));
    }

    @Test
    void cyclicInheritanceReportsUnsat() throws Exception {
        String aie = loadResource("cyclic-inheritance.aie");
        VerificationReport report = checker.check(aie, "");
        assertEquals("UNSAT", report.getResult());
        assertTrue(report.getViolations().stream()
                .anyMatch(v -> "AcyclicGeneralization".equals(v.getInvariantName())));
    }

    @Test
    void abstractMethodInNonAbstractClassReportsUnsat() throws Exception {
        String aie = loadResource("abstract-method-violation.aie");
        VerificationReport report = checker.check(aie, "");
        assertEquals("UNSAT", report.getResult());
        assertTrue(report.getViolations().stream()
                .anyMatch(v -> "AbstractionPolicy".equals(v.getInvariantName())));
    }

    @Test
    void malformedAieReturnsSatForEmptyInstance() {
        String aie = "this is not valid AIE content";
        VerificationReport report = checker.check(aie, "");
        assertEquals("SAT", report.getResult());
    }

    @Test
    void interfaceWithNonAbstractMethodReportsUnsat() {
        String aie = "Root = {\n" +
                "  classifiers = {Interface0}\n" +
                "  Interface0 = { name = \"MyInterface\", isAbstract = \"Yes\", cid = ClassifierID0 }\n" +
                "  Method0 = { memberName = \"foo\", returnType = \"void\", isAbstract = \"No\", visibility = Pub, scope = Instance, mid = MethodID0, paramTypes = {} }\n" +
                "  classParent[Interface0] = null\n" +
                "  localMethods[Interface0] = {Method0}\n" +
                "}\n";
        VerificationReport report = checker.check(aie, "");
        assertEquals("UNSAT", report.getResult());
        assertTrue(report.getViolations().stream()
                .anyMatch(v -> "InterfacePolicy".equals(v.getInvariantName())));
    }

    @Test
    void staticAbstractMethodReportsUnsat() throws Exception {
        String aie = loadResource("static-abstract-method.aie");
        VerificationReport report = checker.check(aie, "");
        assertEquals("UNSAT", report.getResult());
        assertTrue(report.getViolations().stream()
                .anyMatch(v -> "StaticMethodPolicy".equals(v.getInvariantName())));
    }

    @Test
    void interfaceWithInstanceAttributeReportsUnsat() throws Exception {
        String aie = loadResource("interface-instance-attribute.aie");
        VerificationReport report = checker.check(aie, "");
        assertEquals("UNSAT", report.getResult());
        assertTrue(report.getViolations().stream()
                .anyMatch(v -> "InterfacePolicy".equals(v.getInvariantName())));
    }

    @Test
    void interfaceWithClassParentReportsUnsat() throws Exception {
        String aie = loadResource("interface-class-parent.aie");
        VerificationReport report = checker.check(aie, "");
        assertEquals("UNSAT", report.getResult());
        assertTrue(report.getViolations().stream()
                .anyMatch(v -> "GeneralizationKindPolicy".equals(v.getInvariantName())));
    }

    @Test
    void sharedMethodOwnershipReportsUnsat() throws Exception {
        String aie = loadResource("shared-method-ownership.aie");
        VerificationReport report = checker.check(aie, "");
        assertEquals("UNSAT", report.getResult());
        assertTrue(report.getViolations().stream()
                .anyMatch(v -> "ExclusiveDeclarationOwnership".equals(v.getInvariantName())));
    }

    @Test
    void privateMethodInheritedReportsUnsat() throws Exception {
        String aie = loadResource("private-inherited.aie");
        VerificationReport report = checker.check(aie, "");
        assertEquals("UNSAT", report.getResult());
        assertTrue(report.getViolations().stream()
                .anyMatch(v -> "InheritedMemberDerivation".equals(v.getInvariantName())));
    }

    @Test
    void localInheritedOverlapReportsUnsat() throws Exception {
        String aie = loadResource("local-inherited-overlap.aie");
        VerificationReport report = checker.check(aie, "");
        assertEquals("UNSAT", report.getResult());
        assertTrue(report.getViolations().stream()
                .anyMatch(v -> "LocalInheritedSeparation".equals(v.getInvariantName())));
    }

    @Test
    void phantomBindingTargetReportsUnsat() throws Exception {
        String aie = loadResource("phantom-binding.aie");
        VerificationReport report = checker.check(aie, "");
        assertEquals("UNSAT", report.getResult());
        assertTrue(report.getViolations().stream()
                .anyMatch(v -> "ImplementationBindingPolicy".equals(v.getInvariantName())));
    }

    @Test
    void duplicateMethodNameInClassReportsUnsat() throws Exception {
        String aie = loadResource("duplicate-method-key.aie");
        VerificationReport report = checker.check(aie, "");
        assertEquals("UNSAT", report.getResult());
        assertTrue(report.getViolations().stream()
                .anyMatch(v -> "LocalMethodNamespace".equals(v.getInvariantName())));
    }

    @Test
    void inheritedMethodConflictReportsUnsat() throws Exception {
        String aie = loadResource("inherited-method-conflict.aie");
        VerificationReport report = checker.check(aie, "");
        assertEquals("UNSAT", report.getResult());
        assertTrue(report.getViolations().stream()
                .anyMatch(v -> "InheritedConflictPolicy".equals(v.getInvariantName())));
    }

    @ParameterizedTest
    @CsvSource({
        "valid.aie, SAT",
        "duplicate-names.aie, UNSAT",
        "cyclic-inheritance.aie, UNSAT",
        "abstract-method-violation.aie, UNSAT",
        "static-abstract-method.aie, UNSAT",
        "interface-class-parent.aie, UNSAT",
        "shared-method-ownership.aie, UNSAT",
        "private-inherited.aie, UNSAT",
        "local-inherited-overlap.aie, UNSAT",
        "phantom-binding.aie, UNSAT",
        "duplicate-method-key.aie, UNSAT",
        "interface-instance-attribute.aie, UNSAT",
        "inherited-method-conflict.aie, UNSAT"
    })
    void endToEndWithResourceFiles(String resource, String expectedResult) throws Exception {
        String aie = loadResource(resource);
        VerificationReport report = checker.check(aie, "");
        assertEquals(expectedResult, report.getResult());
    }

    @Test
    void inheritedNonAbstractMethodWithBindingInParentIsSat() throws Exception {
        String aie = "Root = {\n" +
                "  classifiers = {Class0, Class1}\n" +
                "  Class0 = { name = \"Parent\", isAbstract = \"No\", cid = ClassifierID0 }\n" +
                "  Class1 = { name = \"Child\", isAbstract = \"No\", cid = ClassifierID1 }\n" +
                "  Method0 = { memberName = \"foo\", returnType = \"void\", isAbstract = \"No\", visibility = Pub, scope = Instance, mid = MethodID0, paramTypes = {} }\n" +
                "  MethodBody0 = {}\n" +
                "  ImplementationBinding0 = { implementer = Class0, target = Method0, body = MethodBody0 }\n" +
                "  ClassifierID0 = {}\n  ClassifierID1 = {}\n  MethodID0 = {}\n" +
                "  classParent[Class0] = null\n" +
                "  classParent[Class1] = Class0\n" +
                "  localMethods[Class0] = {Method0}\n" +
                "  inheritedMethods[Class1] = {Method0}\n" +
                "}";
        VerificationReport report = checker.check(aie, "");
        assertEquals("SAT", report.getResult(),
                "Child inheriting non-abstract method from Parent with binding should be SAT, got: "
                        + report.getViolations().stream().map(VerificationReport.Violation::getDescription).toList());
    }

    @Test
    void threeLevelInheritanceBindingVisibilityIsSat() throws Exception {
        String aie = "Root = {\n" +
                "  classifiers = {Class0, Class1, Class2}\n" +
                "  Class0 = { name = \"Grandparent\", isAbstract = \"No\", cid = ClassifierID0 }\n" +
                "  Class1 = { name = \"Parent\", isAbstract = \"No\", cid = ClassifierID1 }\n" +
                "  Class2 = { name = \"Child\", isAbstract = \"No\", cid = ClassifierID2 }\n" +
                "  Method0 = { memberName = \"foo\", returnType = \"void\", isAbstract = \"No\", visibility = Pub, scope = Instance, mid = MethodID0, paramTypes = {} }\n" +
                "  MethodBody0 = {}\n" +
                "  ImplementationBinding0 = { implementer = Class0, target = Method0, body = MethodBody0 }\n" +
                "  ClassifierID0 = {}\n  ClassifierID1 = {}\n  ClassifierID2 = {}\n  MethodID0 = {}\n" +
                "  classParent[Class0] = null\n" +
                "  classParent[Class1] = Class0\n" +
                "  classParent[Class2] = Class1\n" +
                "  localMethods[Class0] = {Method0}\n" +
                "  inheritedMethods[Class1] = {Method0}\n" +
                "  inheritedMethods[Class2] = {Method0}\n" +
                "}";
        VerificationReport report = checker.check(aie, "");
        assertEquals("SAT", report.getResult(),
                "Grandchild inheriting from grandparent via parent should be SAT, got: "
                        + report.getViolations().stream().map(VerificationReport.Violation::getDescription).toList());
    }

    @Test
    void siblingBindingNotFoundInAncestorChainIsUnsat() throws Exception {
        String aie = "Root = {\n" +
                "  classifiers = {Class0, Class1, Class2}\n" +
                "  Class0 = { name = \"Grandparent\", isAbstract = \"No\", cid = ClassifierID0 }\n" +
                "  Class1 = { name = \"Parent\", isAbstract = \"No\", cid = ClassifierID1 }\n" +
                "  Class2 = { name = \"Child\", isAbstract = \"No\", cid = ClassifierID2 }\n" +
                "  Method0 = { memberName = \"foo\", returnType = \"void\", isAbstract = \"No\", visibility = Pub, scope = Instance, mid = MethodID0, paramTypes = {} }\n" +
                "  MethodBody0 = {}\n" +
                "  ImplementationBinding0 = { implementer = Class1, target = Method0, body = MethodBody0 }\n" +
                "  ClassifierID0 = {}\n  ClassifierID1 = {}\n  ClassifierID2 = {}\n  MethodID0 = {}\n" +
                "  classParent[Class0] = null\n" +
                "  classParent[Class2] = Class0\n" +
                "  localMethods[Class1] = {Method0}\n" +
                "  inheritedMethods[Class2] = {Method0}\n" +
                "}";
        VerificationReport report = checker.check(aie, "");
        assertEquals("UNSAT", report.getResult(),
                "Child inheriting method from Parent not in ancestor chain (bindings invisible), should be UNSAT");
        assertTrue(report.getViolations().stream()
                .anyMatch(v -> v.getDescription().contains("no ImplementationBinding visible in ancestor chain")),
                "Should flag invisible binding, got: " + report.getViolations());
    }

    private static String loadResource(String name) throws IOException, URISyntaxException {
        URL url = InvariantCheckerTest.class.getResource("/" + name);
        if (url == null) throw new IOException("Resource not found: " + name);
        return Files.readString(Path.of(url.toURI()));
    }
}
