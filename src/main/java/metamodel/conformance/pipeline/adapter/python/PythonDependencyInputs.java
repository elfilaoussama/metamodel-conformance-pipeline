package metamodel.conformance.pipeline.adapter.python;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Declared Python environment facts read from a resolver manifest: the locked
 * interpreter version, the installed distributions with a deterministic
 * content digest, and their top-level import names. Unknown row kinds fail
 * closed and the observer never guesses distributions.
 */
public final class PythonDependencyInputs {
    private final String interpreterVersion;
    private final Map<String, Distribution> distributionsByName;
    private final Map<String, Distribution> uniqueDistributionByTopLevelModule;
    private final Set<String> ambiguousTopLevelModules;

    private PythonDependencyInputs(
            String interpreterVersion,
            Map<String, Distribution> distributionsByName,
            Map<String, Distribution> uniqueDistributionByTopLevelModule,
            Set<String> ambiguousTopLevelModules) {
        this.interpreterVersion = interpreterVersion;
        this.distributionsByName = Map.copyOf(distributionsByName);
        this.uniqueDistributionByTopLevelModule = Map.copyOf(uniqueDistributionByTopLevelModule);
        this.ambiguousTopLevelModules = Set.copyOf(ambiguousTopLevelModules);
    }

    public static PythonDependencyInputs none() {
        return new PythonDependencyInputs(null, Map.of(), Map.of(), Set.of());
    }

    public static PythonDependencyInputs fromManifest(Path manifest) {
        Map<String, Distribution> distributionsByName = new LinkedHashMap<>();
        Map<String, Distribution> uniqueDistributionByTopLevelModule = new LinkedHashMap<>();
        Set<String> ambiguousTopLevelModules = new LinkedHashSet<>();
        String interpreterVersion = null;
        try {
            List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
            String contextId = null;
            int lineNumber = 0;
            for (String line : lines) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String[] fields = line.split("\\t", -1);
                String kind = fields[0];
                try {
                    switch (kind) {
                        case "context" -> {
                            requireFields(fields, 3);
                            if (fields[1].isBlank()) {
                                throw new IllegalArgumentException("context id must not be blank");
                            }
                            String version = token(fields[2], "interpreter version");
                            if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
                                throw new IllegalArgumentException("interpreter version must be major.minor.patch");
                            }
                            if (interpreterVersion != null && !interpreterVersion.equals(version)) {
                                throw new IllegalArgumentException("conflicting declared interpreter versions");
                            }
                            interpreterVersion = version;
                            if (contextId == null) {
                                contextId = fields[1];
                            } else if (!contextId.equals(fields[1])) {
                                throw new IllegalArgumentException(
                                        "more than one declared environment context: " + fields[1]);
                            }
                        }
                        case "distribution" -> {
                            requireFields(fields, 5);
                            requireContext(fields[1], contextId);
                            String name = token(fields[2], "distribution name");
                            String version = token(fields[3], "distribution version");
                            String digest = token(fields[4], "distribution digest");
                            if (!digest.matches("[0-9a-f]{64}")) {
                                throw new IllegalArgumentException("distribution digest must be 64 hex digits");
                            }
                            if (distributionsByName.putIfAbsent(name, new Distribution(name, version, digest)) != null) {
                                throw new IllegalArgumentException("duplicate distribution row: " + name);
                            }
                        }
                        case "module" -> {
                            requireFields(fields, 4);
                            requireContext(fields[1], contextId);
                            String module = token(fields[2], "top-level module");
                            String name = token(fields[3], "distribution name");
                            Distribution distribution = distributionsByName.get(name);
                            if (distribution == null) {
                                throw new IllegalArgumentException(
                                        "module references an unobserved distribution: " + name);
                            }
                            // Python namespace packages legitimately share a
                            // top-level module between distributions. A shared
                            // module cannot be attributed to one distribution,
                            // so it stops being attributable at all instead of
                            // being guessed; every other module still is.
                            Distribution previous =
                                    uniqueDistributionByTopLevelModule.putIfAbsent(module, distribution);
                            if (previous != null && !previous.equals(distribution)) {
                                uniqueDistributionByTopLevelModule.remove(module);
                                ambiguousTopLevelModules.add(module);
                            }
                        }
                        default -> throw new IllegalArgumentException(
                                "unsupported Python manifest row kind: " + kind);
                    }
                } catch (RuntimeException failure) {
                    throw new IllegalArgumentException(
                            "invalid Python manifest row " + lineNumber + ": " + failure.getMessage(), failure);
                }
            }
            if (contextId == null && !distributionsByName.isEmpty()) {
                throw new IllegalArgumentException("Python manifest has distributions but no context");
            }
            ambiguousTopLevelModules.forEach(uniqueDistributionByTopLevelModule::remove);
        } catch (IOException failure) {
            throw new UncheckedIOException("Python dependency manifest is not readable: " + manifest, failure);
        }
        return new PythonDependencyInputs(
                interpreterVersion, distributionsByName, uniqueDistributionByTopLevelModule, ambiguousTopLevelModules);
    }

    /** Declared interpreter identity is an exact provider fact, not a version range. */
    public void requireInterpreterVersion(String actualVersion) {
        if (interpreterVersion != null && !interpreterVersion.equals(actualVersion)) {
            throw new IllegalArgumentException("Python interpreter version mismatch: declared "
                    + interpreterVersion + ", observed " + actualVersion);
        }
    }

    private static void requireContext(String rowContext, String contextId) {
        if (contextId == null || !contextId.equals(rowContext)) {
            throw new IllegalArgumentException("row does not reference the declared environment context: " + rowContext);
        }
    }

    public boolean isEmpty() {
        return distributionsByName.isEmpty();
    }

    public Collection<Distribution> distributions() {
        return distributionsByName.values();
    }

    /** Deterministic normalized measurement configuration, not runtime closure. */
    public ContextIdentity contextIdentity() {
        return new ContextIdentity(interpreterVersion,
                distributionsByName.values().stream().sorted(java.util.Comparator.comparing(Distribution::name)).toList(),
                uniqueDistributionByTopLevelModule.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(entry -> new ModuleBinding(entry.getKey(), entry.getValue().name())).toList(),
                ambiguousTopLevelModules.stream().sorted().toList());
    }

    public record ContextIdentity(String declaredInterpreterVersion, List<Distribution> distributions,
                                  List<ModuleBinding> moduleBindings, List<String> ambiguousModules) { }
    public record ModuleBinding(String module, String distribution) { }

    /**
     * The single distribution declaring a top-level import name, or null when
     * the name is undeclared or shared by several distributions (a namespace
     * package): a shared name is never attributed, it is left unresolved.
     */
    public Distribution distributionForTopLevelModule(String module) {
        return uniqueDistributionByTopLevelModule.get(module);
    }

    public boolean isAmbiguousTopLevelModule(String module) {
        return ambiguousTopLevelModules.contains(module);
    }

    public record Distribution(String name, String version, String digest) {
    }

    private static void requireFields(String[] fields, int count) {
        if (fields.length != count) {
            throw new IllegalArgumentException("row requires " + count + " fields");
        }
    }

    private static String token(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }
}
