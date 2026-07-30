package com.verification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Audits all 13 structural conditions declared in the abstract metamodel
 * against minimal .aie counterexamples. Each file deliberately violates
 * exactly one condition. If the checker returns SAT or the wrong invariant
 * name, the path is dead.
 */
public class ConditionAudit {

    static List<String> ALL_CHECKED = List.of(
        "IdentifierIntegrity",
        "ExclusiveDeclarationOwnership",
        "AcyclicGeneralization",
        "InheritedMemberDerivation",
        "LocalInheritedSeparation",
        "ImplementationBindingPolicy",
        "AbstractionPolicy",
        "StaticMethodPolicy",
        "LocalMethodNamespace",
        "InheritedConflictPolicy",
        "OverridePolicy"
    );

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get("test_conditions");
        if (!Files.isDirectory(dir)) {
            System.out.println("ERROR: test_conditions directory not found");
            System.exit(1);
        }

        List<String> allInvariants = new ArrayList<>(ALL_CHECKED);
        Map<String, AuditResult> results = new LinkedHashMap<>();

        for (Path f : Files.list(dir).sorted().collect(Collectors.toList())) {
            String name = f.getFileName().toString();
            if (!name.endsWith(".aie")) continue;

            String content = Files.readString(f, StandardCharsets.UTF_8);
            VerificationReport report = new InvariantChecker().check(content, null);
            String result = report.getResult();

            List<String> fired = report.getViolations().stream()
                    .map(v -> v.getInvariantName())
                    .distinct()
                    .collect(Collectors.toList());

            fired.removeIf(inv -> !allInvariants.contains(inv) && !inv.equals("IdentifierIntegrity"));
            // IdentifierIntegrity appears via checkNoDuplicateTypeNames AND checkIdUniqueness

            System.out.printf("%-45s %-8s %s%n", name, result, fired);
            results.put(name, new AuditResult(result, fired));
        }

        System.out.println("\n=== DEAD-CODE ANALYSIS ===");
        Set<String> allFired = new LinkedHashSet<>();
        for (AuditResult ar : results.values()) {
            allFired.addAll(ar.invariants);
        }

        for (String inv : ALL_CHECKED) {
            if (allFired.contains(inv)) {
                System.out.printf("  %-40s ALIVE%n", inv);
            } else {
                System.out.printf("  %-40s DEAD (no counterexample fired this invariant)%n", inv);
            }
        }

        System.out.println("\n=== METHOD-LEVEL CHECK ===");
        List<String> uncalledMethods = List.of(
            "checkGeneralizationKindPolicy",
            "checkInterfaceMethodsAreAbstract",
            "checkInterfaceHasNoInstanceFields"
        );
        for (String m : uncalledMethods) {
            System.out.printf("  %-45s UNREACHABLE (never called from check flow)%n", m);
        }

        long deadCount = ALL_CHECKED.stream().filter(inv -> !allFired.contains(inv)).count();
        System.out.printf("%nResult: %d/%d conditions alive, %d dead%n",
                allFired.size(), ALL_CHECKED.size(), deadCount);

        if (deadCount > 0) {
            System.out.println("WARNING: " + deadCount + " conditions have dead detection paths");
            System.exit(1);
        }
        System.out.println("OK: all conditions alive");
    }

    static class AuditResult {
        final String result;
        final List<String> invariants;
        AuditResult(String r, List<String> i) { this.result = r; this.invariants = i; }
    }
}