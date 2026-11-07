# Actual Maven compiler-task source facts

This opt-in profile observes declarations in the author's actual javac tasks and
imports guarded facts with `--java-native-build`. It does not reconstruct author
semantics on the host compiler. Collection, admission, canonical projection and
formal rule evaluation remain separate. The default observer is unchanged.

## Qualified boundary

The collector uses Maven3.9.16/JDK17 and a Java8-bytecode source plugin. The
qualified Linux, UTF-8, sequential fork profile uses the exact Plexus javac2.17.0
bridge, actual selected JDK8/11/17/25 and retained response files. Preserve author
toolchains/options; do not rewrite a POM or substitute a host compiler to force
admission. Other bridges, processors, generated-source layouts and additional
roots require qualification. Snapshots do not certify full consumption or unique
binary origins. Constructors/generated members/binary declarations are outside
the source universe; unsupported records reject. Body-less methods remain members
without invented body bindings. Declaration types are not rewritten to satisfy P0.

## Collect

Build a matching collector:

```bash
python3 scripts/maven-frames/build.py \
  --image maven@sha256:880934ae394bf91bc3e57d573e4fc04774f064f3c4df7ccd7cc10b3b126737bf \
  --output "$FRESH_COLLECTOR"
```

Run in the intended Linux environment with declared JDKs/toolchains and the exact
Maven cache available. Variables below are absolute native paths. Keep capture
outside the source tree. ATTEMPT is a fresh32-character lowercase hexadecimal
token; PLUGIN_SHA256 is the independently computed source-trace.jar SHA256.
Use a fresh build tree so compilation is not skipped.

```bash
mkdir -p "$CAPTURE"
cd "$PROJECT"
set +e
timeout --kill-after=5s 120s mvn -o -B -X --toolchains "$TOOLCHAINS" \
  -Dmaven.ext.class.path="$COLLECTOR/compiler-frames.jar" \
  -Dconformance.frames.receipt="$CAPTURE/frames.jsonl" \
  -Dconformance.compiler.receipt="$CAPTURE/compiler.jsonl" \
  -Dconformance.compiler.session="$CAPTURE/session.jsonl" \
  -Dconformance.compiler.attempt="$ATTEMPT" \
  -Dconformance.compiler.sourcePlugin="$COLLECTOR/source-trace.jar" \
  -Dconformance.compiler.sourcePluginSha256="$PLUGIN_SHA256" \
  -Dconformance.compiler.parsedRoot="$CAPTURE/parsed" \
  -Dconformance.compiler.retainInputs=true \
  -Dconformance.compiler.runtimeProfile=observed-javac-semantics-v1 \
  test-compile > "$CAPTURE/native.log" 2>&1
build_exit=$?
set -e
python3 - "$build_exit" "$EXECUTION" <<'PY'
import json, pathlib, sys
with pathlib.Path(sys.argv[2]).open('x') as output:
    json.dump({'exitCode': int(sys.argv[1])}, output)
PY
test "$build_exit" -eq 0
```

The lifecycle is an example; retain the intended actual command. The exit record
must come from the owned process, including timeout/failure. Never invent success.
GNU timeout's process deadline/cleanup grace differ from preparation and analysis
budgets. `-X` preserves response files: exact order, duplicates, empty path entries
and module patching are bound, without executing generated debug scripts.

## Retain and import

At the recorded native source coordinate, create a fresh bundle:

```bash
python3 /absolute/pipeline/scripts/maven-frames/native_source_bundle.py create \
  --source "$PROJECT" --capture "$CAPTURE" --execution "$EXECUTION" \
  --collector "$COLLECTOR/compiler-frames.jar" \
  --plugin "$COLLECTOR/source-trace.jar" --response-root "$PROJECT/target" \
  --destination "$FRESH_BUNDLE"
python3 /absolute/pipeline/scripts/maven-frames/native_source_bundle.py verify \
  --bundle "$FRESH_BUNDLE"
java -Dmetamodel.conformance.python="$PYTHON312" -jar "$PIPELINE_JAR" \
  analyze --source "$FRESH_BUNDLE/source" --java-native-build "$FRESH_BUNDLE" \
  --output "$FRESH_RESULT"
java -jar "$PIPELINE_JAR" verify-capsule \
  --capsule "$FRESH_RESULT/verification-capsule.json"
```

Preserve POSIX retained-input metadata; Windows-native restoration is unqualified.
The response directory must contain all qualified responses for the invocations.
Import needs Python3.12 and may use a relocated bundle. Failed exits, source drift,
incomplete seals, incorrect runtime/phase, malformed facts and census differences
reject. Unowned Java files remain visible and withhold totality evidence. Both
observe/analyze accept the selector; other native/provider inputs and external
parent augmentation cannot be mixed with it.

Each execution remains SELECTED_BUILD/SOURCE_DECLARATIONS, with actual argv and
reported compiler/dependency identities. Generation and complete consumption or
unique binary-origin closure remain unknown. The same projector/merger serves
source-only and author-task facts. Rules remain replaceable and out of collectors.
The older capture_native.py/contexts.tsv bridge is separate, without automatic
admission of this actual-task profile.

## Evidence and limits

Original pinned TypeTools compile-only qualification covers24 files and five
actual8/11/17/25/8 worlds:86 classes,159 methods,28 fields and147 bodies. All88 class
files match vanilla. All17 reference relations, all11 decisions/full witnesses and
replay agree;3 conditions conform and8 remain unevaluated with missing hierarchy.
Small generic/field-hiding/body-less controls validate source expectations apart
from formal outcomes. A fresh declared-source rebuild reproduces common source
facts/class bytes with distinct provenance. This source re-observation differs
from capsule replay and does not establish universal language fidelity, author
test execution or a complete consumed filesystem.