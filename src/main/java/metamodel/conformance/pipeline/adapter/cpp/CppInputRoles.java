package metamodel.conformance.pipeline.adapter.cpp;

import metamodel.conformance.pipeline.model.NativeEvidence;
import metamodel.conformance.pipeline.model.SourceUnit;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Retained physical project inventory; never a complete per-invocation input trace. */
final class CppInputRoles {
    private CppInputRoles() { }

    static List<NativeEvidence.RoleReceipt> retain(Path root, Map<Path, String> physicalHashes,
            Map<String, SourceUnit> units, Map<String, String> sourceByContext,
            Map<String, java.util.Set<Path>> associatedInputs, CppDependencyInputs inputs) {
        var roles = new ArrayList<NativeEvidence.RoleReceipt>();
        for (var context : new TreeMap<>(sourceByContext).entrySet()) {
            Map<String, String> project = new TreeMap<>();
            for (Path physical : associatedInputs.getOrDefault(context.getKey(), java.util.Set.of())) {
                if (!physical.startsWith(root)) continue;
                String relative = root.relativize(physical).toString().replace('\\', '/');
                SourceUnit unit = units.get(relative);
                if (unit != null && unit.sha256().equals(physicalHashes.get(physical))) project.put(relative, unit.sha256());
            }
            boolean declared = inputs.contextForSourcePath(context.getValue()) != null;
            for (var input : project.entrySet()) {
                boolean operand = declared && input.getKey().equals(context.getValue());
                roles.add(new NativeEvidence.RoleReceipt(context.getKey(), input.getKey(), input.getValue(),
                        operand ? NativeEvidence.SourceRole.TARGET : NativeEvidence.SourceRole.UNKNOWN,
                        NativeEvidence.Generation.UNKNOWN,
                        operand ? "Declared translation-unit operand for this context; generation and full provider ownership/closure remain unverified"
                                : "Reported local operand/dependency with verified physical byte receipt; build role and generation remain unknown; not a complete input-consumption trace"));
            }
        }
        return List.copyOf(roles);
    }
}
