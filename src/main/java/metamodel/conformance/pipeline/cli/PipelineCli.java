package metamodel.conformance.pipeline.cli;

import metamodel.conformance.pipeline.ConformancePipeline;
import metamodel.conformance.pipeline.PipelineResult;
import metamodel.conformance.pipeline.adapter.SourceObserver;
import metamodel.conformance.pipeline.adapter.SourceObserverFactory;
import metamodel.conformance.pipeline.adapter.java.JavaDependencyInputs;
import metamodel.conformance.pipeline.capsule.CapsuleVerification;
import metamodel.conformance.pipeline.capsule.CapsuleVerifier;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.decision.DecisionStatus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PipelineCli {
    private PipelineCli() {
    }

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args) {
        try {
            if (args.length == 0 || "--help".equals(args[0]) || "help".equals(args[0])) {
                usage();
                return args.length == 0 ? 64 : 0;
            }
            return switch (args[0]) {
                case "analyze" -> analyze(slice(args));
                case "verify-capsule" -> verifyCapsule(slice(args));
                default -> {
                    System.err.println("Unknown command: " + args[0]);
                    usage();
                    yield 64;
                }
            };
        } catch (IllegalArgumentException usageError) {
            System.err.println("Usage error: " + usageError.getMessage());
            return 64;
        } catch (Exception failure) {
            String message = failure.getMessage();
            System.err.println("PIPELINE_ERROR: "
                    + (message == null ? failure.getClass().getSimpleName() : message));
            return 3;
        }
    }

    private static int analyze(String[] args) throws Exception {
        ParsedOptions options = ParsedOptions.parse(
                args,
                Set.of("source", "output"),
                Set.of("language", "dependency-manifest"),
                Set.of("external-parent", "dependency-jar"));
        Path source = Path.of(options.one("source"));
        Path output = Path.of(options.one("output"));
        List<Path> dependencyArchives = options.many("dependency-jar").stream().map(Path::of).toList();
        String dependencyManifest = options.optional("dependency-manifest");
        if (dependencyManifest != null && !dependencyArchives.isEmpty()) {
            throw new IllegalArgumentException(
                    "--dependency-manifest and --dependency-jar cannot be combined");
        }
        JavaDependencyInputs dependencyInputs = dependencyManifest == null
                ? JavaDependencyInputs.global(dependencyArchives)
                : JavaDependencyInputs.fromManifest(Path.of(dependencyManifest));
        Language language = SourceObserverFactory.parseLanguage(options.optional("language"));
        SourceObserver observer = SourceObserverFactory.create(language, dependencyInputs);
        PipelineResult result = new ConformancePipeline(observer)
                .analyze(source, output, new HashSet<>(options.many("external-parent")));
        result.decisions().forEach(decision ->
                System.out.println(decision.invariantId() + " " + decision.status() + ": " + decision.message()));
        if (!result.observation().unresolvedParents().isEmpty()) {
            System.out.println("Unresolved parents:");
            result.observation().unresolvedParents().forEach(item -> System.out.println(
                    "  " + item.targetName() + " (" + item.sourcePath() + ":" + item.line() + ")"));
        }
        if (!result.observation().diagnostics().isEmpty()) {
            System.out.println("Observation diagnostics:");
            result.observation().diagnostics().forEach(item -> System.out.println(
                    "  " + item.kind() + " " + item.sourcePath() + ":" + item.line()
                            + " " + item.message()));
        }
        if (result.decisions().stream().anyMatch(item -> !item.witnesses().isEmpty())) {
            Map<String, ClassifierObservation> byId = new HashMap<>();
            result.observation().classifiers().forEach(item -> byId.put(item.id(), item));
            Map<String, MemberObservation> membersByKey = new HashMap<>();
            result.observation().members().forEach(item -> membersByKey.put(item.technicalKey(), item));
            System.out.println("Witness:");
            result.decisions().forEach(decision -> decision.witnesses().forEach(witness -> {
                List<String> descriptions = witness.technicalKeys().stream().map(key -> {
                    ClassifierObservation classifier = byId.get(key);
                    MemberObservation member = membersByKey.get(key);
                    if (classifier != null) {
                        return classifier.qualifiedName() + " (" + classifier.sourcePath()
                                + ":" + classifier.startLine() + ")";
                    }
                    if (member != null) {
                        return member.memberName() + " (" + member.sourcePath()
                                + ":" + member.startLine() + ")";
                    }
                    return key;
                }).toList();
                System.out.println("  " + decision.invariantId() + ": " + String.join(" -> ", descriptions));
            }));
        }
        System.out.println("Capsule: " + result.capsulePath());
        if (result.decisions().stream().anyMatch(item -> item.status() == DecisionStatus.NON_CONFORMANT)) {
            return 2;
        }
        return result.decisions().stream().anyMatch(item -> item.status() == DecisionStatus.NOT_EVALUATED)
                ? 3 : 0;
    }

    private static int verifyCapsule(String[] args) {
        ParsedOptions options = ParsedOptions.parse(
                args, Set.of("capsule"), Set.of(), Set.of());
        CapsuleVerification verification = new CapsuleVerifier().verify(Path.of(options.one("capsule")));
        System.out.println((verification.valid() ? "VALID: " : "INVALID: ") + verification.message());
        return verification.valid() ? 0 : 3;
    }

    private static String[] slice(String[] values) {
        return java.util.Arrays.copyOfRange(values, 1, values.length);
    }

    private static void usage() {
        System.out.println("""
                Usage:
                  analyze --source <dir> --output <dir> [--language <java|python|cpp>]
                          [--external-parent <qualified-name>]...
                          [--dependency-jar <path>]...
                          [--dependency-manifest <source-set-to-jar.tsv>]
                  verify-capsule --capsule <verification-capsule.json>

                Java dependency inputs may be global JARs or one source-set-scoped manifest, but not both.
                Manifest rows are: canonical-source-set-relative-path<TAB>absolute-or-resolved-jar-path.
                A source-set key identifies src/<name>/java, optionally below a module-relative prefix.
                Java remains the default source language. Python observes lexical class hierarchy
                and source declaration ownership conservatively. C++ uses Clang under a fixed
                C++17 source-only profile and currently claims only class/struct direct hierarchy.
                Unsupported or configuration-dependent evidence remains NOT_EVALUATED.

                Exit codes: 0 conformant/valid, 2 non-conformant, 3 not-evaluated/invalid, 64 usage.
                """);
    }

    private record ParsedOptions(Map<String, List<String>> values) {
        static ParsedOptions parse(
                String[] args,
                Set<String> required,
                Set<String> optional,
                Set<String> repeatable) {
            Map<String, List<String>> values = new HashMap<>();
            for (int index = 0; index < args.length; index += 2) {
                if (!args[index].startsWith("--") || index + 1 >= args.length) {
                    throw new IllegalArgumentException("options must be --name value pairs");
                }
                String name = args[index].substring(2);
                if (!required.contains(name) && !optional.contains(name) && !repeatable.contains(name)) {
                    throw new IllegalArgumentException("unknown option --" + name);
                }
                if ((required.contains(name) || optional.contains(name)) && values.containsKey(name)) {
                    throw new IllegalArgumentException("option --" + name + " may appear only once");
                }
                values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(args[index + 1]);
            }
            for (String name : required) {
                if (!values.containsKey(name) || values.get(name).get(0).isBlank()) {
                    throw new IllegalArgumentException("missing --" + name);
                }
            }
            for (Map.Entry<String, List<String>> entry : values.entrySet()) {
                if (entry.getValue().stream().anyMatch(String::isBlank)) {
                    throw new IllegalArgumentException("blank value for --" + entry.getKey());
                }
            }
            return new ParsedOptions(values);
        }

        String one(String name) {
            return values.get(name).get(0);
        }

        String optional(String name) {
            List<String> items = values.get(name);
            return items == null ? null : items.get(0);
        }

        List<String> many(String name) {
            return values.getOrDefault(name, List.of());
        }
    }
}
