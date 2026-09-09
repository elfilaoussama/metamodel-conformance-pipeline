# Historical Java corpus validation: 20 pinned repositories

> **Historical result only.** The counts below belong to pipeline commit
> `e68ef12007578403798b1596d55a934d78b272aa` and GitHub Actions run
> `33629584804` from 2026-09-02. They must **not** be interpreted as results for
> the current invariant registry. Later work added implementation/abstraction
> checks and the O-09 override evidence/profile. Any observer, schema, Alloy rule,
> registry requirement, or projection change invalidates reuse of these counts
> until the frozen corpus is rerun on that exact head.

This report records an earlier 20-repository engineering validation of the
metamodel-conformance pipeline. It is not a population estimate for Java
repositories. The corpus is pinned so architectural regressions can be
distinguished from changes in external repositories.

## Reproducibility of the recorded run

- Validated pipeline commit: `e68ef12007578403798b1596d55a934d78b272aa`
- GitHub Actions run: `33629584804`
- Run date: 2026-09-02
- Manifest: `corpus/random-java-20.tsv`
- Sampling seed: `mcp-corpus-2026-08-30-v1`
- Sampling frame: the first 100 GitHub search results documented in
  `corpus/README.md`; repositories were ranked by
  `SHA-256(seed NUL repository_full_name)` and the first 20 were selected.
- Every repository was checked out at the immutable commit recorded in the
  manifest.
- Repository code and build scripts were not executed.

The recorded run processed exactly **2,466 Java files**. All **20/20** repositories
completed pipeline analysis and produced independently replayable verification
capsules.

## Outcome of that architectural validation

The run established that several failures from the first corpus execution had been
closed:

| Previous failure class | Baseline | Recorded run | Status at recorded commit |
|---|---:|---:|---|
| Duplicate qualified type identity causing tool failure | 3 repositories | 0 | Resolved by source-set-aware path identity |
| Alloy parser stack overflow on monolithic exact instance | 3 repositories | 0 | Resolved by deterministic semantic work-unit partitioning |
| Alloy ternary-relation translation capacity failure | 2 repositories | 0 | Resolved by deterministic semantic work-unit partitioning |
| Repositories producing a report but no valid capsule | 8 repositories | 0 | Resolved |

Aggregate tool outcome at that commit:

- **20 analyzed repositories**;
- **20 valid replayable capsules**;
- **0 tool failures**;
- **0 Alloy representation/capacity failures**.

## Invariant outcomes at the recorded commit

The registry at commit `e68ef120...` contained six semantic invariants. Their
repository-level results were:

| Invariant | Conformant | Nonconformant | Not evaluated |
|---|---:|---:|---:|
| `exclusive-declaration-ownership` | 19 | 0 | 1 |
| `acyclic-generalization` | 19 | 0 | 1 |
| `local-namespace-uniqueness` | 19 | 0 | 1 |
| `inherited-view-consistency` | 1 | 0 | 19 |
| `local-inherited-separation` | 1 | 0 | 19 |
| `inherited-namespace-uniqueness` | 1 | 0 | 19 |

These six-invariant counts are intentionally retained for provenance. They are not
forward-filled with zeros or reused for invariants introduced later.

`alpha037/data-structures-and-algorithms` was the corpus entry for which all six
registered invariants had complete evidence and all six were conformant.

`ManishK4514/Strivers-A2Z-DSA-Sheet` contained source units rejected by the Java
parser. The pipeline preserved those diagnostics and therefore marked all six
invariants `NOT_EVALUATED`; it did not silently discard the rejected files.

For the other 18 repositories, declaration ownership, hierarchy, and local
signature evidence were sufficient for the three local/core invariants, but the
independent `javac` inherited-member observation was incomplete because the frozen
source checkout did not include a complete compile-time dependency environment.
Those inherited-evidence-dependent invariants were therefore `NOT_EVALUATED`.

## Evidence diagnostics at the recorded commit

The run recorded:

- 3,857 `EVIDENCE_INCOMPLETE` diagnostics, overwhelmingly from missing third-party
  compile-time types such as Android, JUnit, SLF4J, generated project types, and
  other dependency classes;
- 11 `PARSE_ERROR` diagnostics in the standalone algorithm corpus entry.

These diagnostics are evidence-boundary findings, not invariant violations. The
corpus harness deliberately does not execute Maven/Gradle or repository code to
manufacture a build environment. Exact dependency archives can be supplied to the
pipeline explicitly and are hashed into the canonical source set.

## Historical baseline: first corpus execution

The first run on 2026-08-30 used pipeline commit
`45c3eabd5ebc5f2757365405ae73c4646dd8e2b0` and GitHub Actions run
`33335401769`. It found:

- 11 fully conformant repositories under the then-five-condition implementation;
- 1 apparent O-04 nonconformance;
- 5 indeterminate Alloy representation failures;
- 3 source-identity tool failures.

The apparent O-04 result on `AnJiaoDe/TabLayoutNiubility` contained 144
Spoon-only and 148 Alloy-only inherited memberships. That result was correctly
interpreted as an adapter-semantics problem rather than a defect in the sampled
repository. The subsequent architecture stopped using Spoon's inherited-member
view as the independent empirical authority: declaration facts come from Spoon,
while inherited membership is independently observed with the JDK compiler and
compared with the Alloy-derived formal view.

Because the recorded 2026-09-02 corpus checkout lacked that repository's full
dependency classpath, O-04 was correctly `NOT_EVALUATED` rather than reproducing
or hiding the old disagreement.

## What must happen before new paper counts are reported

The current research branch has materially changed the measurement instrument.
The corrected corpus rerun must therefore use the exact current head and must
publish, for every registered invariant:

- conformant repository count;
- nonconformant repository count;
- `NOT_EVALUATED` repository count;
- evaluated-repository count;
- repositories with findings; and
- total Alloy witness/finding count.

The Java corpus workflow now preserves those witness totals in its aggregate
metrics. O-09 relation-correspondence findings and strict override-policy findings
must remain separate categories rather than being collapsed into one count.

Until that rerun completes successfully and its capsules replay, this historical
report is evidence of pipeline-engineering progress only, not the final empirical
result used to revise the manuscript.
