# Research workflow: reproduction, interpretation, and extension

## 1. Purpose of the software artifact

This repository is a research instrument for studying **empirical correspondence between source programs and formal conformance conditions**. It is not intended to turn a parser into a proof oracle. Its central separation is:

```text
source repository
    ↓
independent frontend observations
    ↓
canonical EMF/Ecore observation
    ↓
exact Alloy instance
    ↓
registered semantic checks
    ↓
CONFORMANT | NON_CONFORMANT | NOT_EVALUATED + witnesses
    ↓
replayable verification capsule
```

The frontend is responsible for saying only what it can observe. Alloy is responsible for deciding whether those observations satisfy a condition. This separation is the main extensibility rule of the project.

## 2. What a result means

A decision is scoped to the exact observation and evidence profile recorded in the verification capsule.

- `CONFORMANT`: all evidence required by that semantic check was complete and the exact Alloy instance contained no violation witness.
- `NON_CONFORMANT`: all required evidence was complete and Alloy returned one or more concrete violation witnesses.
- `NOT_EVALUATED`: at least one required evidence kind could not be justified, or exact evaluation could not be completed safely.

`NOT_EVALUATED` must not be converted to `CONFORMANT`. In this project, inability to establish correspondence is itself an empirical result.

## 3. Build and test the research instrument

Requirements for the current profile are JDK 17, Maven, Python 3 for the Python observer tests, and Clang for the C++ observer profile.

```bash
mvn --batch-mode --no-transfer-progress verify
```

The command must pass before empirical corpus results from that head are reported. It exercises the canonical model, frontend observations, exact Alloy encoding, registered invariants, witnesses, capsule replay, and regression cases.

To build the executable artifact:

```bash
mvn --batch-mode --no-transfer-progress package
JAR=$(find target -maxdepth 1 -name 'metamodel-conformance-pipeline-next-*.jar' -type f | head -n 1)
```

For a formal experiment, record the exact Git commit in addition to the tool version. The repository's frozen corpus workflows do this automatically.

## 4. Analyze a source repository

### Java

```bash
java -jar "$JAR" analyze \
  --source /path/to/java/repository \
  --output /tmp/experiment-java
```

Java is the default language. If the source requires dependency archives to let javac resolve the configured evidence boundary, provide them explicitly:

```bash
java -jar "$JAR" analyze \
  --source /path/to/java/repository \
  --output /tmp/experiment-java \
  --dependency-jar /path/to/dependency-a.jar \
  --dependency-jar /path/to/dependency-b.jar
```

An `--external-parent` declaration is an explicit experiment assumption about a hierarchy target outside the observed source boundary. It must not be interpreted as evidence for members, implementations, overrides, or object instances.

### Python

```bash
java -jar "$JAR" analyze \
  --language python \
  --source /path/to/python/repository \
  --output /tmp/experiment-python
```

Python intentionally claims fewer evidence kinds than Java. Invariants depending on evidence the Python frontend does not justify remain `NOT_EVALUATED`.

### C++

```bash
java -jar "$JAR" analyze \
  --language cpp \
  --source /path/to/cpp/repository \
  --output /tmp/experiment-cpp
```

The current C++ observer uses a fixed Clang C++17 source profile. Build-configuration-dependent evidence that cannot be reconstructed remains incomplete rather than guessed.

## 5. Output artifacts

A successful analysis publishes the canonical observation, the exact Alloy artifact, decisions/witnesses, and a verification capsule in the output directory. The capsule binds the artifacts to their hashes and the frozen Alloy execution configuration.

Replay the result with:

```bash
java -jar "$JAR" verify-capsule \
  --capsule /tmp/experiment-java/verification-capsule.json
```

A paper or empirical report should cite the source repository commit, pipeline commit, observation/evidence profile, and capsule result. Reporting only a final conformance label loses the correspondence information needed to reproduce the claim.

## 6. Exit codes

The CLI uses:

- `0`: every registered check that ran is conformant, or a capsule is valid;
- `2`: at least one evaluated invariant is non-conformant;
- `3`: at least one invariant is not evaluated, or capsule verification failed;
- `64`: command-line usage error.

A corpus harness must preserve this distinction. Exit `3` is not a failed scientific experiment when the intended observation boundary is incomplete; it is often the expected result.

