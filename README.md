# Conformance pipeline: production source candidate

This branch distributes implementation and runtime providers only. Regression
tests, experiment fixtures and generated research evidence remain in the local
validated development history, as requested. It is a candidate distribution,
not a claim of full research readiness or a promotion of main.

Build on Linux with JDK 17 and Maven: `mvn package`.
Run `java -jar target/metamodel-conformance-pipeline-next-0.15.0-rc.41.jar --help`
for supported commands. The Maven/Gradle/CMake/Python provider scripts are in
`scripts/`; retain the manifest's accompanying cache while observing a project.
Providers require their documented build tools and container runtime.

Producer rc.33 passed 413 tests in a fresh local build. Three tiny paired
C++ families retain all 17 formal relations and all 11 complete decision/witness
sets with native controls and replay. Context-qualified physical input roles
are retained without guessing header ownership or generation. Source-only roles
remain unknown; TARGET records only the explicitly declared context operand.
Build-backed C++ corridors now use the recorded immutable provider image and
configured filesystem layout. Supply CPP_OBSERVER_JAVA_HOME as an existing Linux
Java installation compatible with the provider image. Missing runtime/legacy
recipe cases fail explicitly. Local provider/corridor and deadline controls pass.
The frozen JAR SHA256 is
`d186c777a55681a830c7dcc35418e87a58cfb7533a49f2448cb7c31a9bce61f6`.
Python remains isolated/no-site. P0, schemas, registry and unrelated packaged
payloads are unchanged. Full input provenance and research readiness remain open.
No new evidence completeness capabilities were enabled. Unknown or unsupported
semantics remain explicit; capsule replay verifies recorded observations, not
the fidelity of arbitrary source code.

The optional C++ same-pass helper source is
`src/main/resources/native/cpp-observation-helper.cpp`. It requires compatible
Clang/LLVM 14 development libraries and a separately built native executable;
enable it with `-Dmetamodel.conformance.cpp.nativeHelper=/absolute/helper/path`
before `-jar`. The ordinary backend does not provide that tracing evidence.

Provider script follow-up: optional CPP_OBSERVER_NATIVE_HELPER forwards a
compatible same-pass helper into the C++ provider image; invalid configured
helpers fail without fallback. Generated Java output repeat checks now bind
relative file paths and bytes through a shared digest. Four tiny real-provider
generator fixtures pass. These script gates do not certify whole environment
closure or scientific study readiness.

Python resolved-environment corridors require PYTHON_OBSERVER_JAVA_HOME as an
existing compatible Linux Java home. Provider installation/inventory use one
immutable image; lock, manifest and runtime receipts are retained. The runner
checks the actual interpreter before observation and reconciles observer bytes
before replay. These measurements remain SOURCE_ONLY; dependency metadata does
not certify imported type graphs. No-lock fallback remains diagnostic. C++ and
Python now share bounded container execution and forced deadline cleanup.
Small actual locked-corridor and missing-runtime controls pass; full research
readiness remains open.

rc34 rejects intermediate as well as final artifact symlinks and checks actual
capsule-directory containment. The producer passes 413 regression tests. This
fix does not certify safety against concurrent hostile filesystem replacement
or independent source provenance; full research readiness remains open.

rc35 preserves declaration owners of reached binding target members during
projection, repairing a reproduced false-conformance case. All 422 regression
tests pass. Source fidelity and full research readiness remain separate gates.

rc36 matches ordered overload signatures and exact method-body source offsets.
All 423 regression tests pass; the original same-line fixture passes native
checks, full-witness comparison and replay. Full research readiness remains open.

rc37 adds independently checked guarded Python C3 lookup and inherited-member
projection. Dynamic namespace, metaclass and unsupported synthesized/execution
cases remain unevaluated. Implicit plain-function __new__ staticness is preserved.
All 433 integration tests pass; full research readiness remains open.

rc40 admits compiler-selected C++ inherited membership for explicitly complete
contextual lookup populations. Ambiguous, dependent and unsupported scopes stay
unevaluated. Fixed P0 remains unchanged; language/policy disagreements remain
findings. Validated with 457 integration tests, seventeen small native/provider/
formal/replay controls and a seeded overlap. Full research readiness remains open.

rc41 bounds launched C++/Python tool subprocesses during streaming reads and waits.
Timeouts and interruption are execution failures, never semantic findings.
Configured policy is retained separately from semantic capabilities in schema15.
Validated with 467 integration tests and eight small semantic preservation controls
plus four hanging-tool controls. Full stage deadlines, OS containment and complete
research readiness remain open.
