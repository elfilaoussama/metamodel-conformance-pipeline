# Capabilities and limitations

This reference distinguishes the scientific engine from its current web execution
slice. Inspect the running application's capabilities endpoint for its exact
configured engine identity and operational state.

## Current interface

| Operation | Current support | Scientific boundary |
|---|---|---|
| Public GitHub search | Advanced filters, examples, pagination, retained search receipts | Discovery does not establish a representative sample. |
| Source selection | Page and cross-page selection; configurable sources and immutable reviews | A review freezes settings, not source bytes before acquisition. |
| GitHub acquisition | Public HTTPS repositories, exact resolved commits, selected tracked blobs and inventory | No hooks, filters, submodule/LFS materialization or author build. |
| Java analysis | Default source-only analysis with the configured engine and bundled profile | No author-build/provider selection in the web form. |
| Python/C++ analysis | Not currently executable in the web interface | Acquisition and recorded-result inspection remain available. |
| Result import | Matching Java/Python/C++ result artifacts and optional inventory-matching source | Import checks integrity and typed transport; no formal solve. |
| Inspection | Dynamic rule metadata, counts, charts, decisions, complete witnesses, model, evidence, sources and artifacts | Displays recorded results; no independent scientific verdict logic. |
| Formal replay | Separate linked job using the matching qualified engine | Does not re-observe source or reconstruct the build environment. |
| Scheduling | Persisted backlog, independently configured worker slots, owned cancellation | Separate child processes; no automatic host-loss recovery. |

## Operational bounds

There is no eight-repository selection or submission limit. Waiting jobs are
stored in the catalog; the default worker count is one. Larger batches consume
disk, memory and runtime and must still fit the host and per-operation budgets.

GitHub search currently returns twenty candidates per page within GitHub's first
1,000 accessible search results. Its total count may exceed that accessible
window. Rate limits, incomplete responses and query-grammar bounds are external
constraints, not worker limits. Structured additive filters currently accept up
to five values per filter. Pagination preserves the recorded criteria.

The general history shows the latest 100 attempts. A submission-group view shows
all attempts in that group. Entity tables are paged. JSON/form body limits,
inventory checks and per-job budgets remain intentional admission boundaries.
See [Configuration and troubleshooting](operations.md).

## Not yet implemented in the interface

Python/C++ provider-backed execution, Java author-build configuration, observe-only
jobs, profile selection/editing, experiment comparison, general source-reproduction
workflows, automatic host-loss recovery and public/multiuser deployment remain
outside this web slice. The previous Swing application is a workflow reference,
not a compatible execution backend to copy into the new engine.

## Research limitations

No general proof of extraction fidelity is implied. Each language/provider profile
requires independent native controls. New rule profiles require their own
formalization and evaluator-equivalence controls. Corpus validity, adjudication,
sampling sensitivity, statistical accounting and reviewer responses remain
research obligations independent of interface completeness.
