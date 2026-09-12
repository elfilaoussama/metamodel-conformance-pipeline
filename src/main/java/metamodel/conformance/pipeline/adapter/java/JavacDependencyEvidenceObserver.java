package metamodel.conformance.pipeline.adapter.java;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Independently maps javac inherited-member and override facts onto source/support observations. */
final class JavacDependencyEvidenceObserver {
    Result observe(
            Path root,
            List<Path> files,
            List<ClassifierObservation> sourceClassifiers,
            List<MemberObservation> sourceMembers,
            JavaDependencyObservation.Result support,
            List<Path> dependencyArchives) {
        JavaDependencyInputs inputs = JavaDependencyInputs.global(dependencyArchives);
        JavaCompilationContexts.Grouping grouping = JavaCompilationContexts.group(root, files, inputs);
        boolean complete = grouping.unownedFiles().isEmpty();
        Map<String, List<String>> inherited = new HashMap<>();
        Map<String, List<String>> overrides = new HashMap<>();
        Map<String, String> returnTypes = new HashMap<>();
        List<ObservationDiagnostic> diagnostics = new ArrayList<>();

        for (Map.Entry<String, List<Path>> entry : grouping.filesByContext().entrySet()) {
            JavaCompilationContext compilationContext = grouping.context(entry.getKey());
            Set<String> scopedPaths = entry.getValue().stream()
                    .map(path -> relativePath(root, path))
                    .collect(java.util.stream.Collectors.toSet());
            List<ClassifierObservation> scopedClassifiers = sourceClassifiers.stream()
                    .filter(item -> scopedPaths.contains(item.sourcePath())).toList();
            List<MemberObservation> scopedMembers = sourceMembers.stream()
                    .filter(item -> scopedPaths.contains(item.sourcePath())).toList();
            Set<String> upstreamIds = inputs.context(compilationContext.id()) == null
                    ? Set.of() : inputs.upstreamClosure(compilationContext.id());
            Set<String> upstreamPaths = upstreamIds.stream()
                    .flatMap(id -> grouping.filesByContext().getOrDefault(id, List.of()).stream())
                    .map(path -> relativePath(root, path))
                    .collect(java.util.stream.Collectors.toSet());
            List<ClassifierObservation> upstreamClassifiers = sourceClassifiers.stream()
                    .filter(item -> upstreamPaths.contains(item.sourcePath())).toList();
            List<MemberObservation> upstreamMembers = sourceMembers.stream()
                    .filter(item -> upstreamPaths.contains(item.sourcePath())).toList();

            Result observed;
            try (JavacCompilationContext context = JavacCompilationContext.prepare(
                    root, compilationContext, grouping.filesByContext(), inputs)) {
                observed = context.complete()
                        ? observeCompilationContext(
                                root, entry.getValue(), scopedClassifiers, scopedMembers,
                                upstreamClassifiers, upstreamMembers, support.classifiers(),
                                support.members(), context.options())
                        : Result.incomplete(context.diagnostics());
            } catch (Exception failure) {
                observed = Result.incomplete(List.of(new ObservationDiagnostic(
                        DiagnosticKind.EVIDENCE_INCOMPLETE, relativePath(root, entry.getValue().get(0)), 0,
                        "javac dependency evidence context failed: " + failure.getClass().getSimpleName())));
            }
            complete &= observed.complete();
            diagnostics.addAll(observed.diagnostics());
            complete &= mergeConsistent(inherited, observed.inheritedByClassifier(), diagnostics,
                    "inherited-member evidence");
            complete &= mergeConsistent(overrides, observed.overriddenMemberKeysByMember(), diagnostics,
                    "override evidence");
            complete &= mergeConsistent(returnTypes, observed.returnTypesByMember(), diagnostics,
                    "return-type evidence");
        }
        if (!grouping.unownedFiles().isEmpty()) {
            diagnostics.add(new ObservationDiagnostic(
                    DiagnosticKind.EVIDENCE_INCOMPLETE, relativePath(root, grouping.unownedFiles().get(0)), 0,
                    "Java source is not owned by an observed compilation context"));
        }
        if (!complete) { inherited.clear(); overrides.clear(); returnTypes.clear(); }
        return new Result(complete, Map.copyOf(inherited), Map.copyOf(overrides), Map.copyOf(returnTypes),
                diagnostics.stream().distinct().sorted(Comparator.comparing(ObservationDiagnostic::sourcePath)
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

    static Result observeCompilationContext(
            Path root,
            List<Path> files,
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            List<ClassifierObservation> productionClassifiers,
            List<MemberObservation> productionMembers,
            List<ClassifierObservation> supportClassifiers,
            List<MemberObservation> supportMembers,
            List<String> compilerOptions) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return Result.incomplete(List.of(new ObservationDiagnostic(
                    DiagnosticKind.EVIDENCE_INCOMPLETE,
                    relativePath(root, files.get(0)), 0, "JDK compiler is unavailable")));
        }
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                collector, java.util.Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> sources = fileManager.getJavaFileObjectsFromPaths(files);
            JavacTask task = (JavacTask) compiler.getTask(
                    null, fileManager, collector, compilerOptions, null, sources);
            List<CompilationUnitTree> parsed = new ArrayList<>();
            task.parse().forEach(parsed::add);
            task.analyze();
            if (collector.getDiagnostics().stream().anyMatch(item -> item.getKind() == Diagnostic.Kind.ERROR)) {
                return Result.incomplete(evidenceDiagnostics(root, files, collector));
            }
            return resolve(
                    root, parsed, task, classifiers, members,
                    productionClassifiers, productionMembers,
                    supportClassifiers, supportMembers);
        } catch (IOException | RuntimeException | StackOverflowError failure) {
            return Result.incomplete(List.of(new ObservationDiagnostic(
                    DiagnosticKind.EVIDENCE_INCOMPLETE,
                    relativePath(root, files.get(0)), 0,
                    "javac dependency evidence failed: " + failure.getClass().getSimpleName())));
        }
    }

