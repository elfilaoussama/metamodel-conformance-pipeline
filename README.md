# Conformance pipeline: production source candidate

This branch distributes implementation and runtime providers only. Regression
tests, experiment fixtures and generated research evidence remain in the local
validated development history, as requested. It is a candidate distribution,
not a claim of full research readiness or a promotion of main.

Build on Linux with JDK 17 and Maven: `mvn package`.
Run `java -jar target/metamodel-conformance-pipeline-next-0.15.0-rc.30.jar --help`
for supported commands. The Maven/Gradle/CMake/Python provider scripts are in
`scripts/`; retain the manifest's accompanying cache while observing a project.
Providers require their documented build tools and container runtime.

Producer rc.30 passed 405 regression tests in a fresh local normal build.
Two paired semantic families preserve all 17 relations and 11 complete
decision/witness sets with native controls and four analyses/replays.
Python metadata-reported module files and ordered configuration now survive
schema15 JSON/XMI/capsule transport. Schema14 remains supported; a reproduced
empty-field JSON compatibility defect is repaired and validated with the
frozen previous consumer. Nonempty contextual inventory transport also passes.
The frozen validated JAR SHA-256 is
`2cde9cb9d6cd53bd47a91c0e839a73ffe709429a9e1f8fa2760c9bddd9172052`.
Fixed P0, the schema14 definition and the invariant registry remain unchanged.
Reported module locations are not consumed-input receipts: bytecode, shared
images, transient loaders, full environment and input-role closure remain open.
No new evidence completeness capabilities were enabled. Unknown or unsupported
semantics remain explicit; capsule replay verifies recorded observations, not
the fidelity of arbitrary source code.

The optional C++ same-pass helper source is
`src/main/resources/native/cpp-observation-helper.cpp`. It requires compatible
Clang/LLVM 14 development libraries and a separately built native executable;
enable it with `-Dmetamodel.conformance.cpp.nativeHelper=/absolute/helper/path`
before `-jar`. The ordinary backend does not provide that tracing evidence.
