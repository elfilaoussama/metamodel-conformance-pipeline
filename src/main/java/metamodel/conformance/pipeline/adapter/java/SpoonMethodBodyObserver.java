package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.MethodBodyObservation;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;
import metamodel.conformance.pipeline.util.Hashing;
import spoon.Launcher;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class SpoonMethodBodyObserver {
    Result observe(Path root, List<Path> files) {
        return observe(root, files, JavaCompilationContexts.sourceOnlyContext());
    }

    Result observe(Path root, List<Path> files, JavaCompilationContext context) {
        try {
            Collected collected = collect(root, files, context);
            return result(collected.bodies(), collected.complete(), List.of());
        } catch (IOException | RuntimeException batchFailure) {
            // A batch model can fail for reasons unrelated to any single file
            // (for example Spoon package ambiguity when a generated tree shares
            // a package with the checkout). Recover per file, exactly as the
            // type observer does; a file that truly fails stays incomplete
            // evidence.
            List<MethodBodyObservation> merged = new ArrayList<>();
            List<ObservationDiagnostic> diagnostics = new ArrayList<>();
            boolean complete = true;
            for (Path file : files) {
                try {
                    Collected collected = collect(root, List.of(file), context);
                    merged.addAll(collected.bodies());
                    complete &= collected.complete();
                } catch (IOException | RuntimeException failure) {
                    complete = false;
                    diagnostics.add(new ObservationDiagnostic(
                            DiagnosticKind.EVIDENCE_INCOMPLETE,
                            root.relativize(file).toString().replace('\\', '/'), 0,
                            "Spoon method-body observation failed: " + failure.getClass().getSimpleName()));
                }
            }
            return result(merged, complete, diagnostics);
        }
    }

    private static Result result(
            List<MethodBodyObservation> bodies, boolean complete, List<ObservationDiagnostic> diagnostics) {
        List<MethodBodyObservation> canonical = bodies.stream()
                .distinct()
                .sorted(Comparator.comparing(MethodBodyObservation::technicalKey))
                .toList();
        if (canonical.size() != bodies.size()) {
            complete = false;
        }
        return new Result(complete, canonical, diagnostics);
    }

    private static Collected collect(Path root, List<Path> files, JavaCompilationContext context) throws IOException {
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setComplianceLevel(complianceLevel(context));
        launcher.getEnvironment().setPreviewFeaturesEnabled(context.compilerSemantics().previewEnabled());
        launcher.getEnvironment().setCommentEnabled(false);
        files.forEach(file -> launcher.addInputResource(file.toString()));
        var model = launcher.buildModel();
        List<MethodBodyObservation> bodies = new ArrayList<>();
        boolean complete = true;
        for (CtType<?> type : SpoonSourceTypes.declarations(model).stream()
                .sorted(Comparator.comparing(CtType::getQualifiedName)).toList()) {
            for (CtMethod<?> method : type.getMethods().stream()
                    .sorted(Comparator.comparing((CtMethod<?> item) -> item.getSimpleName())
                            .thenComparingInt(item -> item.getPosition().isValidPosition()
                                    ? item.getPosition().getLine() : Integer.MAX_VALUE))
                    .toList()) {
                if (!method.getPosition().isValidPosition() || method.getBody() == null) {
                    continue;
                }
                var position = method.getBody().getPosition();
                if (!position.isValidPosition()) {
                    complete = false;
                    continue;
                }
                Path source = position.getFile().toPath().toRealPath(LinkOption.NOFOLLOW_LINKS);
                if (!source.startsWith(root)) {
                    complete = false;
                    continue;
                }
                String relative = root.relativize(source).toString().replace('\\', '/');
                String technicalKey = "body_" + Hashing.sha256(
                        "java-body\0" + relative + "\0" + position.getSourceStart()
                                + "\0" + position.getSourceEnd());
                bodies.add(new MethodBodyObservation(
                        technicalKey, relative, position.getLine(), position.getEndLine()));
            }
        }
        return new Collected(bodies, complete);
    }

    private static int complianceLevel(JavaCompilationContext context) {
        JavaCompilerSemantics semantics = context == null
                ? JavaCompilerSemantics.unknown() : context.compilerSemantics();
        if (semantics.releaseLevel() != null) return semantics.releaseLevel();
        if (semantics.sourceLevel() != null) return semantics.sourceLevel();
        return Runtime.version().feature();
    }

    private record Collected(List<MethodBodyObservation> bodies, boolean complete) {
        private Collected {
            bodies = List.copyOf(bodies);
        }
    }

    record Result(
            boolean complete,
            List<MethodBodyObservation> bodies,
            List<ObservationDiagnostic> diagnostics) {
        Result {
            bodies = List.copyOf(bodies);
            diagnostics = List.copyOf(diagnostics);
        }
    }
}