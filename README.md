# Conformance pipeline: production source candidate

This branch distributes implementation and runtime providers only. Regression
tests, experiment fixtures and generated research evidence remain in the local
validated development history, as requested. It is a candidate distribution,
not a claim of full research readiness or a promotion of main.

Build on Linux with JDK 17 and Maven: `mvn package`.
Run `java -jar target/metamodel-conformance-pipeline-next-0.15.0-rc.28.jar --help`
for supported commands. The Maven/Gradle/CMake/Python provider scripts are in
`scripts/`; retain the manifest's accompanying cache while observing a project.
Providers require their documented build tools and container runtime.

Producer rc.28 passed 397 regression tests in a fresh local normal build.
Two paired semantic families preserve all 17 relations and 11 complete
decision/witness sets, with native controls and four full analyses/replays.
Five actual-observer controls distinguish changed parser bytes and search paths
while preserving unchanged repeats; cache/opaque-origin receipt controls pass.
The frozen validated JAR SHA-256 is
`f8e3726bdfec11d0eec57afd472e522d14ff856804acb579a4673ca2f0e8b4b6`.
Fixed P0, schema 14 and the invariant registry remain unchanged. Python reported
module backing files and configuration are fingerprinted; consumed bytecode,
shared images, transient loaders and complete environment/input-role closure
remain explicitly unverified.
No new evidence completeness capabilities were enabled. Unknown or unsupported
semantics remain explicit; capsule replay verifies recorded observations, not
the fidelity of arbitrary source code.

The optional C++ same-pass helper source is
`src/main/resources/native/cpp-observation-helper.cpp`. It requires compatible
Clang/LLVM 14 development libraries and a separately built native executable;
enable it with `-Dmetamodel.conformance.cpp.nativeHelper=/absolute/helper/path`
before `-jar`. The ordinary backend does not provide that tracing evidence.
