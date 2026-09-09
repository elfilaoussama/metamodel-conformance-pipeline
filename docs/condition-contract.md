# O-01–O-09 research condition contract

This contract defines the empirical meaning of each formal condition before a language frontend is allowed to claim it as evaluable. The project separates **observation** from **judgment**: source-language frontends collect independently defensible facts, Ecore/XMI preserves those facts, and Alloy alone defines conformance or violation. O-numbers are manuscript trace labels; executable code uses semantic invariant IDs.

A condition is not made executable merely because its Alloy formula exists. It becomes executable for an observation only when every evidence kind required by that semantic check is complete. Missing evidence yields `NOT_EVALUATED` rather than guessed conformance.

| Trace | Condition | Formal/empirical correspondence | Witness | Repository profile |
|---|---|---|---|---|
| O-01 | Independent identity | Distinct carriers require independently observed identifiers, not generated technical keys | Carrier pair | Deferred: independent source identity is not yet justified |
| O-02 | Exclusive declaration ownership | Every observed method/attribute occurs in exactly one local-member relation | Member | Executable |
| O-03 | Acyclic generalization | `no c : Classifier \| c in c.^parents` over the exact observed hierarchy | Classifier | Executable when hierarchy evidence is complete |
| O-04 | Inherited view derivation | Frontend-resolved inherited membership must equal the Alloy-derived ancestor view | Classifier/member pair | Executable when inherited-view evidence is complete |
| O-05 | Local/inherited separation | Local and independently observed inherited membership are atom-disjoint | Classifier/member pair | Executable when inherited-view evidence is complete |
| O-06 | Implementation binding | Standalone source bodies and compiler-resolved declaration/body correspondence form explicit implementer-target-body bindings | Member or body | Executable when implementation evidence is complete |
| O-07 | Abstraction and instantiation | Classifier abstractness must agree with unresolved visible implementations; static methods are separated from abstract method declarations; the formal direct-instance clause additionally requires object evidence | Classifier or member; object for the deferred clause | **Repository-observable abstraction subprofile executable; direct-instance subclause deferred** |
| O-08 | Namespace/conflict | Method key = name + ordered parameter types; attribute key = name | Conflicting member(s) | Local and inherited variants executable under their evidence requirements |
| O-09 | Override discipline | Frontend-resolved source override pairs are compared with the formal ancestor/signature/scope relation; the formal pairs are then checked for strict return equality and abstract-or-implemented disposition | Override pair | Executable when compiler override/return evidence and O-06 implementation evidence are complete |

## Evidence and modeling rules

- `technicalKey` and classifier IDs are deterministic generated trace keys. They are not independent semantic identities and cannot satisfy O-01.
- Members and method bodies are technically contained by the observation root, while declaration ownership and implementation binding are explicit relations. This keeps malformed states representable so Alloy can detect them.
- Method-body keys encode source location only; they do not encode a declaring classifier or target method.
- Implementation bindings are ternary observations: **implementer classifier + target method + body**. The schema can therefore represent an implementation whose implementer differs from the target method's declaring classifier.
- Spoon observes standalone Java bodies and source modifiers. javac independently resolves source declarations/body correspondence. Neither frontend decides O-06 or O-07.
- Conventional Java source sets remain distinct identity domains. An auxiliary set such as `src/test/java` is compiled against an isolated binary compilation of its sibling `src/main/java`, not by merging production and auxiliary source files. This preserves duplicate qualified names as distinct path-based declarations while allowing ordinary test-to-production inheritance and overrides to remain semantically observable. If the production sibling cannot be compiled under the explicit dependency context, dependent javac evidence is incomplete rather than guessed.
- For O-09, javac independently resolves source override pairs and return types. Alloy does not simply trust that relation: it derives the manuscript-level ancestor + accessible method-key + scope candidate relation and reports correspondence separately from the strict return/implementation policy.
- O-09 target discovery follows the canonical ancestor graph and asks javac about each ancestor's **declared** method. It does not infer override pairs from javac's effective inherited-member view, because an overridden ancestor declaration may be suppressed from that view.
- Return types are preserved as explicit canonical values. The current manuscript profile compares them by equality because the formal model does not contain an independently observed subtype relation for return types; a legal Java covariant-return override can therefore be a strict-profile O-09 finding.
- Classifier abstraction (`ABSTRACT`, `CONCRETE`, `UNKNOWN`) and method scope (`INSTANCE`, `STATIC`, `UNKNOWN`) are explicit canonical evidence, not booleans inferred inside Alloy from implementation outcomes.
- Parameter types remain ordered multi-valued observations. They are never flattened into a delimiter-based signature.
- `inheritedMembers` is populated only from frontend semantic resolution. Alloy independently derives its expected inherited view from parents, declarations, visibility, package, inheritability, hiding, and nearer-ancestor priority.
- Each semantic condition is evaluated independently. A violation of one condition does not globally invalidate the exact observation or suppress other decisions.
- `CONFORMANT` means the exact observed facts satisfy that registered check. It does **not** mean unobserved program behavior was proved correct.
- `NOT_EVALUATED` is a first-class research result indicating that the configured observation boundary cannot justify all required evidence.

