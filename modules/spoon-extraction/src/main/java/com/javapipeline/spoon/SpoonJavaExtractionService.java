package com.javapipeline.spoon;

import com.javapipeline.core.*;
import com.javapipeline.core.model.ExtractionResult;
import com.javapipeline.core.model.ExtractionResult.*;
import spoon.Launcher;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtTypeReference;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SpoonJavaExtractionService implements JavaExtractionService {
    @Override
    public ExtractionResult extract(
            String projectName,
            Path repository,
            ExtractionOptions options,
            ProgressListener progress,
            CancellationToken cancellation
    ) throws JavaExtractionException {
        ProgressListener listener = progress == null ? ProgressListener.NONE : progress;
        CancellationToken token = cancellation == null ? CancellationToken.NONE : cancellation;
        Path root = repository.toAbsolutePath().normalize();
        ExtractionOptions effectiveOptions = options == null ? ExtractionOptions.DEFAULT : options;

        try {
            token.throwIfCancellationRequested();
            listener.onProgress(ProgressEvent.indeterminate(
                    ProgressEvent.Stage.DISCOVERING, "Discovering Java source roots"));
            List<Path> sourceRoots = SourceRootDiscovery.discover(root, effectiveOptions.includeTests());

            List<TypeModel> types = new ArrayList<>();
            for (int rootIndex = 0; rootIndex < sourceRoots.size(); rootIndex++) {
                token.throwIfCancellationRequested();
                Path sourceRoot = sourceRoots.get(rootIndex);
                listener.onProgress(new ProgressEvent(
                        ProgressEvent.Stage.EXTRACTING,
                        "Building Spoon model for " + root.relativize(sourceRoot),
                        rootIndex, sourceRoots.size()));
                Launcher launcher = createLauncher(sourceRoot, effectiveOptions.javaComplianceLevel());
                launcher.buildModel();
                List<CtType<?>> spoonTypes = launcher.getModel().getAllTypes().stream()
                        .sorted(Comparator.comparing(CtType::getQualifiedName))
                        .toList();
                for (int typeIndex = 0; typeIndex < spoonTypes.size(); typeIndex++) {
                    CtType<?> type = spoonTypes.get(typeIndex);
                    token.throwIfCancellationRequested();
                    types.add(mapType(root, type));
                    if (typeIndex == 0 || typeIndex + 1 == spoonTypes.size() || typeIndex % 100 == 0) {
                        listener.onProgress(new ProgressEvent(
                                ProgressEvent.Stage.EXTRACTING,
                                "Extracting types from " + root.relativize(sourceRoot),
                                typeIndex + 1, spoonTypes.size()));
                    }
                }
            }
            types.sort(Comparator.comparing(TypeModel::qualifiedName)
                    .thenComparing(TypeModel::sourceFile, Comparator.nullsFirst(String::compareTo)));

            List<Diagnostic> diagnostics = List.of(new Diagnostic(
                    Diagnostic.Severity.INFO,
                    "SOURCE_ROOTS",
                    "Analyzed " + sourceRoots.size() + " source root(s)"));
            return new ExtractionResult("1.0", projectName, root, Instant.now(), sourceRoots, types, diagnostics);
        } catch (RuntimeException | java.io.IOException ex) {
            throw new JavaExtractionException("Spoon extraction failed for " + projectName + ": " + ex.getMessage(), ex);
        }
    }

    private static Launcher createLauncher(Path sourceRoot, int complianceLevel) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(sourceRoot.toString());
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setAutoImports(false);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setLevel("ERROR");
        launcher.getEnvironment().setComplianceLevel(complianceLevel);
        launcher.getEnvironment().setIgnoreSyntaxErrors(false);
        return launcher;
    }

    private static TypeModel mapType(Path repository, CtType<?> type) {
        String superClass = null;
        if (type instanceof CtClass<?> ctClass && ctClass.getSuperclass() != null
                && !"java.lang.Object".equals(ctClass.getSuperclass().getQualifiedName())) {
            superClass = ctClass.getSuperclass().getQualifiedName();
        }
        List<String> interfaces = type.getSuperInterfaces().stream()
                .map(CtTypeReference::getQualifiedName).sorted().toList();
        List<FieldModel> fields = type.getFields().stream()
                .sorted(Comparator.comparing(CtField::getSimpleName))
                .map(field -> new FieldModel(
                        field.getSimpleName(), typeName(field.getType()), visibility(field),
                        field.isStatic(), field.isFinal(), line(field.getPosition())))
                .toList();

        List<ExecutableModel> executables = new ArrayList<>();
        type.getMethods().stream()
                .sorted(Comparator.comparing(method -> method.getSignature()))
                .map(method -> mapExecutable(method, false))
                .forEach(executables::add);
        if (type instanceof CtClass<?> ctClass) {
            ctClass.getConstructors().stream()
                    .sorted(Comparator.comparing(constructor -> constructor.getSignature()))
                    .map(constructor -> mapExecutable(constructor, true))
                    .forEach(executables::add);
        }

        SourcePosition position = type.getPosition();
        return new TypeModel(
                type.getQualifiedName(), type.getSimpleName(), kind(type), superClass,
                interfaces, fields, List.copyOf(executables), type.isAbstract(), type.isFinal(),
                sourceFile(repository, position), line(position));
    }

    private static ExecutableModel mapExecutable(CtExecutable<?> executable, boolean constructor) {
        String returnType = executable instanceof CtMethod<?> method ? typeName(method.getType()) : null;
        boolean staticExecutable = executable instanceof CtModifiable modifiable && modifiable.isStatic();
        boolean abstractExecutable = executable instanceof CtMethod<?> method && method.isAbstract();
        List<ParameterModel> parameters = executable.getParameters().stream()
                .map(parameter -> new ParameterModel(parameter.getSimpleName(), typeName(parameter.getType())))
                .toList();
        return new ExecutableModel(
                executable.getSimpleName(), returnType, visibilityOfExecutable(executable), constructor,
                staticExecutable, abstractExecutable, parameters, line(executable.getPosition()));
    }

    private static String kind(CtType<?> type) {
        if (type.isAnnotationType()) return "annotation";
        if (type instanceof CtEnum<?>) return "enum";
        if (type instanceof CtRecord) return "record";
        if (type instanceof CtInterface<?>) return "interface";
        return "class";
    }

    private static String visibility(CtModifiable element) {
        return element.getVisibility() == null ? "package-private" : element.getVisibility().toString();
    }

    private static String visibilityOfExecutable(CtExecutable<?> executable) {
        return executable instanceof CtModifiable modifiable ? visibility(modifiable) : "package-private";
    }

    private static String typeName(CtTypeReference<?> reference) {
        return reference == null ? "unknown" : reference.getQualifiedName();
    }

    private static Integer line(SourcePosition position) {
        return position != null && position.isValidPosition() ? position.getLine() : null;
    }

    private static String sourceFile(Path repository, SourcePosition position) {
        if (position == null || !position.isValidPosition() || position.getFile() == null) return null;
        Path file = position.getFile().toPath().toAbsolutePath().normalize();
        try { return repository.relativize(file).toString().replace('\\', '/'); }
        catch (IllegalArgumentException ignored) { return file.toString(); }
    }
}
