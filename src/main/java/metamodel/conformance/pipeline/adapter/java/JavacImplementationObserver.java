package metamodel.conformance.pipeline.adapter.java;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.ImplementationAvailability;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.MethodBodyObservation;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class JavacImplementationObserver {
    Result observe(
            Path root,
            List<Path> files,
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            List<MethodBodyObservation> bodies,
            List<Path> dependencyArchives) {
        JavaDependencyInputs inputs = JavaDependencyInputs.global(dependencyArchives);
        JavaCompilationContexts.Grouping grouping = JavaCompilationContexts.group(root, files, inputs);
        Map<String, ImplementationAvailability> availability = new HashMap<>();
        Map<String, List<String>> bindings = new HashMap<>();
        List<ObservationDiagnostic> diagnostics = new ArrayList<>();
        boolean complete = grouping.unownedFiles().isEmpty();
        for (Map.Entry<String, List<Path>> entry : grouping.filesByContext().entrySet()) {
            JavaCompilationContext compilationContext = grouping.context(entry.getKey());
            Set<String> scopedPaths = entry.getValue().stream()
                    .map(path -> relativePath(root, path))
                    .collect(java.util.stream.Collectors.toSet());
            List<ClassifierObservation> scopedClassifiers = classifiers.stream()
                    .filter(item -> scopedPaths.contains(item.sourcePath())).toList();
            List<MemberObservation> scopedMembers = members.stream()
                    .filter(item -> scopedPaths.contains(item.sourcePath())).toList();
            List<MethodBodyObservation> scopedBodies = bodies.stream()
                    .filter(item -> scopedPaths.contains(item.sourcePath())).toList();
            Result result;
            try (JavacCompilationContext context = JavacCompilationContext.prepare(
                    root, compilationContext, grouping.filesByContext(), inputs)) {
                if (!context.complete()) {
                    result = new Result(false, Map.of(), Map.of(), context.diagnostics());
                } else {
                    result = observeCompilationContext(
                            root, entry.getValue(), scopedClassifiers, scopedMembers, scopedBodies,
                            context.options());
                }
            } catch (IOException | RuntimeException failure) {
                result = Result.incomplete(
                        relativePath(root, entry.getValue().get(0)),
                        "javac compilation context failed for implementation evidence: "
                                + failure.getClass().getSimpleName());
            }
            complete &= result.complete();
            diagnostics.addAll(result.diagnostics());
            complete &= mergeConsistent(availability, result.availabilityByMember(),
                    diagnostics, entry.getKey(), "implementation availability");
            complete &= mergeConsistent(bindings, result.bodyKeysByMember(),
                    diagnostics, entry.getKey(), "implementation bindings");
        }
        if (!grouping.unownedFiles().isEmpty()) {
            diagnostics.add(new ObservationDiagnostic(
                    DiagnosticKind.EVIDENCE_INCOMPLETE,
                    relativePath(root, grouping.unownedFiles().get(0)), 0,
                    "Java source is not owned by an observed compilation context"));
        }
        if (!complete) {
            availability.clear();
            bindings.clear();
        }
        return new Result(
                complete, availability, bindings,
                diagnostics.stream().distinct().sorted(
                        Comparator.comparing(ObservationDiagnostic::sourcePath)
                                .thenComparingInt(ObservationDiagnostic::line)
                                .thenComparing(ObservationDiagnostic::message)).toList());
    }

    private static <K, V> boolean mergeConsistent(
            Map<K, V> target, Map<K, V> observed, List<ObservationDiagnostic> diagnostics,
            String contextId, String label) {
        boolean consistent = true;
        for (Map.Entry<K, V> entry : observed.entrySet()) {
            V previous = target.putIfAbsent(entry.getKey(), entry.getValue());
            if (previous != null && !previous.equals(entry.getValue())) {
                consistent = false;
                diagnostics.add(new ObservationDiagnostic(
                        DiagnosticKind.EVIDENCE_INCOMPLETE, "<context>.java", 0,
                        "conflicting " + label + " across compilation context " + contextId));
            }
        }
        return consistent;
    }

    private Result observeCompilationContext(
            Path root,
            List<Path> files,
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            List<MethodBodyObservation> bodies,
            List<String> compilerOptions) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return Result.incomplete(relativePath(root, files.get(0)),
                    "JDK compiler is unavailable; implementation-binding evidence was not observed");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics, java.util.Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> sources = fileManager.getJavaFileObjectsFromPaths(files);
            JavacTask task = (JavacTask) compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    compilerOptions,
                    null,
                    sources);
            List<CompilationUnitTree> parsed = new ArrayList<>();
            task.parse().forEach(parsed::add);
            task.analyze();
            if (diagnostics.getDiagnostics().stream()
                    .anyMatch(item -> item.getKind() == Diagnostic.Kind.ERROR)) {
                return new Result(false, Map.of(), Map.of(),
                        evidenceDiagnostics(root, files, diagnostics));
            }
            return resolve(root, parsed, task, classifiers, members, bodies);
        } catch (IOException | RuntimeException | StackOverflowError failure) {
            return Result.incomplete(relativePath(root, files.get(0)),
                    "javac implementation-binding observation failed: "
                            + failure.getClass().getSimpleName());
        }
    }

    private static Result resolve(
            Path root,
            List<CompilationUnitTree> parsed,
            JavacTask task,
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            List<MethodBodyObservation> bodies) throws IOException {
        Trees trees = Trees.instance(task);
        Types types = task.getTypes();
        Elements elements = task.getElements();
        JavacSourceTypeIndex sourceTypes = JavacSourceTypeIndex.create(root, parsed, trees, task.getElements(), classifiers);
        Map<String, MemberObservation> membersByKey = new HashMap<>();
        members.forEach(member -> membersByKey.put(member.technicalKey(), member));
        Map<MemberLocator, List<MemberObservation>> membersByLocation = new HashMap<>();
        for (ClassifierObservation classifier : classifiers) {
            for (String key : classifier.declaredMemberKeys()) {
                MemberObservation member = membersByKey.get(key);
                if (member == null) {
                    return incomplete(classifiers, "declared method reference could not be mapped");
                }
                if (member.kind() == MemberKind.METHOD) {
                    MemberLocator locator = new MemberLocator(classifier.id(), member.memberName());
                    membersByLocation.computeIfAbsent(locator, ignored -> new ArrayList<>()).add(member);
                }
            }
        }
        Map<BodyLocator, List<MethodBodyObservation>> bodiesByLocation = new HashMap<>();
        for (MethodBodyObservation body : bodies) {
            BodyLocator locator = new BodyLocator(body.sourcePath(), body.startLine());
            bodiesByLocation.computeIfAbsent(locator, ignored -> new ArrayList<>()).add(body);
        }

        Map<String, ImplementationAvailability> availability = new HashMap<>();
        Map<String, List<String>> bindings = new HashMap<>();
        Set<String> mappedMethods = new HashSet<>();
        for (ClassifierObservation classifier : classifiers) {
            TypeElement type = sourceTypes.type(classifier);
            if (type == null) {
                return Result.incomplete(classifier.sourcePath(), classifier.startLine(),
                        "javac classifier could not be mapped uniquely for implementation evidence: "
                                + classifier.qualifiedName() + " at line " + classifier.startLine());
            }
            for (Element element : type.getEnclosedElements()) {
                if (element.getKind() != ElementKind.METHOD
                        || !(element instanceof ExecutableElement method)) {
                    continue;
                }
                Tree tree = trees.getTree(method);
                if (tree == null) {
                    // Compiler-synthesized methods have no source carrier and stay outside the observation.
                    continue;
                }
                if (!(tree instanceof MethodTree methodTree)) {
                    return incomplete(classifiers, "javac source method tree is unavailable");
                }
                SourcePoint methodLocation = methodDeclarationPoint(root, trees, method, methodTree);
                if (methodLocation == null) {
                    return incomplete(classifiers, "javac source method has no canonical source location");
                }
                List<MemberObservation> declarationCandidates = membersByLocation.get(
                        new MemberLocator(classifier.id(), method.getSimpleName().toString()));
                MemberObservation declaration = uniqueDeclaration(
                        declarationCandidates, method, types, elements, methodLocation);
                if (declaration == null) {
                    return Result.incomplete(
                            methodLocation.path(),
                            methodLocation.line(),
                            mappingFailureMessage(classifier, method, types, declarationCandidates));
                }
                if (!mappedMethods.add(declaration.technicalKey())) {
                    return Result.incomplete(
                            methodLocation.path(),
                            methodLocation.line(),
                            "javac source method mapped more than once: "
                                    + classifier.qualifiedName() + "." + method.getSimpleName());
                }
                if (methodTree.getBody() == null) {
                    availability.put(declaration.technicalKey(), ImplementationAvailability.NO_SOURCE_BODY);
                    bindings.put(declaration.technicalKey(), List.of());
                    continue;
                }
                availability.put(declaration.technicalKey(), ImplementationAvailability.SOURCE_BODY);
                SourceRange range = sourceRange(root, trees, method, methodTree.getBody());
                if (range == null) {
                    return Result.incomplete(
                            methodLocation.path(), methodLocation.line(),
                            "javac method body has no canonical source range");
                }
                List<MethodBodyObservation> bodyCandidates = bodiesByLocation.get(
                        new BodyLocator(range.path(), range.startLine()));
                if (bodyCandidates == null || bodyCandidates.size() != 1) {
                    return Result.incomplete(
                            methodLocation.path(), methodLocation.line(),
                            "javac method body could not be matched to one Spoon body");
                }
                bindings.put(declaration.technicalKey(),
                        List.of(bodyCandidates.get(0).technicalKey()));
            }
        }
        long canonicalMethodCount = members.stream()
                .filter(member -> member.kind() == MemberKind.METHOD).count();
        if (mappedMethods.size() != canonicalMethodCount) {
            return incomplete(classifiers,
                    "not every canonical source method was independently mapped by javac");
        }
        return new Result(true, availability, bindings, List.of());
    }

    private static Result incomplete(List<ClassifierObservation> classifiers, String message) {
        String sourcePath = classifiers.isEmpty()
                ? "<unknown>.java" : classifiers.get(0).sourcePath();
        return Result.incomplete(sourcePath, message);
    }

    private static SourcePoint sourcePoint(Path root, Trees trees, Element element) throws IOException {
        var treePath = trees.getPath(element);
        if (treePath == null) {
            return null;
        }
        return sourcePoint(root, trees, treePath.getCompilationUnit(), treePath.getLeaf());
    }

    private static SourcePoint methodDeclarationPoint(
            Path root,
            Trees trees,
            ExecutableElement method,
            MethodTree methodTree) throws IOException {
        var treePath = trees.getPath(method);
        if (treePath == null) {
            return null;
        }
        Tree declarationAnchor = methodTree.getReturnType() == null ? methodTree : methodTree.getReturnType();
        return sourcePoint(root, trees, treePath.getCompilationUnit(), declarationAnchor);
    }

    private static SourcePoint sourcePoint(
            Path root,
            Trees trees,
            CompilationUnitTree unit,
            Tree target) throws IOException {
        long position = trees.getSourcePositions().getStartPosition(unit, target);
        if (position < 0 || unit.getLineMap() == null) {
            return null;
        }
        Path source = Path.of(unit.getSourceFile().toUri()).toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!source.startsWith(root)) {
            return null;
        }
        return new SourcePoint(
                relativePath(root, source),
                Math.toIntExact(unit.getLineMap().getLineNumber(position)));
    }

    private static SourceRange sourceRange(
            Path root,
            Trees trees,
            ExecutableElement method,
            Tree target) throws IOException {
        var path = trees.getPath(method);
        if (path == null) {
            return null;
        }
        CompilationUnitTree unit = path.getCompilationUnit();
        long start = trees.getSourcePositions().getStartPosition(unit, target);
        long end = trees.getSourcePositions().getEndPosition(unit, target);
        if (start < 0 || end < 0 || unit.getLineMap() == null) {
            return null;
        }
        Path source = Path.of(unit.getSourceFile().toUri()).toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!source.startsWith(root)) {
            return null;
        }
        return new SourceRange(
                relativePath(root, source),
                Math.toIntExact(unit.getLineMap().getLineNumber(start)),
                Math.toIntExact(unit.getLineMap().getLineNumber(Math.max(start, end - 1))));
    }

    private static MemberObservation uniqueDeclaration(
            List<MemberObservation> candidates,
            ExecutableElement method,
            Types types,
            Elements elements,
            SourcePoint methodLocation) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        List<MemberObservation> atSourceLocation = candidates.stream()
                .filter(candidate -> candidate.sourcePath().equals(methodLocation.path())
                        && candidate.startLine() == methodLocation.line())
                .toList();
        if (atSourceLocation.size() == 1) {
            return atSourceLocation.get(0);
        }
        if (atSourceLocation.isEmpty()) {
            return null;
        }
        List<MemberObservation> matching = atSourceLocation.stream()
                .filter(candidate -> JavacDeclarationTypes.matches(
                        candidate.parameterTypes(), method, types, elements))
                .toList();
        return matching.size() == 1 ? matching.get(0) : null;
    }

    private static String mappingFailureMessage(
            ClassifierObservation classifier,
            ExecutableElement method,
            Types types,
            List<MemberObservation> candidates) {
        List<String> exact = method.getParameters().stream()
                .map(parameter -> parameter.asType().toString()).toList();
        List<String> erased = method.getParameters().stream()
                .map(parameter -> types.erasure(parameter.asType()).toString()).toList();
        String candidateText = candidates == null ? "[]" : candidates.stream()
                .map(candidate -> candidate.startLine() + ":" + candidate.parameterTypes())
                .sorted()
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        return "javac source method declaration could not be mapped uniquely: owner="
                + classifier.qualifiedName()
                + ", method=" + method.getSimpleName()
                + ", exactParameters=" + exact
                + ", erasedParameters=" + erased
                + ", canonicalCandidates=" + candidateText;
    }

    private static List<ObservationDiagnostic> evidenceDiagnostics(
            Path root,
            List<Path> files,
            DiagnosticCollector<JavaFileObject> collector) {
        String fallback = relativePath(root, files.get(0));
        return collector.getDiagnostics().stream()
                .filter(item -> item.getKind() == Diagnostic.Kind.ERROR)
                .map(item -> new ObservationDiagnostic(
                        DiagnosticKind.EVIDENCE_INCOMPLETE,
                        diagnosticPath(root, item.getSource(), fallback),
                        item.getLineNumber() < 0 ? 0 : Math.toIntExact(item.getLineNumber()),
                        normalizedMessage(root, item.getMessage(java.util.Locale.ROOT))))
                .distinct()
                .sorted(Comparator.comparing(ObservationDiagnostic::sourcePath)
                        .thenComparingInt(ObservationDiagnostic::line)
                        .thenComparing(ObservationDiagnostic::message))
                .toList();
    }

    private static String diagnosticPath(Path root, JavaFileObject source, String fallback) {
        if (source == null) {
            return fallback;
        }
        try {
            Path path = Path.of(source.toUri()).toRealPath(LinkOption.NOFOLLOW_LINKS);
            return path.startsWith(root) ? relativePath(root, path) : fallback;
        } catch (IOException | RuntimeException ignored) {
            return fallback;
        }
    }

    private static String normalizedMessage(Path root, String message) {
        String text = message == null || message.isBlank()
                ? "javac could not complete implementation-binding observation"
                : message;
        return text.replace(root.toAbsolutePath().normalize().toString(), ".")
                .replace('\r', ' ').trim();
    }

    private static String relativePath(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    record Result(
            boolean complete,
            Map<String, ImplementationAvailability> availabilityByMember,
            Map<String, List<String>> bodyKeysByMember,
            List<ObservationDiagnostic> diagnostics) {
        Result {
            availabilityByMember = Map.copyOf(availabilityByMember);
            bodyKeysByMember = bodyKeysByMember.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey,
                            entry -> List.copyOf(entry.getValue())));
            diagnostics = List.copyOf(diagnostics);
        }

        static Result incomplete() {
            return new Result(false, Map.of(), Map.of(), List.of());
        }

        static Result incomplete(String sourcePath, String message) {
            return incomplete(sourcePath, 0, message);
        }

        static Result incomplete(String sourcePath, int line, String message) {
            return new Result(false, Map.of(), Map.of(),
                    List.of(new ObservationDiagnostic(
                            DiagnosticKind.EVIDENCE_INCOMPLETE,
                            sourcePath,
                            line,
                            message)));
        }
    }


    private record MemberLocator(String ownerId, String name) {
    }

    private record BodyLocator(String path, int startLine) {
    }

    private record SourcePoint(String path, int line) {
    }

    private record SourceRange(String path, int startLine, int endLine) {
    }
}