    private static Result resolve(
            Path root,
            List<CompilationUnitTree> parsed,
            JavacTask task,
            List<ClassifierObservation> classifiers,
            List<MemberObservation> members,
            List<ClassifierObservation> productionClassifiers,
            List<MemberObservation> productionMembers,
            List<ClassifierObservation> supportClassifiers,
            List<MemberObservation> supportMembers) throws IOException {
        Trees trees = Trees.instance(task);
        Elements elements = task.getElements();
        Types types = task.getTypes();
        JavacSourceTypeIndex sourceByLocation = JavacSourceTypeIndex.create(root, parsed, trees, elements, classifiers);
        Map<String, List<ClassifierObservation>> productionByName = byQualifiedName(productionClassifiers);
        Map<String, List<ClassifierObservation>> supportByName = byQualifiedName(supportClassifiers);

        Map<String, MemberObservation> membersByKey = new HashMap<>();
        for (MemberObservation member : concatMembers(members, productionMembers, supportMembers)) {
            if (membersByKey.put(member.technicalKey(), member) != null) {
                return incomplete(classifiers, "duplicate canonical member key in dependency evidence context");
            }
        }
        Map<MemberLocator, List<MemberObservation>> membersByOwner = new HashMap<>();
        for (ClassifierObservation classifier : concatClassifiers(
                classifiers, productionClassifiers, supportClassifiers)) {
            for (String key : classifier.declaredMemberKeys()) {
                MemberObservation member = membersByKey.get(key);
                if (member == null) {
                    return incomplete(classifiers, "canonical declaration points to an unavailable member");
                }
                membersByOwner.computeIfAbsent(
                        new MemberLocator(classifier.id(), member.kind(), member.memberName()),
                        ignored -> new ArrayList<>()).add(member);
            }
        }

        Map<String, TypeElement> typeByClassifier = new HashMap<>();
        Map<String, List<String>> inheritedByClassifier = new HashMap<>();
        Map<String, ExecutableElement> localMethods = new HashMap<>();
        Map<String, List<String>> overrides = new HashMap<>();
        Map<String, String> returnTypes = new HashMap<>();

        for (ClassifierObservation classifier : classifiers) {
            TypeElement type = sourceByLocation.type(classifier);
            if (type == null) {
                return Result.incomplete(List.of(new ObservationDiagnostic(
                        DiagnosticKind.EVIDENCE_INCOMPLETE, classifier.sourcePath(), classifier.startLine(),
                        "javac source classifier could not be mapped uniquely: "
                                + classifier.qualifiedName() + " at line " + classifier.startLine())));
            }
            typeByClassifier.put(classifier.id(), type);

            LinkedHashSet<String> inherited = new LinkedHashSet<>();
            for (Element element : elements.getAllMembers(type)) {
                MemberKind kind = memberKind(element.getKind());
                if (kind == null) {
                    continue;
                }
                Owner owner = ownerOf(
                        root, trees, elements, element, sourceByLocation, productionByName, supportByName);
                if (owner == null || owner.classifier().id().equals(classifier.id())) {
                    continue;
                }
                MemberObservation declaration = !owner.binary() && element instanceof ExecutableElement method
                        ? sourceMethodDeclaration(root, trees, owner.classifier(), method, membersByOwner, types, elements)
                        : uniqueDeclaration(
                        membersByOwner.get(new MemberLocator(
                                owner.classifier().id(), kind, element.getSimpleName().toString())),
                        element,
                        types, elements);
                if (declaration == null) {
                    if (owner.binary()) {
                        continue;
                    }
                    return incomplete(classifiers, "javac inherited declaration could not be mapped uniquely");
                }
                inherited.add(declaration.technicalKey());
            }
            inheritedByClassifier.put(classifier.id(), inherited.stream().sorted().toList());

            for (Element enclosed : type.getEnclosedElements()) {
                if (!(enclosed instanceof ExecutableElement method)
                        || enclosed.getKind() != ElementKind.METHOD
                        || trees.getTree(method) == null) {
                    continue;
                }
                MemberObservation declaration = sourceMethodDeclaration(
                        root, trees, classifier, method, membersByOwner, types, elements);
                if (declaration == null) {
                    return incomplete(classifiers, "javac local method could not be mapped uniquely");
                }
                localMethods.put(declaration.technicalKey(), method);
                String returnType = method.getReturnType().toString();
                if (returnType == null || returnType.isBlank()) {
                    return incomplete(classifiers, "javac local method return type is unavailable");
                }
                returnTypes.put(declaration.technicalKey(), returnType);
                overrides.put(declaration.technicalKey(), List.of());
            }
        }

        long expectedMethods = members.stream().filter(member -> member.kind() == MemberKind.METHOD).count();
        if (localMethods.size() != expectedMethods) {
            return incomplete(classifiers, "not every source method was independently mapped by javac");
        }

        for (ClassifierObservation classifier : classifiers) {
            TypeElement ownerType = typeByClassifier.get(classifier.id());
            for (String localKey : classifier.declaredMemberKeys()) {
                ExecutableElement local = localMethods.get(localKey);
                if (local == null) {
                    continue;
                }
                LinkedHashSet<String> targets = new LinkedHashSet<>();
                String mappingFailure = collectOverrideTargets(
                        root,
                        trees,
                        elements,
                        types,
                        ownerType,
                        ownerType,
                        local,
                        sourceByLocation,
                        productionByName,
                        supportByName,
                        membersByOwner,
                        targets,
                        new LinkedHashSet<>());
                if (mappingFailure != null) {
                    return incomplete(classifiers, mappingFailure);
                }
                overrides.put(localKey, targets.stream().sorted().toList());
            }
        }

        return new Result(true, inheritedByClassifier, overrides, returnTypes, List.of());
    }