## 7. Reproduce the frozen empirical campaigns

The repository contains pinned corpus workflows and per-entry scripts for Java, Python, and C++. They clone exact source commits, analyze them, replay the generated capsules, and aggregate invariant statuses and evidence diagnostics.

The frozen campaigns currently cover:

- 20 Java repositories,
- 6 Python repositories,
- 4 C++ repositories.

For a research result, prefer the exact-head CI artifacts over an unrecorded local run. A change to an observer, schema, Alloy rule, registry requirement, or projection policy requires the affected corpus campaign to be rerun before empirical counts are reused.

## 8. Add a new invariant when the required evidence already exists

This is the normal extension path and should **not require a language-adapter change**.

1. State the semantic condition and its witness unit.
2. Identify the existing evidence kinds that are necessary to evaluate it.
3. Add an Alloy violation function. Do not encode the decision in Java/Python/C++.
4. Register the semantic invariant ID in `src/main/resources/invariants/registry.json` with:
   - required evidence,
   - witness function and arity,
   - partition relations and roots,
   - conformance/violation messages,
   - manuscript trace label only as metadata.
5. Verify that `AlloyWorkUnitPlanner` retains every relation and intrinsic field the invariant needs.
6. Add at least:
   - one exact conformant observation,
   - one exact counterexample with a known witness,
   - one missing-evidence case proving `NOT_EVALUATED`.
7. Run exact-head CI and the relevant frozen corpora.

If steps 1–7 can be completed without changing an adapter, the architecture is behaving as intended.

## 9. Add genuinely new evidence

An invariant may require a fact the canonical observation does not currently contain. The correct response is to extend the evidence boundary explicitly, not to infer the answer inside the invariant adapter.

The required sequence is:

1. Define the empirical meaning of the new evidence independently of the invariant outcome.
2. Add a new `EvidenceKind` only if completeness of that fact can be stated precisely.
3. Extend the canonical Java record model and Ecore schema.
4. Advance the observation schema version.
5. Extend XMI writing and reading so replay preserves the fact exactly.
6. Extend the exact Alloy instance with a relation/attribute for the fact.
7. Extend work-unit projection so the fact is not lost during partitioning.
8. Implement a frontend observer that records the fact without deciding conformance.
9. Claim the new evidence kind only when the observer completed it for the full configured boundary.
10. Add source-observer, round-trip, Alloy-counterexample, missing-evidence, and replay tests.
11. Run real repositories and record where the evidence remains incomplete.

A new evidence source should be rejected if its definition is circular—for example, observing “this class is O-07 conformant” instead of observing classifier abstractness and implementation relations separately.

## 10. Add another programming language

A language adapter is an **observer**, not an implementation of the invariant catalogue.

A new language profile should document:

- source files included in the observation boundary;
- compiler/parser and version assumptions;
- package/module/source-root interpretation;
- dependency/build configuration supplied to the observer;
- which evidence kinds are complete;
- which evidence kinds remain unavailable or configuration-dependent;
- deterministic identity/source-location rules used for canonicalization.

The adapter must emit `UNKNOWN` or omit the completeness claim when a fact cannot be justified. It must not synthesize a result merely to make more invariants evaluable.

## 11. Research boundary for O-07

The current repository profile observes classifier abstraction, method abstraction, method static/instance scope, hierarchy, and O-06 implementation bindings. Alloy can therefore evaluate:

- `abstraction-implementation-consistency`;
- `static-abstract-method-separation`.

The formal direct-instance clause requires `Object`/`directInstances` evidence. The repository experiment does not currently observe runtime/model objects, and a `new C(...)` syntax node is not silently treated as an equivalent formal object. That clause remains deferred until a separate object-observation experiment is defined.

This is an example of the project's main methodological rule: **narrow the empirical claim rather than broaden the evidence by assumption**.

## 12. Threats to validity to record with empirical results

At minimum, a study using this pipeline should report:

- dependency/build-environment incompleteness;
- parser/compiler language-version assumptions;
- source-set or module-root interpretation;
- generated/test/fixture source inclusion policy;
- platform-specific headers or libraries;
- evidence kinds that were unavailable for each language;
- repository and pipeline commit selection;
- whether results were replayed from the published capsule.

These are not merely implementation caveats. They define the boundary within which empirical correspondence has been established.
