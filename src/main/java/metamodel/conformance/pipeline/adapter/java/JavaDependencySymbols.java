package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.model.ClassifierAbstraction;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.Inheritability;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberScope;
import metamodel.conformance.pipeline.model.MemberVisibility;
import metamodel.conformance.pipeline.model.MethodAbstraction;

import com.sun.source.util.JavacTask;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads semantic type/member facts from dependency bytecode through javac without loading
 * dependency classes and without executing the analyzed project's build.
 *
 * <p>The caller supplies root type names. Only dependency types reachable from those roots
 * through dependency-owned direct supertypes are materialized. Platform/JDK types remain
 * outside this dependency evidence boundary.</p>
 */
final class JavaDependencySymbols {
    private JavaDependencySymbols() {
    }

    static Result resolve(JavaDependencyClasspath.Result classpath, Set<String> rootTypeNames)
            throws ObservationException {
        Set<String> requested = rootTypeNames == null ? Set.of() : rootTypeNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        if (requested.isEmpty()) {
            return new Result(List.of(), Set.of());
        }
        if (classpath == null || classpath.entries().isEmpty()) {
            return new Result(List.of(), Set.copyOf(requested));
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new ObservationException("JDK compiler is unavailable; dependency bytecode cannot be observed");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            String classpathValue = classpath.paths().stream().map(Object::toString)
                    .collect(java.util.stream.Collectors.joining(File.pathSeparator));
            JavacTask task = (JavacTask) compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    List.of("-proc:none", "-implicit:none", "-classpath", classpathValue, "-Xlint:none"),
                    null,
                    List.of());
            Elements elements = task.getElements();
            Types types = task.getTypes();

            Map<String, TypeSymbol> materialized = new HashMap<>();
            Set<String> unresolved = new LinkedHashSet<>();
            ArrayDeque<String> pending = new ArrayDeque<>(requested);
            while (!pending.isEmpty()) {
                String requestedName = pending.removeFirst();
                if (materialized.containsKey(requestedName) || unresolved.contains(requestedName)) {
                    continue;
                }
                TypeElement type = elements.getTypeElement(requestedName);
                if (type == null) {
                    unresolved.add(requestedName);
                    continue;
                }
                String qualifiedName = type.getQualifiedName().toString();
                JavaDependencyClasspath.Entry archive = classpath.ownerOfType(qualifiedName);
                if (archive == null) {
                    unresolved.add(requestedName);
                    continue;
                }

                List<String> parents = new ArrayList<>();
                for (TypeMirror parentMirror : types.directSupertypes(type.asType())) {
                    Element parentElement = types.asElement(parentMirror);
                    if (!(parentElement instanceof TypeElement parentType)) {
                        continue;
                    }
                    String parentName = parentType.getQualifiedName().toString();
                    if (classpath.ownerOfType(parentName) != null) {
                        parents.add(parentName);
                        if (!materialized.containsKey(parentName)) {
                            pending.addLast(parentName);
                        }
                    }
                }

                List<MemberSymbol> members = new ArrayList<>();
                for (Element member : type.getEnclosedElements()) {
                    MemberSymbol symbol = memberSymbol(type, member, types);
                    if (symbol != null) {
                        members.add(symbol);
                    }
                }
                members = members.stream().distinct().sorted(MemberSymbol.ORDER).toList();
                TypeSymbol symbol = new TypeSymbol(
                        archive.unit().path(),
                        archive.unit().sha256(),
                        qualifiedName,
                        packageName(elements, type),
                        classifierKind(type.getKind()),
                        classifierAbstraction(type),
                        parents.stream().distinct().sorted().toList(),
                        members);
                materialized.put(qualifiedName, symbol);
                materialized.put(requestedName, symbol);
            }

            if (diagnostics.getDiagnostics().stream().anyMatch(item -> item.getKind() == Diagnostic.Kind.ERROR)) {
                String message = diagnostics.getDiagnostics().stream()
                        .filter(item -> item.getKind() == Diagnostic.Kind.ERROR)
                        .map(item -> item.getMessage(Locale.ROOT))
                        .filter(item -> item != null && !item.isBlank())
                        .findFirst().orElse("javac rejected dependency bytecode context");
                throw new ObservationException("dependency bytecode observation failed: " + message);
            }

            List<TypeSymbol> canonical = materialized.values().stream().distinct()
                    .sorted(Comparator.comparing(TypeSymbol::qualifiedName)
                            .thenComparing(TypeSymbol::archiveUnitPath))
                    .toList();
            return new Result(canonical, Set.copyOf(unresolved));
        } catch (ObservationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ObservationException(
                    "dependency bytecode observation failed: " + exception.getMessage(), exception);
        }
    }

    private static MemberSymbol memberSymbol(TypeElement owner, Element element, Types types) {
        if (element instanceof ExecutableElement method && element.getKind() == ElementKind.METHOD) {
            List<String> parameters = method.getParameters().stream()
                    .map(parameter -> types.erasure(parameter.asType()).toString()).toList();
            return new MemberSymbol(
                    MemberKind.METHOD,
                    method.getSimpleName().toString(),
                    parameters,
                    method.getReturnType().toString(),
                    inheritability(owner, method),
                    visibility(owner, method),
                    method.getModifiers().contains(Modifier.ABSTRACT)
                            ? MethodAbstraction.ABSTRACT : MethodAbstraction.CONCRETE,
                    method.getModifiers().contains(Modifier.STATIC)
                            ? MemberScope.STATIC : MemberScope.INSTANCE);
        }
        if (element instanceof VariableElement variable
                && (element.getKind() == ElementKind.FIELD || element.getKind() == ElementKind.ENUM_CONSTANT)) {
            return new MemberSymbol(
                    MemberKind.ATTRIBUTE,
                    variable.getSimpleName().toString(),
                    List.of(),
                    null,
                    inheritability(owner, variable),
                    visibility(owner, variable),
                    MethodAbstraction.UNKNOWN,
                    MemberScope.UNKNOWN);
        }
        return null;
    }

    private static Inheritability inheritability(TypeElement owner, Element member) {
        if (member.getModifiers().contains(Modifier.PRIVATE)) {
            return Inheritability.NOT_INHERITABLE;
        }
        if (owner.getKind().isInterface() && member.getModifiers().contains(Modifier.STATIC)) {
            return Inheritability.NOT_INHERITABLE;
        }
        return Inheritability.INHERITABLE;
    }

    private static MemberVisibility visibility(TypeElement owner, Element member) {
        if (member.getModifiers().contains(Modifier.PRIVATE)) {
            return MemberVisibility.PRIVATE;
        }
        if (member.getModifiers().contains(Modifier.PROTECTED)) {
            return MemberVisibility.PROTECTED;
        }
        if (member.getModifiers().contains(Modifier.PUBLIC) || owner.getKind().isInterface()) {
            return MemberVisibility.PUBLIC;
        }
        return MemberVisibility.PACKAGE;
    }

    private static ClassifierKind classifierKind(ElementKind kind) throws ObservationException {
        return switch (kind) {
            case CLASS, RECORD -> ClassifierKind.CLASS;
            case INTERFACE -> ClassifierKind.INTERFACE;
            case ENUM -> ClassifierKind.ENUM;
            case ANNOTATION_TYPE -> ClassifierKind.ANNOTATION;
            default -> throw new ObservationException("unsupported dependency classifier kind: " + kind);
        };
    }

    private static ClassifierAbstraction classifierAbstraction(TypeElement type) {
        return type.getModifiers().contains(Modifier.ABSTRACT) || type.getKind().isInterface()
                ? ClassifierAbstraction.ABSTRACT : ClassifierAbstraction.CONCRETE;
    }

    private static String packageName(Elements elements, TypeElement type) {
        String value = elements.getPackageOf(type).getQualifiedName().toString();
        return value.isBlank() ? "<default>" : value;
    }

    record TypeSymbol(
            String archiveUnitPath,
            String archiveSha256,
            String qualifiedName,
            String packageName,
            ClassifierKind kind,
            ClassifierAbstraction abstraction,
            List<String> parentQualifiedNames,
            List<MemberSymbol> members) {
        TypeSymbol {
            parentQualifiedNames = List.copyOf(parentQualifiedNames);
            members = List.copyOf(members);
        }
    }

    record MemberSymbol(
            MemberKind kind,
            String name,
            List<String> parameterTypes,
            String returnType,
            Inheritability inheritability,
            MemberVisibility visibility,
            MethodAbstraction abstraction,
            MemberScope scope) {
        private static final Comparator<MemberSymbol> ORDER = Comparator
                .comparing((MemberSymbol member) -> member.kind().name())
                .thenComparing(MemberSymbol::name)
                .thenComparing(member -> String.join("\u0000", member.parameterTypes()));

        MemberSymbol {
            parameterTypes = List.copyOf(parameterTypes);
        }
    }

    record Result(List<TypeSymbol> types, Set<String> unresolvedRootTypes) {
        Result {
            types = List.copyOf(types);
            unresolvedRootTypes = Set.copyOf(unresolvedRootTypes);
        }

        TypeSymbol requireType(String qualifiedName) {
            return types.stream().filter(type -> type.qualifiedName().equals(qualifiedName))
                    .findFirst().orElseThrow();
        }
    }
}
