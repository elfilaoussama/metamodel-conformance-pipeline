# Java checkpoint verification — 2026-09-12

The user reauthorized GitHub Actions testing after local container namespaces and
Maven dependency access proved unavailable. Publication for validation is authorized;
baseline integration and main promotion remain separate.

## Exact tested state

- Local checkpoint: `5a6605e132e2aba41ac1921d3bb65552a3a535a2`.
- Code tree: `fdd46fdbd2c2f33c54f4927cb7fb61987b7c404d`.
- Remote validation commit: `58094302e680b82cdf11f69ce451a00bef8222e9`.
- Authentic remote parent: `965d9100ae2b8f10c9184eca1c1903e7684d3b42`.
- Validation tree: `c5e05974280e5fdd869be105fbbe6f57e5819e17`.
- Only addition to the exact code tree: a branch-scoped validation workflow.
- Branch: `validation/java-checkpoint-5a6605e-20260912`.

Remote code-tree equality was checked before adding the workflow. File modes were
preserved. Reconstructed local parent history was not uploaded as remote ancestry.

## Verified results

[Run 34713598333](https://github.com/OsmGetHub/metamodel-conformance-pipeline/actions/runs/34713598333)
completed successfully in one job on Ubuntu 24.04 / Temurin JDK17.

- Standard `mvn --batch-mode --no-transfer-progress verify`: BUILD SUCCESS.
- 177 tests, zero failures, zero errors, zero skipped; C++ classes included.
- Compilation-context, resolver, fallback, no-special-case, Java architecture,
  and javac semantic gates passed.
- Actual isolated Maven provider fixture passed.
- Actual isolated Gradle provider fixture passed.

Artifact `java-checkpoint-verification`, ID `10304326504`, contains environment
versions, resolved image digests, Maven/gate/provider logs and Surefire reports.
GitHub artifact SHA256:
`09a31924ee6ccf7592a6794fb598dff8ce12e89ba1f373c416b13c834eb263fc`.
The artifact expires 2026-12-11 unless retained elsewhere.

## Limits and next evidence

This closes the final standard-build gap and confirms that the current providers
execute on real containers for authored fixtures. It does not prove all Maven or
Gradle compiler semantics, archive variants, or real-repository evaluability.

The subsequent two-repository evidence run pins Commons Codec to
`322f464c9484e60be91134b1850072b61eab4ab4` (175 Java files) and JOpt Simple to
`1f80fe268d2b038b44702ac12cdc95e5060a92b1` (160 Java files), counted from complete
Git trees. Counts describe sample selection and impose no production limits.
Its only runner change preserves provider manifests before temporary cleanup;
shell syntax, corpus fallback regression, and diff checks passed locally.

The live comparison from Java baseline `0fa8abdbc3484f87195d0484bad05dc188908f58`
to the verification commit is 13 commits ahead, zero behind, 29 changed files.
It contains no C++/Python production changes. The comparison includes existing
build-backed corpus/workflow changes as well as the 19-file unpublished correction
delta; these must be reviewed separately from the temporary verification workflow.

The downloaded verification artifact's checksum matches GitHub's recorded digest.
Summing its Surefire XML reports independently confirms 177/0/0/0.

Main and the Java baseline remain unchanged. Temporary validation workflow branches
must never be merged wholesale into either branch.
