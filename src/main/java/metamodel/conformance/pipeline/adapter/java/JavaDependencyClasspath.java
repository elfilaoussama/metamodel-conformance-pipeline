package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.SourceUnit;
import metamodel.conformance.pipeline.util.Hashing;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Validates and fingerprints a Java dependency boundary while preserving source-set scope. */
final class JavaDependencyClasspath {
    private static final String MULTI_RELEASE_PREFIX = "META-INF/versions/";

    private JavaDependencyClasspath() {
    }

    static Result resolve(List<Path> requested) throws ObservationException {
        if (!(requested instanceof JavaDependencyInputs)) {
            validateArchiveOnlyCompatibilityInput(requested);
        }
        JavaDependencyInputs inputs = JavaDependencyInputs.global(requested);
        Map<String, Entry> entriesByUnitPath = new LinkedHashMap<>();
        for (Path candidate : inputs.allPaths()) {
            try {
                Path normalized = candidate.toAbsolutePath().normalize();
                if (Files.isSymbolicLink(normalized)
                        || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ObservationException("dependency archive is not a regular file: " + candidate);
                }
                if (!normalized.getFileName().toString().endsWith(".jar")) {
                    throw new ObservationException("dependency archive must be a .jar file: " + candidate);
                }
                Path real = normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
                Set<String> typeNames = inspectTypeNames(real);
                String digest = Hashing.sha256(real);
                SourceUnit unit = new SourceUnit(
                        Language.JAVA_ARCHIVE,
                        "dependencies/" + digest + "/" + real.getFileName(),
                        digest);
                entriesByUnitPath.putIfAbsent(unit.path(), new Entry(real, unit, typeNames));
            } catch (ObservationException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ObservationException(
                        "failed to fingerprint dependency archive " + candidate + ": " + exception.getMessage(),
                        exception);
            }
        }
        return new Result(new ArrayList<>(entriesByUnitPath.values()), inputs);
    }


    private static void validateArchiveOnlyCompatibilityInput(List<Path> requested)
            throws ObservationException {
        for (Path candidate : requested == null ? List.<Path>of() : requested) {
            if (candidate == null) {
                throw new ObservationException("dependency archive must not be null");
            }
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(normalized)
                    || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new ObservationException("dependency archive is not a regular file: " + candidate);
            }
            if (normalized.getFileName() == null
                    || !normalized.getFileName().toString().endsWith(".jar")) {
                throw new ObservationException("dependency archive must be a .jar file: " + candidate);
            }
        }
    }

    private static Set<String> inspectTypeNames(Path archive) throws Exception {
        Set<String> names = new TreeSet<>();
        try (JarFile jar = new JarFile(archive.toFile(), true)) {
            for (JarEntry entry : jar.stream().filter(item -> !item.isDirectory()).toList()) {
                String logical = logicalClassEntry(entry.getName());
                if (logical == null) {
                    continue;
                }
                String binaryName = logical.substring(0, logical.length() - ".class".length())
                        .replace('/', '.');
                names.add(binaryName);
                names.add(binaryName.replace('$', '.'));
            }
        }
        return Set.copyOf(names);
    }

    private static String logicalClassEntry(String name) {
        if (name == null || !name.endsWith(".class")) {
            return null;
        }
        String logical = name;
        if (logical.startsWith(MULTI_RELEASE_PREFIX)) {
            String remainder = logical.substring(MULTI_RELEASE_PREFIX.length());
            int separator = remainder.indexOf('/');
            if (separator <= 0 || !remainder.substring(0, separator).chars().allMatch(Character::isDigit)) {
                return null;
            }
            logical = remainder.substring(separator + 1);
        }
        if (logical.equals("module-info.class") || logical.endsWith("/module-info.class")
                || logical.equals("package-info.class") || logical.endsWith("/package-info.class")) {
            return null;
        }
        return logical;
    }

    record Entry(Path path, SourceUnit unit, Set<String> typeNames) {
        Entry {
            typeNames = Set.copyOf(typeNames);
        }

        boolean containsType(String qualifiedName) {
            return qualifiedName != null && typeNames.contains(qualifiedName);
        }
    }

    record Result(List<Entry> entries, JavaDependencyInputs inputs) {
        Result {
            entries = List.copyOf(entries);
            inputs = inputs == null ? JavaDependencyInputs.none() : inputs;
            if (entries.stream().map(entry -> entry.unit().path()).distinct().count() != entries.size()) {
                throw new IllegalArgumentException("dependency archive identity collision");
            }
        }

        /**
         * Compatibility view consumed by existing observers. If inputs are source-set scoped,
         * the structured list object is deliberately retained so javac observers can
         * select exact compilation contexts rather than flattening the union.
         */
        List<Path> paths() {
            return inputs;
        }

        List<Path> allPaths() {
            return entries.stream().map(Entry::path).toList();
        }

        List<SourceUnit> units() {
            return entries.stream().map(Entry::unit)
                    .sorted(Comparator.comparing(SourceUnit::path)).toList();
        }

        Entry ownerOfType(String qualifiedName) {
            for (Entry entry : entries) {
                if (entry.containsType(qualifiedName)) {
                    return entry;
                }
            }
            return null;
        }

        Entry ownerOfType(String sourceSet, String qualifiedName) {
            Set<Path> allowed = Set.copyOf(inputs.pathsForSourceSet(sourceSet));
            for (Entry entry : entries) {
                if (allowed.contains(entry.path()) && entry.containsType(qualifiedName)) {
                    return entry;
                }
            }
            return null;
        }

        void verifyUnchanged() throws ObservationException {
            for (Entry entry : entries) {
                try {
                    String actual = Hashing.sha256(entry.path());
                    if (!actual.equals(entry.unit().sha256())) {
                        throw new ObservationException(
                                "dependency archive changed during observation: " + entry.path());
                    }
                } catch (ObservationException exception) {
                    throw exception;
                } catch (Exception exception) {
                    throw new ObservationException(
                            "failed to revalidate dependency archive " + entry.path(), exception);
                }
            }
        }
    }
}
