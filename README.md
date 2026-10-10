# Conformance pipeline: production source candidate

This branch distributes implementation and runtime providers only. Regression
tests, experiment fixtures and generated research evidence remain in the local
validated development history, as requested. It is a candidate distribution,
not a claim of full research readiness or a promotion of main.

Build on Linux with JDK 17 and Maven: `mvn package`.
Run `java -jar target/metamodel-conformance-pipeline-next-0.15.0-rc.27.jar --help`
for supported commands. The Maven/Gradle/CMake/Python provider scripts are in
`scripts/`; retain the manifest's accompanying cache while observing a project.
Providers require their documented build tools and container runtime.

Producer rc.27 has 393 source-bound regression cases reconciled across a full
run and a four-test correction of one obsolete version assertion. Two paired
semantic families preserve all 17 relations and 11 decision/witness sets, with
native controls and four full analyses/replays. A three-observation same-path
control distinguishes changed executable bytes and preserves unchanged repeats.
The frozen validated JAR SHA-256 is
`e76df8f33eb2d13c3fc1164ba7d5dfd8e8ece6b43b94ce9165802732a0d9627a`.
The C++ guard repair and Python executable identity repair preserve fixed P0,
schema 14 and the invariant registry. Python standard-library and complete
runtime/environment closure remain explicitly unverified.
No new evidence completeness capabilities were enabled. Unknown or unsupported
semantics remain explicit; capsule replay verifies recorded observations, not
the fidelity of arbitrary source code.

The optional C++ same-pass helper source is
`src/main/resources/native/cpp-observation-helper.cpp`. It requires compatible
Clang/LLVM 14 development libraries and a separately built native executable;
enable it with `-Dmetamodel.conformance.cpp.nativeHelper=/absolute/helper/path`
before `-jar`. The ordinary backend does not provide that tracing evidence.
