#!/usr/bin/env python3
"""Attempt receipt: provider/domain/execution are independent of P0 decisions."""
import json
import os
from pathlib import Path
import sys
import tempfile

def main():
    output, repository, commit, provider, domain, clone, resolution, analysis, replay = sys.argv[1:10]
    language = sys.argv[10] if len(sys.argv) == 11 else "cpp"
    if language not in ("cpp", "python"): raise ValueError("unsupported corridor language")
    if language == "python" and domain == "SELECTED_BUILD":
        raise ValueError("Python AST observation is source-only; resolved lock metadata is not a selected-build measurement")
    root = Path(output)
    clone, resolution, analysis, replay = map(int, (clone, resolution, analysis, replay))
    capsule = root / "result/verification-capsule.json"
    success = clone == replay == 0 and analysis in (0, 2, 3) and capsule.is_file()
    data = json.loads(capsule.read_text()) if success else {}
    report = dict(receiptVersion=2, repository=repository, commit=commit,
                  providerStatus=provider, observationDomain=domain,
                  cloneExit=clone, dependencyResolutionExit=resolution,
                  analysisExit=analysis, verificationExit=replay,
                  executionStatus="REPLAY_VERIFIED" if success else "FAILED",
                  projectBuildMeasurement=False,
                  selectedBuildContextAvailable=provider == "RESOLVED" and domain == "SELECTED_BUILD",
                  measurementCompleteness="NOT_CERTIFIED",
                  language=language,
                  toolOutcome=("ANALYZED" if domain == "SELECTED_BUILD" else "SOURCE_ONLY_ANALYZED") if success else "TOOL_FAILURE",
                  observationDiagnostics=data.get("observationDiagnostics", []),
                  decisions=data.get("decisions", []))
    descriptor, temporary = tempfile.mkstemp(dir=root, prefix="report.")
    try:
        with os.fdopen(descriptor, "w") as sink:
            json.dump(report, sink, indent=2); sink.write("\n"); sink.flush(); os.fsync(sink.fileno())
        os.replace(temporary, root / "report.json")
    finally:
        if os.path.exists(temporary): os.unlink(temporary)
    print(language.upper() + "_CORRIDOR_RESULT " + json.dumps(report))
    return 0 if success else 1

if __name__ == "__main__":
    raise SystemExit(main())