    private static String collectOverrideTargets(
            Path root,
            Trees trees,
            Elements elements,
            Types types,
            TypeElement sourceOwner,
            TypeElement ancestorCursor,
            ExecutableElement local,
            JavacSourceTypeIndex sourceByLocation,
            Map<String, List<ClassifierObservation>> productionByName,
            Map<String, List<ClassifierObservation>> supportByName,
            Map<MemberLocator, List<MemberObservation>> membersByOwner,
            Set<String> targets,
            Set<String> visitedAncestors) throws IOException {
        for (var mirror : types.directSupertypes(ancestorCursor.asType())) {
            Element element = types.asElement(mirror);
            if (!(element instanceof TypeElement ancestorType)) {
                continue;
            }
            String ancestorName = ancestorType.getQualifiedName().toString();
            if (!visitedAncestors.add(ancestorName)) {
                continue;
            }
            Owner ancestorOwner = ownerOfType(
                    root, trees, elements, ancestorType, sourceByLocation, productionByName, supportByName);
            if (ancestorOwner != null) {
                for (Element candidate : ancestorType.getEnclosedElements()) {
                    if (!(candidate instanceof ExecutableElement inheritedMethod)
                            || candidate.getKind() != ElementKind.METHOD) {
                        continue;
                    }
                    if (!elements.overrides(local, inheritedMethod, sourceOwner)) {
                        continue;
                    }
                    MemberObservation target = !ancestorOwner.binary()
                            ? sourceMethodDeclaration(root, trees, ancestorOwner.classifier(),
                                    inheritedMethod, membersByOwner, types, elements)
                            : uniqueDeclaration(
                            membersByOwner.get(new MemberLocator(
                                    ancestorOwner.classifier().id(),
                                    MemberKind.METHOD,
                                    candidate.getSimpleName().toString())),
                            candidate,
                            types, elements);
                    if (target == null) {
                        if (ancestorOwner.binary()) {
                            continue;
                        }
                        return "javac override target could not be mapped uniquely: "
                                + ancestorType.getQualifiedName() + "." + inheritedMethod;
                    }
                    targets.add(target.technicalKey());
                }
            }
            String nested = collectOverrideTargets(
                    root, trees, elements, types, sourceOwner, ancestorType, local,
                    sourceByLocation, productionByName, supportByName,
                    membersByOwner, targets, visitedAncestors);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static Owner ownerOfType(
            Path root,
            Trees trees,
            Elements elements,
            TypeElement type,
            JavacSourceTypeIndex sourceByLocation,
            Map<String, List<ClassifierObservation>> productionByName,
            Map<String, List<ClassifierObservation>> supportByName) throws IOException {
        SourcePoint source = sourcePoint(root, trees, type);
        if (source != null) {
            ClassifierObservation classifier = sourceByLocation.classifier(type);
            return classifier == null ? null : new Owner(classifier, false);
        }
        String qualifiedName = elements.getBinaryName(type).toString();
        ClassifierObservation production = unique(productionByName.get(qualifiedName));
        if (production != null) {
            return new Owner(production, true);
        }
        ClassifierObservation support = unique(supportByName.get(qualifiedName));
        return support == null ? null : new Owner(support, true);
    }

    private static Owner ownerOf(
            Path root,
            Trees trees,
            Elements elements,
            Element member,
            JavacSourceTypeIndex sourceByLocation,
            Map<String, List<ClassifierObservation>> productionByName,
            Map<String, List<ClassifierObservation>> supportByName) throws IOException {
        Element enclosing = member.getEnclosingElement();
        if (!(enclosing instanceof TypeElement type)) {
            return null;
        }
        return ownerOfType(root, trees, elements, type, sourceByLocation, productionByName, supportByName);
    }

    private static MemberObservation sourceMethodDeclaration(
            Path root,
            Trees trees,
            ClassifierObservation owner,
            ExecutableElement method,
            Map<MemberLocator, List<MemberObservation>> membersByOwner,
            Types types, Elements elements) throws IOException {
        Tree tree = trees.getTree(method);
        if (!(tree instanceof MethodTree methodTree)) {
            return null;
        }
        SourcePoint point = methodDeclarationPoint(root, trees, method, methodTree);
        if (point == null) {
            return null;
        }
        List<MemberObservation> candidates = membersByOwner.get(
                new MemberLocator(owner.id(), MemberKind.METHOD, method.getSimpleName().toString()));
        if (candidates == null) {
            return null;
        }
        List<MemberObservation> atLine = candidates.stream()
                .filter(candidate -> candidate.sourcePath().equals(point.path())
                        && candidate.startLine() == point.line()).toList();
        if (atLine.size() == 1) {
            return atLine.get(0);
        }
        return uniqueDeclaration(atLine, method, types, elements);
    }

    private static MemberObservation uniqueDeclaration(
            List<MemberObservation> candidates,
            Element element,
            Types types, Elements elements) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (!(element instanceof ExecutableElement method)) {
            return candidates.size() == 1 ? candidates.get(0) : null;
        }
        List<MemberObservation> matching = candidates.stream()
                .filter(candidate -> JavacDeclarationTypes.matches(
                        candidate.parameterTypes(), method, types, elements)).toList();
        return matching.size() == 1 ? matching.get(0) : null;
    }

    private static SourcePoint sourcePoint(Path root, Trees trees, Element element) throws IOException {
        TreePath path = trees.getPath(element);
        if (path == null) {
            return null;
        }
        return sourcePoint(root, trees, path.getCompilationUnit(), path.getLeaf());
    }

    private static SourcePoint methodDeclarationPoint(
            Path root,
            Trees trees,
            ExecutableElement method,
            MethodTree methodTree) throws IOException {
        TreePath path = trees.getPath(method);
        if (path == null) {
            return null;
        }
        Tree anchor = methodTree.getReturnType() == null ? methodTree : methodTree.getReturnType();
        return sourcePoint(root, trees, path.getCompilationUnit(), anchor);
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

    private static List<ObservationDiagnostic> evidenceDiagnostics(
            Path root,
            List<Path> files,
            DiagnosticCollector<JavaFileObject> collector) {
        String fallback = relativePath(root, files.get(0));
        return collector.getDiagnostics().stream()
                .filter(item -> item.getKind() == Diagnostic.Kind.ERROR)
                .map(item -> new ObservationDiagnostic(
                        DiagnosticKind.EVIDENCE_INCOMPLETE,
                        fallback,
                        item.getLineNumber() < 0 ? 0 : Math.toIntExact(item.getLineNumber()),
                        item.getMessage(java.util.Locale.ROOT)))
                .toList();
    }

    private static Result incomplete(List<ClassifierObservation> classifiers, String message) {
        String source = classifiers.isEmpty() ? "<unknown>.java" : classifiers.get(0).sourcePath();
        return Result.incomplete(List.of(new ObservationDiagnostic(
                DiagnosticKind.EVIDENCE_INCOMPLETE, source, 0, message)));
    }

    private static Map<String, List<ClassifierObservation>> byQualifiedName(
            List<ClassifierObservation> classifiers) {
        Map<String, List<ClassifierObservation>> result = new HashMap<>();
        for (ClassifierObservation classifier : classifiers) {
            result.computeIfAbsent(classifier.qualifiedName(), ignored -> new ArrayList<>()).add(classifier);
        }
        return result;
    }

    private static ClassifierObservation unique(List<ClassifierObservation> candidates) {
        return candidates != null && candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static List<ClassifierObservation> concatClassifiers(
            List<ClassifierObservation> first,
            List<ClassifierObservation> second,
            List<ClassifierObservation> third) {
        List<ClassifierObservation> result = new ArrayList<>(first.size() + second.size() + third.size());
        result.addAll(first);
        result.addAll(second);
        result.addAll(third);
        return result;
    }

    private static List<MemberObservation> concatMembers(
            List<MemberObservation> first,
            List<MemberObservation> second,
            List<MemberObservation> third) {
        List<MemberObservation> result = new ArrayList<>(first.size() + second.size() + third.size());
        result.addAll(first);
        result.addAll(second);
        result.addAll(third);
        return result;
    }

    private static MemberKind memberKind(ElementKind kind) {
        return switch (kind) {
            case METHOD -> MemberKind.METHOD;
            case FIELD, ENUM_CONSTANT -> MemberKind.ATTRIBUTE;
            default -> null;
        };
    }

    private static String relativePath(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    record Result(
            boolean complete,
            Map<String, List<String>> inheritedByClassifier,
            Map<String, List<String>> overriddenMemberKeysByMember,
            Map<String, String> returnTypesByMember,
            List<ObservationDiagnostic> diagnostics) {
        Result {
            inheritedByClassifier = Map.copyOf(inheritedByClassifier);
            overriddenMemberKeysByMember = Map.copyOf(overriddenMemberKeysByMember);
            returnTypesByMember = Map.copyOf(returnTypesByMember);
            diagnostics = List.copyOf(diagnostics);
        }

        static Result incomplete(List<ObservationDiagnostic> diagnostics) {
            return new Result(false, Map.of(), Map.of(), Map.of(), diagnostics);
        }
    }


    private record MemberLocator(String ownerId, MemberKind kind, String name) {
    }

    private record SourcePoint(String path, int line) {
    }

    private record Owner(ClassifierObservation classifier, boolean binary) {
    }
}
