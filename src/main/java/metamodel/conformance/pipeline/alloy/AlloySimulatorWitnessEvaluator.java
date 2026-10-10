package metamodel.conformance.pipeline.alloy;

import edu.mit.csail.sdg.alloy4.Err;
import edu.mit.csail.sdg.alloy4.Pos;
import edu.mit.csail.sdg.ast.Expr;
import edu.mit.csail.sdg.ast.ExprVar;
import edu.mit.csail.sdg.ast.Sig;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.sim.SimInstance;
import edu.mit.csail.sdg.sim.SimTuple;
import edu.mit.csail.sdg.sim.SimTupleset;
import edu.mit.csail.sdg.translator.A4Solution;
import edu.mit.csail.sdg.translator.A4TupleSet;

import java.util.ArrayList;
import java.util.List;

/** Task-local official Alloy AST interpretation of a SAT-validated concrete instance. */
final class AlloySimulatorWitnessEvaluator {
    static final String ID = "alloy-sim-instance";
    private final SimInstance instance;

    AlloySimulatorWitnessEvaluator(CompModule module, A4Solution solution,
            AlloyExecutionConfig config) throws Err {
        config.requireSupported();
        if (!ID.equals(config.witnessEvaluatorId())) {
            throw new IllegalArgumentException("unsupported Alloy witness evaluator");
        }
        if (!solution.satisfiable()) {
            throw new IllegalArgumentException("no SAT-validated consistent instance");
        }
        // A static model may have a TemporalInstance wrapper in Pardinus. Inspect
        // actual declarations; unsupported AST operators subsequently raise Err.
        for (Sig sig : module.getAllReachableSigs()) {
            if (sig.isVariable != null) throw new IllegalArgumentException("mutable signatures are unsupported");
            for (var field : sig.getFields()) {
                if (field.isVariable != null) throw new IllegalArgumentException("mutable fields are unsupported");
            }
        }
        instance = new SimInstance(module, solution.getBitwidth(), solution.getMaxSeq());
        for (Sig sig : module.getAllReachableSigs()) if (!sig.builtin) {
            instance.init(sig, readRelation(solution, sig));
            for (var field : sig.getFields()) if (!field.defined) {
                instance.init(field, readRelation(solution, field));
            }
        }
        // String/univ are derived from stored values by SimInstance. Retain even
        // unused literal atoms through an unreferenced, task-local variable.
        instance.init(ExprVar.make(Pos.UNKNOWN, "$imported_literal_atoms", Sig.STRING.type()),
                readRelation(solution, Sig.STRING));
        for (Sig builtin : List.of(Sig.UNIV, Sig.SIGINT, Sig.SEQIDX, Sig.STRING, Sig.NONE)) {
            requireSameRelation(solution, builtin);
        }
        for (Sig sig : module.getAllReachableSigs()) {
            requireSameRelation(solution, sig);
            for (var field : sig.getFields()) requireSameRelation(solution, field);
        }
        String invalid = instance.validate(module);
        if (!invalid.isEmpty()) {
            throw new IllegalStateException("imported Alloy instance validation failed: " + invalid);
        }
    }

    List<List<String>> evaluate(Expr expression) throws Err {
        List<List<String>> tuples = new ArrayList<>();
        for (var tuple : instance.cset(expression)) {
            List<String> atoms = new ArrayList<>();
            for (var atom : tuple) atoms.add(atom.toString());
            tuples.add(List.copyOf(atoms));
        }
        return List.copyOf(tuples);
    }

    private void requireSameRelation(A4Solution solution, Expr expression) throws Err {
        if (!readRelation(solution, expression).equals(instance.cset(expression))) {
            throw new IllegalStateException("Alloy instance import changed a relation: " + expression);
        }
    }

    static SimTupleset readRelation(A4Solution solution, Expr expression) throws Err {
        // Bare Sig/Field overloads swallow Err, including dispatch from eval(Expr).
        // An equivalent union uses the expression path which propagates errors.
        Expr union = expression.plus(expression);
        if (union instanceof Sig || union instanceof Sig.Field) {
            throw new IllegalStateException("relation wrapper collapsed to a silent overload");
        }
        Object value = solution.eval(union);
        if (!(value instanceof A4TupleSet relation)) {
            throw new IllegalStateException("Alloy instance import expected a relation");
        }
        List<SimTuple> tuples = new ArrayList<>();
        for (var tuple : relation) {
            String[] atoms = new String[tuple.arity()];
            for (int index = 0; index < atoms.length; index++) atoms[index] = tuple.atom(index);
            tuples.add(SimTuple.make(atoms));
        }
        return SimTupleset.make(tuples);
    }
}
