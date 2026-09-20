package metamodel.conformance.pipeline.alloy;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.alloy4.Err;
import edu.mit.csail.sdg.alloy4.Pos;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.ast.Func;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Solution;
import edu.mit.csail.sdg.translator.A4Tuple;
import edu.mit.csail.sdg.translator.A4TupleSet;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import metamodel.conformance.pipeline.decision.Decision;
import metamodel.conformance.pipeline.decision.DecisionStatus;
import metamodel.conformance.pipeline.decision.WitnessTuple;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.invariant.InvariantRegistry;
import metamodel.conformance.pipeline.invariant.InvariantDefinition;
import metamodel.conformance.pipeline.emf.ObservationXmiWriter;
import metamodel.conformance.pipeline.cli.PipelineCli;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public final class AlloyInvariantEvaluator {
    private final AlloyExecutionConfig executionConfig;

    public AlloyInvariantEvaluator() {
        this(AlloyExecutionConfig.frozen());
    }

    public AlloyInvariantEvaluator(AlloyExecutionConfig executionConfig) {
        executionConfig.requireSupported();
        this.executionConfig = executionConfig;
    }

    public AlloyExecutionConfig executionConfig() {
        return executionConfig;
    }

    public List<Decision> evaluateAll(Observation observation, String alloyModel) {
        InvariantRegistry registry = InvariantRegistry.load();
        Map<String, Decision> decisionsById = new LinkedHashMap<>();
        List<InvariantDefinition> evaluable = new ArrayList<>();
        for (InvariantDefinition definition : registry.all()) {
            Set<EvidenceKind> missing = missingEvidence(observation, definition);
            if (missing.isEmpty()) {
                evaluable.add(definition);
            } else {
                String names = missing.stream().map(Enum::name).sorted().collect(Collectors.joining(", "));
                decisionsById.put(definition.id(), notEvaluated(
                        definition, "Required evidence is incomplete: " + names));
            }
        }
        if (evaluable.isEmpty()) {
            return orderedDecisions(registry, decisionsById);
        }

        ExactAlloyEncoder encoder = new ExactAlloyEncoder();
        try {
            if (!encoder.encode(observation).equals(alloyModel)) {
                evaluable.forEach(definition -> decisionsById.put(definition.id(), notEvaluated(
                        definition, "The Alloy artifact does not match the canonical observation.")));
                return orderedDecisions(registry, decisionsById);
            }
        } catch (Exception | LinkageError | StackOverflowError failure) {
            evaluable.forEach(definition -> decisionsById.put(definition.id(), notEvaluated(
                    definition, "Alloy encoding validation failed: " + safeMessage(failure))));
            return orderedDecisions(registry, decisionsById);
        }

        if (processWorkers() > 1) {
            return evaluateInWorkerProcesses(observation, alloyModel, registry, evaluable, decisionsById);
        }
        AlloyWorkUnitPlanner planner = new AlloyWorkUnitPlanner();
        for (InvariantDefinition definition : evaluable) {
            try {
                decisionsById.put(definition.id(), evaluate(
                        planner.plan(observation, definition), definition, encoder, executionConfig));
            } catch (Exception | LinkageError | StackOverflowError failure) {
                decisionsById.put(definition.id(), notEvaluated(
                        definition, "Alloy work-unit planning failed: " + safeMessage(failure)));
            }
        }
        return orderedDecisions(registry, decisionsById);
    }

    private List<Decision> evaluateInWorkerProcesses(
            Observation observation, String alloyModel, InvariantRegistry registry,
            List<InvariantDefinition> evaluable, Map<String, Decision> decisionsById) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("alloy-workers-");
            Path observationPath = directory.resolve("observation.xmi");
            Path alloyPath = directory.resolve("repository-instance.als");
            new ObservationXmiWriter().write(observation, observationPath);
            Files.writeString(alloyPath, alloyModel);
            AlloyWorkUnitPlanner planner = new AlloyWorkUnitPlanner();
            for (InvariantDefinition definition : evaluable) {
                List<Observation> units = planner.plan(observation, definition);
                decisionsById.put(definition.id(), evaluateInWorkerProcesses(
                        definition, units.size(), observationPath, alloyPath));
            }
        } catch (Exception failure) {
            evaluable.forEach(definition -> decisionsById.putIfAbsent(definition.id(), notEvaluated(
                    definition, "Alloy process-worker setup failed: " + safeMessage(failure))));
        } finally {
            if (directory != null) deleteTree(directory);
        }
        return orderedDecisions(registry, decisionsById);
    }

    private Decision evaluateInWorkerProcesses(
            InvariantDefinition definition, int unitCount, Path observationPath, Path alloyPath) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(processWorkers(), unitCount));
        try {
            List<Future<Decision>> futures = new ArrayList<>();
            for (int index = 0; index < unitCount; index++) {
                final int unitIndex = index;
                futures.add(executor.submit(() -> invokeWorker(
                        definition.id(), unitIndex, observationPath, alloyPath)));
            }
            List<WitnessTuple> witnesses = new ArrayList<>();
            for (Future<Decision> future : futures) {
                Decision result = future.get();
                if (result.status() == DecisionStatus.NOT_EVALUATED) return result;
                witnesses.addAll(result.witnesses());
            }
            witnesses = witnesses.stream().distinct().sorted(
                    Comparator.comparing(witness -> String.join("\0", witness.technicalKeys()))).toList();
            return witnesses.isEmpty()
                    ? new Decision(DecisionStatus.CONFORMANT, definition.id(), definition.conformanceMessage(), List.of())
                    : new Decision(DecisionStatus.NON_CONFORMANT, definition.id(), definition.violationMessage(), witnesses);
        } finally {
            executor.shutdownNow();
        }
    }

    private static Decision invokeWorker(String invariant, int index, Path observation, Path alloy) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-Dmetamodel.conformance.alloy.processes=1", "-cp",
                System.getProperty("java.class.path"), PipelineCli.class.getName(), "evaluate-work-unit",
                "--observation", observation.toString(), "--alloy", alloy.toString(),
                "--invariant", invariant, "--unit-index", Integer.toString(index))
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0 && exit != 2 && exit != 3) throw new IOException("worker failed: " + output);
        String marker = "WORKER_DECISION_JSON=";
        int start = output.lastIndexOf(marker);
        if (start < 0) throw new IOException("worker produced no decision: " + output);
        String json = output.substring(start + marker.length()).trim();
        return new ObjectMapper().readValue(json, Decision.class);
    }

    private static int processWorkers() {
        String value = System.getProperty("metamodel.conformance.alloy.processes", "1");
        try {
            int workers = Integer.parseInt(value);
            if (workers < 1) throw new NumberFormatException();
            return workers;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("metamodel.conformance.alloy.processes must be a positive integer");
        }
    }

    private static void deleteTree(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    /**
     * Evaluates one deterministic planner projection.  This is the process-worker boundary:
     * callers may execute independent projections in separate JVMs and merge their witnesses
     * in planner order without changing the canonical observation or Alloy encoding.
     */
    public Decision evaluateWorkUnit(
            Observation observation, String alloyModel, String invariantId, int unitIndex) {
        InvariantDefinition definition = InvariantRegistry.load().require(invariantId);
        Set<EvidenceKind> missing = missingEvidence(observation, definition);
        if (!missing.isEmpty()) {
            String names = missing.stream().map(Enum::name).sorted().collect(Collectors.joining(", "));
            return notEvaluated(definition, "Required evidence is incomplete: " + names);
        }
        ExactAlloyEncoder encoder = new ExactAlloyEncoder();
        try {
            if (!encoder.encode(observation).equals(alloyModel)) {
                return notEvaluated(definition, "The Alloy artifact does not match the canonical observation.");
            }
            List<Observation> workUnits = new AlloyWorkUnitPlanner().plan(observation, definition);
            if (unitIndex < 0 || unitIndex >= workUnits.size()) {
                throw new IllegalArgumentException("work-unit index is outside the deterministic plan");
            }
            return evaluate(List.of(workUnits.get(unitIndex)), definition, encoder, executionConfig);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception | LinkageError | StackOverflowError failure) {
            return notEvaluated(definition, "Alloy work-unit planning failed: " + safeMessage(failure));
        }
    }

    private static Set<EvidenceKind> missingEvidence(
            Observation observation, InvariantDefinition definition) {
        return definition.requiredEvidence().stream()
                .filter(required -> !observation.completeEvidence().contains(required))
                .collect(Collectors.toSet());
    }

    private static List<Decision> orderedDecisions(
            InvariantRegistry registry, Map<String, Decision> decisionsById) {
        return registry.all().stream().map(definition -> {
            Decision decision = decisionsById.get(definition.id());
            if (decision == null) {
                throw new IllegalStateException("missing invariant decision: " + definition.id());
            }
            return decision;
        }).toList();
    }

    private static Decision evaluate(
            List<Observation> workUnits,
            InvariantDefinition definition,
            ExactAlloyEncoder encoder,
            AlloyExecutionConfig executionConfig) {
        List<WitnessTuple> witnesses = new ArrayList<>();
        try {
            for (int unitIndex = 0; unitIndex < workUnits.size(); unitIndex++) {
                Observation workUnit = workUnits.get(unitIndex);
                long startedAt = System.nanoTime();
                reportWorkUnit("start", definition, unitIndex, workUnits.size(), workUnit, 0L);
                String model = encoder.encode(workUnit);
                CompModule module = CompUtil.parseEverything_fromString(new A4Reporter(), model);
                Command consistencyCommand = findCommand(module, "ObservationConsistency");
                A4Solution exactSolution = TranslateAlloyToKodkod.execute_command(
                        new A4Reporter(), module.getAllReachableSigs(), consistencyCommand,
                        AlloyOptionsFactory.create(executionConfig));
                if (!exactSolution.satisfiable()) {
                    return notEvaluated(definition, "An exact Alloy work unit is inconsistent.");
                }

                Func witnessFunction = findFunction(module, definition.witnessFunction());
                Object evaluated = exactSolution.eval(witnessFunction.call());
                if (!(evaluated instanceof A4TupleSet tuples)) {
                    throw new IllegalStateException("witness function did not return a relation");
                }
                Map<String, String> atomKeys = encoder.atomTechnicalKeys(workUnit);
                for (A4Tuple tuple : tuples) {
                    if (tuple.arity() != definition.witnessArity()) {
                        throw new IllegalStateException(
                                "witness relation arity does not match the invariant registry");
                    }
                    List<String> technicalKeys = new ArrayList<>();
                    for (int index = 0; index < tuple.arity(); index++) {
                        String atom = normalizeAtom(tuple.atom(index));
                        String technicalKey = atomKeys.get(atom);
                        if (technicalKey == null) {
                            throw new IllegalStateException(
                                    "witness atom has no observation mapping: " + atom);
                        }
                        technicalKeys.add(technicalKey);
                    }
                    witnesses.add(new WitnessTuple(technicalKeys));
                }
                reportWorkUnit("complete", definition, unitIndex, workUnits.size(), workUnit,
                        System.nanoTime() - startedAt);
            }
            witnesses = witnesses.stream().distinct()
                    .sorted(Comparator.comparing(witness -> String.join("\0", witness.technicalKeys())))
                    .toList();
            if (witnesses.isEmpty()) {
                return new Decision(
                        DecisionStatus.CONFORMANT,
                        definition.id(),
                        definition.conformanceMessage(),
                        List.of());
            }
            return new Decision(
                    DecisionStatus.NON_CONFORMANT,
                    definition.id(),
                    definition.violationMessage(),
                    witnesses);
        } catch (Exception | LinkageError | StackOverflowError failure) {
            return notEvaluated(definition, "Alloy work-unit evaluation failed: " + safeMessage(failure));
        }
    }

    private static Command findCommand(CompModule module, String commandName) {
        return module.getAllCommands().stream()
                .filter(command -> commandName.equals(command.label))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "generated Alloy model has no " + commandName + " command"));
    }

    private static Func findFunction(CompModule module, String functionName) {
        for (Func function : module.getAllFunc()) {
            if (function.label.equals(functionName) || function.label.endsWith("/" + functionName)) {
                return function;
            }
        }
        throw new IllegalArgumentException("generated Alloy model has no " + functionName + " function");
    }

    private static String normalizeAtom(String label) {
        int slash = label.lastIndexOf('/');
        String atom = slash >= 0 ? label.substring(slash + 1) : label;
        int instanceSuffix = atom.lastIndexOf('$');
        return instanceSuffix >= 0 ? atom.substring(0, instanceSuffix) : atom;
    }

    private static Decision notEvaluated(InvariantDefinition definition, String message) {
        return new Decision(DecisionStatus.NOT_EVALUATED, definition.id(), message, List.of());
    }

    private static void reportWorkUnit(
            String phase,
            InvariantDefinition definition,
            int index,
            int total,
            Observation workUnit,
            long elapsedNanos) {
        if (!Boolean.getBoolean("metamodel.conformance.alloy.profile")) return;
        int atoms = workUnit.classifiers().size() + workUnit.members().size()
                + workUnit.methodBodies().size() + workUnit.implementationBindings().size();
        System.err.printf("ALLOY_WORK_UNIT phase=%s invariant=%s unit=%d/%d atoms=%d elapsedMillis=%d%n",
                phase, definition.id(), index + 1, total, atoms, elapsedNanos / 1_000_000L);
    }

    private static String safeMessage(Throwable failure) {
        if (failure instanceof Err err && !Pos.UNKNOWN.equals(err.pos)) {
            return err.pos.toShortString() + ": " + err.msg;
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
