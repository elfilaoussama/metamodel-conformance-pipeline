# Source and documentation map

## Engine

The root `pom.xml` builds the scientific CLI and engine under `src/main/`.

| Package or resource | Responsibility |
|---|---|
| `pipeline/adapter/` | Language and native-context observation. |
| `pipeline/model/`, `pipeline/emf/` | Shared typed observation and serialization. |
| `pipeline/invariant/`, `pipeline/decision/` | Registry, evidence admission and decision transport. |
| `pipeline/alloy/` | Exact relational encoding, qualified evaluation and witnesses. |
| `pipeline/capsule/` | Identity-bound recorded verification and replay. |
| `pipeline/cli/` | Command-line entry points. |
| `resources/invariants/registry.json` | Versioned metadata and evidence requirements. |
| `resources/alloy/invariants.als` | Formal witness definitions. |

Paths above are relative to `src/main/java/metamodel/conformance/` for packages
and `src/main/` for resources. The language/provider contracts in the historical
documentation provide detailed implementation context; validate them against the
current source before relying on an older result.

## Experiment Workspace

`apps/research-web/server/` is an independent Spring Boot MVC build. Its Java
package is `metamodel.conformance.web`.

- `CollectionService`, `CollectionStore` and page controllers manage source
  configurations, explicit selections and immutable reviews.
- `GitHubRepositories`, `RepositorySearch`, `RepositoryRequest` and
  `GitRepositorySource` handle discovery and pinned acquisition.
- `RunService`, `JobDispatcher`, `EngineGateway` and `OwnedProcesses` own attempts,
  queue capacity, engine invocation and cancellation boundaries.
- `Catalog` and `WorkbenchDatabase` retain attempt and configuration records.
- `ArtifactViews`, `ResultSummary` and the engine bridge inspect recorded results.
- MVC templates and local static assets present the scientific records.
- `DocumentationPages` renders the reviewed Markdown manual packaged from this
  directory. It does not browse arbitrary filesystem paths.

## Required tooling

The former root `scripts/` tree is now `tools/`. Its providers, batch runners,
reconstruction tools and audits were preserved. For example, `tools/maven-frames/`
contains resources packaged into the engine's `java-native-build` classpath.
Deleting this tooling would remove supported behavior. Its local tests remain
with the responsible tools.

## Documentation hierarchy

`tutorials/` teaches one small complete run. `guides/` answers concrete tasks.
`explanation/` describes the scientific and architectural reasoning.
`reference/` states contracts, capabilities and operational facts. The app packages
these same files, so the installed manual corresponds to that application build.

The former flat `docs/` tree lives under `documentation/archive/`. Historical
validation receipts are preserved, not reclassified as current qualification.
The original long repository README is retained there as `README-original.md`.
Its relative documentation links were relocated; recorded commands and paths in
older reports describe their original environment. The migration does not change
the identities of earlier scientific runs.

Repository readers can explore `documentation/archive/invariant-pipeline.md`,
`documentation/archive/multilanguage-adapter-contract.md`, the language-native
documents, architectural decisions and dated validation records. This archive is
excluded from the installed user manual to avoid presenting old milestone reports
as current operational instructions.

## Contributing safely

Keep verdict logic in the formal layer, operational policy in typed configuration
and presentation in the interface. Preserve the observer/evaluator distinction.
Avoid repository-specific exceptions, duplicate orchestration and speculative
framework layers. Add meaningful controls at the affected boundary and use small
end-to-end projects at integration checkpoints. Do not rewrite earlier evidence
to make a new implementation appear retrospectively validated.
