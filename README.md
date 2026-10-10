# Conformance pipeline: production source candidate

This branch distributes implementation and runtime providers only. Regression
tests, experiment fixtures and generated research evidence remain in the local
validated development history, as requested. It is a candidate distribution,
not a claim of full research readiness or a promotion of main.

Build on Linux with JDK 17 and Maven: `mvn package`.
Run `java -jar target/metamodel-conformance-pipeline-next-0.15.0-rc.26.jar --help`
for supported commands. The Maven/Gradle/CMake/Python provider scripts are in
`scripts/`; retain the manifest's accompanying cache while observing a project.
Providers require their documented build tools and container runtime.

Producer rc.26 was validated locally with 390 passing regression tests and four
small paired native/observation/XMI/full-reference/capsule-replay controls.
The frozen validated JAR SHA-256 is
`5b11146c768647b1e28b4a1feda049beb4fa0b73fb65917c7e3c896c474a13e8`.
The C++ guard repair preserved fixed P0, schema 14 and the invariant registry.
No new evidence completeness capabilities were enabled. Unknown or unsupported
semantics remain explicit; capsule replay verifies recorded observations, not
the fidelity of arbitrary source code.

The optional C++ same-pass helper source is
`src/main/resources/native/cpp-observation-helper.cpp`. It requires compatible
Clang/LLVM 14 development libraries and a separately built native executable;
enable it with `-Dmetamodel.conformance.cpp.nativeHelper=/absolute/helper/path`
before `-jar`. The ordinary backend does not provide that tracing evidence.
