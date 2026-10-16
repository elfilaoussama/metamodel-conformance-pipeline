package metamodel.conformance.pipeline.adapter;

import metamodel.conformance.pipeline.model.NativeEvidence;
import metamodel.conformance.pipeline.util.ToolProcessPolicy;
import java.util.List;

/** Shared operational provenance; it grants no semantic evidence capability. */
public final class ToolRuntimeEvidence {
    private ToolRuntimeEvidence() { }
    public static NativeEvidence.ReportedInputs reported(String contextId,ToolProcessPolicy policy) {
        return new NativeEvidence.ReportedInputs(contextId,"OBSERVER_TOOL_RESOURCE_POLICY",
                "Configured supervision of launched observation tools",List.of(),List.of(
                new NativeEvidence.ConfigurationEntry("policyIdentity",NativeEvidence.Evidence.known(List.of(policy.identity()))),
                new NativeEvidence.ConfigurationEntry("toolTimeoutMillis",NativeEvidence.Evidence.known(List.of(Long.toString(policy.timeoutMillis()))))),
                "Deadline or interrupted tools fail execution and cannot supply complete facts; "
                + "startup/filesystem and arbitrary caller-computation deadlines, OS containment "
                + "and untracked/reparented descendants are not certified by this receipt");
    }
}
