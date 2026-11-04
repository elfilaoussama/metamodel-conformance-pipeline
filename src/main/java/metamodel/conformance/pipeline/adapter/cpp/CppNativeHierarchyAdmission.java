package metamodel.conformance.pipeline.adapter.cpp;

import metamodel.conformance.pipeline.model.NativeEvidence;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finite selected-world hierarchy evidence; never all-configuration coverage. */
final class CppNativeHierarchyAdmission {
    private CppNativeHierarchyAdmission() { }

    static boolean complete(Set<String> contexts, Set<String> tracedContexts,
            Map<String,String> classifierContexts,
            Map<String,List<NativeEvidence.DirectBase>> compilerBases) {
        if(contexts.isEmpty() || !contexts.equals(tracedContexts)
                || !compilerBases.keySet().equals(classifierContexts.keySet())
                || !contexts.containsAll(classifierContexts.values())) return false;
        for(var entry:compilerBases.entrySet())for(var base:entry.getValue()) {
            var target=base.targetClassifierId();
            if(target.knowledge()!=NativeEvidence.Knowledge.KNOWN || target.values().size()!=1
                    || !classifierContexts.get(entry.getKey()).equals(classifierContexts.get(target.values().get(0)))
                    || !Set.of(NativeEvidence.Access.PUBLIC,NativeEvidence.Access.PROTECTED,NativeEvidence.Access.PRIVATE).contains(base.access())
                    || !Set.of(NativeEvidence.Virtuality.VIRTUAL,NativeEvidence.Virtuality.NON_VIRTUAL).contains(base.virtuality())) return false;
        }
        return true;
    }
}
