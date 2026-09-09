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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            for (Observation workUnit : workUnits) {
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

    private static String safeMessage(Throwable failure) {
        if (failure instanceof Err err && !Pos.UNKNOWN.equals(err.pos)) {
            return err.pos.toShortString() + ": " + err.msg;
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
