package metamodel.conformance.pipeline.invariant;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvariantArchitectureTest {
    private static final Pattern VIOLATION_FUNCTION = Pattern.compile(
            "(?m)^fun\\s+([A-Za-z][A-Za-z0-9_]*Violations)\\b");
    private static final Pattern NUMBERED_RULE = Pattern.compile("\\b(?:O-?0[1-9]|Obligation)\\b");

    @Test
    void registryAndAlloyViolationFunctionsAreExactlySynchronized() throws Exception {
        Set<String> registered = new HashSet<>();
        InvariantRegistry.load().all().forEach(item -> registered.add(item.witnessFunction()));
        var resource = getClass().getResourceAsStream("/alloy/invariants.als");
        assertNotNull(resource);
        String alloy;
        try (resource) {
            alloy = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        Set<String> formal = new HashSet<>();
        Matcher matcher = VIOLATION_FUNCTION.matcher(alloy);
        while (matcher.find()) {
            assertTrue(formal.add(matcher.group(1)), "duplicate Alloy witness function");
        }

        assertEquals(registered, formal);
    }

    @Test
    void productionJavaContainsNoInvariantIdsOrNumberedRuleBranches() throws Exception {
        Path sourceRoot = Path.of("src/main/java");
        assertTrue(Files.isDirectory(sourceRoot), "production source tree is required for architecture audit");
        StringBuilder production = new StringBuilder();
        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                production.append(Files.readString(file)).append('\n');
            }
        }
        String java = production.toString();
        for (InvariantDefinition invariant : InvariantRegistry.load().all()) {
            assertFalse(java.contains(invariant.id()),
                    "production Java embeds invariant id " + invariant.id());
        }
        assertFalse(NUMBERED_RULE.matcher(java).find(),
                "production Java contains numbered-rule or obligation terminology");
    }
}
