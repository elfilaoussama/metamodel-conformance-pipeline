package metamodel.conformance.pipeline.adapter.java;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.UnresolvedParent;
import metamodel.conformance.pipeline.model.NativeEvidence;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resolves source parent spellings through attributed javac trees, never import-name guessing. */
final class JavacDirectParentNames {
    private JavacDirectParentNames() { }

    record DeclaredBase(String spelling, String binaryName, String sourceClassifierId, boolean sourceDeclaration) { }
    record MethodTypes(NativeEvidence.Evidence<NativeEvidence.Parameter> parameters,
                       NativeEvidence.Evidence<String> receiverQualifiers,
                       NativeEvidence.Evidence<NativeEvidence.TypeToken> returnType) { }
    record Result(Map<UnresolvedParent, String> parentNames,
                  Map<String, NativeEvidence.Evidence<DeclaredBase>> bases,
                  Map<String, MethodTypes> signatures) {
        static Result empty() { return new Result(Map.of(), Map.of(), Map.of()); }
    }

    static Result measure(Path root, List<Path> files,
            JavaDependencyInputs inputs, Observation source) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return Result.empty();
        var grouping = JavaCompilationContexts.group(root, files, inputs);
        Map<UnresolvedParent, String> names = new HashMap<>();
        var conflicts = new HashSet<UnresolvedParent>();
        Map<String, NativeEvidence.Evidence<DeclaredBase>> bases = new HashMap<>();
        Map<String, MethodTypes> signatures = new HashMap<>();
        Map<String, MemberObservation> members = new HashMap<>();
        source.members().forEach(member -> members.put(member.technicalKey(), member));
        for (var entry : grouping.filesByContext().entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            var collector = new DiagnosticCollector<JavaFileObject>();
            try (var context = JavacCompilationContext.prepare(root, grouping.context(entry.getKey()),
                    grouping.filesByContext(), inputs);
                 var manager = compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8)) {
                if (!context.complete()) continue;
                var task = (JavacTask) compiler.getTask(null, manager, collector, context.options(), null,
                        manager.getJavaFileObjectsFromPaths(entry.getValue()));
                var parsed = new ArrayList<com.sun.source.tree.CompilationUnitTree>();
                task.parse().forEach(parsed::add);
                task.analyze();
                if (collector.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR)) continue;
                var trees = Trees.instance(task);
                var elements = task.getElements();
                var index = JavacSourceTypeIndex.create(root, parsed, trees, elements, source.classifiers());
                for (var classifier : source.classifiers()) {
                    TypeElement type = index.type(classifier);
                    TreePath path = type == null ? null : trees.getPath(type);
                    if (path == null || !(path.getLeaf() instanceof ClassTree declaration)) continue;
                    var parents = new ArrayList<ParentName>();
                    var syntax = new ArrayList<Tree>();
                    if (declaration.getExtendsClause() != null) syntax.add(declaration.getExtendsClause());
                    syntax.addAll(declaration.getImplementsClause());
                    var declaredBases = new ArrayList<DeclaredBase>();
                    for (Tree parent : syntax) {
                        TypeMirror mirror = trees.getTypeMirror(TreePath.getPath(path, parent));
                        String binary = binaryName(mirror, elements);
                        if (binary != null) parents.add(new ParentName(spelling(parent), binary));
                        TypeElement parentType = mirror != null && mirror.getKind() == TypeKind.DECLARED
                                && ((DeclaredType) mirror).asElement() instanceof TypeElement parentElement ? parentElement : null;
                        var sourceParent = parentType == null ? null : index.classifier(parentType);
                        declaredBases.add(new DeclaredBase(parent.toString(), binary,
                                sourceParent == null ? null : sourceParent.id(),
                                parentType != null && trees.getPath(parentType) != null));
                    }
                    bases.put(classifier.id(), NativeEvidence.Evidence.known(declaredBases));
                    for (String memberKey : classifier.declaredMemberKeys()) {
                        MemberObservation member = members.get(memberKey);
                        if (member == null || member.kind() != MemberKind.METHOD) continue;
                        var matches = type.getEnclosedElements().stream()
                                .filter(ExecutableElement.class::isInstance).map(ExecutableElement.class::cast)
                                .filter(method -> method.getKind() == ElementKind.METHOD && method.getSimpleName().contentEquals(member.memberName()))
                                .filter(method -> JavacDeclarationTypes.matches(member.parameterTypes(), method, task.getTypes(), elements))
                                .filter(method -> sourceMethodMatches(trees, method, member))
                                .toList();
                        if (matches.size() != 1) continue;
                        ExecutableElement method = matches.get(0);
                        MethodTree methodTree = (MethodTree) trees.getTree(method);
                        var parameters = NativeEvidence.Evidence.known(method.getParameters().stream()
                                .map(parameter -> new NativeEvidence.Parameter(parameter.asType().toString(),
                                        NativeEvidence.TypeMeaning.COMPILER_TYPE)).toList());
                        NativeEvidence.Evidence<String> receiver = methodTree.getReceiverParameter() == null
                                ? NativeEvidence.Evidence.known(List.of())
                                : NativeEvidence.Evidence.unavailable(NativeEvidence.Knowledge.UNSUPPORTED,
                                        "Java explicit receiver semantics are not represented by qualifier tokens");
                        NativeEvidence.Evidence<NativeEvidence.TypeToken> returned = NativeEvidence.Evidence.known(
                                List.of(new NativeEvidence.TypeToken(method.getReturnType().toString(), NativeEvidence.TypeMeaning.COMPILER_TYPE)));
                        signatures.put(memberKey, new MethodTypes(parameters, receiver, returned));
                    }
                    var effective = new ArrayList<TypeMirror>(type.getInterfaces());
                    effective.add(type.getSuperclass());
                    for (TypeMirror parent : effective) {
                        String binary = binaryName(parent, elements);
                        if (binary != null) parents.add(new ParentName(binary, binary));
                    }
                    for (var unresolved : source.unresolvedParents()) {
                        if (!unresolved.ownerId().equals(classifier.id()) || conflicts.contains(unresolved)) continue;
                        var candidates = parents.stream().filter(p -> normalized(p.spelling()).equals(normalized(unresolved.targetName()))
                                || normalized(p.binary()).equals(normalized(unresolved.targetName())))
                                .map(ParentName::binary).distinct().toList();
                        if (candidates.size() != 1) continue;
                        String previous = names.putIfAbsent(unresolved, candidates.get(0));
                        if (previous != null && !previous.equals(candidates.get(0))) {
                            conflicts.add(unresolved); names.remove(unresolved);
                        }
                    }
                }
            } catch (Exception | StackOverflowError unavailable) {
                // Existing unresolved evidence remains unresolved; this pass grants no capabilities.
            }
        }
        return new Result(Map.copyOf(names), Map.copyOf(bases), Map.copyOf(signatures));
    }

    private static boolean sourceMethodMatches(Trees trees, ExecutableElement method, MemberObservation member) {
        TreePath path = trees.getPath(method);
        if (path == null || !(path.getLeaf() instanceof MethodTree)) return false;
        var unit = path.getCompilationUnit();
        long start = trees.getSourcePositions().getStartPosition(unit, path.getLeaf());
        long end = trees.getSourcePositions().getEndPosition(unit, path.getLeaf());
        if (start < 0 || end <= start) return false;
        int startLine = (int) unit.getLineMap().getLineNumber(start);
        int endLine = (int) unit.getLineMap().getLineNumber(end - 1);
        return member.startLine() >= startLine && member.startLine() <= endLine && member.endLine() == endLine;
    }

    private static String spelling(Tree tree) {
        if (tree instanceof ParameterizedTypeTree parameterized) return spelling(parameterized.getType());
        if (tree instanceof AnnotatedTypeTree annotated) return spelling(annotated.getUnderlyingType());
        return tree.toString();
    }

    private static String binaryName(TypeMirror mirror, javax.lang.model.util.Elements elements) {
        return mirror != null && mirror.getKind() == TypeKind.DECLARED
                && ((DeclaredType) mirror).asElement() instanceof TypeElement type
                ? elements.getBinaryName(type).toString() : null;
    }

    private static String normalized(String name) { return name.replace('$', '.'); }
    private record ParentName(String spelling, String binary) { }
}