## O-07 repository-observable profile

The current repository experiment does not observe runtime/model objects. Therefore it does not reinterpret Java allocation syntax (`new C(...)`) as a formal `Object` or `directInstances` relation. Doing so would change the manuscript semantics and create an unjustified correspondence claim.

The repository-observable O-07 profile instead evaluates two independently registered checks:

1. `abstraction-implementation-consistency`
   - Alloy reuses the O-06 binding relation and derives `unresolvedMethod` itself.
   - If a classifier has any unresolved visible method, the observed classifier must be explicitly abstract.
   - A fully implemented classifier is still allowed to be abstract; the implication is intentionally one-way.
2. `static-abstract-method-separation`
   - Source-observed method abstraction and source-observed static/instance scope are compared in Alloy.
   - A method observed as both static and abstract is a violation.

The formal direct-instance clause remains documented but deferred until an explicit object-observation experiment is defined and independently justified.

## O-09 repository-observable profile

The Java repository profile records two new evidence kinds without deciding conformance:

1. `METHOD_RETURN_TYPES`: every canonical source method is uniquely mapped to javac and receives a compiler-resolved return-type representation.
2. `OVERRIDE_RELATIONS`: for every canonical source method, javac's `Elements.overrides` relation is mapped back to canonical source method atoms within the configured source boundary.

Both evidence kinds are all-or-nothing for the configured Java observation. Compilation errors, ambiguous source-method mappings, or missing required dependency information make the evidence incomplete and the affected O-09 checks become `NOT_EVALUATED`.

O-09 is intentionally exposed as **two registered checks** rather than one conflated result:

1. `override-relation-consistency`
   - Alloy derives formal override candidates from hierarchy, local declarations, contextual accessibility, ordered method keys, and method scope.
   - The derived relation is compared with javac's independently observed `Elements.overrides` relation.
   - A disagreement is a bridge/policy-correspondence finding. It is not mislabeled as a return-type failure.
   - This distinction is important for cases such as Java static hiding: the manuscript profile treats same-key/same-scope ancestor declarations as an override candidate, while javac does not classify static hiding as overriding.
2. `override-discipline`
   - Over the formal override pairs, Alloy enforces the manuscript's strict profile: equal return type and a local declaration that is either explicitly abstract or has an implementation binding in its declaring classifier.
   - `OVERRIDE_RELATIONS` remains a required empirical evidence kind even though the strict policy function itself operates on the formal relation; the pipeline therefore never claims an empirical O-09 policy result when the independent override observation was unavailable.

Separating the two checks preserves causal interpretation: a compiler/formal relation disagreement and a strict return/implementation disagreement are different empirical observations even though both trace to O-09.

## Current executable registry

The registry contains eleven semantic checks:

1. `exclusive-declaration-ownership` (O-02)
2. `acyclic-generalization` (O-03)
3. `inherited-view-consistency` (O-04)
4. `local-inherited-separation` (O-05)
5. `implementation-binding-consistency` (O-06)
6. `abstraction-implementation-consistency` (O-07 repository profile)
7. `static-abstract-method-separation` (O-07 repository profile)
8. `local-namespace-uniqueness` (O-08-local)
9. `inherited-namespace-uniqueness` (O-08-inherited)
10. `override-relation-consistency` (O-09 bridge/correspondence)
11. `override-discipline` (O-09 strict policy)

O-01 remains deferred. The O-07 direct-instance subclause also remains deferred even though the repository-observable abstraction subprofile is executable.
