# O-01 identity-preservation bridge audit

## Purpose

O-01 in the manuscript requires structural identity to remain distinct from names,
values, and serialization labels. The repository pipeline cannot honestly turn this
into a source-conformance invariant by checking its own generated classifier/member
keys: those keys are deterministic trace infrastructure created by the pipeline itself.
Checking that generated keys are unique would therefore be true by construction and
would not constitute independent empirical evidence for O-01.

The corrected repository profile keeps O-01 **deferred as a source-level conformance
claim** and audits the narrower property that the representation bridge does not
conflate distinct declarations while transporting them from source observations to
EMF/XMI and Alloy.

## What is audited

The bridge audit establishes the following transport properties:

1. Two distinct source declarations may have the same member name and ordered
   parameter signature without becoming the same canonical member.
2. Equal externally observed/serialized identifier labels remain representable on
   distinct member carriers; `observedIdentifier` is data, not the containment or
   reference identity used by the pipeline.
3. Canonical XMI round-trip preserves those distinct carriers and their references.
4. Exact Alloy encoding assigns distinct atoms to distinct technical carriers and
   retains a reverse atom-to-technical-key map for provenance.
5. The invariant registry contains no O-01 semantic check whose evidence is merely a
   generated technical key.

These properties are necessary for the manuscript's operational distinction between
a legitimate name/label collision and two declarations accidentally serialized as one
atom. They do **not** prove that a programming language exposes an independent
semantic identifier for every declaration.

## Evidence implemented in the test suite

`IdentityBridgeIntegrityTest` contains three regression cases:

- **same name/signature across owners**: two Java methods with the same name and
  parameter signature in different classifiers retain distinct technical keys,
  distinct declaring classifiers, distinct Alloy atoms, and survive deterministic
  XMI replay. The local namespace invariant remains conformant because the duplicate
  key is across owners rather than within one local namespace.
- **duplicate observed labels**: a canonical observation deliberately assigns the
  same `observedIdentifier` value to two distinct method carriers. The schema accepts
  the state, XMI preserves both carriers, and the exact Alloy atom mapping remains
  injective. This guards against treating a serialization label as carrier identity.
- **no generated-ID O-01 invariant**: the registry is asserted not to contain an
  executable invariant traced as O-01. This prevents later maintenance from
  accidentally reclassifying deterministic technical IDs as independent empirical
  identity evidence.

## Interpretation

A passing bridge audit supports the claim:

> Distinct observed structural carriers are not collapsed merely because they share a
> name, signature, or observed serialization label, and their technical identity is
> preserved through the canonical and exact-formal representations.

It does not support the stronger claim:

> The source repository independently satisfies O-01 identifier uniqueness.

That stronger claim would require a separately justified source/model identity
observation with explicit completeness semantics. Until such evidence exists, O-01
remains outside the executable source-conformance registry.

## Relation to the empirical rerun

The bridge audit should run as part of `mvn verify` before corrected repository counts
are reported. It is a validation of the measurement instrument, not an additional
repository finding category. Therefore O-01 bridge-audit success must not be added to
per-repository `CONFORMANT` counts or used to replace the old paper's
`IdentifierIntegrity` findings one-for-one.
