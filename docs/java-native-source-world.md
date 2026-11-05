# Explicit native Java source world

This opt-in route runs semantic observation in the selected actual compiler,
rather than interpreting another compiler's language/platform request on the
pipeline's host JVM. It reuses the canonical Java projection contracts and the
common evaluator. The default Java route is unchanged.

The current qualified profile is Linux, UTF-8 source, annotation processing
disabled, actual JDK 8, 11, 17 or 25. Qualification is finite and version/input
bound. The route reports `SOURCE_ONLY`: it does not certify project-build
ownership, full compiler consumption or unique binary/module origins.

## Invocation

Create an explicit JSON configuration. Relative paths resolve against the
configuration file's directory. `schemaVersion` is the string `"1"`.

```json
{
  "schemaVersion": "1",
  "javaHome": "/absolute/path/to/the/selected/jdk",
  "feature": 17,
  "releaseLevel": 12,
  "classpath": [],
  "timeoutMillis": 30000
}
```

```bash
java -jar "$PIPELINE_JAR" analyze --source "$SOURCE" --output "$NEW_RESULT" \
  --java-native-world /absolute/path/world.json
java -jar "$PIPELINE_JAR" verify-capsule \
  --capsule "$NEW_RESULT/verification-capsule.json"
```

For actual JDK8/source6/platform8, omit `releaseLevel` and specify
`"sourceLevel": 6, "targetLevel": 6, "feature": 8`. Source/target do not select
an older API platform. Release cannot be combined with source/target, and JDK8
does not accept the native `--release` option. Unknown configuration keys reject.
The timeout applies to each owned helper-compilation or source-observation
process; it is not a claim of an aggregate whole-analysis deadline.

The native selection cannot be combined with the existing dependency-manifest
or dependency-jar CLI switches. Supply declared classpath entries through the
native configuration. That inventory is not full dependency-origin certification.
There is no source-only-to-build-backed upgrade based on directory names.

## What is observed

- Class/interface/enum/annotation declarations, including local/anonymous types,
  are identified through the selected compiler and retained source spans.
- Authored methods and fields are distinguished from compiler-generated members
  by a pre-attribution source census. Constructors and binary Object are outside
  this explicitly source-declaration METHOD/ATTRIBUTE universe.
- Method declaration types, modifiers, source bodies, inherited source members
  and override edges come from the selected compiler. Canonical declarations
  retain their declaration types; generic substitution is not used to rewrite
  the original declaration to make a formal condition conformant.
- Type tokens are compiler-rendered declaration values. They are not a global
  type-identity graph or proof that equal type-variable spellings in different
  owners denote the same type. Conditions needing scoped variable identity,
  bounds or per-receiver generic substitution require a qualified fact/schema
  extension; the current token profile must not be relabeled as that evidence.
- Selected compiler backing files, helper sources/classes, declared classpath
  snapshots and source identity bind the native domain. Returned source buffers
  are validated as UTF-8 bytes and UTF-16 code units. Available wrapper snapshots
  are not represented as proof of complete compiler reads.

Unqualified external bases/overrides withhold the affected evidence. Unsupported
records, compilation errors and mismatched actual compiler versions preserve the
source population with incomplete evidence; they do not fall back to host facts.
Changed original sources or backing inputs reject the observation. Subject method
bodies, initializers and annotation processors are not executed by observation.

## Acceptance boundaries

Small controls cover local/anonymous declarations, generated enum members,
unavailable release APIs and unsupported records. Separate actual8/source6,
actual11/release9, actual17/release12 and actual25/release22 controls preserve
authored generic/override/body facts, canonical serialization, all17 formal
relations, all11 complete decisions/witnesses and capsule replay against the
qualified monolithic reference. Wrong-compiler and unsupported-source controls
produce only `NOT_EVALUATED` decisions and do not use host fallback.

These source-only controls do not by themselves admit author-build worlds,
arbitrary processors/modules or all language constructs. The separate
[author-task capture/import route](maven-native-runtime-capture.md) qualifies
the original TypeTools multi-execution source profile while retaining dependency
origin and complete-consumption unknowns.
Replay validates recorded facts/formal results, not universal extraction fidelity.
