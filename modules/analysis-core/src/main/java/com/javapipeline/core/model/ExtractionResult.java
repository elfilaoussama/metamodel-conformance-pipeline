package com.javapipeline.core.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ExtractionResult(
        String schemaVersion,
        String projectName,
        Path repository,
        Instant generatedAt,
        List<Path> sourceRoots,
        List<TypeModel> types,
        List<Diagnostic> diagnostics
) {
    public ExtractionResult {
        schemaVersion = Objects.requireNonNullElse(schemaVersion, "1.0");
        projectName = Objects.requireNonNull(projectName, "projectName");
        repository = Objects.requireNonNull(repository, "repository").toAbsolutePath().normalize();
        generatedAt = Objects.requireNonNullElseGet(generatedAt, Instant::now);
        sourceRoots = List.copyOf(sourceRoots);
        types = List.copyOf(types);
        diagnostics = List.copyOf(diagnostics);
    }

    public record TypeModel(
            String qualifiedName,
            String simpleName,
            String kind,
            String superClass,
            List<String> interfaces,
            List<FieldModel> fields,
            List<ExecutableModel> executables,
            boolean abstractType,
            boolean finalType,
            String sourceFile,
            Integer line
    ) { }

    public record FieldModel(
            String name,
            String type,
            String visibility,
            boolean staticField,
            boolean finalField,
            Integer line
    ) { }

    public record ExecutableModel(
            String name,
            String returnType,
            String visibility,
            boolean constructor,
            boolean staticExecutable,
            boolean abstractExecutable,
            List<ParameterModel> parameters,
            Integer line
    ) { }

    public record ParameterModel(String name, String type) { }

    public record Diagnostic(Severity severity, String code, String message) {
        public enum Severity { INFO, WARNING, ERROR }
    }
}
