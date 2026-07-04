# Architecture

## Dependency direction

```text
Swing desktop
  |-- analysis-core
  |-- github-ingestion --> analysis-core
  |-- github-search --> analysis-core
  |-- spoon-extraction --> analysis-core
  `-- verification-integration --> analysis-core
```

`analysis-core` has no JGit, Spoon, Swing, JSON, or framework dependencies.
It defines the vocabulary used between stages:

- `RepositoryIngestionService`
- `JavaExtractionService`
- `RepositoryRequest` / `IngestedRepository`
- `ExtractionResult`
- `ProgressListener` / `CancellationToken`

Adapters depend inward on these contracts. The desktop composes adapters but
does not embed their implementation logic.

## Workflow

1. Validate and normalize a GitHub HTTPS URL.
2. Clone into `<workspace>/<owner>__<repository>`, or explicitly reuse a clone.
3. Discover standard Maven/Gradle Java source roots throughout the repository.
4. Build one Spoon model per selected source root to isolate modules and bound memory.
5. Fail the repository task when Spoon cannot construct a valid model.
6. Map the model into versioned, library-neutral records.
7. Atomically write `extraction.json` beneath the repository output folder.
8. Optionally invoke the isolated AlloyInEcore runtime with the selected dynamic
   `.recore` metamodel and the extraction JSON.
9. Map the Spoon records to an AIE instance, solve the generated Kodkod problem,
   and write named violations to JSON and CSV.
10. Publish SAT/UNSAT and every mapped constraint violation to the Swing table.

Repository tasks are currently processed sequentially by a `SwingWorker`. This
keeps memory bounded because Spoon models can be large. The queue and service
contracts permit a future bounded executor without changing the adapters.

## Extension rules

- Add providers by implementing a core interface; do not add provider-specific
  types to `analysis-core`.
- Add new analysis stages as separate modules with their own input/output
  contracts.
- Do not make Swing components available to worker services.
- Capture configuration on the event-dispatch thread before starting work.
- Every long-running adapter must report progress and honor cancellation.
- Outputs require a schema version and deterministic ordering.
- A failed parse, clone, or write is a failed task; partial success must be
  explicit in the result, never inferred from an exit code.

## Next-stage candidates

- local-directory ingestion;
- GHS-backed repository discovery as another ingestion/catalog adapter;
- bounded concurrent repository processing;
- extraction cache keyed by repository revision and options;
- configurable mapping profiles for structurally different AlloyInEcore metamodels;
- richer diagnostics and per-module extraction isolation.
