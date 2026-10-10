package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.model.ClassifierAbstraction;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.Inheritability;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberScope;
import metamodel.conformance.pipeline.model.MemberVisibility;
import metamodel.conformance.pipeline.model.MethodAbstraction;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.SourceUnit;

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
        return resolve(classpath, rootTypeNames, null, List.of());
    }

    /**
     * Resolves dependency and, when supplied, platform symbols through the exact javac options
     * reconstructed for a compilation context. A platform symbol is never inferred by name.
     */
    static Result resolve(
            JavaDependencyClasspath.Result classpath,
            Set<String> rootTypeNames,
            JavaPlatformProvenance platform,
            List<String> compilerOptions) throws ObservationException {
        Set<String> requested = rootTypeNames == null ? Set.of() : rootTypeNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        if (requested.isEmpty()) {
            return new Result(List.of(), Set.of());
        }
        if (classpath == null || (classpath.entries().isEmpty() && platform == null
                && (compilerOptions == null || compilerOptions.isEmpty()))) {
            return new Result(List.of(), Set.copyOf(requested));
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new ObservationException("JDK compiler is unavailable; dependency bytecode cannot be observed");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (JavacBinaryInputs fileManager = new JavacBinaryInputs(compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8))) {
            List<String> options = new ArrayList<>();
            if (compilerOptions != null && !compilerOptions.isEmpty()) {
                options.addAll(compilerOptions);
            } else {
                String classpathValue = classpath.paths().stream().map(Object::toString)
                        .collect(java.util.stream.Collectors.joining(File.pathSeparator));
                options.addAll(List.of("-proc:none", "-implicit:none", "-Xlint:none", "-classpath", classpathValue));
            }
            if (options.contains("--module-path") && !options.contains("--add-modules")) {
                // Module-path dependency types are readable only when their modules
                // are resolved. This task reads metadata without compiling sources,
                // so every module on the path is made readable.
                options.addAll(List.of("--add-modules", "ALL-MODULE-PATH"));
            }
            JavacTask task = (JavacTask) compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    options,
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
                if (type == null && requestedName.indexOf('$') >= 0) {
                    // Unresolved parents may arrive in binary nested form
                    // (Outer$Inner); javac's element lookup uses canonical names.
                    type = elements.getTypeElement(requestedName.replace('$', '.'));
                }
                if (type == null) {
                    unresolved.add(requestedName);
                    continue;
                }
                // Declaration identity uses javac's binary name, matching source
                // classifier and correspondence indexes. TypeMirror spellings
                // used for parameter/return evidence remain separate.
                String qualifiedName = elements.getBinaryName(type).toString();
                // Completion must precede origin lookup: a listed candidate is not a consumed input.
                List<? extends Element> enclosed = type.getEnclosedElements();
                JavacBinaryInputs.Read origin = fileManager.origin(type, elements);
                String binaryName = elements.getBinaryName(type).toString();
                var module = elements.getModuleOf(type);
                String moduleName = module == null || module.isUnnamed() ? "" : module.getQualifiedName().toString();
                boolean releaseLibrary = origin == null && !fileManager.hasReadsFor(binaryName)
                        && !fileManager.hasSourceReadsFor(binaryName) && platform != null
                        && platform.suppliesReleaseSymbol(binaryName, moduleName, options);
                if (origin == null && !releaseLibrary) {
                    unresolved.add(requestedName);
                    continue;
                }
                SourceUnit owner = releaseLibrary
                        ? new SourceUnit(Language.JAVA_PLATFORM, platform.unitPath(), platform.sha256())
                        : binaryUnit(origin, classpath, platform);
                if (owner == null) {
                    unresolved.add(requestedName);
                    continue;
                }

                List<String> parents = new ArrayList<>();
                for (TypeMirror parentMirror : types.directSupertypes(type.asType())) {
                    Element parentElement = types.asElement(parentMirror);
                    if (!(parentElement instanceof TypeElement parentType)) {
                        continue;
                    }
                    String parentName = elements.getBinaryName(parentType).toString();
                    if (platform == null) {
                        parentType.getEnclosedElements();
                        JavacBinaryInputs.Read parentOrigin = fileManager.origin(parentType, elements);
                        if (parentOrigin != null && binaryUnit(parentOrigin, classpath, null) == null) continue;
                    }
                    parents.add(parentName);
                    if (!materialized.containsKey(parentName)) {
                        pending.addLast(parentName);
                    }
                }

                List<MemberSymbol> members = new ArrayList<>();
                for (Element member : enclosed) {
                    MemberSymbol symbol = memberSymbol(type, member, types);
                    if (symbol != null) {
                        members.add(symbol);
                    }
                }
                members = members.stream().distinct().sorted(MemberSymbol.ORDER).toList();
                TypeSymbol symbol = new TypeSymbol(
                        owner.path(),
                        owner.sha256(),
                        platform == null ? "" : platform.sha256(),
                        owner.language(),
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
                if (unsupportedCompilerConfiguration(message)) {
                    // The running compiler cannot support the context's release
                    // level: every requested root stays unresolved and the caller
                    // records fail-closed diagnostics instead of aborting the
                    // observation. This mirrors the parser-level boundary, which
                    // reports an unsupported source level as incomplete evidence.
                    return new Result(List.of(), Set.copyOf(requested));
                }
                throw new ObservationException("dependency bytecode observation failed: " + message);
            }

            Set<String> materializedNames = materialized.values().stream()
                    .map(TypeSymbol::qualifiedName).collect(java.util.stream.Collectors.toSet());
            List<TypeSymbol> canonical = materialized.values().stream().distinct()
                    .map(type -> new TypeSymbol(
                            type.archiveUnitPath(), type.archiveSha256(), type.resolutionPlatformSha256(),
                            type.sourceLanguage(),
                            type.qualifiedName(), type.packageName(), type.kind(), type.abstraction(),
                            type.parentQualifiedNames().stream().filter(materializedNames::contains).toList(),
                            type.members()))
                    .sorted(Comparator.comparing(TypeSymbol::qualifiedName)
                            .thenComparing(TypeSymbol::archiveUnitPath))
                    .toList();
            // This adapter certifies bytecode metadata. An implicitly read source can
            // affect erasure or hierarchy without a captured source-input receipt.
            if (fileManager.hasSourceReads()) return new Result(List.of(), Set.copyOf(requested));
            fileManager.verifyUnchanged();
            classpath.verifyUnchanged();
            List<SourceUnit> consumed = fileManager.reads().stream()
                    .map(read -> binaryUnit(read, classpath, platform))
                    .filter(java.util.Objects::nonNull).distinct()
                    .sorted(Comparator.comparing(SourceUnit::path)).toList();
            return new Result(canonical, Set.copyOf(unresolved), consumed);
        } catch (ObservationException exception) {
            throw exception;
        } catch (Exception exception) {
            if (unsupportedCompilerConfiguration(exception.getMessage())) {
                return new Result(List.of(), Set.copyOf(requested));
            }
            throw new ObservationException(
                    "dependency bytecode observation failed: " + exception.getMessage(), exception);
        }
    }

    private static SourceUnit binaryUnit(JavacBinaryInputs.Read read,
            JavaDependencyClasspath.Result classpath, JavaPlatformProvenance platform) {
        if (platform != null && platform.ownsBinary(read.uri())) {
            return new SourceUnit(Language.JAVA_PLATFORM, platform.unitPath(), platform.sha256());
        }
        if ("jrt".equals(read.uri().getScheme())) return null;
        // Class bytes, not a possibly stale archive digest, bind decoded non-platform facts.
        return new SourceUnit(Language.JAVA_ARCHIVE,
                "bytecode/" + read.sha256() + "/" + read.binaryName().replace('.', '/') + ".class", read.sha256());
    }

    static java.nio.file.Path archivePath(java.net.URI uri) {
        if (!"jar".equals(uri.getScheme())) return null;
        String nested = uri.getSchemeSpecificPart();
        int separator = nested.indexOf("!/");
        if (separator < 0) return null;
        try {
            java.net.URI archive = java.net.URI.create(nested.substring(0, separator));
            return "file".equals(archive.getScheme())
                    ? java.nio.file.Path.of(archive).toAbsolutePath().normalize() : null;
        } catch (IllegalArgumentException unsupported) { return null; }
    }

    private static boolean unsupportedCompilerConfiguration(String message) {
        if (message == null) {
            return false;
        }
        return (message.contains("release version") && message.contains("not supported"))
                || message.contains("invalid source release")
                || message.contains("invalid target release")
                || message.contains("plug-in not found")
                || message.contains("plugin not found")
                || message.contains("not found on the processor path");
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
            String resolutionPlatformSha256,
            Language sourceLanguage,
            String qualifiedName,
            String packageName,
            ClassifierKind kind,
            ClassifierAbstraction abstraction,
            List<String> parentQualifiedNames,
            List<MemberSymbol> members) {
        TypeSymbol {
            resolutionPlatformSha256 = resolutionPlatformSha256 == null ? "" : resolutionPlatformSha256;
            parentQualifiedNames = List.copyOf(parentQualifiedNames);
            members = List.copyOf(members);
        }

        TypeSymbol(
                String archiveUnitPath,
                String archiveSha256,
                String qualifiedName,
                String packageName,
                ClassifierKind kind,
                ClassifierAbstraction abstraction,
                List<String> parentQualifiedNames,
                List<MemberSymbol> members) {
            this(archiveUnitPath, archiveSha256, "", Language.JAVA_ARCHIVE, qualifiedName, packageName,
                    kind, abstraction, parentQualifiedNames, members);
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

    record Result(List<TypeSymbol> types, Set<String> unresolvedRootTypes, List<SourceUnit> consumedUnits) {
        Result {
            types = List.copyOf(types);
            unresolvedRootTypes = Set.copyOf(unresolvedRootTypes);
            consumedUnits = List.copyOf(consumedUnits);
        }
        Result(List<TypeSymbol> types, Set<String> unresolvedRootTypes) {
            this(types, unresolvedRootTypes, List.of());
        }

        TypeSymbol requireType(String qualifiedName) {
            return types.stream().filter(type -> type.qualifiedName().equals(qualifiedName))
                    .findFirst().orElseThrow();
        }
    }
}
