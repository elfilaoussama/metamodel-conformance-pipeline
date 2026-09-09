# Random Java corpus (20 repositories)

Sampling date: 2026-08-30.

The sampling frame is the first 100 GitHub repository-search results for:

```text
language:Java fork:false archived:false stars:10..500 size:100..5000 pushed:2025-01-01..2025-12-31
```

Repositories are ranked by:

```text
SHA-256("mcp-corpus-2026-08-30-v1" NUL repository_full_name)
```

The first 20 hashes form the corpus. `random-java-20.tsv` records the complete
selection, rank hash, default branch, reported GitHub size, and immutable commit.
No repository is replaced after analysis, including extraction failures,
non-conformant results, or incomplete evidence.

The analysis uses a uniform two-pass boundary policy. The first pass records all
parents outside the repository source graph. The second pass explicitly declares
those names external and evaluates the closed repository-internal graph. Both
logs and the final verification artifacts are retained.

The random corpus is a broad fail-closed boundary test. Its isolated provider
observation may be unable to obtain trustworthy build evidence; that condition is
recorded and evidence-dependent invariants become `NOT_EVALUATED`.

`java-build-backed-medium.tsv` is a separate pinned corpus for provider
validation. It contains four medium-sized public Java libraries: two Maven and
two Gradle builds. The Gradle entries retain the nested-wrapper and internal
project-artifact structures that previously exposed resolver defects, while the
redundant large jobs are excluded to bound hosted-runner cost. Its gate requires
a replayable capsule, a successful resolver exit, at least one compilation
context, and at least one resolved archive for every entry. Repository
compilation and test tasks are not requested; Gradle build configuration is
evaluated only inside the read-only, capability-restricted resolver container.
