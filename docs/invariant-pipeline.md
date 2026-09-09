# Invariant-driven pipeline contract

The pipeline is independent of the current invariant set.

```text
Source repositories
  -> language-specific observers
  -> extracted evidence with provenance and completeness
  -> canonical EMF observation
  -> mechanical exact Alloy encoding
  -> invariant registry
  -> Alloy witness functions
  -> generic invariant evaluator
  -> CONFORMANT | NON_CONFORMANT | NOT_EVALUATED
  -> witnesses mapped to source provenance
  -> verification capsule and independent replay
```

## Terminology

- **Extracted evidence** is what a language observer can establish from source.
- **Invariant** is a formal property evaluated by Alloy over that evidence.
- **Policy profile** is the selected registry of invariants.
- **Specification trace** links an invariant to a research condition such as
  O-03; it is metadata and never controls execution.

## Stable extension contract

Every registry entry declares a semantic invariant ID, an Alloy witness
function, witness arity, required evidence kinds, and messages. The evaluator
iterates the registry and contains no switch, branch, or class named after an
individual invariant.

Changing an existing formula changes only its Alloy witness function. Adding an
invariant adds one registry entry and one witness function. An observer or the
EMF schema changes only when the new invariant requires evidence that the
canonical observation cannot yet represent.

Missing required evidence produces `NOT_EVALUATED` for only the affected
invariant. A non-empty Alloy witness relation produces `NON_CONFORMANT`; an empty
relation over a satisfiable exact observation produces `CONFORMANT`. Parsing,
encoding, solving, witness-arity, or provenance failures can never produce
`CONFORMANT`.

## Exact work units

The registry declares `partitionRelations` and `partitionRoots` for each
invariant. The generic planner treats the selected observed relations as an
undirected connectivity graph and produces deterministic connected components.
Disconnected components may be packed together to reduce solver startup cost,
but a component is never split. Directed relation tuples are preserved unchanged;
connectivity is used only to identify independent solver work. The packing target
is an execution heuristic, not a repository-size limit: no observed atom or tuple
is dropped, and an oversized connected component remains intact.

All witness tuples from all work units are deduplicated and aggregated before the
invariant decision is produced. A failure in any required work unit makes that
invariant `NOT_EVALUATED`. This avoids repository-size caps while preserving every
cross-atom relation declared relevant by the invariant registry.

## Independent Java inherited view

Spoon observes declarations, direct parents, member signatures, and source
provenance. A separate JDK compiler task observes inherited memberships through
`Elements.getAllMembers`; annotation processing is disabled and the compiler uses
an empty classpath or the exact dependency archives supplied for the experiment,
so repository build plugins are not executed. Javac members are mapped back to
Spoon declaration atoms only through source ownership, member kind/name, ordered
parameter types, and source provenance.

`INHERITED_MEMBERS` is declared complete only when javac reports no errors and all
internal type and declaration mappings are unique. Any missing dependency,
duplicate mapping, compiler failure, or unsupported source shape yields incomplete
evidence and an empty stored inherited relation. The affected invariants then return
`NOT_EVALUATED`; the adapter never substitutes its own inheritance algorithm.

Schema v12 records member visibility and classifier package as separate source facts.
Alloy derives contextual accessibility from those facts: public and protected
members are accessible along inheritance, private members are not, and
package-private members require an unbroken same-package inheritance path.
Interface static methods remain explicitly non-inheritable. Javac independently
observes the resulting inherited view, so the expected and observed relations do not
come from the same algorithm.

Javac failures are preserved as `EVIDENCE_INCOMPLETE` diagnostics with canonical
source locations. These diagnostics may coexist with independently complete evidence
for ownership, hierarchy, or local signatures; only invariants requiring the missing
inherited view return `NOT_EVALUATED`.

Conventional `src/<set>/java` roots are separate semantic source sets. Structural
parent resolution first uses the declaring source set, then its sibling
`src/main/java`, and only then a globally unique declaration. This prevents
duplicate test and production type names from becoming accidental ambiguity while
preserving both declarations in the canonical observation. Compiler-derived
evidence remains fail-closed when the source-set compilation context cannot be
reconstructed without ambiguity.

