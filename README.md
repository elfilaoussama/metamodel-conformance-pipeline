# Conformance pipeline: production source candidate

This branch distributes implementation and runtime providers only. Regression
tests, experiment fixtures and generated research evidence remain in the local
validated development history, as requested. It is a candidate distribution,
not a claim of full research readiness or a promotion of main.

Build on Linux with JDK 17 and Maven: `mvn package`.
Run `java -jar target/metamodel-conformance-pipeline-next-0.15.0-rc.32.jar --help`
for supported commands. The Maven/Gradle/CMake/Python provider scripts are in
`scripts/`; retain the manifest's accompanying cache while observing a project.
Providers require their documented build tools and container runtime.

Producer rc.32 passed 408 tests in a fresh local build. Four benign startup
controls confirm that isolated/no-site source parsing does not execute project
ast.py or PYTHONPATH/site startup hooks. Two paired tiny semantic families retain
all 17 formal relations, 11 complete decision/witness sets and capsule replay.
Configured launch and captured-source receipts are distinct from complete
execution provenance. Fixed P0 and schemas remain unchanged. The frozen JAR SHA256
is `7e5155377dae53ad408808b58ccf208f758984eb0cebf1819feb45517b547b66`.
This milestone does not certify complete language semantics or research readiness.
No new evidence completeness capabilities were enabled. Unknown or unsupported
semantics remain explicit; capsule replay verifies recorded observations, not
the fidelity of arbitrary source code.

The optional C++ same-pass helper source is
`src/main/resources/native/cpp-observation-helper.cpp`. It requires compatible
Clang/LLVM 14 development libraries and a separately built native executable;
enable it with `-Dmetamodel.conformance.cpp.nativeHelper=/absolute/helper/path`
before `-jar`. The ordinary backend does not provide that tracing evidence.
