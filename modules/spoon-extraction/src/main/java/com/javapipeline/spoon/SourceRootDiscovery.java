package com.javapipeline.spoon;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

final class SourceRootDiscovery {
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".idea", "target", "build", "out", "node_modules");

    private SourceRootDiscovery() { }

    static List<Path> discover(Path repository, boolean includeTests) throws IOException {
        Path mainSuffix = Path.of("src", "main", "java");
        Path testSuffix = Path.of("src", "test", "java");
        List<Path> roots = new ArrayList<>();
        Files.walkFileTree(repository, EnumSet.noneOf(java.nio.file.FileVisitOption.class), 10,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                        Path name = directory.getFileName();
                        if (!directory.equals(repository) && name != null
                                && IGNORED_DIRECTORIES.contains(name.toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (directory.endsWith(mainSuffix) || (includeTests && directory.endsWith(testSuffix))) {
                            roots.add(directory.toAbsolutePath().normalize());
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        roots.sort(Comparator.comparing(Path::toString));
        return roots.isEmpty() ? List.of(repository.toAbsolutePath().normalize()) : List.copyOf(roots);
    }
}
