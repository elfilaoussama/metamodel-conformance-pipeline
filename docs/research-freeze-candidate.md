# Research freeze candidate

Branch: `integration/research-freeze-candidate`

This branch is a validation candidate, not an already-approved research baseline. It combines the currently isolated paper-critical work so that one executable gate can test the interactions before any merge or empirical rerun.

## Included work

### O-09 override evidence

Source branch: `feature/override-evidence-v2`

The candidate includes schema-12 method return-type and override-relation evidence, EMF/XMI persistence, exact Alloy projection, two separate O-09 result classes (`override-relation-consistency` and `override-discipline`), source provenance, capsule replay, compiler/source-set fail-closed behavior, and the Java auxiliary-to-production binary context.

The source-set hardening preserves main/test duplicate qualified names as distinct carriers while allowing auxiliary sources to resolve production inheritance and overrides. O-09 target discovery uses declared canonical ancestor methods, not javac's effective inherited-member view. Generic bridge regression tests require synthetic class-file bridge methods to remain outside the canonical source relation.

Java observer provenance is versioned for the behavioral change:

- structural Spoon/javac observation: `spoon-java` `0.9.1`
- implementation/override-enriched Java observation: `spoon-java` `1.3.1`

### O-01 identity bridge audit

Source branch: `feature/identity-bridge-audit`

O-01 remains deferred as a source-conformance invariant because generated technical keys are not independent empirical identities. The included bridge audit instead verifies that distinct observed carriers remain distinct through canonical observation, XMI round-trip, and exact Alloy atom mapping even when names/signatures or observed labels are equal. Bridge-audit success is instrument validation and must not be counted as repository-level O-01 conformance.

### Paper corpus recovery

Source branch: `feature/paper-corpus-recovery`

The exact historical 224-repository selection is included and byte-identical to the study artifact:

`d6818780078944b5b12063c780a480dc8c8686eb`

Membership is 75 Java, 75 Python, and 74 C++ repositories. The original per-repository clone SHAs were not durably exported by the historical study, so the fallback is explicitly labeled `RECONSTRUCTED_SELECTION_CUTOFF`: the latest commit on the repository's canonical default branch at or before `2026-07-29T12:09:33Z`.

The first complete reconstructed pin manifest must be frozen with its SHA-256 digest. Subsequent corrected experiment runs must consume those immutable pins rather than reconstructing them again from mutable repository metadata.

## Required freeze gate

This candidate is not acceptable for merge or paper-result generation until all of the following have executed successfully on the exact candidate head:

1. recovered 224-selection blob/structure integrity check;
2. Maven `verify`, including O-01, O-09, source-set, XMI, Alloy, projection, determinism, and capsule regressions;
3. pinned real-repository verification;
4. frozen engineering corpora: 20 Java, 6 Python, 4 C++;
5. manual paper-corpus reconstruction workflow producing exactly 224 immutable pins and a SHA-256 digest;
6. review of any `NOT_EVALUATED` shifts caused by the corrected Java semantic context before launching the paper campaign.

A workflow failure before checkout, with no executed job steps, is infrastructure failure and does not satisfy or invalidate any of these gates.

## After the gate is green

Only after the exact candidate head passes should the project:

1. freeze the reconstructed 224-pin manifest and digest;
2. run the corrected empirical campaign on those pins;
3. preserve per repository/invariant status, evidence completeness, diagnostics, witness counts, witness provenance, pipeline commit, adapter/tool versions, and capsule replay result;
4. report O-09 bridge/correspondence findings separately from strict override-discipline findings;
5. keep O-01 bridge-audit success outside repository conformance counts;
6. compare corrected results with the historical paper numbers as historical-vs-corrected results, never by silently reusing old counts.
