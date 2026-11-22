package metamodel.conformance.pipeline.adapter.cpp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Declared C++ build facts read from a resolver manifest. Only declared facts
 * are consumed; unknown row kinds fail closed and the observer never guesses
 * include directories, macros, or language standards.
 */
public final class CppDependencyInputs {
    private final Map<String, CppCompileContext> contextsBySourcePath;
    private final CppConfiguredBuildModel configuredBuildModel;
    private final CppConfiguredTargetSelection targetSelection;
    private final CppInputSpace inputSpace;
    private final CppNativeInvocationPlan nativePlan;

    private CppDependencyInputs(Map<String, CppCompileContext> contextsBySourcePath, CppConfiguredBuildModel configuredBuildModel) {
        this(contextsBySourcePath,configuredBuildModel,null);
    }

    private CppDependencyInputs(Map<String, CppCompileContext> contextsBySourcePath,
            CppConfiguredBuildModel configuredBuildModel,CppConfiguredTargetSelection targetSelection) {
        this(contextsBySourcePath,configuredBuildModel,targetSelection,null);
    }

    private CppDependencyInputs(Map<String, CppCompileContext> contextsBySourcePath,
            CppConfiguredBuildModel configuredBuildModel,CppConfiguredTargetSelection targetSelection,CppInputSpace inputSpace) {
        this(contextsBySourcePath,configuredBuildModel,targetSelection,inputSpace,null);
    }
    private CppDependencyInputs(Map<String, CppCompileContext> contextsBySourcePath,
            CppConfiguredBuildModel configuredBuildModel,CppConfiguredTargetSelection targetSelection,
            CppInputSpace inputSpace,CppNativeInvocationPlan nativePlan) {
        this.contextsBySourcePath = Map.copyOf(contextsBySourcePath);
        this.configuredBuildModel = configuredBuildModel;
        this.targetSelection=targetSelection;
        this.inputSpace=inputSpace;
        this.nativePlan=nativePlan;
    }

    public static CppDependencyInputs none() {
        return new CppDependencyInputs(Map.of(), null);
    }

