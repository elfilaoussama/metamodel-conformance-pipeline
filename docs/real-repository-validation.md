# Real-repository integration validation

The integration gate analyzes the real
[`elfilaoussama/metamodel-conformance-pipeline`](https://github.com/elfilaoussama/metamodel-conformance-pipeline)
repository, not a copied fixture.

For reproducibility, CI checks out the immutable commit
`25a80241f7514aa0a9e9a5ad2c5ec3fa90277527`. That revision contains 56 Java
source files. The script explicitly declares the six known parent types supplied by
the JDK, Swing, and JGit rather than silently omitting those hierarchy edges.

The current registry contains six executable invariants. This pinned repository is
dependency-rich and the validation deliberately does not manufacture a compile
classpath, so the independent `javac` inherited-member observation is expected to
remain incomplete. The gate therefore checks both successful conformance claims and
correct fail-closed behavior.

The gate succeeds only when:

1. source observation completes and emits a replayable verification capsule;
2. no parser error is present;
3. an explicit `EVIDENCE_INCOMPLETE` diagnostic records the missing inherited-member
   dependency boundary;
4. `exclusive-declaration-ownership`, `acyclic-generalization`, and
   `local-namespace-uniqueness` are `CONFORMANT`;
5. `inherited-view-consistency`, `local-inherited-separation`, and
   `inherited-namespace-uniqueness` are `NOT_EVALUATED` rather than being
   guessed from incomplete evidence;
6. exactly six decisions are present, matching the current registry; and
7. independent capsule replay validates every recorded artifact digest and decision.

This integration test complements the frozen 20-repository corpus. The pinned
single repository provides a fast deterministic end-to-end gate, while the corpus
verifies that source-set collisions and large Alloy instances no longer cause
architectural tool failure.

The pinned commit makes the test reproducible. Updating the repository under test or
supplying a dependency archive is a deliberate reviewable change, not an untracked
dependency on a latest branch or local machine state.
