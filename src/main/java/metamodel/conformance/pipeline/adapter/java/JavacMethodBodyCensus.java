package metamodel.conformance.pipeline.adapter.java;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.MethodBodyObservation;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Captures explicit method blocks before attribution can mutate or synthesize trees.
 * A syntax census does not certify compilation, ownership, binding or conformance. */
record JavacMethodBodyCensus(boolean complete, List<MethodBodyObservation> bodies,
                            List<ObservationDiagnostic> diagnostics) {
    JavacMethodBodyCensus {
        bodies = List.copyOf(bodies);
        diagnostics = List.copyOf(diagnostics);
    }

    static JavacMethodBodyCensus unavailable() {
        return new JavacMethodBodyCensus(false, List.of(), List.of());
    }

    static JavacMethodBodyCensus capture(Path root, List<Path> files, List<CompilationUnitTree> units,
                                        Trees trees, boolean syntaxErrors) {
        var bodies = new ArrayList<MethodBodyObservation>();
        var diagnostics = new ArrayList<ObservationDiagnostic>();
        Set<Path> expected = files.stream().map(file -> file.toAbsolutePath().normalize())
                .collect(java.util.stream.Collectors.toSet());
        if (expected.isEmpty()) return new JavacMethodBodyCensus(units.isEmpty() && !syntaxErrors, List.of(), List.of());
        Set<Path> observed = new HashSet<>();
        String fallback = files.isEmpty() ? "" : root.relativize(files.get(0)).toString().replace('\\', '/');
        for (var unit : units) {
            Path unitPath = Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize();
            boolean declared = expected.contains(unitPath);
            String diagnosticPath = declared ? root.relativize(unitPath).toString().replace('\\', '/') : fallback;
            if (!observed.add(unitPath)) {
                syntaxErrors = true;
                diagnostics.add(new ObservationDiagnostic(DiagnosticKind.EVIDENCE_INCOMPLETE,
                        diagnosticPath, 0, "javac method-body census has a duplicate compilation unit"));
            }
            if (!declared) continue; // No canonical body may reference an unretained source unit.
            new TreeScanner<Void, Void>() {
                @Override public Void visitMethod(MethodTree method, Void unused) {
                    // Constructors have no return type; lambdas are not MethodTree nodes.
                    if (method.getReturnType() != null && method.getBody() != null) {
                        try {
                            Path source = Path.of(unit.getSourceFile().toUri())
                                    .toRealPath(LinkOption.NOFOLLOW_LINKS);
                            long start = trees.getSourcePositions().getStartPosition(unit, method.getBody());
                            long end = trees.getSourcePositions().getEndPosition(unit, method.getBody());
                            if (!source.startsWith(root) || start < 0 || end <= start || unit.getLineMap() == null)
                                throw new IllegalArgumentException("method block has no canonical source span");
                            String path = root.relativize(source).toString().replace('\\', '/');
                            bodies.add(new MethodBodyObservation(
                                    SpoonMethodBodyObserver.bodyTechnicalKey(path, start, end - 1), path,
                                    Math.toIntExact(unit.getLineMap().getLineNumber(start)),
                                    Math.toIntExact(unit.getLineMap().getLineNumber(end - 1))));
                        } catch (IOException | RuntimeException failure) {
                            diagnostics.add(new ObservationDiagnostic(DiagnosticKind.EVIDENCE_INCOMPLETE,
                                    diagnosticPath, 0, "javac method-body census has an unavailable source span"));
                        }
                    }
                    return super.visitMethod(method, unused);
                }
            }.scan(unit, null);
        }
        if (!observed.equals(expected)) {
            diagnostics.add(new ObservationDiagnostic(DiagnosticKind.EVIDENCE_INCOMPLETE,
                    fallback, 0, "javac method-body compilation-unit population differs from declared inputs"));
        }
        var canonical = bodies.stream().distinct()
                .sorted(Comparator.comparing(MethodBodyObservation::technicalKey)).toList();
        return new JavacMethodBodyCensus(!syntaxErrors && diagnostics.isEmpty()
                && canonical.size() == bodies.size(), canonical, diagnostics);
    }
}
