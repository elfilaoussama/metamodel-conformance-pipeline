# Find and acquire repositories

GitHub discovery identifies candidate inputs. It does not establish a
representative sample, compile a project or evaluate its conditions.

## Define a search

Open **GitHub search**. Use the example buttons to populate a starting query,
then inspect and change the fields. The panel supports keywords or a raw query,
language, owners, topics, licenses, star/fork/size ranges, activity dates and
repository-state filters. Sorting and pagination are part of the recorded search.

For a fast interface check, search for
`repo:jenkins-docs/simple-java-maven-app`. This identifies a small public example,
not a scientific sampling strategy. A broader starting point is `parser` with
language `Java` and an explicit size range. Record why your study uses each filter.

Multiple licenses represent alternatives; multiple topics are combined as
requirements. Multiple owners include their repositories. Inspect the generated
query before searching. Validation explains incompatible ranges and unsupported
query combinations rather than silently changing their meaning. Current query
limits and GitHub API bounds are documented in the
[capabilities reference](../reference/capabilities.md).

## Select candidates

Use the checkbox beside a repository or **Select this page**. The page selection
control supports partial selection. Candidates can be retained across result
pages. Inspect the selected-candidate list before continuing. A page checkbox
selects that page, not every repository in GitHub's reported population.

Configure the selected sources. A saved study is optional organization for
repeated work, not a required methodological step. Existing selections and their
configuration remain available after restarting the service.

## Review and submit

Choose a branch, tag or commit, and optionally a source subdirectory. Select
source acquisition or Java source-only analysis. Review the immutable submission
before choosing its start action. Browsing or changing a search does not start
scientific computation.

The worker resolves the requested revision to an exact commit. It retains tracked
blobs and a hash inventory without running checkout filters, hooks, submodules,
LFS materialization or project builds. A branch can move between discovery and
acquisition; the acquired commit receipt, not the search timestamp, identifies
the analyzed input. Source bytes are captured at submission/acquisition time.

## Follow the submission

The entire selection can be submitted together. The persisted queue and configured
worker slots determine how many attempts run concurrently. Worker capacity does
not define a repository-selection limit. Each job retains its own state, failure
or result. A failed entry does not make other entries conformant.

Acquisition completion means that source was retained. For Java, **Analyze retained
Java source** can launch a separate attempt without fetching again, after a full
inventory check. Python and C++ acquisition does not currently lead to web analysis.

## Preserve sampling evidence

Retain the discovery receipt, generated query, retrieval time, result pages,
selection rationale, exclusions and acquired commits. GitHub search is limited
and time-dependent. Unknowns, failed acquisitions and excluded inputs belong in
the study accounting. See [Scientific methodology](../explanation/methodology.md).
