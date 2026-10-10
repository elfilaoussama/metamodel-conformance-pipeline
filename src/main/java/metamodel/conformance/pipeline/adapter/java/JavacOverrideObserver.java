package metamodel.conformance.pipeline.adapter.java;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.ObservationDiagnostic;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Independently asks javac which source methods override other source methods and records
 * resolved return-type names. It observes source-language facts only; policy remains in Alloy.
 */
final class JavacOverrideObserver {
    Result observe(
            Path root,
            List<Path> files,
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            List<Path> dependencyArchives) {
        JavaDependencyInputs inputs = JavaDependencyInputs.global(dependencyArchives);
        JavaCompilationContexts.Grouping grouping = JavaCompilationContexts.group(root, files, inputs);
        Map<String, String> returnTypes = new HashMap<>();
        Map<String, List<String>> overrides = new HashMap<>();
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
            Set<String> upstreamIds = inputs.context(compilationContext.id()) == null
                    ? Set.of() : inputs.upstreamClosure(compilationContext.id());
            Set<String> upstreamPaths = upstreamIds.stream()
                    .flatMap(id -> grouping.filesByContext().getOrDefault(id, List.of()).stream())
                    .map(path -> relativePath(root, path))
                    .collect(java.util.stream.Collectors.toSet());
            List<ClassifierObservation> upstreamClassifiers = classifiers.stream()
                    .filter(item -> upstreamPaths.contains(item.sourcePath())).toList();
            List<MemberObservation> upstreamMembers = members.stream()
                    .filter(item -> upstreamPaths.contains(item.sourcePath())).toList();
            Result result;
            try (JavacCompilationContext context = JavacCompilationContext.prepare(
                    root, compilationContext, grouping.filesByContext(), inputs)) {
                result = context.complete()
                        ? observeCompilationContext(root, entry.getValue(), scopedClassifiers, scopedMembers,
                                upstreamClassifiers, upstreamMembers, context.options())
                        : new Result(false, Map.of(), Map.of(), context.diagnostics());
            } catch (IOException | RuntimeException failure) {
                result = Result.incomplete(relativePath(root, entry.getValue().get(0)),
                        "javac compilation context failed for override/return-type evidence: "
                                + failure.getClass().getSimpleName());
            }
            complete &= result.complete();
            diagnostics.addAll(result.diagnostics());
            complete &= mergeConsistent(returnTypes, result.returnTypesByMember(), diagnostics,
                    "return-type evidence");
            complete &= mergeConsistent(overrides, result.overriddenMemberKeysByMember(), diagnostics,
                    "override evidence");
        }
        if (!grouping.unownedFiles().isEmpty()) {
            diagnostics.add(new ObservationDiagnostic(
                    DiagnosticKind.EVIDENCE_INCOMPLETE, relativePath(root, grouping.unownedFiles().get(0)), 0,
                    "Java source is not owned by an observed compilation context"));
        }
        if (!complete) { returnTypes.clear(); overrides.clear(); }
        return new Result(complete, returnTypes, overrides, diagnostics.stream().distinct().sorted(
                Comparator.comparing(ObservationDiagnostic::sourcePath)
                        .thenComparingInt(ObservationDiagnostic::line)
                        .thenComparing(ObservationDiagnostic::message)).toList());
    }

    private static <K, V> boolean mergeConsistent(
            Map<K, V> target, Map<K, V> observed, List<ObservationDiagnostic> diagnostics, String label) {
        boolean consistent = true;
        for (Map.Entry<K, V> entry : observed.entrySet()) {
            V previous = target.putIfAbsent(entry.getKey(), entry.getValue());
            if (previous != null && !previous.equals(entry.getValue())) {
                consistent = false;
                diagnostics.add(new ObservationDiagnostic(
                        DiagnosticKind.EVIDENCE_INCOMPLETE, "<context>.java", 0,
                        "conflicting " + label + " across compilation contexts"));
            }
        }
        return consistent;
    }

    private Result observeCompilationContext(
            Path root,
            List<Path> files,
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            List<ClassifierObservation> productionClassifiers,
            List<MemberObservation> productionMembers,
            List<String> compilerOptions) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return Result.incomplete(relativePath(root, files.get(0)),
                    "JDK compiler is unavailable; override/return-type evidence was not observed");
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
            return resolve(
                    root,
                    parsed,
                    task,
                    classifiers,
                    members,
                    productionClassifiers,
                    productionMembers);
        } catch (IOException | RuntimeException | StackOverflowError failure) {
            return Result.incomplete(relativePath(root, files.get(0)),
                    "javac override/return-type observation failed: "
                            + failure.getClass().getSimpleName());
        }
    }

    private static Result resolve(
            Path root,
            List<CompilationUnitTree> parsed,
            JavacTask task,
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            List<ClassifierObservation> productionClassifiers,
            List<MemberObservation> productionMembers) throws IOException {
        Trees trees = Trees.instance(task);
        Types types = task.getTypes();
        Elements elements = task.getElements();
        JavacSourceTypeIndex sourceTypes = JavacSourceTypeIndex.create(root, parsed, trees, elements, classifiers);

        List<ClassifierObservation> canonicalClassifiers = concat(classifiers, productionClassifiers);
        Map<String, ClassifierObservation> classifierById = new HashMap<>();
        for (ClassifierObservation classifier : canonicalClassifiers) {
            if (classifierById.put(classifier.id(), classifier) != null) {
                return incomplete(classifiers, "duplicate canonical classifier identity in override context");
            }
        }
        Map<String, MemberObservation> membersByKey = new HashMap<>();
        members.forEach(member -> membersByKey.put(member.technicalKey(), member));
        productionMembers.forEach(member -> membersByKey.put(member.technicalKey(), member));
        Map<MemberLocator, List<MemberObservation>> membersByOwnerAndName = new HashMap<>();
        for (ClassifierObservation classifier : canonicalClassifiers) {
            for (String key : classifier.declaredMemberKeys()) {
                MemberObservation member = membersByKey.get(key);
                if (member == null) {
                    return incomplete(classifiers, "declared method reference could not be mapped");
                }
                if (member.kind() == MemberKind.METHOD) {
                    MemberLocator locator = new MemberLocator(classifier.id(), member.memberName());
                    membersByOwnerAndName.computeIfAbsent(locator, ignored -> new ArrayList<>()).add(member);
                }
            }
        }

        Map<String, TypeElement> typeByClassifier = new HashMap<>();
        Map<String, ExecutableElement> localMethodByKey = new HashMap<>();
        Map<String, String> returnTypes = new HashMap<>();
        Set<String> mappedMethods = new HashSet<>();
        for (ClassifierObservation classifier : classifiers) {
            TypeElement type = sourceTypes.type(classifier);
            if (type == null) {
                return Result.incomplete(classifier.sourcePath(), classifier.startLine(),
                        "javac classifier could not be mapped uniquely for override evidence: "
                                + classifier.qualifiedName() + " at line " + classifier.startLine());
            }
            typeByClassifier.put(classifier.id(), type);
            for (Element element : type.getEnclosedElements()) {
                if (element.getKind() != ElementKind.METHOD
                        || !(element instanceof ExecutableElement method)) {
                    continue;
                }
                Tree tree = trees.getTree(method);
                if (tree == null) {
                    // Compiler-synthesized methods are outside the source observation boundary.
                    continue;
                }
                if (!(tree instanceof MethodTree methodTree)) {
                    return incomplete(classifiers, "javac source method tree is unavailable");
                }
                SourcePoint methodLocation = methodDeclarationPoint(root, trees, method, methodTree);
                if (methodLocation == null) {
                    return incomplete(classifiers,
                            "javac source method has no canonical source location");
                }
                List<MemberObservation> declarationCandidates = membersByOwnerAndName.get(
                        new MemberLocator(classifier.id(), method.getSimpleName().toString()));
                MemberObservation declaration = uniqueSourceDeclaration(
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
                            "javac source method mapped more than once for override evidence: "
                                    + classifier.qualifiedName() + "." + method.getSimpleName());
                }
                localMethodByKey.put(declaration.technicalKey(), method);
                String returnType = method.getReturnType().toString();
                if (returnType.isBlank()) {
                    return Result.incomplete(
                            methodLocation.path(), methodLocation.line(),
                            "javac source method has no resolved return-type name");
                }
                returnTypes.put(declaration.technicalKey(), returnType);
            }
        }
        long canonicalMethodCount = members.stream()
                .filter(member -> member.kind() == MemberKind.METHOD).count();
        if (mappedMethods.size() != canonicalMethodCount) {
            return incomplete(classifiers,
                    "not every canonical source method was independently mapped by javac for override evidence");
        }

        for (ClassifierObservation classifier : classifiers) {
            TypeElement type = typeByClassifier.get(classifier.id());
            String mappingError = mapAncestorTypes(
                    classifier,
                    type,
                    classifierById,
                    typeByClassifier,
                    types,
                    elements,
                    new HashSet<>());
            if (mappingError != null) {
                return Result.incomplete(classifier.sourcePath(), classifier.startLine(), mappingError);
            }
        }

        Map<String, List<String>> overrides = new HashMap<>();
        for (String key : localMethodByKey.keySet()) {
            overrides.put(key, List.of());
        }
        for (ClassifierObservation classifier : classifiers) {
            TypeElement owner = typeByClassifier.get(classifier.id());
            if (owner == null) {
                return incomplete(classifiers, "javac override owner could not be resolved");
            }
            Set<String> ancestors = ancestorIds(classifier, classifierById);
            for (String key : classifier.declaredMemberKeys()) {
                MemberObservation member = membersByKey.get(key);
                if (member == null || member.kind() != MemberKind.METHOD) {
                    continue;
                }
                ExecutableElement local = localMethodByKey.get(key);
                if (local == null) {
                    return incomplete(classifiers,
                            "javac local override declaration could not be resolved");
                }
                LinkedHashSet<String> targets = new LinkedHashSet<>();
                for (String ancestorId : ancestors) {
                    ClassifierObservation ancestor = classifierById.get(ancestorId);
                    TypeElement ancestorType = typeByClassifier.get(ancestorId);
                    if (ancestor == null || ancestorType == null) {
                        return Result.incomplete(
                                member.sourcePath(), member.startLine(),
                                "canonical override ancestor has no javac type mapping");
                    }
                    for (String targetKey : ancestor.declaredMemberKeys()) {
                        MemberObservation target = membersByKey.get(targetKey);
                        if (target == null || target.kind() != MemberKind.METHOD) {
                            continue;
                        }
                        List<ExecutableElement> candidates;
                        ExecutableElement sourceCandidate = localMethodByKey.get(targetKey);
                        if (sourceCandidate != null) {
                            candidates = List.of(sourceCandidate);
                        } else {
                            candidates = binaryMethodCandidates(ancestorType, target, types, elements);
                        }
                        if (candidates.isEmpty()) {
                            return Result.incomplete(
                                    member.sourcePath(), member.startLine(),
                                    "canonical ancestor method could not be mapped to javac: owner="
                                            + ancestor.qualifiedName() + ", method=" + target.memberName());
                        }
                        if (candidates.stream().anyMatch(candidate ->
                                elements.overrides(local, candidate, owner))) {
                            targets.add(targetKey);
                        }
                    }
                }
                overrides.put(key, targets.stream().sorted().toList());
            }
        }
        return new Result(true, returnTypes, overrides, List.of());
    }

    private static String mapAncestorTypes(
            ClassifierObservation classifier,
            TypeElement type,
            Map<String, ClassifierObservation> classifierById,
            Map<String, TypeElement> typeByClassifier,
            Types types,
            Elements elements,
            Set<String> active) {
        if (type == null) {
            return "javac classifier type is unavailable while mapping override ancestors";
        }
        if (!active.add(classifier.id())) {
            return "cyclic canonical hierarchy encountered while mapping override ancestors";
        }
        try {
            List<? extends TypeMirror> directSupertypes = types.directSupertypes(type.asType());
            for (String parentId : classifier.parentIds()) {
                ClassifierObservation parent = classifierById.get(parentId);
                if (parent == null) {
                    return "canonical parent is outside the active javac source-set context";
                }
                TypeElement mappedParent = typeByClassifier.get(parentId);
                List<TypeElement> matches = directSupertypes.stream()
                        .map(types::asElement)
                        .filter(TypeElement.class::isInstance)
                        .map(TypeElement.class::cast)
                        .filter(candidate -> mappedParent != null
                                ? types.isSameType(candidate.asType(), mappedParent.asType())
                                : elements.getBinaryName(candidate).contentEquals(parent.qualifiedName()))
                        .toList();
                if (matches.size() != 1) {
                    return "javac direct parent could not be mapped uniquely: " + parent.qualifiedName();
                }
                TypeElement parentType = matches.get(0);
                TypeElement previous = typeByClassifier.putIfAbsent(parentId, parentType);
                if (previous != null && !types.isSameType(previous.asType(), parentType.asType())) {
                    return "canonical parent mapped to inconsistent javac types: " + parent.qualifiedName();
                }
                String nested = mapAncestorTypes(
                        parent,
                        previous == null ? parentType : previous,
                        classifierById,
                        typeByClassifier,
                        types,
                        elements,
                        active);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        } finally {
            active.remove(classifier.id());
        }
    }

    private static Set<String> ancestorIds(
            ClassifierObservation classifier,
            Map<String, ClassifierObservation> classifierById) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        collectAncestorIds(classifier, classifierById, result);
        return result;
    }

    private static void collectAncestorIds(
            ClassifierObservation classifier,
            Map<String, ClassifierObservation> classifierById,
            Set<String> result) {
        for (String parentId : classifier.parentIds()) {
            if (!result.add(parentId)) {
                continue;
            }
            ClassifierObservation parent = classifierById.get(parentId);
            if (parent != null) {
                collectAncestorIds(parent, classifierById, result);
            }
        }
    }

    private static List<ExecutableElement> binaryMethodCandidates(
            TypeElement owner,
            MemberObservation canonical,
            Types types, Elements elements) {
        return owner.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.METHOD)
                .filter(ExecutableElement.class::isInstance)
                .map(ExecutableElement.class::cast)
                .filter(method -> method.getSimpleName().contentEquals(canonical.memberName()))
                .filter(method -> parameterTypesMatch(canonical, method, types, elements))
                .toList();
    }

    private static boolean parameterTypesMatch(
            MemberObservation canonical,
            ExecutableElement method,
            Types types, Elements elements) {
        return JavacDeclarationTypes.matches(canonical.parameterTypes(), method, types, elements);
    }

    private static List<ClassifierObservation> concat(
            List<ClassifierObservation> left, List<ClassifierObservation> right) {
        List<ClassifierObservation> result = new ArrayList<>(left.size() + right.size());
        result.addAll(left);
        result.addAll(right);
        return result;
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

    private static MemberObservation uniqueSourceDeclaration(
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
        return "javac source method declaration could not be mapped uniquely for override evidence: owner="
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
                ? "javac could not complete override/return-type observation"
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
            Map<String, String> returnTypesByMember,
            Map<String, List<String>> overriddenMemberKeysByMember,
            List<ObservationDiagnostic> diagnostics) {
        Result {
            returnTypesByMember = Map.copyOf(returnTypesByMember);
            overriddenMemberKeysByMember = overriddenMemberKeysByMember.entrySet().stream()
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

    private record SourcePoint(String path, int line) {
    }
}
