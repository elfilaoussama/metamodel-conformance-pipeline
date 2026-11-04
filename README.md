# Metamodel Conformance Pipeline

A Java/Python/C++ source-observation and formal-conformance engine. Language
observers record facts and evidence in a shared, versioned EMF model. Registered
Alloy rules evaluate those facts and return source-linked witnesses.

Public version **0.14.0** is based on the validated rc64 engineering checkpoint.
It replaces the earlier multi-module prototype. This is an engineering baseline;
whole-project semantic completeness and publication readiness remain open.
The unfinished native-Java rc65 route is not included.

```text
source + declared build inputs -> language observer -> canonical observation.xmi
    -> rule registry + Alloy model -> decisions + witnesses -> replay capsule
```

## Build and quick start

Use JDK 17 and Maven 3.9+. Check that `JAVA_HOME` points to JDK 17.

```bash
mvn package
PIPELINE_JAR=target/metamodel-conformance-pipeline-next-0.14.0.jar
mkdir -p build/example-source
printf '%s\n' 'class Example { int value; }' > build/example-source/Example.java
java -jar "$PIPELINE_JAR" --help
java -jar "$PIPELINE_JAR" analyze \
  --source build/example-source --output build/example-result
java -jar "$PIPELINE_JAR" verify-capsule \
  --capsule build/example-result/verification-capsule.json
```

This public source distribution omits regression tests and research artifacts at
the project owner's request. A successful `mvn package` here establishes a build,
not a test-suite pass. The exact rc64 development checkpoint passed 679 tests
with zero failures, errors or skips, plus controlled reference/replay and rule
identity checks; those controls and receipts are retained in local development.

## Results and scope

| Decision | Meaning |
|---|---|
| `CONFORMANT` | Required evidence is available and the selected rule has no violation witnesses. |
| `NON_CONFORMANT` | The selected rule has one or more violation witnesses. |
| `NOT_EVALUATED` | Required evidence is unavailable or evaluation failed. |

Conformance is relative to the declared source/build population and selected
rule profile. A successful capsule replay checks the recorded observation and
formal result; it does not independently prove that an arbitrary repository was
faithfully extracted. Unsupported language features and unresolved dependencies
remain explicit. A source-only run does not establish full build ownership.

Java uses Spoon and javac evidence; Python uses guarded source/native observation;
C++ build-backed observation requires a compatible Clang helper and qualified
compilation context. Some provider/recovery tools require Linux and Docker.
Use each tool's documented arguments and qualified toolchain profile. Merely
installing a newer compiler does not qualify a new observation world.

## Replaceable rules

The bundled eleven-rule profile is a regression/research baseline. Observers do
not contain rule verdict logic. Conditions over existing facts belong in the
registry and Alloy resources; additional concepts require a versioned observation
extension. New profiles need positive, negative and unknown controls, and their
partition assumptions must be validated.

Capsule format **8** binds the exact registry and Alloy-rule bytes and the formal
engine profile. Replay rejects changed profiles before evaluating artifacts.
Older v7 capsules require their original producer; this release does not silently
upgrade them. Completed artifacts are published with the capsule written last.

See [the invariant contract](docs/invariant-pipeline.md) and
[the observation architecture decision](docs/decisions/0001-observation-contract.md).

## Provider and reproduction tools

- `scripts/resolve-java-dependencies.sh`, `resolve-maven-dependencies.sh` and
  `resolve-gradle-dependencies.sh`: declared Java compilation contexts.
- `scripts/resolve-cpp-dependencies.sh` and `run-cpp-selected-build.py`:
  explicitly selected CMake/Clang contexts.
- `scripts/resolve-python-dependencies.sh` and `run-python-resolved-environment.py`:
  locked Python environment corridors with explicit source-only boundaries.
- [Sequential batches](scripts/batch/README.md): bounded execution and opt-in recovery.
- `scripts/recorded-input-archive.py` and `reproduction-bundle.py`: integrity-checked
  declared input retention/restoration, distinct from universal input-consumption proof.
- [Maven cache profiles](scripts/maven-cache/README.md) and
  `scripts/maven-frames/`: version-qualified native metadata/input capture.

Provider receipts, source re-observation and formal replay establish different
properties. Do not treat a retained input bundle as complete compiler-consumption
proof or a zero-exit provider run as semantic completeness.

## License

[MIT](LICENSE). Existing repository history and license are preserved.
