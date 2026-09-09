# Metamodel Conformance Pipeline Next

A deterministic, fail-closed pipeline that observes source code as a small EMF
model, maps extracted evidence to registered invariants, and evaluates those
invariants with the official Alloy engine.

The current repository profile contains eleven semantic checks spanning the
manuscript conditions O-02 through O-09:

- `exclusive-declaration-ownership` (trace: O-02);
- `acyclic-generalization` (trace: O-03);
- `inherited-view-consistency` (trace: O-04);
- `local-inherited-separation` (trace: O-05);
- `implementation-binding-consistency` (trace: O-06);
- `abstraction-implementation-consistency` (trace: O-07 repository profile);
- `static-abstract-method-separation` (trace: O-07 repository profile);
- `local-namespace-uniqueness` (trace: O-08-local);
- `inherited-namespace-uniqueness` (trace: O-08-inherited);
- `override-relation-consistency` (trace: O-09 bridge/correspondence); and
- `override-discipline` (trace: O-09 strict policy).

O-01 remains an identity-preservation/bridge-integrity question rather than a
source conformance predicate because generated technical keys are not independent
source identities. The O-07 direct-instance clause also remains outside the
repository experiment because runtime/model objects are not observed.

```text
source -> language observer -> extracted evidence -> canonical observation.xmi
       -> invariant registry + exact Alloy instance -> official Alloy solution
       -> invariant witnesses + provenance -> verification-capsule.json
```

This is deliberately not a general source-code-to-Ecore reverse engineer. The
Ecore model is a stable observation contract shared by language adapters. Each
adapter records evidence; it never implements an invariant.

## Decision semantics

| Result | Meaning |
|---|---|
| `CONFORMANT` | The invariant's Alloy witness relation is empty. |
| `NON_CONFORMANT` | The invariant's Alloy witness relation contains one or more tuples. |
| `NOT_EVALUATED` | Required evidence is missing or evaluation failed; no conformance claim is made. |

Unresolved parent types therefore cannot silently disappear from the graph.

## Build and run

Requirements: JDK 17 and Maven 3.9+.

```bash
mvn verify
mvn -q -DskipTests package
java -jar target/metamodel-conformance-pipeline-next-0.9.0-SNAPSHOT.jar \
  analyze --source examples/acyclic --output build/acyclic
java -jar target/metamodel-conformance-pipeline-next-0.9.0-SNAPSHOT.jar \
  verify-capsule --capsule build/acyclic/verification-capsule.json
```

Projects that require external compile-time types can provide exact dependency
archives with repeated `--dependency-jar` options. Each accepted JAR is validated
as a regular non-symbolic-link file, hashed, and included in the canonical source
set as `JAVA_ARCHIVE` evidence. Dependency archives improve compiler resolution;
they do not make an unresolved external hierarchy conformant by themselves.

The same input and tool version produce byte-identical `observation.xmi`, Alloy
model, and capsule. Capsule format v6 archives the exact Alloy 6.2.0/SAT4J
execution profile, including symmetry, skolemization, overflow, unrolling, core,
and decomposition options. Replay rejects any missing or changed option before
interpreting the artifacts. Wall-clock timestamps are intentionally excluded.
Invariant metadata and evidence requirements come from one registry; all invariant
semantics come from one Alloy resource. For each evaluable invariant, the registry
declares which observed relations connect an exact solver work unit and which atom
kind roots those units. The evaluator solves every deterministic connected
component, aggregates the Alloy-defined witnesses, and maps their atoms back to
source locations. Java performs structural partitioning only; it does not decide
whether an invariant is violated. An inconsistent work unit is `NOT_EVALUATED`,
never `CONFORMANT`.

Artifact publication is fail-closed. Producers and verifiers enforce the same byte
limits (32 MiB XMI, 16 MiB Alloy, and 1 MiB capsule), with UTF-8 text measured as
encoded bytes. XMI and Alloy files are replaced atomically, and the capsule is
written last as the completed-run marker. Once extraction succeeds, a failed rerun
removes any older capsule before publishing new artifacts, so partial output cannot
be mistaken for a verified result.

