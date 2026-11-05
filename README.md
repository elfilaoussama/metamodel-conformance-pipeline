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
PIPELINE_JAR=target/metamodel-conformance-pipeline-next-0.15.0.jar
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

### Actual Java compiler source facts (opt-in)

See the complete [source-only configuration](docs/java-native-source-world.md)
and [author-task capture instructions](docs/maven-native-runtime-capture.md).

The default Java observer is unchanged. Two explicit native routes use the
selected compiler's source declarations and the same canonical projector:

- `--java-native-world world.json` runs a selected compiler on a bounded,
  source-only control. It does not claim author-build ownership. Its strict JSON
  configuration has `schemaVersion: "1"`, absolute `javaHome`, integer `feature`,
  `sourceLevel`/`targetLevel` or `releaseLevel`, `classpath` and `timeoutMillis`.
- `--java-native-build bundle` imports source facts captured inside successful
  author compilation tasks. It does not reattribute with the pipeline's JDK.
  Every captured execution remains a distinct `SELECTED_BUILD` /
  `SOURCE_DECLARATIONS` world, including module-patch arguments and compiler
  backing identities.

Both options work with `observe` and `analyze`; they cannot be combined with each
other, other-language selection, dependency-manifest/JAR options or external-parent
allowlists. These profiles support authored class/interface/enum/annotation
declarations, methods, fields and method bodies. Constructors, generated members
and binary-only declarations are outside this source universe; records reject.
External hierarchy/override correspondence remains incomplete when it cannot be
represented faithfully. No complete consumed-input or unique binary-origin
certificate follows from a successful source projection.

For the author-task route, use a collector built from the matching source version
and opt into `conformance.compiler.runtimeProfile=observed-javac-semantics-v1`
with `conformance.compiler.retainInputs=true`. The qualified capture corridor is
sequential forked javac through Maven compiler/Plexus 2.17.0 with exact recorded
response files, UTF-8 source and actual selected JDKs 8/11/17/25. Missing compiler
calls, failed builds, unsupported profiles and incomplete native receipts reject.
This route requires POSIX-preserved retained-input metadata and Python 3.12;
Windows-native bundle restoration is not qualified. The older
`capture_native.py`/`contexts.tsv` corridor above remains a separate, narrower
metadata/re-observation profile; it does not automatically produce this bundle.

From an already observed successful build, retain its owned-launcher exit record
(`execution.json` with integer `exitCode: 0`), collector/plugin JARs, full Java
source tree, sealed capture files and response files. Do not synthesize a success
record for an unobserved build. Create and check the source bundle:

```bash
python3 scripts/maven-frames/native_source_bundle.py create \
  --source "$NATIVE_PROJECT" --capture "$CAPTURE" --execution "$EXECUTION" \
  --collector "$COLLECTOR_JAR" --plugin "$SOURCE_PLUGIN_JAR" \
  --response-root "$NATIVE_PROJECT/target" --destination "$FRESH_SOURCE_BUNDLE"
python3 scripts/maven-frames/native_source_bundle.py verify \
  --bundle "$FRESH_SOURCE_BUNDLE"
java -Dmetamodel.conformance.python="$PYTHON312" -jar "$PIPELINE_JAR" \
  analyze --source "$FRESH_SOURCE_BUNDLE/source" \
  --java-native-build "$FRESH_SOURCE_BUNDLE" --output "$FRESH_RESULT"
java -jar "$PIPELINE_JAR" verify-capsule \
  --capsule "$FRESH_RESULT/verification-capsule.json"
```

Capture layout is `frames.jsonl`, `compiler.jsonl`, `session.jsonl`, and `parsed/`
with the recorded PARSE traces, actual-task semantic facts and retained inputs.
The response-root currently contains the exact Plexus `*.arguments` files for
the selected invocations. Bundle construction requires the project at its recorded
native absolute coordinate; import subsequently reads the retained bundle rather
than the original project paths. Additional/outside-root generated sources require an
explicitly qualified input contract. Unowned Java files remain visible and
remove declaration-totality evidence. Bundle checksums establish byte identity,
not signed execution attestation. Replaying the resulting capsule checks retained
formal artifacts; it neither recompiles source nor replaces a clean author-build
re-observation experiment.
