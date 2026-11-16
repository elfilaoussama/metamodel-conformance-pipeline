# Metamodel Conformance Pipeline: Complete End-to-End Architecture and Execution Reference

**Document type:** Source-grounded technical architecture and research-methodology manual  
**Prepared:** 2026-11-16  
**Core source:** [`elfilaoussama/metamodel-conformance-pipeline`](https://github.com/elfilaoussama/metamodel-conformance-pipeline)  
**Inspected engine snapshot:** `metamodel-conformance-pipeline-main.zip`, SHA-256 `ea11bb17f591821a873e218ee34bada3e1802f9076f3c951a0fc1a15c7f8ee06`, corresponding to engine source at Git commit [`5ea604fb29b0ff9807c621b7286691440ee9a630`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/tree/5ea604fb29b0ff9807c621b7286691440ee9a630).  
**Engine version:** `0.15.0` (`pom.xml`); **formal evaluator:** Alloy `6.2.0`, SAT4J; **default observation schema:** `17`; **capsule format:** `8`.  
**Figure:** [Pipeline architecture diagram (most recent committed version)](https://github.com/elfilaoussama/metamodel-conformance-pipeline/tree/main/documentation/assets/pipeline-architecture). The later locally edited V5 figure has the same broad engine modules, but the diagram is not the authoritative execution specification.

> **Scope and evidence status.** This document explains what the inspected **source code and project documentation** implement. It is not a claim that the source extraction is scientifically validated for arbitrary repositories, that the full Maven/Alloy suite was executed during this documentation task, or that an earlier manuscript experiment has been reproduced. Where a capability is conditional, unavailable, or merely described by the archived documentation, it is marked accordingly. Paths and rules refer to the public baseline unless explicitly labeled otherwise. The optional manuscript-aligned patch is a separate profile, not the default engine in this snapshot.

---

## 0. Executive mental model: what goes in, what happens, what comes out

**One-sentence purpose:** The pipeline converts a declared, versioned **source-code world** into a typed structural **observation**, translates that observation into an **exact Alloy instance**, evaluates a **versioned collection of invariant witness functions** only where the prerequisite evidence is admitted, and retains the decisions together with the facts, rule identity, hashes and replay information needed to examine how they arose.

**The input is not just a GitHub URL.** For a scientifically meaningful run it is a combination of source bytes, a selected revision, a source-language profile, dependency/build context, frontend/toolchain identity, rule profile and execution settings. Different selections may be different experiments even when they share a repository name.

**The output is not simply PASS/FAIL.** Each registered condition receives a `Decision` with a status, identifier, explanation and zero or more *witness tuples*. The allowed statuses are `CONFORMANT`, `NON_CONFORMANT`, and `NOT_EVALUATED`. The three canonical output files are `observation.xmi`, `repository-instance.als`, and `verification-capsule.json`.

### 0.1 Core execution graph (scientific engine)

```text
Declared source world / local directory / pinned GitHub acquisition
                       |
             [Input/context providers]
                       |
          [Observer factory / dispatch]
                 /     |      \
           Java      Python     C++
        Spoon/javac    AST      Clang
                 \     |      /
                  [Typed Observation]
                       |
                 EMF/XMI writer
                       |
                 observation.xmi
                       |
                 EMF/XMI reader
                       |
                [Exact Alloy encoder]  <--- immutable rule profile
                       |
               repository-instance.als
                       |
              [Condition evaluator]   <--- rule metadata
                 |             |
       required evidence?    canonical input validation
                 |
             [Work-unit planner]
                       |
      [Alloy SAT consistency + witness interpretation]
                       |
       Decisions + full witness tuples / NOT_EVALUATED
                       |
               [Capsule writer]
                       |
             verification-capsule.json
                       |
       [Separate capsule verification/replay]
```

**Important ordering correction:** In the actual `ConformancePipeline.analyze()` implementation, the entire canonical Alloy file is generated **before** the evaluator performs its per-condition evidence-admission check. The architecture diagram may place admission visually beside formal evaluation, but that is not a license to describe it as occurring before encoding. A condition with missing evidence is subsequently marked `NOT_EVALUATED`; its existence does not prevent the earlier XMI and Alloy artifacts from being generated.

### 0.2 The things that must never be conflated

| Concept | What it actually means | What it does **not** mean |
|---|---|---|
| **Repository identity** | Project/revision and selected source inventory | Automatically complete build/dependencies |
| **Observed source world** | Source and context actually made available to an observer | All possible runtime behavior |
| **Observed facts** | Frontend-reported structural data | A proof that those facts match the original language semantics |
| **Observation completeness** | The observer has positively admitted named `EvidenceKind` requirements | Every possible semantic property is known |
| **Invariant specification** | A formal witness function plus registration metadata | A native language compiler validity rule |
| **Alloy consistency** | The exact encoded observation has a satisfiable model under the frozen formal configuration | The project compiles, runs, or satisfies every invariant |
| **Empty violation relation** | No witness for an admitted formal condition in this exact observation | A universal theorem about all repositories |
| **Verification capsule** | Identity-bound retained results and formal inputs | A certified copy of the original repository or build |
| **Formal replay** | Recompute recorded formal decisions from retained observation/formal model | Re-extract the source or independently validate observation fidelity |
| **Empirical experiment** | Many separately controlled runs with defensible population accounting | A single successful `analyze` command |

---

## 1. Architectural boundaries: who is responsible for what?

The implementation is intentionally separated into **science-facing engine components**, **source/build preparation**, and an **optional local operations interface**. A successful UI job and a scientifically valid invariant conclusion are different propositions.

| Boundary | Code owner | Responsibility | Must not do |
|---|---|---|---|
| Source/world selection | CLI arguments; input/build providers in `tools/`; optional Git acquisition in web layer | Identify source population, dependencies, toolchain and context | Invent missing build facts |
| Language observation | `adapter/` and `adapter/java`, `adapter/python`, `adapter/cpp` | Produce facts, diagnostics, evidence-completeness claims | Compute final invariant verdicts |
| Canonical representation | `model/` + `emf/` | Validate, normalize and serialize typed observations | Silently turn unknown facts into known ones |
| Rule specification | `invariant/` + `resources/invariants/` + `resources/alloy/` | Register meaning, prerequisites, witness relation, partition metadata, profile identity | Rewrite facts to manufacture conformance |
| Exact formal encoding | `alloy/ExactAlloyEncoder` | Reify recorded source facts as a finite exact relational instance | Add unconstrained alternatives to repair observed defects |
| Scientific evaluator | `alloy/AlloyInvariantEvaluator` and witness interpreter | Admit rules, solve exact instances, interpret witnesses and assign decisions | Call an unevaluated rule conformant |
| Evidence packaging | `capsule/` | Bind decisions, facts, tool/profile/solver identities and hashes | Claim source fidelity just because hashes match |
| Reproduction | `capsule/CapsuleVerifier` and separate source/native controls | Distinguish formal replay from source re-observation | Rebrand formal replay as independent source verification |
| Experiment Workspace | `apps/research-web/` | Queue, acquire, invoke, inspect and show scientific artifacts | Maintain another private rule checker |

The **engine itself** is the subject of the research architecture figure. The local web workbench is a consumer of that engine, not the formal authority. It should be documented when explaining how researchers operate the tool, but does not need to be inside a core-science architecture illustration.

The main entry class is [`ConformancePipeline.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/ConformancePipeline.java). The independent web bridge is [`EngineGateway.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/apps/research-web/server/src/main/java/metamodel/conformance/web/EngineGateway.java).

---

## 2. The input: what precisely is a source world?

### 2.1 Inputs at the repository boundary

A common research workflow starts with a GitHub repository, but **GitHub is one possible source acquisition route, not an invariant checker**. A repository may be selected in the web workbench or prepared externally and passed to the CLI as a local directory. The actual scientific engine takes a local `Path sourceRoot`, the chosen `SourceObserver`, a set of explicitly named external parents and an output directory.

The relevant input dimensions are:

1. **Code population:** which tracked or locally selected files, their bytes and paths; how generated/external support files are handled.
2. **Revision:** exact commit or immutable local snapshot, rather than a floating branch name.
3. **Language:** `java` (default), `python` or `cpp`; the `JAVA_ARCHIVE` and `JAVA_PLATFORM` enum values label support evidence, **not** user-selected primary languages.
4. **Dependency scope:** classpath JARs, language-specific dependency manifests, imported packages/modules, headers, platform resources, and build-derived context where supported.
5. **Compilation/runtime context:** relevant JDK/compiler/Clang/CPython identity, options, imports, include roots and declared build frame.
6. **Observation policy:** source-only versus selected native/source-world or author-task route; unresolved references and externally supplied hints.
7. **Rule profile:** exact registry/rule bytes and their SHA-256 digests.
8. **Execution configuration:** the supported Alloy/SAT solver settings and runtime worker choices.
9. **Output destination:** a fresh or deliberately managed directory where the canonical artifacts are retained.

These inputs have different provenance. A copied file tree alone does not establish a reconstructed build. In particular, source-only observation is not equivalent to proving which files, build targets, generated sources, dependency versions and flags the original project consumed.

### 2.2 When using GitHub through the local workbench

`GitRepositorySource` resolves a public HTTPS repository to an exact Git commit and materializes selected tracked Git blobs. It validates object identities, checks the retained inventory and records an acquisition receipt. It intentionally does **not** execute repository hooks, arbitrary checkout filters or an author build. It does not automatically materialize submodules or Git LFS. The current code imposes operational bounds including **2,000 selected files / 64 MiB total**, individual source-file limits and an acquisition budget. A rejected file population is an acquisition/attempt outcome, not a formal invariant violation.

The web path is approximately:

```text
search/select -> freeze review configuration -> resolve Git commit
-> list/validate selected tracked blobs -> materialize source snapshot
-> create queued attempt -> owned engine subprocess -> inspect resulting artifacts
```

**The web-executable analysis slice is Java source-only** with the configured engine. The interface can acquire Python/C++ sources and inspect/import matching results, but the documented web front end does not yet offer native Python/C++ execution, arbitrary profile selection or author-build setup. Those corridors belong to the CLI and provider tools.

**Primary sources:** [`GitRepositorySource.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/apps/research-web/server/src/main/java/metamodel/conformance/web/GitRepositorySource.java); [`capabilities.md`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/documentation/reference/capabilities.md).

### 2.3 When using the CLI directly

`PipelineCli.selectedObserver()` interprets the user-selected language and dependency/world flags, then delegates to `SourceObserverFactory`. Relevant options include:

| CLI option | Function and boundary |
|---|---|
| `--source <dir>` | Local root of the selected source population |
| `--output <dir>` | Directory for canonical output files |
| `--language java\|python\|cpp` | Selects the corresponding observer; defaults to Java |
| `--external-parent <name>` | Repeated external-parent hints; **not** a general proof of complete external hierarchy |
| `--dependency-jar <path>` | Repeated Java dependency JAR inputs, global form |
| `--dependency-manifest <path>` | Language-aware dependency manifest; Java, Python and C++ use distinct parsing/resolver contracts |
| `--java-native-world <config>` | Explicit selected-native-JDK source world |
| `--java-native-build <bundle>` | Recorded author-task/native-build context |

The CLI rejects incompatible combinations: a Java dependency manifest and repeated `--dependency-jar` inputs cannot be mixed; the two explicit native Java modes cannot be combined; native Java contexts cannot be casually combined with non-Java languages or separate dependency inputs.

The factory contract is `Observation observe(Path sourceRoot, Set<String> externalParents)`. **This is a language-independent interface**, but its output completeness is not language-independent by fiat.

**Primary sources:** [`PipelineCli.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/cli/PipelineCli.java); [`SourceObserverFactory.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/adapter/SourceObserverFactory.java).

### 2.4 Input snapshot and change detection

`SourceInputSnapshot.capture` takes selected physical source files, copies their bytes to an owned temporary tree, hashes the captured files, and later checks whether the original selected-file population or bytes changed while observation was taking place. It rejects escaping or aliased/symlinked source inputs under its stated constraints. The source observer reads from its captured source world and can refuse inconsistent input transitions.

This protects **within-run input consistency**. It does not automatically provide a complete archival copy of the entire development environment or establish that the selection was representative. Toolchain, dependency and native compiler evidence have their own boundaries and controls.

---

## 3. The language-observation layer: three frontends, one contract

This layer is often the largest source of empirical uncertainty. Its job is **observation**, not verdict assignment. Each frontend determines which facts it can report and which evidence categories it can justifiably claim complete.

### 3.1 Java observer

**Selection:** Default `java` route through `JavaDependencyAwareSourceObserver`, which delegates to a source observer and enriches/qualifies source facts with relevant dependency and compiler context. The repository also supports explicit `nativeJava` and `nativeJavaBuild` routes.

**Main technologies:** Spoon parses source declarations and structure; `javac`-based components supply native compiler observations, including inherited-member/override evidence in supported Java contexts. Dependency JAR and classpath handling distinguishes source declarations from bytecode-supported and platform-supported entities.

**What the Java layer tries to record:**

- Source units (path + bytes digest), class/interface-like classifier declarations, their source spans and contextual identities.
- Direct parent/generalization references, known and unresolved parents, and source-set/dependency context.
- Declared methods and attributes, ordered parameter-type labels, member kind, visibility and inheritability.
- Method abstraction, static/instance scope, return-type observations when supported.
- Independently resolved inherited views and source-native override links where available.
- Method bodies and explicit implementer-target-body associations where admissible.
- Evidence completeness and compiler/parse/coverage diagnostics instead of fabricated facts.

**Important source-world variants:**

- **Default source-only:** convenient structural observation under a documented source-only context. It is not automatically the original build environment.
- **Dependency-aware:** supplies JARs or source-set context and may add support classifiers/members. Contextual identity matters; the same displayed class name from distinct contexts must not be merged without evidence.
- **Explicit native JDK:** invokes selected compiler semantics under a declared configuration, rather than assuming the host JDK is interchangeable with a requested language/platform target.
- **Native author-task bundle:** an opt-in recorded build frame, not a claim that all arbitrary build dependencies have been independently certified.

**Safety and interpretive limits:** Compiler success, inheritance fidelity, body availability and full classpath provenance are separate questions. The observer does not execute the subject project's method bodies. A Java compiler accepting a construct and a shared research rule accepting its semantics may legitimately differ.

**Primary sources:** [`JavaDependencyAwareSourceObserver.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/adapter/java/JavaDependencyAwareSourceObserver.java), [`SpoonJavaObserver.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/adapter/java/SpoonJavaObserver.java), [`JavacInheritedMemberObserver.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/adapter/java/JavacInheritedMemberObserver.java), [`java-native-source-world.md`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/documentation/archive/java-native-source-world.md).

### 3.2 Python observer

**Selection:** `PythonAstObserver` using CPython's `ast` bridge and associated source/runtime-context evidence. The source-only parser is designed not to run the target program as its method of discovering classes.

**Potentially observed:** Lexically visible class definitions, class relationships when statically resolvable, source declaration ownership, selected native method/signature/abstraction/scope evidence and conditional source-derived inherited/override information where the specific observer's readiness tests succeed. The actual `completeEvidence` set is **computed for each run**; do not generalize an older Python contract's capabilities to every newer observation.

**Why Python is difficult:** Import resolution, dynamically assigned bases, descriptors, decorators, metaclasses, runtime additions, multiple-inheritance resolution and interpreter/environment state can defeat static certainty. A declaration's annotation is not the same thing as a dynamically checked runtime type. The observer retains knowledge/unknown distinctions and native receipts instead of rewriting unsupported behavior into Java-like facts.

**What `NOT_EVALUATED` means here:** The observation may still include many valid source declarations while refusing to evaluate conditions that require complete inheritance, local signatures, native lookup, return-type or implementation evidence. The correct response is to report its specific missing prerequisite(s), not to fill the gaps from the formal model.

**Primary sources:** [`PythonAstObserver.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/adapter/python/PythonAstObserver.java), [`PythonStaticLookup.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/adapter/python/PythonStaticLookup.java), [`python-native-evidence.md`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/documentation/archive/python-native-evidence.md).

### 3.3 C++ observer

**Selection:** `ClangCppObserver`. It uses compiler AST information and associated translation-unit/context logic. The default source-only behavior declares a C++17-oriented profile; additional resolver/native-plan paths can supply richer configured context and native receipts.

**Potentially observed:** Class/struct declarations, base-class/generalization information, source membership, method facts and conditional compiler-derived evidence. The current implementation contains optional routes for method-return/override/hierarchy/inheritance/body/binding admission, but whether those categories are complete depends on the selected native contexts, source population, compiler receipts and available prerequisites.

**Why C++ requires a compiler context:** Macros, `#include` paths, standard-library headers, `#if` conditionals, target flags, translation-unit selection, overloaded declarations and out-of-line definitions can change what exists in a specific compilation. One source spelling is not necessarily one semantic translation-unit world. The adapter uses context identities and records support/dependency input evidence to avoid silently collapsing them.

**The responsible interpretation:** Compiler evidence is not automatically full-project/whole-program proof. A configured target and a host Clang can cover a selected source slice; that does not mean every generated file, link-time definition, platform dependency or build variation has been reproduced. Missing or incompatible context blocks relevant conditions.

**Primary sources:** [`ClangCppObserver.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/adapter/cpp/ClangCppObserver.java), [`CppDependencyInputs.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/adapter/cpp/CppDependencyInputs.java), [`cpp-context-evidence.md`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/documentation/archive/cpp-context-evidence.md).

### 3.4 Why the observers must stay separate

Spoon, CPython AST and Clang do not expose identical semantics. The architecture requires a common **carrier format**, not a fiction that every language has the same evidence. The adapters should map their supported concepts to one typed model, preserve native receipts, declare incomplete categories, and leave final policy decisions to the invariant evaluator. There is **no private Java checker / Python checker / C++ checker** for the bundled formal conditions.

---

## 4. Canonical Observation: the shared object model

### 4.1 The `Observation` record

The main typed contract is `metamodel.conformance.pipeline.model.Observation`. It has the following groups:

| Field/group | Meaning |
|---|---|
| `schemaVersion` | Version of the recorded observation model (current default `17`) |
| `adapterId`, `adapterVersion` | Which observer produced the representation |
| `externalParents` | Declared external-parent hints carried with this observation |
| `completeEvidence` | Set of evidence categories which the observer claims complete for its selected context |
| `units` | Source/support units with language, canonical relative path and SHA-256 |
| `classifiers` | Contextual class/interface/struct-like carriers with inheritance and member references |
| `members` | Method and attribute carriers, signatures, scope, visibility, abstraction, overrides |
| `methodBodies` | Source-positioned method-body carriers |
| `implementationBindings` | Explicit implementer -> target-method -> body links |
| `unresolvedParents` | Parent references for which a complete target could not be established |
| `diagnostics` | Parse, resolution, context and evidence-incompleteness messages |
| `platformEvidence` | Relevant platform provenance where supplied |
| `nativeEvidence` | Additional domain, language mode, role, invocation, lookup, signature, body/binding, source-input and build-selection receipts |

The canonical constructor sorts key populations and validates references. Examples include: no duplicate source-unit paths, no duplicate classifier/member keys, class parents must refer to known classifier IDs, override relations must connect known methods, bindings must refer to known class/method/body carriers, and referenced source paths must occur in the source-unit inventory. An observation that violates those structural transport constraints is rejected instead of being silently repaired.

`completeEvidence` is **not inferred simply because some fields are present**. It is an explicit record, e.g. a hierarchy that contains an unresolved parent cannot simultaneously claim complete `HIERARCHY` evidence. Parse errors also invalidate positive completeness claims under the record's rules.

### 4.2 What is actually in a classifier?

A `ClassifierObservation` contains a technical `cls_...` ID, qualified name, package name, kind, source file and line range, direct parent IDs, declared member keys, an **independently observed** inherited-member list when available, and an abstraction flag. It is a **record about a classifier in an observation context**. Its identity is not just its short class name.

### 4.3 What is actually in a method or attribute?

A `MemberObservation` contains a `mem_...` technical key, optional `observedIdentifier`, kind (`METHOD`/`ATTRIBUTE`), inheritability, visibility, member name, source span, **ordered** parameter types, abstraction, static/instance scope, optional return type, and keys of overridden methods. Attributes cannot carry method-specific abstraction, scope, return type or override data under the record's validation.

This distinction matters for overloaded methods: matching only the method name would be an incorrect approximation of the baseline method-key policy. Ordered parameter position matters in the formal relation.

### 4.4 What is a body/binding?

A `MethodBodyObservation` has a `body_...` key and source span. An `ImplementationBindingObservation` links exactly three recorded carriers: its implementer classifier, target method and body (`bind_...`, `cls_...`, `mem_...`, `body_...`). The existence of such a record is observational data; whether it satisfies the formal binding policy is evaluated later.

### 4.5 Technical keys are not an O-01 identity theorem

IDs and technical keys make it possible to refer consistently to observed atoms and witnesses. Some are deterministic hashes of contextual data. That makes them suitable **transport/provenance identifiers**, not independent empirical proof of object, class, method or attribute identity preservation. O-01 cannot be claimed merely because the encoder generated unique atom names.

### 4.6 Native evidence, provenance and incomplete knowledge

`NativeEvidence.Bundle` supports context-specific receipts: domains, source roles, compiler invocation information, parent/base observations, method signatures, independently observed lookup relationships, body bindings, reported input inventories, build selections, definition correspondences and language modes. Its typed `Evidence<T>` distinguishes **KNOWN** values (including a genuinely known empty list) from unavailable values carrying an explanation. This is stronger than silently substituting an empty array for an unknown source fact.

A source path and line locate a physical declaration; a context ID locates the semantic measurement world in which it was interpreted. The two are complementary, not interchangeable.

**Primary sources:** [`Observation.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/model/Observation.java), [`ClassifierObservation.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/model/ClassifierObservation.java), [`MemberObservation.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/model/MemberObservation.java), [`NativeEvidence.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/model/NativeEvidence.java).

---

## 5. EMF/Ecore and XMI: why there is a serialization boundary

The in-memory `Observation` is normalized into an **Ecore-defined typed representation** and written to `observation.xmi` through `ObservationXmiWriter`. Its current default schema is `17`, with the namespace `urn:metamodel-conformance:observation:17`. The reader supports the appropriate bundled compatibility schemas; code permits reading versions 14 through 17 under their defined contracts.

The full `analyze` method writes the XMI to disk, then **reads that file back through `ObservationXmiReader`** before formal encoding. This means that the formal layer operates on a representation that has passed through the actual archival transport rather than solely on the observer's original in-memory object.

### 5.1 What this boundary accomplishes

- Makes observation a distinct inspectable artifact, not an ephemeral precursor to a verdict.
- Serializes canonical graph references and native evidence according to a versioned metamodel.
- Forces the pipeline to confront serialization/reading errors before evaluating rules.
- Separates source-language acquisition from the Alloy implementation.
- Preserves a stable source of facts for replay, comparison and diagnostics.

### 5.2 What it does not accomplish

A successful XMI write/read round trip proves only that facts are transferable and structurally accepted under that contract. It does **not** prove that Spoon, `ast`, or Clang observed the source correctly. It also does not certify that the original repository build was reproduced. That requires independent native controls and source-world provenance.

### 5.3 Data validation examples

| Case | Correct treatment |
|---|---|
| Member points to a nonexistent source unit | Reject malformed canonical observation |
| Binding points to a nonexistent body | Reject malformed canonical observation |
| Unknown superclass/base | Record unresolved reference; do not declare hierarchy complete |
| Frontend parser error | Preserve diagnostic; do not claim complete evidence |
| Unknown visibility or return type | Retain unknown/unavailable semantics; admit only compatible conditions |
| Same simple class name in two native contexts | Keep contextual identity distinct |

**Primary sources:** [`ObservationSchema.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/emf/ObservationSchema.java), [`ObservationXmiWriter.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/emf/ObservationXmiWriter.java), [`ObservationXmiReader.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/emf/ObservationXmiReader.java), and `src/main/resources/model/observation-v17.ecore`.

---

## 6. The invariant profile: the exact role of its two files

The invariant profile is deliberately **independent of the language observers**. It has two different parts that must not be merged conceptually:

| File | Contains | Does not contain |
|---|---|---|
| `src/main/resources/invariants/registry.json` | Rule IDs, titles, theory traces, witness function names/arity, required evidence, partition roots/relations, decision messages | Source-language parsing code or proof that the rule is correct |
| `src/main/resources/alloy/invariants.als` | Alloy predicates, helper relations and violation-witness functions | Per-repository source facts |

`InvariantProfile.load()` captures the **bytes of both resources**, parses the registry, validates UTF-8 Alloy source, and computes the pair of SHA-256 hashes making up the immutable profile identity. `ExactAlloyEncoder` and `AlloyInvariantEvaluator` must use the same captured identity; a mismatch is rejected by the pipeline's constructor. Worker processes carry an expected profile-token check.

### 6.1 Evidence requirement is registered per rule

Each `InvariantDefinition` supplies `requiredEvidence`. If a rule requires evidence categories not present in `Observation.completeEvidence`, the evaluator produces `NOT_EVALUATED` for **that rule**. This permits legitimate partial reports: a source observation might evaluate ownership while refusing to evaluate inherited lookup or override discipline.

### 6.2 Witness function and arity

A rule entry names a precise Alloy function returning a set/relation of violating atoms. Examples:

- `AcyclicGeneralizationViolations`: returns classifier atoms on an observed cycle; arity 1.
- `InheritedViewConsistencyViolations`: returns classifier-member pairs where observed versus derived membership differs; arity 2.
- `OverrideDisciplineViolations`: returns method pairs violating the selected override policy; arity 2.

The evaluator checks that the returned tuple arity matches the registry. If no violating tuples exist **after admitted and successful formal evaluation**, the result for that rule is `CONFORMANT`. If at least one exists, it is `NON_CONFORMANT` and the tuples are retained.

### 6.3 All eleven baseline checks (actual registry)

The baseline has 11 *registered checks*, not eleven unrelated theoretical conditions and not necessarily the same eleven functions as a historical manuscript checker.

| ID | Theory trace | Checks, in plain terms | Main required evidence |
|---|---|---|---|
| `exclusive-declaration-ownership` | O-02 | Every observed member has exactly one declaring classifier | Ownership |
| `acyclic-generalization` | O-03 | Inheritance graph has no cycle in selected formal population | Hierarchy |
| `inherited-view-consistency` | O-04 | Independently observed inherited set equals the formal derived view | Hierarchy, ownership, signatures, inheritability, inherited members |
| `local-inherited-separation` | O-05 | A member is not simultaneously locally declared and observed-inherited in the same source classifier | Ownership, inherited members |
| `implementation-binding-consistency` | O-06 | Body ownership/availability and the explicit implementer-method-body policy | Hierarchy, ownership, signatures, inheritability, bodies, abstraction, bindings |
| `abstraction-implementation-consistency` | O-07 (partial) | Unresolved visible implementation obligations require a classifier to be abstract | Hierarchy, ownership, signatures, inheritability, bodies, abstraction, bindings, classifier abstraction |
| `static-abstract-method-separation` | O-07 auxiliary | A source method is not both static and abstract under the rule's semantics | Ownership, method abstraction, method scope |
| `local-namespace-uniqueness` | O-08 local | Local method keys/attribute names are unique | Ownership, signatures |
| `inherited-namespace-uniqueness` | O-08 inherited | Formal inherited member set has no conflicting duplicate keys | Hierarchy, ownership, signatures, inheritability, inherited members |
| `override-relation-consistency` | O-09 bridge | Frontend-observed override pairs agree with the formal candidate relation | Hierarchy, ownership, signatures, inheritability, scope, override relations |
| `override-discipline` | O-09 | Formal overrides satisfy the rule's return-type/implementation policy | Hierarchy, ownership, signatures, inheritability, bodies, abstraction, scope, bindings, return types, override relations |

**Do not interpret all these checks as unconditional source-language judgments.** They are conditions of a *specified research profile*. For example, the baseline O-09 return-type clause is stricter than legal Java covariant returns, and the baseline O-04 inherited-view policy includes visibility/package-path conditions. A reported disagreement may be a deliberate profile/language mismatch, incomplete observation, a real structural contradiction or an implementation error. It must be independently adjudicated.

### 6.4 Twelve evidence kinds (full current enum)

`HIERARCHY`, `DECLARATION_OWNERSHIP`, `LOCAL_SIGNATURES`, `INHERITABILITY`, `INHERITED_MEMBERS`, `METHOD_BODIES`, `METHOD_ABSTRACTION`, `IMPLEMENTATION_BINDINGS`, `CLASSIFIER_ABSTRACTION`, `METHOD_SCOPE`, `METHOD_RETURN_TYPES`, `OVERRIDE_RELATIONS`.

A useful way to think about them is as **measurement prerequisites**, not truth values. They answer, "Do we have enough justified observations to run this rule?" not, "Does the source satisfy the rule?"

### 6.5 Known theory-to-engine limitations

- **O-01:** no independently justified source-level identifier population sufficient for the paper's complete identity-preservation condition. Generated transport keys are not adequate substitutes.
- **O-07:** the engine checks source-visible abstraction/implementation obligations and an auxiliary static-method rule; it does not observe runtime objects/direct-instance incidence, so the manuscript's full direct-instance proposition is not implemented.
- **O-03:** the baseline witness function quantifies `SourceClassifiers`, not every represented support classifier; a separate manuscript-observable patch proposed a broader population.
- **O-04, O-05, O-08 and O-09:** baseline source-/support-world and derived/observed-view choices may differ from the manuscript's formal referents. These are not harmless spelling differences.
- **O-06:** a previous source audit identified a binding-check population gap involving support-declared targets, and proposed a patch. The actual Alloy-level regression still needs execution to confirm the new profile.
- **O-09:** the baseline strict condition flags a formal override if either optional return type is absent. The updated manuscript's optional-equality expression treats `none = none` differently. A patch was authored for this specific mismatch, but was not published as the default baseline profile.

**Profile provenance note:** The separate `manuscript-observable` source patch has static/reference and Java-planner controls reported in a local test report, but its full Maven/Alloy integration and the 224-repository empirical rerun were **not** completed in that report. Historical experiments and newly defined profiles must keep different identifiers and outcome tables.

**Primary sources:** [`registry.json`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/resources/invariants/registry.json), [`invariants.als`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/resources/alloy/invariants.als), [`InvariantProfile.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/invariant/InvariantProfile.java), [`InvariantDefinition.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/invariant/InvariantDefinition.java).

---

## 7. The exact Alloy encoder: how facts become relational atoms

`ExactAlloyEncoder.encode(Observation)` does not ask Alloy to invent a plausible project structure. It builds a **finite, exactly constrained representation of the recorded observation**.

### 7.1 Symbolic atom populations

The encoder creates typed atom families corresponding to the observed data:

- `Classifier` atoms, one per represented classifier.
- `Member` atoms, one per represented method/attribute.
- `MethodBody` atoms, one per recorded body.
- `ImplementationBinding` atoms, one per recorded binding.
- `NameToken`, `TypeToken`, `PackageToken` and `PositionToken` atoms for signatures, packages and ordered parameter positions.
- Enumerated atoms for classifier/method abstraction, method scope, visibility, member kind and inheritability.

Canonical `cls_...`, `mem_...`, `body_...` and `bind_...` identifiers map to Alloy-safe atom names; textual names/types are represented by deterministic token atoms. Crucially, tokens are *model encodings* of observed values, not independently observed identifiers sufficient for O-01.

### 7.2 Relations encoded from actual facts

Examples of generated relations are:

| Relation | Exact meaning |
|---|---|
| `parents` | Observed classifier -> direct parent classifier |
| `declaredMembers` | Classifier -> its locally declared members |
| `observedInheritedMembers` | Classifier -> frontend-reported inherited members |
| `packageName`, `classifierAbstraction` | Per-classifier recorded package/abstraction |
| `kind`, `inheritability`, `visibility` | Per-member recorded categorical attributes |
| `memberName`, `parameterTypeAt`, `returnType` | Name, ordered parameter positions and optional return type |
| `abstraction`, `memberScope` | Method abstraction and instance/static state |
| `observedOverrides` | Frontend-resolved override links |
| `implementer`, `target`, `body` | Triple incidence encoded through binding atoms |

The generated file has relational signatures, singleton atoms, exact relation facts, the captured invariant Alloy source, and a `run ObservationConsistency for exactly ...` command with **exact atom counts** derived from the recorded observation.

### 7.3 Why `ObservationConsistency` is not the invariant verdict

The bundled `invariants.als` declares an empty `pred ObservationConsistency {}` because the exact source-specific facts and signatures are already injected into the generated model. The evaluator uses that command to ask whether the **exact imported fact structure can be instantiated consistently** under the Alloy semantics and supported solver configuration.

A satisfiable consistency command says: "The formal representation is internally consistent enough to interpret the witness relations." It does **not** mean that all eleven substantive rules hold. Their violation functions are evaluated afterwards.

An *inconsistent* exact work unit is not reported as compliant; its affected rule becomes `NOT_EVALUATED`, with an explanation.

### 7.4 Why the `.als` file is kept

`repository-instance.als` is the human-inspectable formal snapshot. It lets a reviewer see the real finite carriers and relations that the evaluator consumed, rather than trusting a screenshot of the reported verdict. It can be regenerated from the retained XMI and matching profile; its text is compared during evaluation and replay.

**Primary source:** [`ExactAlloyEncoder.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/alloy/ExactAlloyEncoder.java).

---

## 8. Evidence admission: when the instrument is allowed to judge

The evaluator first iterates over every registered rule and computes:

```text
missing = rule.requiredEvidence - observation.completeEvidence
```

If `missing` is nonempty, it records `NOT_EVALUATED` and includes the missing evidence-kind names in the message. Otherwise the rule is provisionally evaluable. A rule **cannot** receive `CONFORMANT` merely because a missing relation happens to be represented as empty.

If there is at least one evaluable rule, the evaluator first regenerates the canonical Alloy file and compares it against the supplied `.als` string. A mismatch makes the would-be evaluable rules `NOT_EVALUATED` instead of continuing on an untrusted formal snapshot. Rules already blocked by missing evidence remain blocked for their original reason.

For an admitted rule, actual evaluation can still fail during work-unit planning, Alloy parsing, consistency solving, witness interpretation or atom-to-source mapping. Those failures also produce `NOT_EVALUATED` in the relevant evaluator paths, rather than invented positive or negative findings.

### 8.1 Decision table for one rule

| Prerequisites | Formal execution | Witness relation | Recorded status |
|---|---|---|---|
| Missing required evidence | Not admitted | Not interpreted | `NOT_EVALUATED` |
| Evidence present; canonical `.als` mismatch | Rejected | Not interpreted | `NOT_EVALUATED` |
| Evidence present; work-unit planning/solver/interpretation fails | Incomplete/invalid | Not reliable | `NOT_EVALUATED` |
| Evidence present; exact solve succeeds | Completed | Empty | `CONFORMANT` |
| Evidence present; exact solve succeeds | Completed | One or more tuples | `NON_CONFORMANT` |

**Important:** The global `analyze` command can itself throw during source observation, serialization or preparation. Such a run may not reach the per-rule decision stage at all, and may not produce a completed capsule. Do not equate an operational failure with a recorded `NOT_EVALUATED` for every invariant.

**Primary source:** [`AlloyInvariantEvaluator.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/alloy/AlloyInvariantEvaluator.java).

---

## 9. Work-unit planning: why and how the formal work is partitioned

The exact source observation can contain many classifiers, members, bodies and bindings. Instead of unconditionally giving the solver one huge graph, `AlloyWorkUnitPlanner` constructs **condition-specific projections**.

### 9.1 Planner inputs

For each rule, registry metadata supplies:

- `partitionRelations`: relations that must be preserved to evaluate that witness function (e.g. `PARENTS`, `DECLARED_MEMBERS`, `OBSERVED_INHERITED_MEMBERS`, `IMPLEMENTATION_BINDINGS`, `OVERRIDE_RELATIONS`).
- `partitionRoots`: the kinds of carriers that determine the evaluated population (classifier, member, body).

The planner constructs an undirected connectivity graph over classifier (`C:`), member (`M:`), body (`B:`) and binding (`I:`) nodes. Based on the rule's relations and roots, it forms connected components or required ancestor/source-rooted closures and packs compatible components into deterministic work units. The baseline target is **32 atoms**, a packing objective rather than a formal scope theorem.

### 9.2 What a projection retains

A projected observation keeps only relevant classifier/member/body/binding carriers and the selected relations, but still reuses the appropriate source-unit inventory and identity labels. It must not drop a parent or declaration owner if doing so would change the semantics of the witness being checked. It must not invent new facts or silently substitute an unobserved relation.

### 9.3 Model reuse and optional subprocesses

The evaluator can group tasks that produce **identical generated Alloy model text**. It solves a shared exact model and interprets the appropriate witness functions against that one validated instance; it does not reuse arbitrary atom correspondences across different models. It may alternatively run independent work-unit batches in separately spawned JVMs with a frozen profile-token check, controlled by `metamodel.conformance.alloy.processes`.

Process-level concurrency is **not** equivalent to multiple Alloy threads sharing one mutable in-process solver state. Equivalence of partitioned outcomes to full-model outcomes must be established **for each relevant profile**, including complete witness tuples, not just status counts.

### 9.4 Partitioning risk and theoretical scope

The baseline planner uses source-rooted choices for some hierarchy-dependent rules. The prior manuscript-aligned patch proposed separate global-population metadata for O-03 and O-06. That matters because a support-only cycle or binding could be omitted by a source-rooted projection even when the paper's quantified theory refers to all represented carriers. The existence of a planner is **not**, by itself, evidence that every future rule is partition-safe.

**Primary sources:** [`AlloyWorkUnitPlanner.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/alloy/AlloyWorkUnitPlanner.java), [`InvariantDefinition.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/invariant/InvariantDefinition.java).

---

## 10. The Alloy evaluator and witness interpreter

### 10.1 Frozen execution profile

`AlloyExecutionConfig.frozen()` specifies Alloy **6.2.0**, solver ID **`sat4j`**, and a supported set of options including symmetry and witness interpretation. The evaluator refuses unsupported configurations rather than assuming results from another solver configuration are interchangeable. Exact profile identity matters even when two resources have the same human-readable rule names.

### 10.2 The two formal operations

1. **Consistency solve:** parse the exact Alloy model; find and execute `ObservationConsistency`; require a satisfiable instance over its fixed carriers.
2. **Witness evaluation:** locate the rule's registered `witnessFunction`, evaluate its relation using `AlloySimulatorWitnessEvaluator` on the SAT-validated concrete instance, and map returned atom tuples back to recorded technical keys.

The witness interpreter imports relations from the solver's instance into an official Alloy simulation representation, validates relation correspondence and refuses unsupported mutable-variable semantics. It is a concrete-instance relation interpreter, **not** a claim that all source language programs have been proved to satisfy a theorem.

### 10.3 How witness tuples are formed

A returned tuple might be a classifier, a method, a classifier/member pair or a pair of override-related methods, depending on the rule's declared arity. The evaluator:

1. Validates each tuple has the registered arity.
2. Normalizes Alloy atom labels.
3. Looks up every atom in the encoder's `atomTechnicalKeys` map.
4. Constructs a `WitnessTuple(List<String> technicalKeys)`.
5. Removes duplicates and sorts tuples deterministically.
6. Emits a rule-level `Decision` with its exact status, identifier, message and witness collection.

Source paths and lines are recovered by joining those technical keys with the retained classifier/member records. The CLI's printed description is therefore a *view* over source-linked evidence, not the primary scientific record. Multiple tuples may involve one source declaration; raw witness counts are not defect prevalence rates.

### 10.4 What `NON_CONFORMANT` actually establishes

The formal relation has at least one witness *within the admitted, exact observation and chosen rule profile*. It does not alone establish that the original compiler would reject the project, that the source extractor was correct, or that the violation should be called a bug without source-level adjudication.

**Primary sources:** [`AlloyInvariantEvaluator.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/alloy/AlloyInvariantEvaluator.java), [`AlloySimulatorWitnessEvaluator.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/alloy/AlloySimulatorWitnessEvaluator.java), [`AlloyExecutionConfig.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/alloy/AlloyExecutionConfig.java).

---

## 11. The complete decision contract and operational exit codes

A `Decision` is a record with **four fields**:

```text
Decision(
  status: DecisionStatus,
  invariantId: String,
  message: String,
  witnesses: List<WitnessTuple>
)
```

`DecisionStatus` has exactly three values:

| Status | Precise meaning | Witness interpretation |
|---|---|---|
| `CONFORMANT` | Admitted exact rule evaluation completed and found no violation tuple | Empty |
| `NON_CONFORMANT` | Admitted exact rule evaluation returned one or more violation tuples | At least one retained tuple |
| `NOT_EVALUATED` | Missing evidence or inability to complete trustworthy formal evaluation | Do not infer empty relation means success |

The `analyze` CLI prints every rule's decision and explanatory message, then unresolved parents and observation diagnostics when present, then readable witness summaries and the capsule path. Exit codes for `analyze` have precedence: **2** if *any* condition is nonconformant, else **3** if at least one is not evaluated, else **0** if all registered decisions are conformant. An argument/usage error is **64**; a caught processing exception is **3**. A full output can contain both nonconformant and unknown conditions even though the process exit code reports the stronger precedence class.

The separate `verify-capsule` command uses **0** for a valid formal replay and **3** for invalid/failed replay; it does not return a verdict about whether the source project conforms. `observe` returns **0** for a diagnostic-free observation and **3** if diagnostics exist. These operational returns must not replace reading the actual decision list.

**Primary sources:** [`Decision.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/decision/Decision.java), [`DecisionStatus.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/decision/DecisionStatus.java), [`PipelineCli.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/cli/PipelineCli.java).

---

## 12. Outputs: exactly what is retained and why

A normal completed `analyze` run creates these three canonical artifacts inside the specified output directory:

```text
<run-output>/
  observation.xmi             # Source/native observation, canonical typed facts
  repository-instance.als     # Exact finite Alloy representation and witness rules
  verification-capsule.json  # Completed-result marker, identities and all decisions
```

### 12.1 `observation.xmi`: the facts

Contains the serialized observation: schema and observer identity, source units and hashes, classifiers, members, inheritance relations, bodies, bindings, unresolved references, diagnostics and native/provenance evidence. Its contents answer **what the observer recorded**, including what remained incomplete.

### 12.2 `repository-instance.als`: the formal instance

Contains the exact Alloy carriers, relations, singleton atoms, rule functions and fixed scopes derived from that XMI and the captured profile. It answers **what formal structure was actually evaluated**. It is not the original program, a reconstructed compiler environment, or the historical manuscript's earlier `.aie` artifact.

### 12.3 `verification-capsule.json`: the result/provenance contract

`VerificationCapsule` stores:

| Field | Meaning |
|---|---|
| `formatVersion` | Capsule transport format (here `8`) |
| `toolId`, `toolVersion` | Named producing engine and its version |
| `schemaVersion` | Observation transport version |
| `adapterId`, `adapterVersion` | Language observer identity |
| `sourceSetSha256` | Digest of the recorded source-unit inventory |
| `observationPath`, `observationSha256` | Path and SHA-256 of XMI artifact |
| `alloyPath`, `alloySha256` | Path and SHA-256 of exact formal artifact |
| `invariantProfile` | Pair of registry/rule SHA-256 identities |
| `alloyExecution` | Frozen solver/witness-execution configuration |
| `observationDiagnostics` | Recorded observation diagnostics |
| `decisions` | Every rule's status, explanation and full witness tuples |

The capsule is written **last**. `ConformancePipeline.analyze()` removes any existing capsule at the destination before starting a new run. This matters: if observation or formal evaluation fails before publication, old evidence must not remain under a stale completion marker. A partial directory with XMI or Alloy but no valid capsule must **not** be reported as a finished run.

Artifacts use the code's size limits, atomic-file handling and path constraints. Retained support inventories, acquisition receipts, operation logs, timing, or imported source snapshots may exist in the workbench or reproduction workflow, but are **not all fields inside this three-file capsule**. Reporting must identify what was actually retained by the selected route.

**Primary sources:** [`VerificationCapsule.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/capsule/VerificationCapsule.java), [`CapsuleWriter.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/capsule/CapsuleWriter.java), [`ConformancePipeline.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/ConformancePipeline.java).

---

## 13. Replay versus reproduction: three distinct levels

These are three different operations; a successful result at one level does not automatically imply the others.

| Level | Input needed | What the code checks/does | What remains unproven |
|---|---|---|---|
| **Import / inspection** | Matching retained artifacts, optional retained source | Checks integrity/typed views; may regenerate inspection records | Does not solve Alloy or reobserve source |
| **Formal replay** | Capsule, XMI, exact Alloy file, matching engine and profile | Checks hashes/identities, regenerates Alloy text, re-evaluates decisions, compares complete decision lists | Source extraction fidelity; complete rebuild reproduction |
| **Fresh source re-observation** | Frozen source revision, exact source inventory and relevant dependencies/toolchain/build context | Re-runs an observer under that world; then compares observation and decisions | Must independently validate native facts and sampling validity |

### 13.1 What `CapsuleVerifier.verify()` actually does

It validates the capsule file and type/version, requires the expected producer, checks the frozen Alloy configuration and invariant-profile identity, resolves referenced artifacts without path escape/symlinks, checks byte hashes and source-set digest, reads the canonical XMI, confirms observation metadata and diagnostics, regenerates the exact Alloy text, and re-runs the formal decisions. It reports valid only when the repeated decision list matches the capsule's stored decisions.

A valid capsule thus supports **repeatability under the retained facts, producer and formal profile**. It cannot tell whether Python's effective MRO, a Java selected JDK, a C++ configured translation unit or the original author's full dependency world was accurately measured without independent source controls.

### 13.2 A full scientific reproduction requires more

Re-observation requires the same revisions, actual selected source population, dependency bytes, build/task selections, compiler/runtime identities, environment inputs, observer code and rule profile. Then the investigator should compare the normalized fact population **before** comparing decisions and witness tuples. If facts differ, a decision difference may be an extraction/source-world difference rather than a rule change.

**Primary sources:** [`CapsuleVerifier.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/capsule/CapsuleVerifier.java), [`reproduce.md`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/documentation/guides/reproduce.md).

---

## 14. Operational entry points, commands and outcomes

### 14.1 Build prerequisites

The public README specifies **full JDK 17** and **Maven 3.9+** for the engine; separate native providers can require CPython, Clang, build tools, Java distributions and controlled environments. Compile the engine with the pinned project version:

```bash
mvn --batch-mode --no-transfer-progress verify
```

The root POM sets artifact `metamodel-conformance-pipeline-next` version `0.15.0`, with a shaded CLI JAR expected at `target/metamodel-conformance-pipeline-next-0.15.0.jar` in a standard build. The existence of source files here is **not** evidence that this command was just run successfully.

### 14.2 Java CLI: full run

```bash
java -jar target/metamodel-conformance-pipeline-next-0.15.0.jar \
  analyze --language java \
  --source /absolute/path/to/java-project \
  --output /absolute/path/to/new-result
```

What happens:

1. CLI validates the options and selects the Java observer.
2. Observer snapshots and interprets the selected source world.
3. In-memory `Observation` is serialized to XMI and reloaded.
4. Exact Alloy instance is encoded and written.
5. Each registered condition is evidence-admitted or marked unknown.
6. Admitted conditions are planned, solved and interpreted.
7. Capsule is written with all decisions.
8. CLI prints summary/diagnostics/witnesses and returns the aggregate exit code.

### 14.3 Java CLI: observe only

```bash
java -jar target/metamodel-conformance-pipeline-next-0.15.0.jar \
  observe --language java \
  --source /absolute/path/to/java-project \
  --output /absolute/path/to/observation-check
```

**Observe-only does not produce the exact Alloy file or a verification capsule** and does not evaluate invariants. It is useful for diagnosing evidence coverage, unresolved parents, observer problems and source-world configuration before invoking expensive formal evaluation.

### 14.4 Python and C++ CLI

```bash
# The selected toolchain and environment still need to be available.
java -jar target/metamodel-conformance-pipeline-next-0.15.0.jar \
  analyze --language python --source /path/to/python-project --output /path/to/python-result

java -jar target/metamodel-conformance-pipeline-next-0.15.0.jar \
  analyze --language cpp --source /path/to/cpp-project --output /path/to/cpp-result
```

These examples select CLI observer corridors. They are **not** assertions that all repository types are supported or that every condition will be evaluable. Language dependency manifests and selected build contexts may be needed for defensible source-world coverage. Do not execute these examples in the local web interface and call them equivalent; the web execution slice is narrower.

### 14.5 Formal replay

```bash
java -jar target/metamodel-conformance-pipeline-next-0.15.0.jar \
  verify-capsule --capsule /path/to/new-result/verification-capsule.json
```

Expected console prefix is `VALID:` or `INVALID:`. `VALID:` is **formal artifact/decision replay** for the matching producer/profile, not native-source validation.

### 14.6 Work-unit commands (internal / controlled research use)

The CLI also exposes `evaluate-work-unit` and `evaluate-work-units` with explicit `--observation`, `--alloy`, `--invariant` and unit-index/range flags. These enable separate worker JVMs and controlled partition studies. Their output includes `WORKER_DECISION_JSON=...`, which should be treated as a machine-facing decision transport, not a substitute for a complete experiment/capsule.

### 14.7 Optional Experiment Workspace execution

The local Spring Boot MVC/Thymeleaf workbench supplies discovery, source selection, reviews, queued job attempts, result inspection, import and replay. It uses SQLite for operational records and an owned child process for the scientific engine. The default compute worker count is **one**, and extra slots invoke independent processes rather than changing Alloy's semantic interpretation. The workbench's `EngineGateway.analyze()` launches the bundled `analyze` CLI with local staged source and result paths; it does not calculate the verdicts itself.

Operational success indicators such as `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED` or a timeout are **attempt states**. They must not be counted as invariant-status classes. The web also has operation budgets (for example an engine subprocess budget on that path) which are not the same as a universal CLI deadline.

**Primary sources:** [`PipelineCli.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/cli/PipelineCli.java), [`EngineGateway.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/apps/research-web/server/src/main/java/metamodel/conformance/web/EngineGateway.java), [`first-analysis.md`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/documentation/tutorials/first-analysis.md).

---

## 15. A worked conceptual example: track one fact from input to verdict

The example in this section is an **illustrative derivation**, not a claim of a just-executed run or a pasted real capsule. It explains how to reason about the actual code path.

Assume a source file contains a class `Base` declaring a method `process`, and a class `Child` extending `Base`. Under a supported observer context:

1. **Source world:** the file bytes and their path are recorded as a `SourceUnit` with SHA-256; the exact revision and relevant dependencies are fixed externally.
2. **Declaration observation:** the observer records `Base` and `Child` as classifiers, their source spans and qualified names, and a parent link from `Child` to `Base` if resolved.
3. **Member observation:** it records `process` as a `MemberObservation` with the method's name and ordered parameter types, and attaches it to `Base.declaredMemberKeys`.
4. **Inherited-view observation:** if the selected observer can independently establish `Child`'s inherited view, it records the relevant member key in `Child.inheritedMemberKeys` and may admit `INHERITED_MEMBERS`; otherwise the category remains incomplete.
5. **Normalization:** these records are validated, deterministically ordered, written to `observation.xmi` and reloaded.
6. **Alloy mapping:** `Base` and `Child` become distinct classifier atoms; the parent and declared-member links become fixed relations. The observed inherited membership, if recorded, becomes a *separate* exact relation.
7. **Profile lookup:** the `inherited-view-consistency` rule requires five evidence categories. If any is missing, the condition is `NOT_EVALUATED` even though source declarations exist.
8. **Formal comparison:** if admitted, the rule computes a formal inherited-member view from the profile's ancestor/accessibility/hiding policy and compares it to the recorded inherited view.
9. **Decision:** any classifier/member mismatch becomes a recorded two-key witness and a `NON_CONFORMANT` status for that condition. If the relation is empty, evaluation succeeds and the condition is `CONFORMANT`.
10. **Retention and replay:** the XMI, exact Alloy model, profile identity, witness list and decision are bound into the capsule. A verifier can replay those formal facts and decisions.

**Crucial inference boundary:** If the formal inherited view differs from the compiler's view, the immediate justified statement is **a mismatch between independently observed and formally derived relations under the chosen profile**. It may take a native compiler control to decide whether the discrepancy is a source-language policy difference, an extraction error, or a bug in the formal rule.

### 15.1 The same example under missing dependencies

Suppose `Child` extends `ExternalBase`, but the needed definition is not available under the selected source world. The observer may record an `UnresolvedParent` and cannot claim complete `HIERARCHY`. A hierarchy-dependent rule becomes `NOT_EVALUATED`; the pipeline does not treat an unresolvable parent as proof that the inheritance graph is acyclic. Other independent rules may still be evaluable if their own evidence prerequisites are admitted.

### 15.2 The same example with an inconsistent exact model

Suppose source facts pass canonical transport but an exact work-unit model is inconsistent under Alloy's encoding assumptions. The evaluator does not use logical contradiction to call the rule true. It returns `NOT_EVALUATED` for the affected task/rule and records the failure reason.

---

## 16. Read the architecture diagram without mistaking shapes for semantics

The latest figure is a **research component/connector diagram**, not a standardized UML or SysML notation. Its graphic vocabulary is deliberate but the implementation and registry remain authoritative.

| Figure form | Intended architectural meaning | Example | Caution |
|---|---|---|---|
| Large outer dashed rectangle | Boundary of the scientific instrument | Entire pipeline | Does not mean all repository/build infrastructure is inside it |
| Smaller dashed rectangles | A responsibility/module group | Language observers, Invariant profile, Formal verification, Evidence admission, Replay | Groups do not execute on their own |
| Central dashed circle | Emphasized shared observation module | Shared observation | Circle indicates centrality, not a mathematical set or data value |
| Solid processing rectangle | Executable transformation or coordination | Normalize, Exact encoder, Work units, Capsule writer | It may delegate to multiple classes/processes |
| Document with bent/wavy edge | Versioned file input or generated artifact | `registry.json`, `invariants.als`, `observation.xmi`, `repository-instance.als` | Document shape does not mean a database |
| Cylinder | Structured in-memory representation or retained artifact bundle | EMF/Ecore, Artifacts | Does **not** imply an actual SQL database in the scientific core |
| Diamond | Condition controlling evaluation branch | `Ready?` | It checks evidence prerequisites, not the final conformance verdict |
| Rounded status label | Scientific result category | `CONFORMANT`, `NON_CONFORMANT`, `NOT_EVALUATED` | The labels denote outcomes, not operations |
| Solid directed arrow | Source data, execution order or recorded output transfer | Normalize -> EMF/Ecore; encoder -> `.als` | Direction and exact endpoints matter; arrows are not all proofs of causation |
| Light dotted arrow | Required rule/profile dependency | Rule profile -> admission | Not another source-data stream |
| Thin shared connector/bus | Explicitly merged or shared inputs of the declared type | Multiple observer outputs; retained artifact provenance | Junctions require careful interpretation, not incidental crossings |

The status labels may be softly tinted for recognition. **Color is redundant:** the status text and shapes must remain interpretable in monochrome printing. Modules and arrows should not share equally attention-grabbing borders. The architecture must be readable even if every accent is removed.

### 16.1 One subtle diagram-vs-code distinction

The diagram groups the evidence decision near the solver, but the implementation orders **encode before `evaluateAll()`'s evidence admission**. Likewise, `NOT_EVALUATED` is not always reached *only* by the `Ready? = no` branch: formal errors, inconsistent work units, input/model drift and other blocked evaluations can cause the same status. The legend-free figure is a deliberately simplified architectural view, not a substitute for the algorithm trace in Sections 7-11.

---

## 17. Failure modes from the first byte to the final capsule

Failures should be diagnosed at the stage where they arise. This table prevents treating every blocked run as a failed invariant.

| Stage | Example failure | Expected architectural consequence | Reporting classification |
|---|---|---|---|
| Git acquisition | Unsupported submodule/LFS pointer, exceeding retained-file budget, failed commit resolution | No verified selected-source snapshot | Acquisition failure; do not invent per-rule verdicts |
| Input selection | Root escapes trusted boundary or file changes mid-observation | Observer may reject selected world | Input-integrity/observation failure |
| Dependency/provider | Missing JAR, incompatible JDK/Clang context, unbound selected build target | Partial evidence or failed observation | Provider/coverage failure; list attempted population |
| Language parsing | Syntax or compiler parse error | Diagnostics; complete-evidence claims withheld; sometimes observer aborts | Observation diagnostic/failure |
| Hierarchy resolution | Unknown external base | `HIERARCHY` not complete; affected rules blocked | Per-condition `NOT_EVALUATED` where a decision is produced |
| Canonical model | Duplicate key, dangling target or invalid source path | `Observation` validation fails | Structural-transport failure, not nonconformance |
| XMI writer/reader | Serialization/schema mismatch | Formal stage cannot proceed | Serialization failure; capsule absent |
| Rule profile | Missing registry, duplicate IDs, invalid resource bytes, profile mismatch | Reject initialization or evaluation | Configuration/profile failure |
| Exact encoder | Invalid technical ID, malformed relation data, size/serialization failure | No trustworthy exact `.als` or evaluation | Encoding failure |
| Evidence admission | Required `EvidenceKind` missing | Rule not judged | `NOT_EVALUATED` |
| Work-unit planning | Unsafe/unavailable projection or failed plan | Relevant rule cannot be soundly checked | `NOT_EVALUATED` in evaluator path |
| SAT consistency | Exact work unit unsatisfiable | Do not claim rule holds | `NOT_EVALUATED` |
| Witness interpretation | Wrong arity, unknown atom mapping, unsupported AST construct | Relevant rule blocked | `NOT_EVALUATED` |
| Capsule publication | Write/hashing error | Cannot mark run completed | Incomplete/failed attempt |
| Replay | Missing artifact, altered hash, wrong profile/producer, changed decisions | Capsule rejected | Invalid **replay**; does not automatically relabel historical decisions |

### 17.1 Handling partial output safely

- A file being present does not make a run complete.
- A *capsule* being present is intended as the completion marker, but it must still be validated before publication claims.
- A newly failed attempt must not overwrite the identity of a previously valid experiment.
- A `NOT_EVALUATED` rule is not an excluded repository. It remains part of the relevant attempted population unless the research protocol independently specifies exclusion.
- Timeouts, failures and missing inputs should be counted and reported, not silently dropped to raise the observed conformance percentage.

---

## 18. How to design and report a scientifically defensible corpus experiment

The engine supplies a reproducible instrument path, not a sampling protocol. An experiment over the paper's historical repositories must **freeze and verify the exact population** before results can be compared.

### 18.1 Lock the experiment identity

For each repository/case, retain at minimum:

- Stable repository identity, exact commit and tracked source population.
- Selection and exclusion rationale, and whether dependencies/generated files were included.
- Language, observer route, adapter identity/version and selected contexts.
- Native compiler/runtime/provider identity, arguments and relevant input hashes.
- Source inventory and `sourceSetSha256`.
- Profile registry/rule SHA-256 values, engine JAR identity, Alloy/solver configuration.
- Canonical observation, exact `.als`, capsule, complete witnesses and diagnostics.
- Attempt state, timing, timeout/cancellation reason and any retries, with unique attempt identities.
- Independent native control expectations and any adjudication of disagreements.

### 18.2 Distinguish units of analysis

A repository is **not** an Alloy witness tuple; a classifier is **not** a source file; one invariant decision is **not** a repository-level pass; an attempt is **not** necessarily a completed observation. Report denominators separately:

| Count | Unit |
|---|---|
| Selected population | Repositories, commits or source-world cases |
| Acquisition/observation | Successful/failed attempts or contexts |
| Admitted evaluations | Invariant-case decisions where prerequisites were met |
| Unknown results | Invariant-case `NOT_EVALUATED` decisions |
| Findings | Distinct relation-witness tuples, per registered rule |
| Affected source entities | Deduplicated declarations/technical keys, with clearly stated mapping |

The paper's earlier *224-repository* experiment used a **different extraction/mapping/checker chain** (`TypeModel JSON` -> `.aie` -> historical Java checker) from this engine's XMI/Alloy chain. Published historical counts cannot simply be copied into the new engine's results table. A same-corpus rerun requires the original frozen repository list and source revisions, an executable verified engine and an explicit paired comparison plan.

### 18.3 Separate three validation questions

1. **Source-semantic validity:** independent native compiler/runtime checks agree with observed facts under the declared source world.
2. **Formal-evaluator validity:** exact instance, witness functions and optimized partitioning compute what the rule specification intends.
3. **Corpus-level inference:** the recorded cases and outcome denominators support the manuscript's empirical statements.

Passing question 2 cannot compensate for untested questions 1 or 3. A reference model with 47 queries in the prior paper is also a separate validation campaign from using Alloy 6.2.0 to interpret observations from real repositories.

### 18.4 Recommended minimum retained results table

| Column | Meaning |
|---|---|
| `experiment_id` / `attempt_id` | Unique study and run identity |
| `repository`, `commit`, `language`, `world_id` | Frozen source case |
| `engine_sha`, `adapter_id`, `schema_version` | Producer facts |
| `registry_sha`, `rules_sha`, `alloy_config` | Formal profile facts |
| `observation_sha`, `alloy_sha`, `capsule_sha` | Artifact integrity |
| `invariant_id`, `decision_status`, `message` | One recorded condition outcome |
| `required_evidence`, `complete_evidence`, `missing_evidence` | Admission provenance |
| `witness_tuple_count`, `witness_technical_keys` | Result details without invented aggregation |
| `attempt_state`, `duration`, `timeout_or_failure_reason` | Operational accounting |
| `native_control_status`, `adjudication` | Independent scientific interpretation |

This is a **recommended external experiment ledger schema**, not a claim that the current capsule already contains every listed field.

---

## 19. What changes when you add or update an invariant?

An invariant is not just a new diagram box. A correct change requires separating **theoretical meaning**, **observable inputs**, **formal witness logic**, **admission conditions**, and **evaluator qualification**.

1. **Write the theory independently.** State the proposition, quantified carriers, language scope, exceptions and expected violations. Do not derive it from whichever source fields happen to be available.
2. **Map the theory to the canonical model.** Decide whether all necessary facts (and their completeness guarantees) already exist.
3. **Extend observation only if necessary.** New concepts may require a new model/schema version, adapter projections and native evidence; do not inject invariant-specific shortcuts into one language observer.
4. **Add registry metadata.** Assign a stable semantic ID, `specificationTrace`, witness function/arity, `requiredEvidence`, partition metadata and messages.
5. **Add or change Alloy witness logic.** The function should return exactly the carrier relation representing violations, under a documented formal policy.
6. **Review partitioning.** Demonstrate that work units preserve all involved relations and quantified populations. Compare complete witnesses, not only status.
7. **Construct independent source controls.** Include positive cases, negative/counterexamples and unknown/unsupported cases. Confirm the source/native expectations separately.
8. **Rebuild and run formal integration.** Exercise the production encoder, official Alloy evaluator and capsule replay using the new frozen profile.
9. **Version the profile and outputs.** Preserve old registry/rule hashes and old study results. A changed semantics or observation population is a new experiment.
10. **Document deviations.** If the cross-language policy differs from valid Java/Python/C++ source semantics, report it as a profile comparison rather than automatically calling the project defective.

The preceding steps describe the recommended scientific change process. **They are not assertions that a new manuscript-aligned profile has already passed them all.**

**Primary source:** [`rule-profiles.md`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/documentation/explanation/rule-profiles.md).

---

## 20. Security, resource and trust boundaries

The code contains controls to reduce accidental source/artefact confusion and unsafe path handling. They are operational safeguards, not a general security proof.

- The CLI validates allowed option combinations. Native context tools have additional explicit contracts and may reject unsupported inputs.
- Input snapshotting validates selected paths and detects source changes during capture.
- EMF/XMI model construction rejects broken structural references.
- The encoder validates technical IDs and produces canonical exact relation text.
- Artifact writes are bounded and use atomic publication mechanisms where implemented.
- `CapsuleVerifier` rejects paths that escape the capsule directory or involve forbidden symlink components; it verifies artifact hashes before formal replay.
- The local web service uses loopback/session/origin controls, a trusted input-root/workspace split, owned subprocesses, bounded source acquisition and independent worker slots.
- The workbench is **not designed to be exposed as an unmodified public multiuser service**.
- A GitHub source acquisition receipt attests to selected tracked blobs, not automatically to a build, executable dependencies, source representativeness or compiler fidelity.

When a native source/build provider executes a helper, its actual executable, arguments, environment and controlled provenance must be reviewed independently. A verification capsule is not a sandbox certificate.

---

## 21. Module-level call graph and code lookup

Use the following table to find the concrete implementation rather than relying on the architecture drawing alone. All paths are relative to the repository root and pinned to the inspected engine source commit.

| What you want to understand | File or location | Key entrypoint/data contract |
|---|---|---|
| Root build/dependencies | [`pom.xml`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/pom.xml) | Engine version, dependency versions, shaded CLI entrypoint |
| CLI parsing/exit codes | [`PipelineCli.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/cli/PipelineCli.java) | `run`, `analyze`, `observe`, `verifyCapsule`, observer selection |
| Scientific orchestration | [`ConformancePipeline.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/ConformancePipeline.java) | `analyze` (observe -> XMI -> encode -> evaluate -> capsule) |
| Observer dispatch | [`SourceObserverFactory.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/adapter/SourceObserverFactory.java) | Language/native-context selection |
| Source snapshot | [`SourceInputSnapshot.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/adapter/SourceInputSnapshot.java) | Byte capture and changed-population detection |
| Java observer | [`JavaDependencyAwareSourceObserver.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/adapter/java/JavaDependencyAwareSourceObserver.java) | Source/compiler/dependency observation |
| Python observer | [`PythonAstObserver.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/adapter/python/PythonAstObserver.java) | CPython AST and conditional native evidence |
| C++ observer | [`ClangCppObserver.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/adapter/cpp/ClangCppObserver.java) | Clang/translation-unit source observation |
| Canonical record | [`Observation.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/model/Observation.java) | Typed population, sorting, referential integrity, completeness claims |
| Evidence enum | [`EvidenceKind.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/model/EvidenceKind.java) | All twelve prerequisite kinds |
| EMF schema | [`ObservationSchema.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/emf/ObservationSchema.java) | Observation schema version and Ecore load |
| XMI serialization | [`ObservationXmiWriter.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/emf/ObservationXmiWriter.java) | Canonical XMI writing |
| XMI decoding | [`ObservationXmiReader.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/emf/ObservationXmiReader.java) | Canonical XMI reading/validation |
| Rule registry | [`InvariantDefinition.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/invariant/InvariantDefinition.java) | Metadata, evidence and partition contract |
| Frozen rule identity | [`InvariantProfile.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/invariant/InvariantProfile.java) | Byte-captured registry/rule profile and hashes |
| Registered conditions | [`registry.json`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/resources/invariants/registry.json) | Eleven baseline checks and prerequisites |
| Formal formulas | [`invariants.als`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/resources/alloy/invariants.als) | Exact baseline violation functions |
| Exact encoder | [`ExactAlloyEncoder.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/alloy/ExactAlloyEncoder.java) | Exact atoms, relations, scopes, generated `.als` |
| Work-unit planner | [`AlloyWorkUnitPlanner.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/alloy/AlloyWorkUnitPlanner.java) | Condition-specific projections and packing |
| Scientific evaluator | [`AlloyInvariantEvaluator.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/alloy/AlloyInvariantEvaluator.java) | Admission, consistency solve, witnesses, decisions |
| Concrete witness interpreter | [`AlloySimulatorWitnessEvaluator.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/alloy/AlloySimulatorWitnessEvaluator.java) | SAT-validated instance evaluation |
| Frozen solver options | [`AlloyExecutionConfig.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/alloy/AlloyExecutionConfig.java) | Alloy/SAT4J settings and witness interpreter ID |
| Scientific result | [`Decision.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/decision/Decision.java) | Status/message/witness tuples |
| Capsule format | [`VerificationCapsule.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/capsule/VerificationCapsule.java) | Hashed result and producer/profile metadata |
| Capsule replay | [`CapsuleVerifier.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/src/main/java/metamodel/conformance/pipeline/capsule/CapsuleVerifier.java) | Integrity checks, regenerated Alloy and decision comparison |
| Git acquisition (web) | [`GitRepositorySource.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/apps/research-web/server/src/main/java/metamodel/conformance/web/GitRepositorySource.java) | Pinned tracked Git blobs/inventory |
| Web engine bridge | [`EngineGateway.java`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/blob/5ea604fb29b0ff9807c621b7286691440ee9a630/apps/research-web/server/src/main/java/metamodel/conformance/web/EngineGateway.java) | Child JVM invocation, result inspection and replay |
| Provider/batch tooling | [`tools/`](https://github.com/elfilaoussama/metamodel-conformance-pipeline/tree/5ea604fb29b0ff9807c621b7286691440ee9a630/tools) | Dependency/build preparation and experimental automation |

---

## 22. Remaining scientific and implementation questions (explicitly not settled by this manual)

This document is a verified **description of implementation paths**, not their universal certification. Before calling the instrument a publication-validated reproducer, obtain evidence for the following:

1. **Native source fidelity:** independent Java/Python/C++ controls for every source/world profile and claimed evidence category, including negative and unsupported cases.
2. **Theory-to-rule equivalence:** an explicit manuscript-condition -> observation -> Alloy witness crosswalk, especially O-01, O-04, O-06, O-07 and O-09.
3. **Partition equivalence:** full-model vs projected-model decision and complete witness-tuple correspondence under the selected frozen rule profile.
4. **Replay qualification:** actual Maven/Alloy regression under the intended release JAR, with raw logs and original artifact hashes.
5. **Corpus identity:** the exact 224 original repository revisions, selected files, dependencies and exclusions before attempting a paired rerun.
6. **Result accounting:** denominators for attempted, observed, admitted, not-evaluated, failed and excluded populations; no mixing with historical TypeModel/`.aie` results.
7. **Profile version boundaries:** the authored manuscript-observable patch must pass its full integration controls before replacing or supplementing baseline experimental findings.
8. **Released test evidence:** the public README states qualification tests and generated research validation records are not included in the public distribution. Their existence or historical description is **not** a publicly rerun qualification result.

---

## 23. Quick glossary

| Term | Plain-language explanation |
|---|---|
| **Adapter / observer** | Extracts facts from a selected language source world |
| **Provider** | Prepares context (files, dependencies, build/compiler inputs) used by an observer |
| **Source world** | Actual selected files and semantic/tool context being measured |
| **Source unit** | Recorded source/support input path, language and byte digest |
| **Classifier** | Observed class/interface/struct-like carrier |
| **Member** | Observed method or attribute carrier |
| **Native evidence** | Language/compiler-specific receipts not flattened into generic guesses |
| **Complete evidence** | Explicit claims that named prerequisites were observed sufficiently for evaluation |
| **Ecore** | Versioned metamodel specifying the observation transport structure |
| **XMI** | XML-based serialization of the typed EMF/Ecore observation |
| **Invariant registry** | Metadata giving each rule's ID, witness function, prerequisites and partition policy |
| **Alloy rule profile** | Relational definitions expressing formal policy and witness functions |
| **Exact Alloy instance** | Finite formal representation constrained to recorded observation carriers/relations |
| **Witness** | Concrete violating atom tuple returned by an admitted rule's relation |
| **Work unit** | Deterministic rule-specific projection used for manageable evaluation |
| **Decision** | Rule-specific status, message and complete witness tuples |
| **Capsule** | Immutable-style retained record binding identities, hashes, decisions and artifacts |
| **Replay** | Re-evaluation from stored formal evidence without re-observing source |
| **Re-observation** | A new observation of the actual selected source world under a specified tool/context |

---

## 24. The full lifecycle, condensed into thirty checkpoints

Use this as an operational checklist, not as a claim that any particular run has completed every stage:

- [ ] 01. Define the scientific question and condition profile.
- [ ] 02. Specify repository/source-case selection criteria.
- [ ] 03. Freeze the repository revision or local source snapshot.
- [ ] 04. Identify the files, roles and dependency/build context to be observed.
- [ ] 05. Declare language, observer profile, compiler/runtime/provider versions.
- [ ] 06. Define a fresh run/output identity and attempt record.
- [ ] 07. Capture selected input bytes, inventories and digests.
- [ ] 08. Select a `SourceObserver` through the source-world factory.
- [ ] 09. Parse/compile-observe each supported source context.
- [ ] 10. Record classifiers, members, direct parents and source spans.
- [ ] 11. Record derived/native facts, bodies, overrides and bindings where justified.
- [ ] 12. Preserve unresolved references, diagnostics and native receipts.
- [ ] 13. Admit only justified `completeEvidence` categories.
- [ ] 14. Validate canonical observation IDs, links and source inventory.
- [ ] 15. Serialize the observation to the versioned Ecore/XMI file.
- [ ] 16. Reload and validate the canonical XMI.
- [ ] 17. Capture registry/Alloy rule-resource bytes and profile hashes.
- [ ] 18. Encode the exact finite Alloy carriers and relations.
- [ ] 19. Persist `repository-instance.als` and its hash.
- [ ] 20. Compare per-rule required evidence with admitted categories.
- [ ] 21. Mark missing-prerequisite rules `NOT_EVALUATED`.
- [ ] 22. Verify that the `.als` text matches canonical regenerated encoding.
- [ ] 23. Plan rule-specific graph/closure work units.
- [ ] 24. Parse/solve each admissible exact Alloy work unit consistently.
- [ ] 25. Interpret violation functions and map atom tuples to technical keys.
- [ ] 26. Form complete `CONFORMANT`/`NON_CONFORMANT`/`NOT_EVALUATED` decisions.
- [ ] 27. Retain diagnostics, full witnesses and canonical artifact hashes.
- [ ] 28. Write `verification-capsule.json` **last**.
- [ ] 29. Verify/replay the capsule under matching engine/profile conditions.
- [ ] 30. Independently adjudicate source/native fidelity and report complete population accounting.

**Bottom line:** The scientific result is not a green icon or a successful parser. It is a **traceable chain** from a specified input world through qualified observations and exact formal evaluation to a source-linked, reproducible set of condition-specific decisions, with unknowns and unverified assumptions retained rather than hidden.
