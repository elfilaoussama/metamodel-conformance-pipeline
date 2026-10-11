package metamodel.conformance.pipeline.adapter.python;

import java.util.List;
import metamodel.conformance.pipeline.model.NativeEvidence;
import metamodel.conformance.pipeline.model.SourceUnit;
import static metamodel.conformance.pipeline.model.NativeEvidence.*;

/** Captured parser inputs and configured launch, without build or consumption certification. */
final class PythonObservationInputs {
    private PythonObservationInputs() { }

    static ReportedInputs launch(String contextId, List<String> command, PythonRuntimeExecutable image) {
        return new ReportedInputs(contextId, "PYTHON_CONFIGURED_LAUNCH", "Configured ProcessBuilder command and child-reported runtime image",
                List.of(new ReportedItem("runtimeImage", image.path(), Evidence.known(List.of(
                        new Input(image.path(), image.sha256(), Evidence.unavailable(Knowledge.UNKNOWN, "image modification time not captured")))),
                        "Child-reported executable path; backing bytes verified before and after decoding")),
                List.of(new ConfigurationEntry("command", Evidence.known(command)),
                        new ConfigurationEntry("configuredWorkingDirectory", Evidence.unavailable(Knowledge.UNKNOWN, "ProcessBuilder inherits the parent working directory; child directory is reported separately")),
                        new ConfigurationEntry("environment", Evidence.unavailable(Knowledge.UNKNOWN, "inherited process environment is not captured"))),
                "Configured command is not an OS execution trace; intermediary launcher, shared images and complete consumed inputs remain unverified");
    }

    static ReportedInputs sources(String contextId, List<SourceUnit> units) {
        return new ReportedInputs(contextId, "PYTHON_CAPTURED_SOURCES", "Captured source-only parser population",
                units.stream().map(unit -> new ReportedItem(unit.path(), unit.path(), Evidence.known(List.of(
                        new Input(unit.path(), unit.sha256(), Evidence.unavailable(Knowledge.UNKNOWN, "source modification time not captured")))),
                        "Digest from the private parser snapshot; original population and bytes verified unchanged")).toList(),
                List.of(), "Captured source input does not establish build ownership, generation status or complete interpreter consumption");
    }

    static List<RoleReceipt> roles(String contextId, List<SourceUnit> units) {
        return units.stream().map(unit -> new RoleReceipt(contextId, unit.path(), unit.sha256(),
                SourceRole.UNKNOWN, Generation.UNKNOWN,
                "Selected source-only parser input; no build-target/support/noncompiled or generator attribution observed")).toList();
    }
}
