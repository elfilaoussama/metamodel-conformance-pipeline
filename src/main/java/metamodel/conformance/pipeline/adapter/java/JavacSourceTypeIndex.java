package metamodel.conformance.pipeline.adapter.java;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import metamodel.conformance.pipeline.model.ClassifierObservation;

import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Source correspondence only: no inheritance or invariant policy is inferred here. */
final class JavacSourceTypeIndex {
    private final Map<String, List<TypeElement>> typesByClassifier = new HashMap<>();
    private final Map<TypeElement, List<ClassifierObservation>> classifiersByType = new HashMap<>();

    static JavacSourceTypeIndex create(Path root, List<CompilationUnitTree> units,
            Trees trees, Elements elements, List<ClassifierObservation> classifiers) throws IOException {
        JavacSourceTypeIndex index = new JavacSourceTypeIndex();
        Path canonicalRoot = root.toRealPath();
        Map<SourceEnd, List<ClassifierObservation>> bySourceEnd = new HashMap<>();
        for (ClassifierObservation classifier : classifiers) {
            bySourceEnd.computeIfAbsent(new SourceEnd(classifier.sourcePath(), classifier.endLine()),
                    ignored -> new ArrayList<>()).add(classifier);
        }
        for (CompilationUnitTree unit : units) {
            Path file = Path.of(unit.getSourceFile().toUri()).toRealPath();
            if (!file.startsWith(canonicalRoot)) {
                continue;
            }
            String path = canonicalRoot.relativize(file).toString().replace('\\', '/');
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitClass(ClassTree node, Void unused) {
                    if (trees.getElement(getCurrentPath()) instanceof TypeElement type) {
                        long start = trees.getSourcePositions().getStartPosition(unit, node);
                        long end = trees.getSourcePositions().getEndPosition(unit, node);
                        if (start >= 0 && end > start) {
                            int startLine = (int) unit.getLineMap().getLineNumber(start);
                            int endLine = (int) unit.getLineMap().getLineNumber(end - 1);
                            List<ClassifierObservation> candidates = bySourceEnd.getOrDefault(
                                    new SourceEnd(path, endLine), List.of());
                            for (ClassifierObservation classifier : candidates) {
                                // javac includes declaration annotations in its start position;
                                // Spoon may start at the declaration name. Both retain the end.
                                // Names distinguish named declarations sharing the same line.
                                boolean named = type.getNestingKind() == NestingKind.TOP_LEVEL
                                        || type.getNestingKind() == NestingKind.MEMBER;
                                boolean nameMatches = !named
                                        || classifier.qualifiedName().equals(elements.getBinaryName(type).toString())
                                        || classifier.qualifiedName().equals(type.getQualifiedName().toString());
                                if (nameMatches && classifier.startLine() >= startLine
                                        && classifier.startLine() <= endLine && classifier.endLine() == endLine) {
                                    index.typesByClassifier.computeIfAbsent(classifier.id(),
                                            ignored -> new ArrayList<>()).add(type);
                                    index.classifiersByType.computeIfAbsent(type,
                                            ignored -> new ArrayList<>()).add(classifier);
                                }
                            }
                        }
                    }
                    return super.visitClass(node, unused);
                }
            }.scan(unit, null);
        }
        return index;
    }

    TypeElement type(ClassifierObservation classifier) {
        List<TypeElement> matches = typesByClassifier.getOrDefault(classifier.id(), List.of());
        if (matches.size() != 1) return null;
        TypeElement type = matches.get(0);
        return classifiersByType.get(type).size() == 1 ? type : null;
    }

    ClassifierObservation classifier(TypeElement type) {
        List<ClassifierObservation> matches = classifiersByType.getOrDefault(type, List.of());
        if (matches.size() != 1) return null;
        ClassifierObservation classifier = matches.get(0);
        return typesByClassifier.get(classifier.id()).size() == 1 ? classifier : null;
    }

    private record SourceEnd(String path, int line) {}
}