Inherited memberships are observed independently with the JDK compiler's
`Elements.getAllMembers` API, using annotation processing disabled and either an
empty classpath or the exact fingerprinted dependency archives supplied by the
caller. Spoon remains responsible for declaration evidence, while source
provenance maps javac's semantic members back to the canonical declaration atoms.
`INHERITED_MEMBERS` is complete only when javac reports no errors and every internal
type/member mapping is unique. Otherwise only invariants requiring that evidence
return `NOT_EVALUATED`; an absent tuple is never treated as complete evidence.
The adapter records declaration visibility and classifier package independently.
Alloy derives contextual accessibility, including package-private inheritance and
mixed-package ancestor chains. Private and interface-static declarations remain
non-inheritable. Javac's independently observed inherited view is compared with
that formal derivation.

O-09 uses a second independent compiler observation. Javac resolves each canonical
source method's return type and the source-method pairs for which
`Elements.overrides` holds. Schema-12 `observation.xmi` preserves those facts as
`METHOD_RETURN_TYPES` and `OVERRIDE_RELATIONS`. Alloy independently derives the
manuscript-level ancestor/accessibility/signature/scope candidate relation.

The O-09 result is intentionally split. `override-relation-consistency` compares
the compiler-observed relation with the formal candidate relation and therefore
reports bridge or policy-correspondence disagreements separately. For example,
Java static hiding is not reported by `Elements.overrides`, while the manuscript's
same-key/same-scope structural predicate can still classify the pair as a formal
candidate. `override-discipline` separately evaluates the manuscript's strict
return-equality and abstract-or-implemented consequences over formal override
pairs. It still requires independent `OVERRIDE_RELATIONS` evidence, so an unavailable
frontend relation cannot make the strict empirical check vacuously conformant.
Compiler or mapping incompleteness yields `NOT_EVALUATED` rather than an
empty-relation conformance result.

Conventional module source sets such as `module/src/main/java` and
`module/src/test/java` are treated as separate semantic source sets. Duplicate
qualified type names in different source sets therefore remain distinct declarations
instead of poisoning the entire repository observation. Parent resolution prefers a
same-set declaration, then a sibling production declaration, and finally a globally
unique declaration. Compiler-derived evidence is claimed only when the configured
source set can be resolved without ambiguity; missing build/source-set context is
reported as incomplete evidence rather than guessed.

## Invariant extensibility

The Java evaluator contains no invariant identifiers and no invariant-specific
branches. Adding or changing an invariant consists of changing its entry in
`src/main/resources/invariants/registry.json` and its Alloy witness function in
`src/main/resources/alloy/invariants.als`. The registry declares the required
evidence, witness arity, partition relations, and partition roots. The generic
evaluator discovers every entry, checks its evidence, plans its exact work units,
evaluates its Alloy function, maps its tuples to provenance, and records the
result. Java changes are needed only when a genuinely new kind of source evidence
or structural projection primitive must be supported—not when an invariant
formula changes.

The canonical EMF model retains member kind, name, visibility, declaring package,
inheritability, complete ordered parameter-type lists, method abstraction/scope,
return types, and independently observed override relations. The exact Alloy model
preserves these as structural relations. Alloy—not Java—defines the policy
judgment. Future invariants may reuse this evidence; if they need a genuinely new
source fact, the observation contract and adapter must be extended rather than
evaluating from invented evidence.

## Scope

The current adapter accepts a closed Java source root. Parent types declared
outside that root must be explicitly allowlisted with `--external-parent`; an
unallowlisted parent makes hierarchy-dependent invariants `NOT_EVALUATED`.
Resource exhaustion and solver failures are reported as failures, not scientific
limits or findings. Declarations with the same qualified name in different source
paths retain distinct path-based identities. A reference that cannot be assigned
uniquely across those declarations remains unresolved, so hierarchy-dependent
invariants are `NOT_EVALUATED`; the adapter never chooses a source set implicitly.

Build-system dependency resolution is isolated and best effort. If a resolver
cannot produce a trustworthy archive manifest, the corpus harness still runs the
source-only observation and records the resolver exit separately; dependency-
dependent invariants remain `NOT_EVALUATED` and the resulting capsule stays
replayable.

Java files rejected by the parser remain in the hashed source set and are recorded
as normalized, source-path diagnostics in schema-12 `observation.xmi`. Valid files
may still be preserved as partial observations, but no evidence kind is marked
complete and every invariant is `NOT_EVALUATED`. The Alloy artifact and capsule are
still emitted and independently replayable; a parse error is not a missing result.

See [the observation contract](docs/decisions/0001-observation-contract.md) and
[the invariant pipeline contract](docs/invariant-pipeline.md). The latest frozen
engineering-corpus evidence is recorded in
[the current corpus validation](docs/current-corpus-validation.md).