## Independent Java override view

O-09 adds compiler-observed facts without moving policy judgment into Java. For
every canonical source method, a javac task records its resolved return-type
representation and maps `Elements.overrides` pairs back to canonical method atoms.
`METHOD_RETURN_TYPES` and `OVERRIDE_RELATIONS` are complete only when compilation
succeeds and every source method can be mapped uniquely. Missing or ambiguous
compiler evidence therefore makes the affected O-09 checks `NOT_EVALUATED`.

The Alloy model receives `returnType` and `observedOverrides` as exact relations.
It independently derives the manuscript-level candidate relation from hierarchy,
contextual accessibility, ordered method keys, and method scope. O-09 then has two
registered outputs because these answer different empirical questions:

1. `override-relation-consistency` compares javac's independent override relation
   with the formal structural relation. A non-empty witness is a bridge or
   policy-correspondence disagreement. It is not automatically a type-policy
   violation. Java static hiding is the canonical boundary case: javac does not
   classify it as overriding, while the manuscript's same-key/same-scope structural
   predicate can still identify a formal candidate.
2. `override-discipline` evaluates the manuscript's strict consequences over formal
   override pairs: equal return type and a local declaration that is explicitly
   abstract or has an implementation binding in its declaring classifier. The
   registry still requires `OVERRIDE_RELATIONS` for this empirical check, even
   though the Alloy function itself operates on the formal relation, so unavailable
   independent override evidence cannot produce a vacuous conformance claim.

This split preserves causal interpretation in the corrected experiment. A frontend
versus formal-relation disagreement and a strict return/implementation disagreement
are both relevant to O-09 but are not the same finding.

## Reproducible Alloy execution

Capsule format v6 records the immutable execution configuration actually owned
by the evaluator: Alloy version, solver ID, partial-instance inference, symmetry,
skolem depth, core settings, Kodkod recording, overflow policy, unrolling, and
decomposition settings. The verifier accepts only the frozen supported profile,
reconstructs `A4Options` from that archived object, and rejects configuration
drift before reading or solving the observation artifacts. Machine-specific
temporary-directory fields are deliberately excluded from experiment identity.

## Atomic artifact publication

The producer and independent verifier share the same limits: 32 MiB for canonical
XMI, 16 MiB for the Alloy artifact, and 1 MiB for the verification capsule. Text
limits count UTF-8 bytes. Each artifact is written through a temporary file and
atomically replaces its target where the filesystem supports that operation.

The verification capsule is the final commit marker for a completed run. Extraction
finishes before an existing marker is removed; after that point, any encoding,
solving, or writing failure leaves no capsule claiming that the output directory is
complete. A new capsule is published only after the XMI, Alloy model, decisions, and
their hashes are finalized.

## Evidence projections

The canonical EMF observation is the durable evidence boundary. It preserves
member kind, name, visibility, declaring package, inheritability, complete ordered
method-parameter type lists, method abstraction/scope, return types, and independent
override relations. The exact Alloy model carries those observations structurally
through `kind`, `memberName`, `parameterTypeAt`, `returnType`, and
`observedOverrides` relations. Parameter positions and type tokens remain separate
atoms, so Java never groups members into semantic namespace-key equivalence classes.

Alloy alone defines whether two methods have the same ordered signature, whether
two attributes have the same local name, whether the frontend and formal override
relations correspond, and whether formal override pairs satisfy the selected strict
policy. A future invariant may reuse these relations without changing Java control
flow. If it needs a genuinely new source fact, that fact must first be added to the
canonical observation and independently observed; the pipeline must never infer
element-level evidence that the frontend did not provide.

## Parse diagnostics

Parser-rejected source units remain part of the canonical source-set digest and
are represented as source-path diagnostics in the observation model. Successfully
parsed units may be retained for diagnostic value, but any parse error clears all
global evidence-completeness claims. Consequently every registered invariant is
`NOT_EVALUATED`, while deterministic XMI, Alloy, decisions, and capsule replay are
still produced. A parse failure is therefore explicit evidence about observation
completeness, never a tool-success result or an absent artifact.