    public static CppDependencyInputs fromManifest(Path manifest) {
        Map<String, CppCompileContext> contextsBySourcePath = new LinkedHashMap<>();
        CppConfiguredBuildModel configuredModel = null;
        CppInputSpace inputSpace = null;
        String selection=null;
        String nativeBinding=null;
        List<String> nativeWorlds=new ArrayList<>();
        Map<String,String> nativeInvocations=new LinkedHashMap<>();
        Set<String> nativeContextIds=new java.util.HashSet<>();
        Map<String,CppCompileContext> allContexts=new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
            int lineNumber = 0;
            Map<String, Builder> contextsById = new LinkedHashMap<>();
            for (String line : lines) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String[] fields = line.split("\\t", -1);
                String kind = fields[0];
                try {
                    switch (kind) {
                        case "native-membership" -> {
                            requireFields(fields,3);
                            if(nativeBinding!=null || !fields[1].equals("cpp-native-membership-binding-v1"))
                                throw new IllegalArgumentException("exactly one supported native membership binding required");
                            nativeBinding=fields[2];
                        }
                        case "native-world" -> {
                            requireFields(fields,3);
                            if(!fields[1].equals("cpp-native-world-v1"))throw new IllegalArgumentException("unsupported native world");
                            nativeWorlds.add(fields[2]);
                        }
                        case "native-invocation" -> {
                            requireFields(fields,3);requireContextId(fields[1]);
                            if(nativeInvocations.putIfAbsent(fields[1],fields[2])!=null)
                                throw new IllegalArgumentException("duplicate native invocation");
                        }
                        case "input-space" -> {
                            requireFields(fields,3);
                            if(inputSpace!=null || !fields[1].equals(CppInputSpace.FORMAT))
                                throw new IllegalArgumentException("exactly one supported captured input space required");
                            inputSpace=CppInputSpace.parse(fields[2]);
                        }
                        case "build-selection" -> {
                            requireFields(fields,3);
                            if(selection!=null || !fields[1].equals("direct-targets-v1"))
                                throw new IllegalArgumentException("exactly one supported target population required");
                            selection=fields[2];
                        }
                        case "build-model" -> {
                            requireFields(fields, 3);
                            if (configuredModel != null || !fields[1].equals("cmake-codemodel-v2"))
                                throw new IllegalArgumentException("exactly one supported configured build model required");
                            configuredModel = CppConfiguredBuildModel.parse(fields[2]);
                        }
                        case "context" -> {
                            requireFields(fields, 3);
                            requireContextId(fields[1]);
                            if (contextsById.containsKey(fields[1])) {
                                throw new IllegalArgumentException("duplicate context row: " + fields[1]);
                            }
                            if (!List.of(".", "ordered-v2", "ordered-v3", "ordered-v4", "ordered-v5", "ordered-v6", "ordered-v7").contains(fields[2])) {
                                throw new IllegalArgumentException("unsupported context version");
                            }
                            contextsById.put(fields[1], new Builder(!fields[2].equals("."),
                                    List.of("ordered-v3","ordered-v4","ordered-v5","ordered-v6","ordered-v7").contains(fields[2]),
                                    List.of("ordered-v4","ordered-v5","ordered-v6","ordered-v7").contains(fields[2]),
                                    List.of("ordered-v5","ordered-v6","ordered-v7").contains(fields[2]),List.of("ordered-v6","ordered-v7").contains(fields[2])));
                            if(fields[2].equals("ordered-v7"))nativeContextIds.add(fields[1]);
                        }
                        case "source" -> {
                            requireFields(fields, 3);
                            Builder builder = requireBuilder(contextsById, fields[1]);
                            String path = canonicalRelativePath(fields[2], "source path");
                            if (builder.sourcePath != null) {
                                throw new IllegalArgumentException(
                                        "context declares more than one source path: " + fields[1]);
                            }
                            builder.sourcePath = path;
                        }
                        case "compiler" -> {
                            requireFields(fields, 3);
                            Builder builder = requireBuilder(contextsById, fields[1]);
                            if (!builder.sealed || builder.compiler != null) {
                                throw new IllegalArgumentException("compiler attestation requires ordered-v3 and exactly one row");
                            }
                            builder.compiler = CompilerIdentity.parse(fields[2]);
                        }
                        case "execution" -> {
                            requireFields(fields,3);
                            Builder builder=requireBuilder(contextsById,fields[1]);
                            if (!builder.layoutRequired || builder.execution != null) {
                                throw new IllegalArgumentException("execution requires ordered-v4 and exactly one row");
                            }
                            builder.execution=ExecutionLayout.parse(fields[2]);
                        }
                        case "include", "system-include", "quote-include", "after-include", "define", "undef", "std", "flag" -> {
                            requireFields(fields, 3);
                            Builder builder = requireBuilder(contextsById, fields[1]);
                            String value = fields[2];
                            if (value.isBlank() || value.contains("\r") || value.contains("\n")) {
                                throw new IllegalArgumentException("invalid operation operand");
                            }
                            if (!builder.ordered && !List.of("include", "define", "std").contains(kind)) {
                                throw new IllegalArgumentException("operation requires ordered-v2 context");
                            }
                            if (kind.endsWith("include") && !builder.layoutRequired) value = canonicalRelativePath(value, "include directory");
                            if (value.indexOf('\0')>=0) throw new IllegalArgumentException("invalid operation operand");
                            if (kind.equals("flag") && !SUPPORTED_FLAGS.contains(value)) {
                                throw new IllegalArgumentException("unsupported semantic flag: " + value);
                            }
                            if (kind.equals("std") && !value.matches("(c\\+\\+|gnu\\+\\+)(98|03|11|14|17|20|2a|2b)")) {
                                throw new IllegalArgumentException("unsupported language standard");
                            }
                            builder.operations.add(new CompileOperation(kind, value));
                        }
                        default -> throw new IllegalArgumentException(
                                "unsupported C++ manifest row kind: " + kind);
                    }
                } catch (RuntimeException failure) {
                    throw new IllegalArgumentException(
                            "invalid C++ manifest row " + lineNumber + ": " + failure.getMessage(), failure);
                }
            }
            for (Map.Entry<String, Builder> entry : contextsById.entrySet()) {
                Builder builder = entry.getValue();
                if (builder.spaceRequired != (inputSpace != null))
                    throw new IllegalArgumentException("ordered-v6 requires captured roots and cannot mix legacy contexts");
                if (builder.modelRequired != (configuredModel != null))
                    throw new IllegalArgumentException("ordered-v5 requires one configured build model and cannot mix legacy contexts");
                if (builder.sealed && builder.compiler == null) {
                    throw new IllegalArgumentException("ordered-v3 context requires compiler attestation");
                }
                if (builder.layoutRequired && builder.execution == null) {
                    throw new IllegalArgumentException("ordered-v4 context requires execution layout");
                }
                if (builder.ordered && !nativeContextIds.contains(entry.getKey()) && builder.operations.stream().noneMatch(op -> op.kind().equals("std"))) {
                    throw new IllegalArgumentException("ordered context requires an explicit language standard");
                }
                if (builder.sourcePath == null) {
                    throw new IllegalArgumentException(
                            "C++ manifest context has no source path: " + entry.getKey());
                }
                Path declaredSource=Path.of(builder.execution==null?".":builder.execution.sourceRoot()).resolve(builder.sourcePath);
                String modelSource=inputSpace==null?builder.sourcePath:
                        configuredModel.sourceRoot().relativize(declaredSource).toString().replace('\\','/');
                Path modelSourceRoot=configuredModel==null?null:configuredModel.sourceRoot();
                Path modelBuildRoot=configuredModel==null?null:configuredModel.buildRoot();
                if (inputSpace != null && (!inputSpace.observationRoot().toString().equals(builder.execution.sourceRoot())
                        || !inputSpace.bindingSha256().equals(builder.execution.treeSha256())
                        || !inputSpace.contains(declaredSource)
                        || inputSpace.roots().stream().noneMatch(root -> root.kind()==CppInputSpace.Kind.SOURCE
                                && root.path().equals(modelSourceRoot))
                        || inputSpace.roots().stream().noneMatch(root -> root.kind()==CppInputSpace.Kind.BUILD
                                && root.path().equals(modelBuildRoot))))
                    throw new IllegalArgumentException("configured build/source roots differ from captured input space");
                if (configuredModel != null && (!configuredModel.declaresSource(modelSource)
                        || (inputSpace==null && !configuredModel.sourceRoot().toString().equals(builder.execution.sourceRoot()))
                        || !Path.of(builder.execution.workingDirectory()).startsWith(configuredModel.buildRoot())))
                    throw new IllegalArgumentException("configured target model does not cover the declared source/layout");
                if (!builder.ordered && builder.operations.stream().filter(op -> op.kind().equals("std")).count() > 1) {
                    throw new IllegalArgumentException(
                            "C++ manifest context declares more than one language standard: " + entry.getKey());
                }
                CppCompileContext compiled = new CppCompileContext(
                        builder.sourcePath,
                        builder.ordered, List.copyOf(builder.operations), builder.compiler, builder.execution);
                allContexts.put(entry.getKey(),compiled);
                CppCompileContext previous = nativeContextIds.contains(entry.getKey())?null:
                        contextsBySourcePath.putIfAbsent(builder.sourcePath,compiled);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "multiple C++ contexts for one source path are unsupported: " + builder.sourcePath);
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("C++ dependency manifest is not readable: " + manifest, failure);
        }
        if (configuredModel != null && allContexts.isEmpty())
            throw new IllegalArgumentException("configured build model without compile contexts");
        if (inputSpace!=null && allContexts.isEmpty())
            throw new IllegalArgumentException("captured input space without compile contexts");
        if (inputSpace!=null && selection==null)
            throw new IllegalArgumentException("captured input space requires explicit configured target selection");
        CppDependencyInputs result=new CppDependencyInputs(contextsBySourcePath,configuredModel,null,inputSpace);
        if(!nativeContextIds.isEmpty()) {
            if(nativeBinding==null || nativeWorlds.isEmpty() || selection==null
                    || !nativeContextIds.equals(allContexts.keySet()))
                throw new IllegalArgumentException("ordered-v7 requires complete unmixed native world evidence");
            var selected=result.readSelection(selection);
            var plan=CppNativeInvocationPlan.parse(nativeBinding,nativeWorlds,nativeInvocations,
                    configuredModel,selected,allContexts,result::sourcePath);
            return new CppDependencyInputs(Map.of(),configuredModel,selected,inputSpace,plan);
        }
        if(nativeBinding!=null || !nativeWorlds.isEmpty() || !nativeInvocations.isEmpty())
            throw new IllegalArgumentException("native world evidence requires ordered-v7 contexts");
        return selection==null?result:result.selectTargets(selection);
    }

    public boolean isEmpty() {
        return nativePlan==null?contextsBySourcePath.isEmpty():nativePlan.invocations().isEmpty();
    }

    public CppConfiguredBuildModel configuredBuildModel() { return configuredBuildModel; }
    public CppConfiguredTargetSelection targetSelection() { return targetSelection; }
    public CppNativeInvocationPlan nativePlan() { return nativePlan; }
    private List<CppCompileContext> contexts() {
        return nativePlan==null?List.copyOf(contextsBySourcePath.values()):
                nativePlan.invocations().values().stream().map(CppNativeInvocationPlan.Invocation::context).toList();
    }
    String capturedSpaceSha256() { return inputSpace==null?null:inputSpace.bindingSha256(); }

    /** Selection is a measurement population, not inferred transitive link closure. */
    public CppDependencyInputs selectTargets(String configuration,Set<String> ids) {
        if(nativePlan!=null) {
            if(!targetSelection.configuration().equals(configuration)
                    || !targetSelection.targets().stream().map(CppConfiguredTargetSelection.Target::id).collect(java.util.stream.Collectors.toSet()).equals(ids))
                throw new IllegalArgumentException("native invocation plan requires its recorded target population");
            return this;
        }
        if(configuredBuildModel==null)throw new IllegalArgumentException("target selection requires a configured build model");
        var selected=CppConfiguredTargetSelection.select(configuredBuildModel,configuration,ids);
        return applySelection(selected);
    }
    private CppDependencyInputs applySelection(CppConfiguredTargetSelection selected) {
        Set<String> sources=new java.util.HashSet<>();
        for(var target:selected.targets())for(var source:target.sources())if(source.compiled()) {
            if(!source.language().equals("CXX") || !(inputSpace==null?
                    source.path().startsWith(configuredBuildModel.sourceRoot()):inputSpace.contains(source.path())))
                throw new IllegalArgumentException("selected C++ source language/layout unsupported");
            String relative=sourcePath(source.path());
            if(!contextsBySourcePath.containsKey(relative))
                throw new IllegalArgumentException("selected compiled source has no attested compile context: "+relative);
            sources.add(relative);
        }
        if(sources.isEmpty())throw new IllegalArgumentException("selected population has no compiled C++ sources");
        return new CppDependencyInputs(contextsBySourcePath,configuredBuildModel,selected,inputSpace);
    }

    public Set<String> selectedSourcePaths() {
        if(nativePlan!=null)return nativePlan.invocations().values().stream().map(i->i.context().sourcePath())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if(targetSelection==null)return Set.of();
        var result=new java.util.TreeSet<String>();
        for(var target:targetSelection.targets())for(var source:target.sources())if(source.compiled())
            result.add(sourcePath(source.path()));
        return Set.copyOf(result);
    }

    private String sourcePath(Path path) {
        return inputSpace==null?configuredBuildModel.sourceRoot().relativize(path).toString().replace('\\','/'):
                inputSpace.relativePath(path);
    }

    private CppDependencyInputs selectTargets(String value) {
        return applySelection(readSelection(value));
    }
    private CppConfiguredTargetSelection readSelection(String value) {
        if(configuredBuildModel==null)throw new IllegalArgumentException("target selection requires a configured build model");
        try {
            var mapper=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            var node=mapper.readTree(value);
            if(node==null || !node.isObject() || node.size()!=2 || !node.path("configuration").isTextual()
                    || !node.path("targetIds").isArray())throw new IllegalArgumentException("invalid target population envelope");
            Set<String> ids=new java.util.HashSet<>();
            for(var id:node.get("targetIds"))if(!id.isTextual() || !ids.add(id.textValue()))
                throw new IllegalArgumentException("target IDs must be textual and unique");
            return CppConfiguredTargetSelection.select(configuredBuildModel,node.get("configuration").textValue(),ids);
        } catch(IOException invalid) {throw new IllegalArgumentException("invalid target population JSON",invalid);}
    }

    /**
     * The declared compile context of a translation unit, or null when the
     * resolver declared no facts for it.
     */
    public CppCompileContext contextForSourcePath(String relativeSourcePath) {
        if(nativePlan!=null) {
            var matches=contexts().stream().filter(c->c.sourcePath().equals(relativeSourcePath)).toList();
            if(matches.size()>1)throw new IllegalArgumentException("configured source requires an explicit invocation identity");
            return matches.isEmpty()?null:matches.get(0);
        }
        return contextsBySourcePath.get(relativeSourcePath);
    }

    public void validateCompilerIdentity(CompilerIdentity actual) {
        for (CppCompileContext context : contexts()) {
            if (context.compiler() != null && !context.compiler().equals(actual)) {
                throw new IllegalArgumentException("declared compiler is incompatible with observing compiler for "
                        + context.sourcePath());
            }
        }
    }

    public void validateExecutionLayout(Path root) throws IOException {
        if (inputSpace!=null) inputSpace.validate(root);
        String digest=null;
        for (CppCompileContext context:contexts()) {
            ExecutionLayout layout=context.execution();
            if (layout==null) continue;
            if (!root.toString().equals(layout.sourceRoot())) {
                throw new IllegalArgumentException("configured logical source root differs from observing root");
            }
            Path cwd=Path.of(layout.workingDirectory());
            if (!Files.isDirectory(cwd) || !cwd.equals(cwd.toRealPath())) {
                throw new IllegalArgumentException("configured working directory is unavailable or aliased");
            }
            Path operand=Path.of(layout.sourceOperand());
            Path actual=(operand.isAbsolute()?operand:cwd.resolve(operand)).normalize();
            if (!actual.equals(root.resolve(context.sourcePath())) || !Files.isRegularFile(actual)
                    || !actual.equals(actual.toRealPath())) {
                throw new IllegalArgumentException("configured source operand differs from declared source");
            }
            for (CompileOperation operation:context.operations()) {
                if (operation.kind().endsWith("include")) {
                    Path include=Path.of(operation.value());
                    include=(include.isAbsolute()?include:cwd.resolve(include)).normalize();
                    if (!(inputSpace==null?include.startsWith(root):inputSpace.contains(include))
                            || !Files.isDirectory(include) || !include.equals(include.toRealPath())) {
                        throw new IllegalArgumentException("configured include is unavailable, aliased or outside the input tree");
                    }
                }
            }
            if (inputSpace!=null) continue; // Each declared tree is checked independently above.
            if (digest==null) digest=CppInputTree.digest(root);
            if (!digest.equals(layout.treeSha256())) {
                throw new IllegalArgumentException("configured input tree differs from provider receipt");
            }
        }
    }

    public record ExecutionLayout(String sourceRoot,String workingDirectory,String sourceOperand,String treeSha256) {
        public ExecutionLayout {
            for (String value:List.of(sourceRoot,workingDirectory,sourceOperand,treeSha256)) {
                if (value.isBlank() || value.indexOf('\0')>=0 || value.indexOf('\r')>=0 || value.indexOf('\n')>=0) {
                    throw new IllegalArgumentException("invalid execution layout atom");
                }
            }
            for (String value:List.of(sourceRoot,workingDirectory)) {
                Path path=Path.of(value);
                if (!path.isAbsolute() || !path.normalize().toString().equals(value)) {
                    throw new IllegalArgumentException("execution directory must be canonical and absolute");
                }
            }
            if (!treeSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid input tree digest");
            if (sourceOperand.startsWith("-")) throw new IllegalArgumentException("source operand cannot be a compiler option");
        }
        private static ExecutionLayout parse(String value) {
            try {
                var mapper=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
                var node=mapper.readTree(value);
                if (node==null || !node.isObject() || node.size()!=4) throw new IllegalArgumentException("execution layout needs exactly four fields");
                for (String key:List.of("sourceRoot","workingDirectory","sourceOperand","treeSha256")) {
                    if (!node.path(key).isTextual()) throw new IllegalArgumentException("execution layout fields must be textual");
                }
                return new ExecutionLayout(node.get("sourceRoot").asText(),node.get("workingDirectory").asText(),
                        node.get("sourceOperand").asText(),node.get("treeSha256").asText());
            } catch (IOException failure) { throw new IllegalArgumentException("invalid execution layout JSON",failure); }
        }
    }

    public record CompilerIdentity(String executableSha256, String versionSha256, String target) {
        public CompilerIdentity {
            if (executableSha256 == null || !executableSha256.matches("[0-9a-f]{64}")
                    || versionSha256 == null || !versionSha256.matches("[0-9a-f]{64}")
                    || target == null || !target.matches("[A-Za-z0-9_.+-]+")) {
                throw new IllegalArgumentException("invalid compiler identity");
            }
        }
        private static CompilerIdentity parse(String value) {
            try {
                var node = new ObjectMapper().readTree(value);
                if (node == null || !node.isObject() || node.size() != 3
                        || !node.path("executableSha256").isTextual()
                        || !node.path("versionSha256").isTextual()
                        || !node.path("target").isTextual()) {
                    throw new IllegalArgumentException("compiler identity requires exactly three textual fields");
                }
                return new CompilerIdentity(node.get("executableSha256").asText(),
                        node.get("versionSha256").asText(), node.get("target").asText());
            } catch (IOException failure) {
                throw new IllegalArgumentException("invalid compiler identity JSON", failure);
            }
        }
    }

    private static final java.util.Set<String> SUPPORTED_FLAGS = java.util.Set.of(
            "-fPIC", "-fpic", "-fPIE", "-fpie", "-pthread", "-fno-exceptions",
            "-fexceptions", "-fno-rtti", "-frtti", "-O0", "-O1", "-O2", "-O3",
            "-Os", "-Oz", "-Og", "-g", "-g0", "-g1", "-g2", "-g3",
            "-Wall", "-Wextra", "-Wpedantic", "-Werror", "-Wno-error", "-pedantic", "-pedantic-errors");

    public record CompileOperation(String kind, String value) { }
    public record CppCompileContext(String sourcePath, boolean ordered, List<CompileOperation> operations,
                                   CompilerIdentity compiler, ExecutionLayout execution) {
        public CppCompileContext { operations = List.copyOf(operations); }
        public List<String> arguments(Path root) {
            List<CompileOperation> consumed = operations;
            if (!ordered) {
                consumed = new ArrayList<>();
                for (String kind : List.of("std", "define", "include")) {
                    for (CompileOperation op : operations) if (op.kind().equals(kind)) consumed.add(op);
                }
            }
            List<String> result = new ArrayList<>();
            for (CompileOperation op : consumed) {
                switch (op.kind()) {
                    case "define" -> result.add("-D" + op.value());
                    case "undef" -> result.add("-U" + op.value());
                    case "std" -> result.add("-std=" + op.value());
                    case "flag" -> result.add(op.value());
                    default -> {
                        String option = switch (op.kind()) {
                            case "include" -> "-I";
                            case "system-include" -> "-isystem";
                            case "quote-include" -> "-iquote";
                            case "after-include" -> "-idirafter";
                            default -> throw new IllegalArgumentException("unsupported operation");
                        };
                        result.add(option); result.add(execution==null?root.resolve(op.value()).toString():op.value());
                    }
                }
            }
            return List.copyOf(result);
        }
    }
    private static final class Builder {
        private String sourcePath;
        private final boolean ordered;
        private final boolean sealed;
        private final boolean layoutRequired;
        private final boolean modelRequired;
        private final boolean spaceRequired;
        private CompilerIdentity compiler;
        private ExecutionLayout execution;
        private final List<CompileOperation> operations = new ArrayList<>();
        private Builder(boolean ordered, boolean sealed, boolean layoutRequired, boolean modelRequired,boolean spaceRequired) {
            this.ordered = ordered; this.sealed = sealed; this.layoutRequired=layoutRequired;
            this.modelRequired = modelRequired;
            this.spaceRequired = spaceRequired;
        }
    }
    private static void requireFields(String[] fields, int count) {
        if (fields.length != count) {
            throw new IllegalArgumentException("row requires " + count + " fields");
        }
    }

    private static void requireContextId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("context id must not be blank");
        }
    }

    private static Builder requireBuilder(Map<String, Builder> contextsById, String id) {
        Builder builder = contextsById.get(id);
        if (builder == null) {
            throw new IllegalArgumentException("row references an unobserved context: " + id);
        }
        return builder;
    }

    private static String canonicalRelativePath(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        String path = value.trim().replace('\\', '/');
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(label + " has unrepresentable surrounding whitespace");
        }
        if (".".equals(path)) {
            return ".";
        }
        if (path.startsWith("/") || path.endsWith("/") || path.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException(label + " must be source-root-relative: " + value);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(label + " must be canonical: " + value);
            }
        }
        return path;
    }
}
