# Conformance pipeline: production source candidate

This branch distributes implementation and runtime providers only. Regression
tests, experiment fixtures and generated research evidence remain in the local
validated development history, as requested. It is a candidate distribution,
not a claim of full research readiness or a promotion of main.

Build on Linux with JDK 17 and Maven: `mvn package`.
Run `java -jar target/metamodel-conformance-pipeline-next-0.15.0-rc.46.jar --help`
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

rc42 retains explicit method-body syntax independently of successful Java analysis.
Interface Object lookup carriers do not become inherited source declarations or override ancestors.
Explicit superinterface declarations and normal class/Object inheritance remain observed.
Validated with 477 current regression cases across a focused package and bounded Maven shards,
twelve small native/source/reference/replay cases (including a repeat) and two model-fault controls.
Controls use serialized analysis with identity-bound JVM class metadata sharing; native compiler, reference and replay remain separate.
Cold-process startup exceeded its limit on one case; general runtime and three-language F3/F4 research readiness remain open.

rc43 retains Python class-method source suites independently of runtime binding.
Guarded vanilla classes admit explicit implementation bindings and concrete-class evidence.
Compiler scope errors retain positive spans while withholding completeness; target code is not executed by extraction.
Validated with 486 current regression cases and twelve small native/full-reference/replay cases.
Legal abstract/static callable bodies and unknown transformations remain distinct from fixed-P0 findings.
ABC/custom-metaclass binding, C++ cross-TU closure and full research-release readiness remain open.

rc44 captures configured CMake File API target facts in ordered-v5 manifests.
The configured-model digest participates in C++ compiler-world identity; exact generated capture bytes are checked before selected-build execution.
Declared target dependencies and link fragments do not certify implementation or consumed-linker-input closure.
Validated with 492 current JVM tests, fourteen script controls, three small actual-provider/native/full-reference/replay cases and a complete typed repeat with source timestamps preserved.
A paired accepted/candidate check preserves existing source facts and all eleven decision statuses.
C++ cross-TU body/binding admission and the full research-release gates remain open.

rc45 retains native C++ source bodies independently from declaration correspondence.
Positive same-invocation canonical bindings and native classifier abstraction are retained; complete selected-program implementation bindings remain unavailable.
Body spans use consumed source bytes; macro spans and dependent abstraction stay unknown.
Validated with 500 current JVM tests and thirteen small full-reference/replay controls.
Whole research-release gates remain open.

rc46 supports an explicit configured C++ target source population.
Pass an optional JSON file with configuration and targetNames as the third resolver argument.
Dependency edges do not select sources automatically; excluded translation units and headers do not acquire fallback compiler worlds.
Selection is retained in provenance and context identity. This is direct source membership, not transitive link closure or cross-TU binding completeness.
Validated with 513 current JVM tests, 23 script controls, three actual-provider/native/full-reference/replay selected cases, a typed repeat and accepted/candidate legacy preservation.
Whole research-release gates remain open.
